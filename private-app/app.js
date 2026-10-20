'use strict';

const $ = selector => document.querySelector(selector);
const nativeAndroid = window.Capacitor?.isNativePlatform?.() === true;
const CATEGORIES = ['Alimentação', 'Transporte', 'Moradia', 'Saúde', 'Lazer', 'Assinaturas', 'Salário', 'Outros'];
const ICONS = { Alimentação: '⌁', Transporte: '↗', Moradia: '⌂', Saúde: '✚', Lazer: '◌', Assinaturas: '◇', Salário: '↓', Outros: '·' };
const formatter = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const pad = number => String(number).padStart(2, '0');
const localDate = (date = new Date()) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
const clean = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
function readStorage(storage, key, fallback = '') { try { return storage.getItem(key) ?? fallback; } catch { return fallback; } }
function store(storage, key, value) { try { storage.setItem(key, value); return true; } catch { return false; } }
const configured = document.querySelector('meta[name="clareza-api"]')?.content;
const DEFAULT_API = configured ? new URL(configured, location.href).href : 'https://controle-despesas-mensais.onrender.com/api';
let apiUrl = readStorage(sessionStorage, 'clareza-api-url', DEFAULT_API);
let apiKey = readStorage(sessionStorage, 'clareza-api-key');
let privacyMode = readStorage(localStorage, 'clareza-privacy-mode') === 'true';
let movements = [], budgets = {}, goals = [], ofAccounts = [], ofInvestments = [], editingId = null, editingGoalId = null, deferredInstallPrompt;
let loadedPeriod = null, loadVersion = 0, saving = false, exporting = false;
const money = value => privacyMode ? '••••••' : formatter.format(value || 0);
const cents = value => Math.round(Number(value) * 100);

function loadBudgets() {
  try {
    const legacy = apiUrl === 'https://controle-despesas-mensais.onrender.com/api' ? readStorage(localStorage, 'clareza-budgets', '{}') : '{}';
    const saved = JSON.parse(readStorage(localStorage, `clareza-budgets:${apiUrl}`, legacy));
    budgets = Object.fromEntries(CATEGORIES.map(category => {
      const value = Number(saved?.[category]);
      return [category, Number.isFinite(value) && value >= 0 ? value : 0];
    }));
  } catch { budgets = {}; }
}
function bounds(period) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) throw new Error('Selecione um mês válido.');
  const [year, month] = period.split('-').map(Number);
  return { start: `${period}-01`, end: `${period}-${pad(new Date(year, month, 0).getDate())}` };
}
function monthName(period) { return new Date(`${period}-01T12:00:00`).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' }); }
function validateUrl(value) {
  const url = new URL(value);
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || (url.protocol !== 'https:' && !(local && url.protocol === 'http:'))) {
    throw new Error('Use HTTPS para sua API, ou HTTP apenas em localhost.');
  }
  return url.href.replace(/\/$/, '');
}
async function request(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 90000);
  try {
    let response;
    try {
      response = await fetch(`${apiUrl}${path}`, {
        ...options, cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: { 'X-API-Key': apiKey, ...(options.body ? { 'Content-Type': 'application/json' } : {}) }
      });
    } catch {
      throw new Error(controller.signal.aborted ? 'O servidor demorou para responder. Aguarde e tente novamente.' : 'Sem conexão com o servidor. Verifique sua internet e o endereço da API.');
    }
    if (!response.ok) {
      if (response.status === 401) {
        endSession();
        throw new Error('Chave inválida ou expirada. Entre novamente.');
      }
      if (response.status === 400) throw new Error('Revise os campos: descrição, data, categoria e valor com até duas casas decimais.');
      if (response.status === 404) throw new Error('Registro ou endereço não encontrado. Atualize a lista.');
      throw new Error(`O servidor não concluiu a operação (${response.status}). Tente novamente.`);
    }
    return response.status === 204 ? null : await response.json();
  } finally { clearTimeout(timeout); }
}
function status(message, error = false) {
  $('#dashboard-status').textContent = message;
  $('#dashboard-status').classList.toggle('danger', error);
}
function totals() {
  const sum = type => movements.filter(x => x.tipo === type).reduce((total, x) => total + cents(x.valor), 0) / 100;
  const entries = sum('ENTRADA'), expenses = sum('SAIDA');
  return { entries, expenses, balance: (cents(entries) - cents(expenses)) / 100 };
}
async function load() {
  const period = $('#period').value;
  const range = bounds(period), version = ++loadVersion;
  loadedPeriod = null;
  movements = [];
  render();
  status('Carregando lançamentos… O primeiro acesso ao servidor pode demorar.');
  try {
    const items = await request(`/movimentacoes?inicio=${range.start}&fim=${range.end}`);
    if (version !== loadVersion) return false;
    if (!Array.isArray(items)) throw new Error('Resposta inesperada. Confira o endereço da API.');
    movements = items;
    try {
      const remoteBudgets = await request('/limites');
      if (remoteBudgets && typeof remoteBudgets === 'object') {
        budgets = Object.fromEntries(CATEGORIES.map(category => {
          const val = Number(remoteBudgets[category]);
          return [category, Number.isFinite(val) && val >= 0 ? val : 0];
        }));
        store(localStorage, `clareza-budgets:${apiUrl}`, JSON.stringify(budgets));
      }
    } catch { /* use local cache */ }
    try {
      const remoteGoals = await request('/metas');
      goals = Array.isArray(remoteGoals) ? remoteGoals : [];
    } catch { /* keep goals empty if offline */ }
    try {
      const accs = await request('/open-finance/contas');
      ofAccounts = Array.isArray(accs) ? accs : [];
      const invs = await request('/open-finance/investimentos');
      ofInvestments = Array.isArray(invs) ? invs : [];
    } catch { ofAccounts = []; ofInvestments = []; }
    loadedPeriod = period;
    render();
    status(`${items.length} lançamento(s) em ${monthName(period)}. Dados atualizados.`);
    return true;
  } catch (error) {
    if (version === loadVersion) { status(error.message, true); render(); }
    throw error;
  }
}
function render() {
  const ready = loadedPeriod === $('#period').value;
  $('#export').disabled = exporting || !ready || !movements.length;
  $('#repeat-recurring').disabled = !ready;
  const { entries, expenses, balance } = totals();
  for (const [id, value] of [['balance', balance], ['entries', entries], ['expenses', expenses]]) {
    $(`#${id}`).textContent = ready ? money(value) : '—';
  }
  $('#saving-rate').textContent = !ready ? '—' : privacyMode ? '••••' : entries ? `${Math.round(balance / entries * 100)}%` : '—';
  $('#balance').classList.toggle('negative', ready && balance < 0);
  $('#balance-note').textContent = ready && balance < 0 ? 'As despesas registradas superam as entradas' : 'Entradas menos despesas registradas, sem saldo bancário inicial';
  $('#month-description').textContent = `Acompanhe seus registros de ${monthName($('#period').value)}.`;
  const largest = [...movements.filter(x => x.tipo === 'SAIDA')].sort((a, b) => b.valor - a.valor)[0];
  $('#insight-title').textContent = ready ? 'Uma leitura dos seus registros.' : 'Aguardando seus dados.';
  $('#insight-text').textContent = !ready ? 'Atualize para consultar este período.' : privacyMode ? 'Valores ocultos. Mostre os valores para ver o resumo.' : largest ? `A maior despesa registrada é ${largest.descricao}: ${money(largest.valor)}. Este resumo considera apenas os lançamentos cadastrados.` : 'Adicione suas entradas e despesas para acompanhar o mês.';
  renderList();
  renderBudgets();
  renderGoals();
  renderOpenFinance();
  renderAccounts();
}
function renderList() {
  if (!loadedPeriod) { $('#list').innerHTML = '<p class="empty">Os dados deste mês ainda não foram carregados.</p>'; return; }
  const term = $('#search').value.trim().toLocaleLowerCase('pt-BR'), type = $('#type-filter').value;
  const visible = movements.filter(x => (type === 'TODOS' || x.tipo === type) && `${x.descricao} ${x.categoria} ${x.conta || ''}`.toLocaleLowerCase('pt-BR').includes(term)).sort((a, b) => b.data.localeCompare(a.data));
  $('#list').innerHTML = visible.length ? visible.map(x => `<article class="transaction">
    <span class="category-icon" aria-hidden="true">${ICONS[x.categoria] || '·'}</span>
    <div class="transaction-copy"><strong>${clean(x.descricao)}</strong><small>${clean(x.categoria)} · ${new Date(`${x.data}T12:00:00`).toLocaleDateString('pt-BR')}${x.conta ? ` · ${clean(x.conta)}` : ''}${x.recorrente ? ' · Recorrente' : ''}</small></div>
    <strong class="amount ${x.tipo === 'ENTRADA' ? 'income-text' : ''}">${privacyMode ? money(0) : `${x.tipo === 'ENTRADA' ? '+' : '−'} ${money(x.valor)}`}</strong>
    <div class="row-actions"><button data-edit="${clean(x.id)}" aria-label="Editar ${clean(x.descricao)}">Editar</button><button data-delete="${clean(x.id)}" aria-label="Excluir ${clean(x.descricao)}">×</button></div></article>`).join('') : '<p class="empty">Nenhum lançamento encontrado. Use “Adicionar” para começar.</p>';
}
function renderBudgets() {
  if (!loadedPeriod) { $('#budget-list').innerHTML = '<p class="empty">Aguardando os lançamentos do mês.</p>'; return; }
  $('#budget-list').innerHTML = CATEGORIES.filter(category => budgets[category] || movements.some(x => x.categoria === category && x.tipo === 'SAIDA')).map(category => {
    const spent = movements.filter(x => x.categoria === category && x.tipo === 'SAIDA').reduce((sum, x) => sum + cents(x.valor), 0) / 100;
    const limit = Number(budgets[category] || 0), over = limit && spent > limit;
    const percentage = !privacyMode && limit ? Math.min(100, Math.round(spent / limit * 100)) : 0;
    return `<div class="budget"><div><span>${category}</span><strong>${money(spent)} <small>${limit ? `/ ${money(limit)}` : 'sem limite'}</small></strong></div><div class="progress ${over && !privacyMode ? 'over' : ''}" aria-hidden="true"><i style="width:${percentage}%"></i></div><small>${privacyMode ? 'Valores ocultos' : limit ? over ? `${money(spent - limit)} acima do limite` : `${money(limit - spent)} disponíveis no limite` : 'Defina um limite para acompanhar'}</small></div>`;
  }).join('') || '<p class="empty">Defina limites para acompanhar suas categorias.</p>';
}
function renderGoals() {
  if (!loadedPeriod) { $('#goal-list').innerHTML = '<p class="empty">Aguardando carregamento.</p>'; return; }
  if (!goals.length) {
    $('#goal-list').innerHTML = '<p class="empty">Nenhuma meta cadastrada. Use “+ Nova meta” para começar.</p>';
    return;
  }
  $('#goal-list').innerHTML = goals.map(g => {
    const alvo = Number(g.valorAlvo) || 0;
    const atual = Number(g.valorAtual) || 0;
    const pct = alvo > 0 ? Math.min(100, Math.round((atual / alvo) * 100)) : 0;
    const concluida = atual >= alvo && alvo > 0;
    const prazoFmt = g.prazo ? new Date(`${g.prazo}T12:00:00`).toLocaleDateString('pt-BR') : null;
    return `<div class="goal">
      <div class="goal-header">
        <strong>${clean(g.nome)}</strong>
        <div class="row-actions">
          <button data-edit-goal="${clean(g.id)}" aria-label="Editar meta ${clean(g.nome)}">Editar</button>
          <button data-delete-goal="${clean(g.id)}" aria-label="Excluir meta ${clean(g.nome)}">×</button>
        </div>
      </div>
      <div class="progress ${concluida ? 'completed' : ''}" aria-hidden="true"><i style="width:${privacyMode ? 0 : pct}%"></i></div>
      <div class="goal-values">
        <span>${privacyMode ? '••••' : `${pct}% concluído`}</span>
        <strong>${privacyMode ? money(0) : `${money(atual)} / ${money(alvo)}`}</strong>
      </div>
      ${prazoFmt ? `<small class="goal-date">Prazo: ${prazoFmt}</small>` : ''}
    </div>`;
  }).join('');
}
function renderAccounts() {
  if (!loadedPeriod) { $('#account-list').innerHTML = '<p class="empty">Aguardando lançamentos.</p>'; return; }
  const accounts = {};
  for (const m of movements) {
    const name = (m.conta && m.conta.trim()) ? m.conta.trim() : 'Sem conta informada';
    if (!accounts[name]) accounts[name] = { entradas: 0, despesas: 0 };
    if (m.tipo === 'ENTRADA') accounts[name].entradas += cents(m.valor);
    else accounts[name].despesas += cents(m.valor);
  }
  const keys = Object.keys(accounts);
  if (!keys.length) {
    $('#account-list').innerHTML = '<p class="empty">Nenhum lançamento no período.</p>';
    return;
  }
  $('#account-list').innerHTML = keys.sort().map(name => {
    const ent = accounts[name].entradas / 100;
    const desp = accounts[name].despesas / 100;
    const saldo = (accounts[name].entradas - accounts[name].despesas) / 100;
    return `<div class="account-item">
      <div>
        <strong>${clean(name)}</strong>
        <small>Entradas: ${money(ent)} · Despesas: ${money(desp)}</small>
      </div>
      <strong class="${saldo < 0 ? 'danger' : saldo > 0 ? 'income-text' : ''}">${money(saldo)}</strong>
    </div>`;
  }).join('');
}
function renderOpenFinance() {
  if (!loadedPeriod) {
    $('#of-accounts-list').innerHTML = '<p class="empty">Aguardando carregamento.</p>';
    $('#of-investments-list').innerHTML = '<p class="empty">Aguardando carregamento.</p>';
    return;
  }
  if (!ofAccounts.length) {
    $('#of-accounts-list').innerHTML = '<p class="empty">Nenhuma conta conectada. Conecte pelo Open Finance.</p>';
  } else {
    $('#of-accounts-list').innerHTML = ofAccounts.map(acc => {
      const isCard = acc.tipo === 'CARTAO_CREDITO';
      const tagLabel = isCard ? 'Cartão' : (acc.tipo === 'POUPANCA' ? 'Poupança' : 'Conta Corrente');
      const tagClass = isCard ? 'of-tag card' : 'of-tag';
      const details = isCard
        ? `Fatura atual: <strong>${money(acc.faturaAtual || 0)}</strong> · Limite: ${money(acc.limiteCredito || 0)}`
        : `Saldo disponível: <strong class="${acc.saldo < 0 ? 'danger' : 'income-text'}">${money(acc.saldo || 0)}</strong>`;
      return `<div class="of-card">
        <div class="of-card-header">
          <div>
            <strong>${clean(acc.instituicao)}</strong>
            <small class="muted" style="display:block">${clean(acc.nome)}</small>
          </div>
          <span class="${tagClass}">${tagLabel}</span>
        </div>
        <div class="goal-values" style="margin-top:8px">
          <span>${details}</span>
        </div>
      </div>`;
    }).join('');
  }

  if (!ofInvestments.length) {
    $('#of-investments-list').innerHTML = '<p class="empty">Nenhum investimento sincronizado.</p>';
  } else {
    const totalInvestido = ofInvestments.reduce((sum, inv) => sum + cents(inv.valorInvestido || 0), 0) / 100;
    const itemsHtml = ofInvestments.map(inv => {
      const rentab = inv.rentabilidade != null ? ` · Rentab.: ${Number(inv.rentabilidade).toFixed(2).replace('.', ',')}%` : '';
      return `<div class="of-card">
        <div class="of-card-header">
          <div>
            <strong>${clean(inv.instituicao)}</strong>
            <small class="muted" style="display:block">${clean(inv.nome)}</small>
          </div>
          <span class="of-tag inv">${clean(inv.tipo || 'INVESTIMENTO')}</span>
        </div>
        <div class="goal-values" style="margin-top:8px">
          <span>Posição: <strong>${money(inv.valorInvestido || 0)}</strong>${rentab}</span>
        </div>
      </div>`;
    }).join('');
    $('#of-investments-list').innerHTML = `<div class="account-item" style="margin-bottom:12px;background:var(--mint);border-color:transparent">
      <div>
        <strong style="color:var(--forest)">Total em Investimentos</strong>
        <small style="color:var(--forest)">Patrimônio consolidado</small>
      </div>
      <strong style="color:var(--forest)">${money(totalInvestido)}</strong>
    </div>` + itemsHtml;
  }
}
function openMovement(item) {
  editingId = item?.id || null;
  const form = $('#movement-form');
  form.reset();
  $('#form-error').textContent = '';
  $('#form-title').textContent = item ? 'Editar lançamento' : 'Novo lançamento';
  const date = localDate();
  form.elements.data.value = item?.data || (date.startsWith($('#period').value) ? date : `${$('#period').value}-01`);
  if (item) {
    if (![...form.elements.categoria.options].some(option => option.value === item.categoria)) form.elements.categoria.add(new Option(item.categoria, item.categoria));
    for (const key of ['descricao', 'valor', 'categoria', 'tipo', 'conta']) form.elements[key].value = item[key] ?? '';
  }
  form.elements.recorrente.checked = Boolean(item?.recorrente);
  $('#movement-dialog').showModal();
}
function endSession() {
  ++loadVersion;
  try { sessionStorage.removeItem('clareza-api-key'); } catch { /* unavailable storage */ }
  apiKey = '';
  movements = [];
  goals = [];
  ofAccounts = [];
  ofInvestments = [];
  loadedPeriod = null;
  $('#api-key').value = '';
  $('#dashboard').classList.add('hidden');
  $('#access').classList.remove('hidden');
  $('#logout').classList.add('hidden');
  $('#privacy').classList.add('hidden');
  document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
  render();
}
async function connect() {
  const button = $('#access-form button');
  button.disabled = true;
  $('#access-error').textContent = 'Conectando… O servidor pode levar até 90 segundos para responder.';
  try {
    apiUrl = validateUrl($('#api-url').value.trim());
    apiKey = $('#api-key').value.trim();
    if (!apiKey) throw new Error('Informe sua chave de acesso.');
    loadBudgets();
    if (!await load()) return;
    store(sessionStorage, 'clareza-api-url', apiUrl);
    store(sessionStorage, 'clareza-api-key', apiKey);
    $('#api-key').value = '';
    $('#access-error').textContent = '';
    $('#access').classList.add('hidden');
    for (const id of ['dashboard', 'privacy', 'logout']) $(`#${id}`).classList.remove('hidden');
  } catch (error) { $('#access-error').textContent = error.message; }
  finally { button.disabled = false; }
}
async function reload() { try { await load(); } catch (error) { if (!apiKey) $('#access-error').textContent = error.message; } }
$('#access-form').onsubmit = event => { event.preventDefault(); connect(); };
$('#logout').onclick = endSession;
$('#reload').onclick = reload;
$('#period').onchange = () => { if (!$('#period').value) $('#period').value = localDate().slice(0, 7); reload(); };
$('#search').oninput = renderList;
$('#type-filter').onchange = renderList;
$('#add').onclick = () => openMovement();
$('#close').onclick = () => { if (!saving) $('#movement-dialog').close(); };
$('#movement-dialog').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
$('#movement-form').onsubmit = async event => {
  event.preventDefault();
  if (saving) return;
  const form = event.currentTarget, data = Object.fromEntries(new FormData(form));
  const payload = { ...data, descricao: data.descricao.trim(), conta: data.conta.trim(), valor: Number(data.valor), recorrente: data.recorrente === 'on' };
  if (!payload.descricao || !Number.isFinite(payload.valor) || payload.valor <= 0) { $('#form-error').textContent = 'Informe descrição e valor válidos.'; return; }
  saving = true;
  const controls = [...form.elements];
  controls.forEach(control => control.disabled = true);
  $('#form-error').textContent = 'Salvando…';
  try {
    await request(editingId ? `/movimentacoes/${editingId}` : '/movimentacoes', { method: editingId ? 'PUT' : 'POST', body: JSON.stringify(payload) });
    $('#movement-dialog').close();
    $('#period').value = payload.data.slice(0, 7);
    try { await load(); }
    catch { status('Lançamento salvo, mas a lista não pôde ser atualizada. Use Atualizar antes de cadastrar novamente.', true); }
  } catch (error) {
    const message = `${error.message} Se a conexão caiu durante o envio, atualize a lista antes de tentar novamente.`;
    $('#form-error').textContent = message;
    if (!apiKey) $('#access-error').textContent = message;
  } finally { saving = false; controls.forEach(control => control.disabled = false); }
};
$('#list').onclick = async event => {
  const edit = event.target.closest('[data-edit]'), remove = event.target.closest('[data-delete]');
  if (edit) { const item = movements.find(x => String(x.id) === edit.dataset.edit); if (item) openMovement(item); }
  if (remove && !remove.disabled && confirm('Excluir este lançamento? Essa operação não pode ser desfeita.')) {
    remove.disabled = true;
    try { await request(`/movimentacoes/${remove.dataset.delete}`, { method: 'DELETE' }); await reload(); }
    catch (error) { status(error.message, true); if (!apiKey) $('#access-error').textContent = error.message; }
    finally { remove.disabled = false; }
  }
};
$('#edit-budgets').onclick = () => {
  $('#budget-fields').innerHTML = CATEGORIES.map(category => `<label>${category}<input name="${category}" type="number" min="0" max="999999999" step="0.01" value="${Number(budgets[category]) || ''}" placeholder="Sem limite"></label>`).join('');
  $('#budget-dialog').showModal();
};
$('#close-budgets').onclick = () => $('#budget-dialog').close();
$('#repeat-recurring').onclick = async () => {
  if (!loadedPeriod) return;
  if (!confirm(`Trazer os lançamentos recorrentes do mês anterior para ${monthName($('#period').value)}?`)) return;
  status('Buscando e importando recorrentes…');
  try {
    const res = await request(`/movimentacoes/recorrencias?periodo=${$('#period').value}`, { method: 'POST' });
    const count = res?.criados || 0;
    await load();
    status(count > 0 ? `${count} lançamento(s) recorrente(s) importado(s) para este mês.` : 'Nenhum novo lançamento recorrente para importar.');
  } catch (error) {
    status(error.message, true);
  }
};
$('#budget-form').onsubmit = async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = new FormData(form);
  const newBudgets = Object.fromEntries(CATEGORIES.map(category => [category, Number(data.get(category)) || 0]));
  try {
    const updated = await request('/limites', { method: 'PUT', body: JSON.stringify(newBudgets) });
    budgets = Object.fromEntries(CATEGORIES.map(category => [category, Number(updated?.[category]) || 0]));
    store(localStorage, `clareza-budgets:${apiUrl}`, JSON.stringify(budgets));
    $('#budget-dialog').close();
    renderBudgets();
    status('Limites salvos e sincronizados com seus dispositivos.');
  } catch (error) {
    budgets = newBudgets;
    const saved = store(localStorage, `clareza-budgets:${apiUrl}`, JSON.stringify(budgets));
    $('#budget-dialog').close();
    renderBudgets();
    status(saved ? 'Limites salvos localmente neste navegador.' : 'Limites aplicados nesta sessão. O navegador bloqueou o armazenamento.', !saved);
  }
};
function openGoal(item) {
  editingGoalId = item?.id || null;
  const form = $('#goal-form');
  form.reset();
  $('#goal-error').textContent = '';
  $('#goal-form-title').textContent = item ? 'Editar meta' : 'Nova meta';
  if (item) {
    form.elements.nome.value = item.nome ?? '';
    form.elements.valorAlvo.value = item.valorAlvo ?? '';
    form.elements.valorAtual.value = item.valorAtual ?? '0';
    form.elements.prazo.value = item.prazo ?? '';
  }
  $('#goal-dialog').showModal();
}
$('#add-goal').onclick = () => openGoal();
$('#close-goal').onclick = () => { if (!saving) $('#goal-dialog').close(); };
$('#goal-dialog').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
$('#goal-form').onsubmit = async event => {
  event.preventDefault();
  if (saving) return;
  const form = event.currentTarget, data = Object.fromEntries(new FormData(form));
  const payload = {
    nome: data.nome.trim(),
    valorAlvo: Number(data.valorAlvo),
    valorAtual: Number(data.valorAtual) || 0,
    prazo: data.prazo ? data.prazo : null
  };
  if (!payload.nome || !Number.isFinite(payload.valorAlvo) || payload.valorAlvo <= 0) {
    $('#goal-error').textContent = 'Informe um nome e valor alvo válidos.';
    return;
  }
  saving = true;
  const controls = [...form.elements];
  controls.forEach(control => control.disabled = true);
  $('#goal-error').textContent = 'Salvando…';
  try {
    await request(editingGoalId ? `/metas/${editingGoalId}` : '/metas', {
      method: editingGoalId ? 'PUT' : 'POST',
      body: JSON.stringify(payload)
    });
    $('#goal-dialog').close();
    try {
      const items = await request('/metas');
      goals = Array.isArray(items) ? items : [];
    } catch { /* keep current goals */ }
    renderGoals();
    status('Meta salva com sucesso.');
  } catch (error) {
    $('#goal-error').textContent = error.message;
  } finally {
    saving = false;
    controls.forEach(control => control.disabled = false);
  }
};
$('#goal-list').onclick = async event => {
  const edit = event.target.closest('[data-edit-goal]'), remove = event.target.closest('[data-delete-goal]');
  if (edit) {
    const item = goals.find(x => String(x.id) === edit.dataset.editGoal);
    if (item) openGoal(item);
  }
  if (remove && !remove.disabled && confirm('Excluir esta meta? Essa operação não pode ser desfeita.')) {
    remove.disabled = true;
    try {
      await request(`/metas/${remove.dataset.deleteGoal}`, { method: 'DELETE' });
      goals = goals.filter(x => String(x.id) !== remove.dataset.deleteGoal);
      renderGoals();
      status('Meta excluída.');
    } catch (error) {
      status(error.message, true);
    } finally {
      remove.disabled = false;
    }
  }
};
function updatePrivacy() {
  $('#privacy').textContent = privacyMode ? 'Mostrar valores' : 'Ocultar valores';
  $('#privacy').setAttribute('aria-pressed', String(privacyMode));
}
$('#privacy').onclick = () => { privacyMode = !privacyMode; store(localStorage, 'clareza-privacy-mode', String(privacyMode)); updatePrivacy(); render(); };
$('#export').onclick = async () => {
  if (exporting || !loadedPeriod || !movements.length) return;
  if (privacyMode && !confirm('O CSV contém valores reais mesmo com o modo privacidade. Exportar?')) return;
  const cell = value => {
    let text = String(value ?? '');
    if (/^[\s]*[=+@\-\t\r\n]/.test(text)) text = `'${text}`;
    return `"${text.replaceAll('"', '""')}"`;
  };
  const rows = movements.map(x => [x.data, x.descricao, x.categoria, x.conta || '', x.tipo, Number(x.valor).toFixed(2).replace('.', ','), x.recorrente ? 'Sim' : 'Não']);
  const csv = [['Data', 'Descrição', 'Categoria', 'Conta', 'Tipo', 'Valor', 'Recorrente'], ...rows].map(row => row.map(cell).join(';')).join('\r\n');
  if (nativeAndroid) {
    exporting = true;
    $('#export').disabled = true;
    try {
      const result = await window.Capacitor.nativePromise('ClarezaFiles', 'exportCsv', {
        filename: `clareza-${loadedPeriod}.csv`, content: `\uFEFF${csv}`
      });
      if (apiKey) status(result.saved ? 'CSV salvo no local escolhido.' : 'Exportação cancelada.');
    } catch {
      if (apiKey) status('Não foi possível salvar o CSV. Tente novamente e escolha outro local.', true);
    } finally { exporting = false; render(); }
    return;
  }
  const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url; link.download = `clareza-${loadedPeriod}.csv`;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$('#ai-cat-btn').onclick = async () => {
  const form = $('#movement-form');
  const desc = form.elements.descricao.value.trim();
  if (!desc) {
    $('#form-error').textContent = 'Digite uma descrição para a IA categorizar.';
    return;
  }
  const btn = $('#ai-cat-btn');
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Pensando…';
  try {
    const res = await request('/ia/categorizar', { method: 'POST', body: JSON.stringify({ descricao: desc }) });
    if (res && res.categoria) {
      if (![...form.elements.categoria.options].some(o => o.value === res.categoria)) {
        form.elements.categoria.add(new Option(res.categoria, res.categoria));
      }
      form.elements.categoria.value = res.categoria;
      $('#form-error').textContent = `Categoria sugerida (${res.origem === 'GEMINI' ? 'Gemini IA' : 'Regra'}): ${res.categoria}`;
    }
  } catch (error) {
    $('#form-error').textContent = `Não foi possível sugerir: ${error.message}`;
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
};
$('#ai-insight-btn').onclick = async () => {
  if (!loadedPeriod) return;
  const btn = $('#ai-insight-btn');
  btn.disabled = true;
  const originalText = btn.textContent;
  btn.textContent = 'Gerando insights…';
  try {
    const res = await request(`/ia/insights?periodo=${$('#period').value}`);
    if (res) {
      if (res.titulo) $('#insight-title').textContent = res.titulo;
      if (res.resumo) $('#insight-text').textContent = res.resumo;
      const sugBox = $('#ai-suggestions');
      if (Array.isArray(res.sugestoes) && res.sugestoes.length) {
        sugBox.innerHTML = `<strong>Dicas da IA:</strong><ul>${res.sugestoes.map(s => `<li>${clean(s)}</li>`).join('')}</ul>`;
        sugBox.classList.remove('hidden');
      }
      status(`Leitura do mês gerada com ${res.origem === 'GEMINI' ? 'Google Gemini' : 'análise inteligente'}.`);
    }
  } catch (error) {
    status(error.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
};
$('#open-finance-btn').onclick = () => {
  $('#of-error').textContent = '';
  $('#of-dialog').showModal();
};
$('#close-of').onclick = () => {
  if (!saving) $('#of-dialog').close();
};
$('#of-dialog').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
$('#connect-real-btn').onclick = async () => {
  if (saving) return;
  const btn = $('#connect-real-btn');
  saving = true;
  btn.disabled = true;
  $('#of-error').textContent = 'Iniciando conexão segura com a Pluggy…';
  try {
    const res = await request('/open-finance/connect-token', { method: 'POST' });
    if (res && res.connectToken && window.PluggyConnect) {
      $('#of-dialog').close();
      const pluggy = new window.PluggyConnect({
        connectToken: res.connectToken,
        onSuccess: async (itemData) => {
          status('Banco conectado! Sincronizando contas, faturas e investimentos…');
          try {
            const syncRes = await request('/open-finance/sincronizar', {
              method: 'POST',
              body: JSON.stringify({ itemId: itemData?.item?.id })
            });
            await load();
            status(`Conexão concluída: ${syncRes?.contasSincronizadas || 0} conta(s), ${syncRes?.investimentosSincronizados || 0} investimento(s) e ${syncRes?.transacoesImportadas || 0} lançamento(s) atualizados.`);
          } catch (e) {
            status(`Banco conectado, mas houve falha ao sincronizar: ${e.message}`, true);
          }
        },
        onError: (error) => {
          status(`Erro no Pluggy Connect: ${error?.message || 'Falha na conexão bancária'}`, true);
        }
      });
      pluggy.init();
    } else {
      $('#of-error').textContent = res?.mensagem || 'Chaves da Pluggy não configuradas no Render. Use "Simular Contas em Modo Sandbox" para testar.';
    }
  } catch (error) {
    $('#of-error').textContent = error.message;
  } finally {
    saving = false;
    btn.disabled = false;
  }
};
$('#sync-of-btn').onclick = async () => {
  if (saving) return;
  const btn = $('#sync-of-btn');
  saving = true;
  btn.disabled = true;
  $('#of-error').textContent = 'Sincronizando modo Sandbox…';
  try {
    const res = await request('/open-finance/sincronizar', { method: 'POST', body: JSON.stringify({ itemId: 'mock-sandbox-item' }) });
    $('#of-dialog').close();
    await load();
    const msg = `Open Finance sincronizado (modo simulação): ${res?.contasSincronizadas || 0} conta(s), ${res?.investimentosSincronizados || 0} investimento(s) e ${res?.transacoesImportadas || 0} lançamento(s) importado(s).`;
    status(msg);
  } catch (error) {
    $('#of-error').textContent = error.message;
  } finally {
    saving = false;
    btn.disabled = false;
  }
};
window.clarezaBack = () => {
  if (saving || exporting) return true;
  const dialog = document.querySelector('dialog[open]');
  if (dialog) { dialog.close(); return true; }
  return false;
};
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); if (nativeAndroid) return; deferredInstallPrompt = event; $('#install').classList.remove('hidden'); });
$('#install').onclick = async () => { if (!deferredInstallPrompt) return; await deferredInstallPrompt.prompt(); await deferredInstallPrompt.userChoice; deferredInstallPrompt = null; $('#install').classList.add('hidden'); };
window.addEventListener('offline', () => status('Você está offline. É necessário conectar à internet para consultar e salvar lançamentos.', true));
window.addEventListener('online', () => { if (apiKey) reload(); });
$('#period').value = localDate().slice(0, 7);
$('#api-url').value = apiUrl;
updatePrivacy();
if (apiKey) { $('#api-key').value = apiKey; connect(); }
if (!nativeAndroid && 'serviceWorker' in navigator) window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => { /* app remains usable without installation */ }));
