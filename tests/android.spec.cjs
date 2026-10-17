const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    // Older supported Android WebViews have AbortController but not AbortSignal.timeout.
    AbortSignal.timeout = undefined;
    window.nativeExports = [];
    window.nativeResult = { saved: true };
    window.Capacitor = {
      isNativePlatform: () => true,
      nativePromise: async (plugin, method, data) => {
        if (plugin !== 'ClarezaFiles' || method !== 'exportCsv') throw new Error('Unexpected bridge call');
        window.nativeExports.push(data);
        if (window.nativeFailure) throw new Error('test failure');
        return window.nativeResult;
      }
    };
    window.workerAttempts = 0;
    navigator.serviceWorker.register = async () => { window.workerAttempts++; };
  });
  await page.route('**/api/movimentacoes?**', route => route.fulfill({ json: [
    { id: 99, descricao: '=Teste Android', categoria: 'Outros', tipo: 'SAIDA', valor: 12.34, data: '2026-10-17' }
  ] }));
  await page.goto('/');
  await page.locator('#api-key').fill('browser-test-only');
  await page.locator('#access-form button').click();
  await expect(page.locator('#dashboard')).toBeVisible();
});

test('native CSV saves through picker and handles cancellation and failure', async ({ page }) => {
  await page.locator('#export').click();
  await expect(page.locator('#dashboard-status')).toContainText('CSV salvo');
  const exported = await page.evaluate(() => window.nativeExports[0]);
  expect(exported.filename).toMatch(/^clareza-\d{4}-\d{2}\.csv$/);
  expect(exported.content).toContain("'=Teste Android");
  expect(exported.content).toContain('12,34');
  await page.evaluate(() => { window.nativeResult = { saved: false }; });
  await page.locator('#export').click();
  await expect(page.locator('#dashboard-status')).toContainText('cancelada');
  await page.evaluate(() => { window.nativeFailure = true; });
  await page.locator('#export').click();
  await expect(page.locator('#dashboard-status')).toContainText('Não foi possível salvar');
  await expect(page.locator('#export')).toBeEnabled();
});

test('native shell skips service worker and back closes open dialogs', async ({ page }) => {
  expect(await page.evaluate(() => window.workerAttempts)).toBe(0);
  await page.locator('#add').click();
  expect(await page.evaluate(() => window.clarezaBack())).toBe(true);
  await expect(page.locator('#movement-dialog')).not.toBeVisible();
  await page.locator('#edit-budgets').click();
  expect(await page.evaluate(() => window.clarezaBack())).toBe(true);
  await expect(page.locator('#budget-dialog')).not.toBeVisible();
  expect(await page.evaluate(() => window.clarezaBack())).toBe(false);
});
