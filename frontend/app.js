const DEMO_MODE = true;
const API = 'https://controle-despesas-mensais.onrender.com/api';
let deferredInstallPrompt;
const demoTransactions = [
  { id: 'demo-1', descricao: 'Supermercado Boa Compra', categoria: 'Alimentação', data: '2026-09-01', valor: 186.4, tipo: 'SAIDA' },
  { id: 'demo-2', descricao: 'Salário mensal', categoria: 'Salário', data: '2026-09-01', valor: 6850, tipo: 'ENTRADA' },
  { id: 'demo-3', descricao: 'Assinatura de streaming', categoria: 'Lazer', data: '2026-08-30', valor: 39.9, tipo: 'SAIDA' },
  { id: 'demo-4', descricao: 'Corrida por aplicativo', categoria: 'Transporte', data: '2026-08-29', valor: 24.5, tipo: 'SAIDA' }
];
let transactions = [...demoTransactions];
let editingId = null;
const money = value => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
const icons = { Alimentação: '⌁', Transporte: '↗', Lazer: '○', Salário: '↑', default: '◆' };
function renderTransactions(items) {
  document.querySelector('#transactions').innerHTML = items.length ? items.map(item => `<div class="transaction"><div class="transaction-icon">${icons[item.categoria] || icons.default}</div><div class="transaction-main"><strong>${item.descricao}</strong><small>${item.categoria} · ${new Date(item.data + 'T12:00:00').toLocaleDateString('pt-BR')}</small></div><span class="transaction-value ${item.tipo === 'ENTRADA' ? 'positive' : 'out'}">${item.tipo === 'ENTRADA' ? '+' : '-'} ${money(item.valor)}</span><div class="transaction-actions"><button data-action="edit" data-id="${item.id}" aria-label="Editar ${item.descricao}">✎</button><button data-action="delete" data-id="${item.id}" aria-label="Excluir ${item.descricao}">×</button></div></div>`).join('') : '<p class="empty-state">Nenhum lançamento encontrado.</p>';
}
function updateMetrics() {
  const entries = transactions.filter(item => item.tipo === 'ENTRADA').reduce((sum, item) => sum + Number(item.valor), 0);
  const expenses = transactions.filter(item => item.tipo === 'SAIDA').reduce((sum, item) => sum + Number(item.valor), 0);
  const balance = entries - expenses;
  document.querySelector('#saldo').textContent = money(balance);
  document.querySelector('#entradas').textContent = money(entries);
  document.querySelector('#despesas').textContent = money(expenses);
  document.querySelector('#saldo-periodo').textContent = money(balance);
  document.querySelector('#despesas-meta').textContent = `${transactions.filter(item => item.tipo === 'SAIDA').length} lançamento(s)`;
  document.querySelector('#saldo-meta').textContent = balance >= 0 ? 'Disponível no período' : 'Atenção: saldo negativo';
  document.querySelector('#saldo-periodo').classList.toggle('negative', balance < 0);
}
function refresh() {
  const query = document.querySelector('#search').value.toLowerCase().trim();
  const type = document.querySelector('#type-filter').value;
  const visible = transactions.filter(item => (type === 'TODOS' || item.tipo === type) && `${item.descricao} ${item.categoria}`.toLowerCase().includes(query)).sort((a, b) => b.data.localeCompare(a.data));
  renderTransactions(visible);
  updateMetrics();
}
function saveLocal() { localStorage.setItem('clareza-demo-transactions', JSON.stringify(transactions)); }
async function loadTransactions() {
  if (DEMO_MODE) {
    const saved = localStorage.getItem('clareza-demo-transactions');
    transactions = saved ? JSON.parse(saved) : [...demoTransactions];
    refresh();
    return;
  }
  try {
    const response = await fetch(`${API}/movimentacoes`);
    if (!response.ok) throw new Error('API offline');
    const items = await response.json();
    transactions = items;
    refresh();
  } catch { transactions = [...demoTransactions]; refresh(); }
}
const modal = document.querySelector('#modal');
function openModal(item = null) {
  editingId = item?.id || null;
  const form = document.querySelector('#movement-form');
  form.reset();
  document.querySelector('#modal-eyebrow').textContent = item ? 'Editar movimentação' : 'Nova movimentação';
  document.querySelector('#modal-title').textContent = item ? 'Editar lançamento' : 'Registrar lançamento';
  form.elements.data.value = item?.data || new Date().toISOString().slice(0, 10);
  if (item) Object.entries(item).forEach(([key, value]) => { if (form.elements[key]) form.elements[key].value = value; });
  modal.classList.remove('hidden');
}
document.querySelector('#new-movement').onclick = () => openModal();
document.querySelector('#close-modal').onclick = () => modal.classList.add('hidden');
document.querySelector('#movement-form').onsubmit = async event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.target));
  const payload = { ...data, id: editingId || (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()), valor: Number(data.valor), recorrente: false };
  if (DEMO_MODE) {
    transactions = editingId ? transactions.map(item => item.id === editingId ? payload : item) : [payload, ...transactions];
    saveLocal();
  } else {
    await fetch(`${API}/movimentacoes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  }
  modal.classList.add('hidden'); event.target.reset(); editingId = null; refresh();
};
document.querySelector('#transactions').onclick = event => {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const item = transactions.find(entry => entry.id === button.dataset.id);
  if (button.dataset.action === 'edit') openModal(item);
  if (button.dataset.action === 'delete' && item && window.confirm(`Excluir "${item.descricao}"?`)) {
    transactions = transactions.filter(entry => entry.id !== item.id); saveLocal(); refresh();
  }
};
document.querySelector('#search').oninput = refresh;
document.querySelector('#type-filter').onchange = refresh;
document.querySelector('#ask-ai').onclick = async () => {
  const button = document.querySelector('#ask-ai');
  const summary = document.querySelector('#ai-resumo');
  button.disabled = true; summary.textContent = 'Analisando suas movimentações...';
  if (DEMO_MODE) {
    const total = transactions.filter(item => item.tipo === 'SAIDA').reduce((sum, item) => sum + Number(item.valor), 0);
    summary.textContent = `Nesta demonstração, suas despesas somam ${money(total)}. O próximo passo seria conectar seus dados com segurança.`;
  } else {
    try { const response = await fetch(`${API}/ia/analisar`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mensagem: 'Analise meu mês' }) }); const data = await response.json(); summary.textContent = data.resposta; } catch { summary.textContent = 'Conecte a API para receber uma análise personalizada dos seus gastos.'; }
  }
  button.disabled = false;
};
window.addEventListener('beforeinstallprompt', event => {
  event.preventDefault();
  deferredInstallPrompt = event;
  document.querySelector('#install-app').classList.remove('hidden');
});
document.querySelector('#install-app').onclick = async () => {
  if (!deferredInstallPrompt) return;
  deferredInstallPrompt.prompt();
  await deferredInstallPrompt.userChoice;
  deferredInstallPrompt = null;
  document.querySelector('#install-app').classList.add('hidden');
};
if ('serviceWorker' in navigator) window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'));
loadTransactions();
