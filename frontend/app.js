const DEMO_MODE = true;
const API = 'https://controle-despesas-mensais.onrender.com/api';
const demoTransactions = [
  { descricao: 'Supermercado Boa Compra', categoria: 'Alimentação', data: '2026-09-01', valor: 186.4, tipo: 'SAIDA' },
  { descricao: 'Salário mensal', categoria: 'Receita', data: '2026-09-01', valor: 6850, tipo: 'ENTRADA' },
  { descricao: 'Assinatura de streaming', categoria: 'Lazer', data: '2026-08-30', valor: 39.9, tipo: 'SAIDA' },
  { descricao: 'Corrida por aplicativo', categoria: 'Transporte', data: '2026-08-29', valor: 24.5, tipo: 'SAIDA' }
];
let transactions = [...demoTransactions];
const money = value => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
const icons = { Alimentação: '⌁', Transporte: '↗', Lazer: '○', Receita: '↑', default: '◆' };
function renderTransactions(items) {
  document.querySelector('#transactions').innerHTML = items.slice(0, 6).map(item => `<div class="transaction"><div class="transaction-icon">${icons[item.categoria] || icons.default}</div><div class="transaction-main"><strong>${item.descricao}</strong><small>${item.categoria} · ${new Date(item.data + 'T12:00:00').toLocaleDateString('pt-BR')}</small></div><span class="transaction-value ${item.tipo === 'ENTRADA' ? 'positive' : 'out'}">${item.tipo === 'ENTRADA' ? '+' : '-'} ${money(item.valor)}</span></div>`).join('');
}
async function loadTransactions() {
  if (DEMO_MODE) {
    const saved = localStorage.getItem('clareza-demo-transactions');
    transactions = saved ? JSON.parse(saved) : [...demoTransactions];
    renderTransactions(transactions);
    return;
  }
  try {
    const response = await fetch(`${API}/movimentacoes`);
    if (!response.ok) throw new Error('API offline');
    const items = await response.json();
    transactions = items.length ? items : demoTransactions;
    renderTransactions(transactions);
  } catch { transactions = [...demoTransactions]; renderTransactions(transactions); }
}
const modal = document.querySelector('#modal');
document.querySelector('#new-movement').onclick = () => modal.classList.remove('hidden');
document.querySelector('#close-modal').onclick = () => modal.classList.add('hidden');
document.querySelector('#movement-form').onsubmit = async event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.target));
  const payload = { ...data, valor: Number(data.valor), tipo: 'SAIDA', data: new Date().toISOString().slice(0, 10), recorrente: false };
  if (DEMO_MODE) {
    transactions = [payload, ...transactions];
    localStorage.setItem('clareza-demo-transactions', JSON.stringify(transactions));
  } else {
    await fetch(`${API}/movimentacoes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  }
  modal.classList.add('hidden'); event.target.reset(); loadTransactions();
};
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
loadTransactions();
