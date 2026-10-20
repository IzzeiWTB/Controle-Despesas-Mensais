const { test, expect } = require('@playwright/test');

async function login(page) {
  await page.goto('/');
  await page.locator('#api-key').fill('browser-test-only');
  await page.locator('#access-form button').click();
  await expect(page.locator('#dashboard')).toBeVisible();
}

test('login, CRUD, month filters, budgets, privacy, CSV and logout', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await page.locator('#api-key').fill('invalid-key');
  await page.locator('#access-form button').click();
  await expect(page.locator('#access-error')).toContainText('inválida');
  await login(page);
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');
  await page.locator('#add').click();
  await page.locator('[name=descricao]').fill('=Mercado teste');
  await page.locator('[name=valor]').fill('125.50');
  await expect(page.locator('[name=data]')).toHaveValue(/2026-10-/);
  await page.locator('[name=conta]').fill('Carteira de teste');
  await page.locator('[name=recorrente]').check();
  await page.locator('#movement-form [type=submit]').click();
  await expect(page.locator('.transaction')).toHaveCount(1);
  await expect(page.locator('.transaction')).toContainText('Carteira de teste');
  await expect(page.locator('.transaction')).toContainText('Recorrente');
  await page.reload();
  await expect(page.locator('#dashboard')).toBeVisible();
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('.transaction')).toHaveCount(1);
  await page.locator('[data-edit]').click();
  await page.locator('[name=valor]').fill('99.90');
  await page.locator('#movement-form [type=submit]').click();
  await expect(page.locator('#expenses')).toContainText('99,90');
  await page.locator('#search').fill('sem correspondencia');
  await expect(page.locator('.transaction')).toHaveCount(0);
  await page.locator('#search').fill('');
  await page.locator('#edit-budgets').click();
  await page.locator('#budget-fields [name="Alimentação"]').fill('200');
  await page.locator('#budget-form [type=submit]').click();
  await expect(page.locator('#budget-list')).toContainText('100,10');
  await page.locator('#privacy').click();
  await expect(page.locator('#expenses')).toHaveText('••••••');
  await expect(page.locator('#saving-rate')).toHaveText('••••');
  await expect(page.locator('#insight-text')).toContainText('Valores ocultos');
  await page.locator('#privacy').click();
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#export').click();
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  let csv = ''; for await (const chunk of stream) csv += chunk.toString();
  expect(csv).toContain("'=Mercado teste");
  await page.locator('#period').fill('2026-11');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');
  await expect(page.locator('.transaction')).toHaveCount(0);
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('.transaction')).toHaveCount(1);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-delete]').click();
  await expect(page.locator('.transaction')).toHaveCount(0);
  await page.locator('#logout').click();
  await expect(page.locator('#access')).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('clareza-api-key'))).toBeNull();
  expect(errors).toEqual([]);
});

test('mobile editing is accessible and page has no horizontal overflow', async ({ page, request }) => {
  const result = await request.post('/api/movimentacoes', {
    headers: { 'X-API-Key': 'browser-test-only' },
    data: { descricao: 'Teste celular', valor: 10, categoria: 'Outros', tipo: 'SAIDA', data: '2026-10-16' }
  });
  const item = await result.json();
  try {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.locator('#period').fill('2026-10');
    await page.locator('#period').dispatchEvent('change');
    await expect(page.locator('[data-edit]')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.locator('[data-edit]').click();
    await expect(page.locator('#movement-dialog')).toBeVisible();
    await page.locator('#close').click();
    await page.screenshot({ path: 'test-results/mobile.png', fullPage: true });
  } finally { await request.delete(`/api/movimentacoes/${item.id}`, { headers: { 'X-API-Key': 'browser-test-only' } }); }
});

test('failed reload clears old month totals and exposes retry', async ({ page }) => {
  await login(page);
  await page.route('**/api/movimentacoes?**', route => route.abort());
  await page.locator('#period').fill('2026-11');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Sem conexão');
  await expect(page.locator('#balance')).toHaveText('—');
  await expect(page.locator('#export')).toBeDisabled();
  await page.unroute('**/api/movimentacoes?**');
  await page.locator('#reload').click();
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');
});

test('service worker does not cache authenticated responses', async ({ page }) => {
  await login(page);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await expect(page.locator('#dashboard')).toBeVisible();
  const cached = await page.evaluate(async () => {
    const names = await caches.keys();
    const all = await Promise.all(names.map(async name => (await (await caches.open(name)).keys()).map(request => request.url)));
    return all.flat();
  });
  expect(cached.some(url => url.includes('/api/'))).toBeFalsy();
});

test('financial goals, recurring repeat, and account breakdown', async ({ page }) => {
  await login(page);
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');

  // Test Goal creation
  await page.locator('#add-goal').click();
  await page.locator('#goal-form [name=nome]').fill('Comprar notebook');
  await page.locator('#goal-form [name=valorAlvo]').fill('4000');
  await page.locator('#goal-form [name=valorAtual]').fill('1500');
  await page.locator('#goal-form [name=prazo]').fill('2026-12-31');
  await page.locator('#goal-form [type=submit]').click();
  await expect(page.locator('.goal')).toHaveCount(1);
  await expect(page.locator('.goal')).toContainText('Comprar notebook');
  await expect(page.locator('.goal')).toContainText('38%');

  // Edit Goal
  await page.locator('[data-edit-goal]').click();
  await page.locator('#goal-form [name=valorAtual]').fill('4000');
  await page.locator('#goal-form [type=submit]').click();
  await expect(page.locator('.goal')).toContainText('100%');

  // Delete Goal
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-delete-goal]').click();
  await expect(page.locator('.goal')).toHaveCount(0);

  // Add movement with account
  await page.locator('#add').click();
  await page.locator('[name=descricao]').fill('Salário Mensal');
  await page.locator('[name=valor]').fill('5000');
  await page.locator('[name=tipo]').selectOption('ENTRADA');
  await page.locator('[name=conta]').fill('Banco do Brasil');
  await page.locator('[name=recorrente]').check();
  await page.locator('#movement-form [type=submit]').click();

  // Verify account breakdown
  await expect(page.locator('#account-list')).toContainText('Banco do Brasil');
  await expect(page.locator('#account-list')).toContainText('5.000,00');

  // Go to next month 2026-11 and repeat recurring
  await page.locator('#period').fill('2026-11');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');
  await expect(page.locator('.transaction')).toHaveCount(0);

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#repeat-recurring').click();
  await expect(page.locator('.transaction')).toHaveCount(1);
  await expect(page.locator('.transaction')).toContainText('Salário Mensal');

  // Clean up
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-delete]').click();
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-delete]').click();
});

test('ai categorization, insights and open finance synchronization', async ({ page }) => {
  await login(page);
  await page.locator('#period').fill('2026-10');
  await page.locator('#period').dispatchEvent('change');
  await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');

  // 1. AI Categorization in movement form
  await page.locator('#add').click();
  await page.locator('[name=descricao]').fill('iFood Marmita');
  await page.locator('#ai-cat-btn').click();
  await expect(page.locator('[name=categoria]')).toHaveValue('Alimentação');
  await expect(page.locator('#form-error')).toContainText('Alimentação');
  await page.locator('#close').click();

  // 2. Open Finance synchronization (mock sandbox)
  await page.locator('#open-finance-btn').click();
  await expect(page.locator('#of-dialog')).toBeVisible();
  await page.locator('#sync-of-btn').click();
  await expect(page.locator('#dashboard-status')).toContainText('Open Finance sincronizado');
  await expect(page.locator('#of-accounts-list')).toContainText('Nubank');
  await expect(page.locator('#of-accounts-list')).toContainText('Cartão');
  await expect(page.locator('#of-accounts-list')).toContainText('Fatura atual:');
  await expect(page.locator('#of-investments-list')).toContainText('Total em Investimentos');
  await expect(page.locator('#of-investments-list')).toContainText('Tesouro Selic 2029');

  // 3. AI Insights
  await page.locator('#ai-insight-btn').click();
  await expect(page.locator('#dashboard-status')).toContainText('gerada com');
  await expect(page.locator('#insight-title')).not.toHaveText('Seu resumo está pronto.');
  await expect(page.locator('#ai-suggestions')).toBeVisible();
});
