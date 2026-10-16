'use strict';

const $ = selector => document.querySelector(selector);
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
let movements = [], budgets = {}, editingId = null, deferredInstallPrompt;
let loadedPeriod = null, loadVersion = 0, saving = false;
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
  let response;
  try {
    response = await fetch(`${apiUrl}${path}`, {
      ...options, cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(90000),
      headers: { 'X-API-Key': apiKey, ...(options.body ? { 'Content-Type': 'application/json' } : {}) }
    });
  } catch (error) {
    throw new Error(error.name === 'TimeoutError' ? 'O servidor demorou para responder. Aguarde e tente novamente.' : 'Sem conexão com o servidor. Verifique sua internet e o endereço da API.');
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
  return response.status === 204 ? null : response.json();
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
  $('#export').disabled = !ready || !movements.length;
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
$('#budget-form').onsubmit = event => {
  event.preventDefault();
  const data = new FormData(event.currentTarget);
  budgets = Object.fromEntries(CATEGORIES.map(category => [category, Number(data.get(category)) || 0]));
  const saved = store(localStorage, `clareza-budgets:${apiUrl}`, JSON.stringify(budgets));
  $('#budget-dialog').close();
  renderBudgets();
  status(saved ? 'Limites salvos neste navegador para todos os meses.' : 'Limites aplicados nesta sessão. O navegador bloqueou o armazenamento.', !saved);
};
function updatePrivacy() {
  $('#privacy').textContent = privacyMode ? 'Mostrar valores' : 'Ocultar valores';
  $('#privacy').setAttribute('aria-pressed', String(privacyMode));
}
$('#privacy').onclick = () => { privacyMode = !privacyMode; store(localStorage, 'clareza-privacy-mode', String(privacyMode)); updatePrivacy(); render(); };
$('#export').onclick = () => {
  if (!loadedPeriod || !movements.length) return;
  if (privacyMode && !confirm('O CSV contém valores reais mesmo com o modo privacidade. Exportar?')) return;
  const cell = value => {
    let text = String(value ?? '');
    if (/^[\s]*[=+@\-\t\r\n]/.test(text)) text = `'${text}`;
    return `"${text.replaceAll('"', '""')}"`;
  };
  const rows = movements.map(x => [x.data, x.descricao, x.categoria, x.conta || '', x.tipo, Number(x.valor).toFixed(2).replace('.', ','), x.recorrente ? 'Sim' : 'Não']);
  const csv = [['Data', 'Descrição', 'Categoria', 'Conta', 'Tipo', 'Valor', 'Recorrente'], ...rows].map(row => row.map(cell).join(';')).join('\r\n');
  const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url; link.download = `clareza-${loadedPeriod}.csv`;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); deferredInstallPrompt = event; $('#install').classList.remove('hidden'); });
$('#install').onclick = async () => { if (!deferredInstallPrompt) return; await deferredInstallPrompt.prompt(); await deferredInstallPrompt.userChoice; deferredInstallPrompt = null; $('#install').classList.add('hidden'); };
window.addEventListener('offline', () => status('Você está offline. É necessário conectar à internet para consultar e salvar lançamentos.', true));
window.addEventListener('online', () => { if (apiKey) reload(); });
$('#period').value = localDate().slice(0, 7);
$('#api-url').value = apiUrl;
updatePrivacy();
if (apiKey) { $('#api-key').value = apiKey; connect(); }
if ('serviceWorker' in navigator) window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => { /* app remains usable without installation */ }));
