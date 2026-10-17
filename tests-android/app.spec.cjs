const { test, expect } = require('@playwright/test');
const { _android } = require('playwright');
const path = require('node:path');

test('installed APK: native shell, CRUD with isolated H2, hardware back and real CSV picker', async ({ request }) => {
  const devices = await _android.devices({ omitDriverInstall: true });
  // Never run destructive test setup against the owner's physical phone.
  const device = devices.find(item => item.serial().startsWith('emulator-'));
  expect(device, 'Start a dedicated Android emulator before this test').toBeTruthy();
  expect((await device.shell('getprop ro.boot.qemu.avd_name')).toString().trim()).toBe('Clareza_API_35');
  device.setDefaultTimeout(30000);
  const pkg = 'br.com.clareza.financeiro';
  let page;
  try {
    // This fixed file contains only the fixture exported by this dedicated test.
    await device.shell('rm -f /sdcard/Download/clareza-2099-12.csv');
    await device.installApk(path.resolve('mobile/artifacts/Clareza-1.0-debug.apk'));
    await device.shell(`am force-stop ${pkg}`);
    await device.shell(`pm clear ${pkg}`);
    const launch = (await device.shell(`am start -W -n ${pkg}/.MainActivity`)).toString();
    expect(launch).toContain('Status: ok');
    const webview = await device.webView({ pkg });
    page = await webview.page();
    await expect(page.locator('#access')).toBeVisible();
    await device.screenshot({ path: 'test-results/android-login.png' });
    expect(await page.evaluate(() => window.Capacitor.isNativePlatform())).toBe(true);
    expect(await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length)).toBe(0);
    expect(await page.locator('#api-url').inputValue()).toBe('https://controle-despesas-mensais.onrender.com/api');
    // No production requests/credentials. Forward API calls to this run's disposable H2.
    await page.route('https://controle-despesas-mensais.onrender.com/api/**', async route => {
      const req = route.request();
      const headers = { Origin: 'https://localhost' };
      for (const name of ['x-api-key', 'content-type', 'access-control-request-method', 'access-control-request-headers']) {
        if (req.headers()[name]) headers[name] = req.headers()[name];
      }
      const response = await request.fetch(new URL(req.url()).pathname + new URL(req.url()).search, {
        method: req.method(), headers,
        data: req.postData() ?? undefined
      });
      await route.fulfill({ response });
    });
    await page.locator('#api-key').fill('invalid-key');
    await page.locator('#access-form button').click();
    await expect(page.locator('#access-error')).toContainText('inválida');
    await page.locator('#api-key').fill('browser-test-only');
    await page.locator('#access-form button').click();
    await expect(page.locator('#dashboard')).toBeVisible();
    await page.locator('#period').fill('2099-12');
    await page.locator('#period').dispatchEvent('change');
    await expect(page.locator('#dashboard-status')).toContainText('Dados atualizados');
    await page.locator('#add').click();
    await device.shell('input keyevent 4');
    await expect(page.locator('#movement-dialog')).not.toBeVisible();
    await page.locator('#add').click();
    await page.locator('[name=descricao]').fill('Teste APK isolado');
    await page.locator('[name=valor]').fill('12.34');
    await page.locator('#movement-form [type=submit]').click();
    await expect(page.locator('.transaction')).toHaveCount(1);
    await page.locator('[data-edit]').click();
    await page.locator('[name=valor]').fill('23.45');
    await page.locator('#movement-form [type=submit]').click();
    await expect(page.locator('.transaction')).toContainText('23,45');
    await page.reload();
    await expect(page.locator('#dashboard')).toBeVisible();
    await page.locator('#period').fill('2099-12');
    await page.locator('#period').dispatchEvent('change');
    await expect(page.locator('.transaction')).toContainText('23,45');

    await page.locator('#export').click();
    await expect.poll(async () => (await device.shell('dumpsys activity activities')).toString().includes('android.intent.action.CREATE_DOCUMENT')).toBe(true);
    await device.shell('input keyevent 4');
    await expect(page.locator('#dashboard-status')).toContainText('cancelada');
    await page.locator('#export').click();
    // Inspect the native picker, not a mocked JavaScript bridge.
    let bounds;
    await expect.poll(async () => {
      await device.shell('uiautomator dump /sdcard/clareza-picker.xml');
      const xml = (await device.shell('cat /sdcard/clareza-picker.xml')).toString();
      const node = xml.match(/<node[^>]*resource-id="android:id\/button1"[^>]*>/)?.[0];
      bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
      return Boolean(bounds);
    }).toBe(true);
    const x = Math.round((Number(bounds[1]) + Number(bounds[3])) / 2);
    const y = Math.round((Number(bounds[2]) + Number(bounds[4])) / 2);
    await device.shell(`input tap ${x} ${y}`);
    await expect(page.locator('#dashboard-status')).toContainText('CSV salvo');
    const csv = (await device.shell('cat /sdcard/Download/clareza-2099-12.csv')).toString();
    expect(csv).toContain('Teste APK isolado');
    expect(csv).toContain('23,45');
    await device.screenshot({ path: 'test-results/android-dashboard.png' });
    page.once('dialog', dialog => dialog.accept());
    await page.locator('[data-delete]').click();
    await expect(page.locator('.transaction')).toHaveCount(0);
    await page.locator('#logout').click();
    expect(await page.evaluate(() => sessionStorage.getItem('clareza-api-key'))).toBeNull();
    await expect(page.locator('#access')).toBeVisible();
  } finally {
    if (page) await page.context().close();
    await device.shell(`am force-stop ${pkg}`);
    await device.close();
  }
});
