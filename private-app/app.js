const API = 'https://controle-despesas-mensais.onrender.com/api';
let apiKey = sessionStorage.getItem('clareza-api-key') || '';
let movements = [];
let editingId = null;
const money = value => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
const el = selector => document.querySelector(selector);
function render() {
  const entries = movements.filter(item => item.tipo === 'ENTRADA').reduce((sum, item) => sum + Number(item.valor), 0);
  const expenses = movements.filter(item => item.tipo === 'SAIDA').reduce((sum, item) => sum + Number(item.valor), 0);
  el('#balance').textContent = money(entries - expenses);
  el('#entries').textContent = money(entries);
  el('#expenses').textContent = money(expenses);
  el('#list').innerHTML = movements.length ? movements.map(item => `<div class="item"><div class="item-main"><strong>${item.descricao}</strong><small>${item.categoria} · ${new Date(item.data + 'T12:00:00').toLocaleDateString('pt-BR')}</small></div><span class="value ${item.tipo === 'ENTRADA' ? 'positive' : ''}">${item.tipo === 'ENTRADA' ? '+' : '-'} ${money(item.valor)}</span><div class="actions"><button data-edit="${item.id}" aria-label="Editar">✎</button><button data-delete="${item.id}" aria-label="Excluir">×</button></div></div>`).join('') : '<p>Não há movimentações cadastradas.</p>';
}
async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, { ...options, headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey, ...(options.headers || {}) } });
  if (!response.ok) throw new Error(response.status === 401 ? 'Chave inválida.' : `Erro ${response.status}`);
  return response.status === 204 ? null : response.json();
}
async function connect() {
  try { movements = await request('/movimentacoes'); el('#access').classList.add('hidden'); el('#dashboard').classList.remove('hidden'); el('#logout').classList.remove('hidden'); render(); }
  catch (error) { el('#access-error').textContent = error.message; }
}
function openForm(item = null) {
  editingId = item?.id || null;
  const form = el('#movement-form'); form.reset();
  el('#form-title').textContent = item ? 'Editar lançamento' : 'Novo lançamento';
  form.elements.data.value = item?.data || new Date().toISOString().slice(0, 10);
  if (item) Object.entries(item).forEach(([key, value]) => { if (form.elements[key]) form.elements[key].value = value; });
  el('#movement-dialog').showModal();
}
el('#access-form').onsubmit = event => { event.preventDefault(); apiKey = el('#api-key').value.trim(); sessionStorage.setItem('clareza-api-key', apiKey); connect(); };
el('#logout').onclick = () => { sessionStorage.removeItem('clareza-api-key'); apiKey = ''; location.reload(); };
el('#add').onclick = () => openForm();
el('#close').onclick = () => el('#movement-dialog').close();
el('#movement-form').onsubmit = async event => { event.preventDefault(); const data = Object.fromEntries(new FormData(event.target)); const payload = { ...data, valor: Number(data.valor), tipo: data.tipo, recorrente: false }; try { movements = editingId ? await request(`/movimentacoes/${editingId}`, { method: 'PUT', body: JSON.stringify(payload) }).then(() => request('/movimentacoes')) : await request('/movimentacoes', { method: 'POST', body: JSON.stringify(payload) }); el('#movement-dialog').close(); editingId = null; render(); } catch (error) { alert(error.message); } };
el('#list').onclick = async event => { const edit = event.target.closest('[data-edit]'); const remove = event.target.closest('[data-delete]'); if (edit) openForm(movements.find(item => item.id === edit.dataset.edit)); if (remove && confirm('Excluir este lançamento?')) { await request(`/movimentacoes/${remove.dataset.delete}`, { method: 'DELETE' }); movements = movements.filter(item => item.id !== remove.dataset.delete); render(); } };
if (apiKey) { el('#api-key').value = apiKey; connect(); }
