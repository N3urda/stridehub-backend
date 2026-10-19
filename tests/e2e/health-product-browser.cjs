/* Product acceptance: real authenticated pages and blood-pressure API, with
 * clearly synthetic sleep/status responses for bounded recovery scenarios.
 * Only this script's DEMO-health-product measurement is written or removed. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8082', 'Use the disposable product acceptance service');
const credentials = JSON.parse(fs.readFileSync('var/runtime/credentials.json'));
const shots = path.join(process.cwd(), 'var/runtime/product-e2e/health-screenshots'); fs.mkdirSync(shots, {recursive: true});
const measurementId = 'DEMO-health-product-' + Date.now();
let browser, passed = 0;
const report = text => { console.log('PASS ' + text); passed++; };
async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api/v1/' + route, {method, headers: {Authorization: 'Bearer ' + credentials.apiKey, ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: response.status, data: response.status === 204 ? null : await response.json()};
}
const daysBefore = (date, days) => { const value = new Date(date + 'T12:00:00Z'); value.setUTCDate(value.getUTCDate() - days); return value.toISOString().slice(0, 10); };
(async () => {
    const today = (await api('training/today')).data.date;
    assert.equal((await api('health/blood-pressure/' + measurementId, 'PUT', {version: 0, measuredAt: today + 'T00:00:00Z', systolic: 122, diastolic: 78, pulse: null, arm: 'unknown', posture: 'unknown', notes: 'DEMO health product acceptance'})).status, 200);
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport: {width: 390, height: 844}, timezoneId: 'Asia/Shanghai'});
    const page = await context.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
    let sleepFails = false, pressureFails = false, statusFails = false, statusReads = 0, syncMode = 'success';
    let syncStatus = {configured: true, state: 'idle', lastAttemptAt: null, lastSuccessAt: null, lastError: null, nextAction: '手表需先同步到 Zepp 手机 App。', imported: 0, updated: 0, unchanged: 0, skipped: 0};
    const sleepRows = [2, 40].map((days, i) => ({id: 'DEMO-health-product-sleep-' + i, date: daysBefore(today, days), startedAt: daysBefore(today, days + 1) + 'T23:00:00+08:00', endedAt: daysBefore(today, days) + 'T07:00:00+08:00', timeZone: '+08:00', totalMinutes: 450 + i * 10, deepMinutes: 90, lightMinutes: 300, remMinutes: 60, awakeMinutes: null, score: null, restingHeartRate: null, wakeCount: null}));
    const fulfill = (route, data, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(data)});
    await page.route('**/admin/health/api/**', async route => {
        const url = new URL(route.request().url());
        if (url.pathname.endsWith('/sleep/status')) { statusReads++; return fulfill(route, statusFails ? {message: 'DEMO 同步状态暂不可用'} : syncStatus, statusFails ? 503 : 200); }
        if (url.pathname.endsWith('/sleep/sync')) {
            assert.deepEqual(route.request().postDataJSON(), {days: 90});
            if (syncMode === 'dropped') return route.abort('failed');
            syncStatus = {...syncStatus, state: syncMode === 'auth' ? 'auth_required' : 'syncing', lastAttemptAt: new Date().toISOString(), lastError: syncMode === 'auth' ? '授权已失效。' : null, nextAction: syncMode === 'auth' ? '请在服务器重新连接 Zepp，再刷新同步状态。' : '正在连接 Zepp。'};
            return fulfill(route, syncStatus, syncMode === 'auth' ? 503 : 200);
        }
        if (url.pathname.endsWith('/sleep')) {
            if (sleepFails) return fulfill(route, {message: 'DEMO 睡眠记录暂不可用'}, 503);
            return fulfill(route, {records: sleepRows.filter(row => row.date >= url.searchParams.get('from') && row.date <= url.searchParams.get('to'))});
        }
        if (pressureFails && url.pathname.endsWith('/blood-pressure') && route.request().method() === 'GET') return fulfill(route, {message: 'DEMO 血压记录暂不可用'}, 503);
        return route.continue();
    });
    await page.goto(base + '/admin/health');
    await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url => !url.pathname.includes('/login')), page.locator('button[type=submit]').click()]);
    await page.goto(base + '/admin/health');
    await page.locator('#sleep-range').getByText(/90 天中有 2 天留有记录/).waitFor();
    await page.locator(`[data-record-id="${measurementId}"]`).waitFor();
    const initialFrom = await page.locator('#health-range [name=from]').inputValue();
    const initialTo = await page.locator('#health-range [name=to]').inputValue();
    await page.locator('#pressure-new').click(); await page.locator('#pressure-form [name=systolic]').fill('127'); await page.locator('#pressure-form [name=diastolic]').fill('79');
    const sevenFrom = daysBefore(initialTo, 6);
    await page.locator('#health-range [name=from]').fill(sevenFrom);
    await page.locator('#health-applied-range').getByText(/尚未应用/).waitFor();
    await page.setViewportSize({width: 320, height: 844});
    await page.waitForTimeout(220);
    assert.match(await page.locator('#sleep-range').innerText(), new RegExp(initialFrom));
    assert.ok((await page.locator('#sleep-chart svg').textContent()).includes(initialFrom.slice(5)));
    await page.locator('#health-refresh').click(); await page.locator('#health-refresh:enabled').waitFor();
    assert.match(await page.locator('#sleep-range').innerText(), new RegExp(initialFrom), 'Refresh must use the applied range, not an unsubmitted date');
    assert.equal(await page.locator('#pressure-form [name=systolic]').inputValue(), '127');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    report('unsubmitted dates and resizing never relabel existing trends; refresh preserves the blood-pressure draft at 320px');

    sleepFails = true;
    await page.locator('#health-range [type=submit]').click(); await page.locator('#sleep-history-status').getByText(/仍显示/).waitFor();
    assert.match(await page.locator('#pressure-range').innerText(), new RegExp(sevenFrom));
    assert.match(await page.locator('#sleep-range').innerText(), new RegExp(initialFrom));
    assert.ok((await page.locator('#sleep-history-status').innerText()).includes(sevenFrom));
    await page.setViewportSize({width: 390, height: 844}); await page.waitForTimeout(220);
    assert.ok((await page.locator('#sleep-chart svg').textContent()).includes(initialFrom.slice(5)));
    sleepFails = false; pressureFails = true;
    await page.getByRole('button', {name: '最近 30 天', exact: true}).click();
    await page.locator('#pressure-status').getByText(/仍显示/).waitFor();
    assert.match(await page.locator('#pressure-range').innerText(), new RegExp(sevenFrom));
    assert.match(await page.locator('#sleep-range').innerText(), new RegExp(daysBefore(initialTo, 29)));
    assert.match(await page.locator('#sleep-range').innerText(), /30 天中有 1 天留有记录/);
    assert.equal(await page.locator('#pressure-form [name=systolic]').inputValue(), '127');
    pressureFails = false;
    await page.getByRole('button', {name: '最近 7 天', exact: true}).click(); await page.locator('#health-refresh:enabled').waitFor();
    assert.equal(await page.getByRole('button', {name: '最近 7 天', exact: true}).getAttribute('aria-pressed'), 'true');
    await page.getByRole('button', {name: '最近 90 天', exact: true}).click(); await page.locator('#sleep-range').getByText(/90 天中有 2 天留有记录/).waitFor();
    report('partial failures retain independently labelled successful ranges; 7/30/90-day shortcuts and coverage remain accurate');

    await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/sleep/sync')), page.locator('#sleep-sync').click()]); await page.locator('#sleep-status').getByText(/正在同步/).waitFor();
    const readsBefore = statusReads;
    syncStatus = {...syncStatus, state: 'success', lastSuccessAt: new Date().toISOString(), imported: 2, updated: 1, unchanged: 4, skipped: 3, nextAction: '已同步；手表需先同步到 Zepp 手机 App。'};
    await page.locator('#sleep-status').getByText(/同步完成/).waitFor({timeout: 12000});
    assert.ok(statusReads > readsBefore); assert.equal(await page.locator('#sleep-sync').isEnabled(), true);
    assert.match(await page.locator('#sleep-sync-result').innerText(), /新增 2 条 · 更新 1 条 · 未变化 4 条 · 跳过 3 条/);
    assert.equal(await page.locator('#pressure-form [name=systolic]').inputValue(), '127');
    report('synthetic in-progress sync automatically reaches completion, displays result counts and retains the draft');

    syncMode = 'auth'; await page.locator('#sleep-sync').click();
    await page.locator('#sleep-status').getByText(/请在服务器重新连接 Zepp/).waitFor();
    assert.match(await page.locator('#sleep-sync-notice').innerText(), /已有睡眠记录仍保留/);
    assert.equal(await page.locator('#sleep-records .health-record').count(), 2);
    statusFails = true; await page.locator('#sleep-sync-refresh').click(); await page.locator('#sleep-sync-notice').getByText(/无法确认同步状态/).waitFor();
    assert.equal(await page.locator('#sleep-sync-refresh').isEnabled(), true);
    statusFails = false; syncStatus = {...syncStatus, state: 'no_data', lastError: null, nextAction: '请先将手表同步到 Zepp 手机 App，再重试。', imported: 0, updated: 0, unchanged: 0, skipped: 0};
    await page.locator('#sleep-sync-refresh').click(); await page.locator('#sleep-status').getByText(/历史记录仍保留/).waitFor();
    assert.equal(await page.locator('#sleep-records .health-record').count(), 2);
    report('structured authorization recovery, status-read failure and no-data results keep history visible and offer retry');

    await page.clock.install();
    syncStatus = {...syncStatus, state: 'syncing', lastError: null, nextAction: '正在连接 Zepp。'};
    await page.locator('#health-refresh').click(); await page.locator('#sleep-status').getByText(/正在同步/).waitFor();
    await page.clock.fastForward(121000);
    await page.locator('#sleep-sync-notice').getByText(/自动检查已暂停/).waitFor();
    const readsAtPause = statusReads;
    await page.clock.fastForward(300000); assert.equal(statusReads, readsAtPause, 'Bounded polling must stop issuing requests');
    syncStatus = {...syncStatus, state: 'success', nextAction: '已同步。'};
    await page.locator('#sleep-sync-refresh').click(); await page.locator('#sleep-status').getByText(/同步完成/).waitFor();
    assert.equal(await page.locator('#sleep-sync').isEnabled(), true);
    syncMode = 'dropped'; await page.locator('#sleep-sync').click(); await page.locator('#sleep-sync:enabled').waitFor();
    assert.match(await page.locator('#sleep-status').innerText(), /同步完成/);
    assert.match(await page.locator('#sleep-sync-notice').innerText(), /本次同步请求未得到确认/);
    assert.equal(await page.locator('#pressure-form [name=systolic]').inputValue(), '127');
    report('virtual-clock acceptance proves the two-minute polling bound, manual recovery and lost sync-response reconciliation');
    await page.screenshot({path: path.join(shots, 'health-product-mobile.png'), fullPage: true});
    await page.setViewportSize({width: 1440, height: 1000}); await page.clock.runFor(200);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: path.join(shots, 'health-product-desktop.png'), fullPage: true});
    assert.deepEqual(errors, []); report('desktop and narrow layouts fit with no JavaScript errors');
    console.log(`Completed ${passed} health product acceptance groups.`);
})().catch(error => { console.error(error.stack); process.exitCode = 1; }).finally(async () => {
    if (browser) await browser.close();
    const record = await api('health/blood-pressure/' + measurementId);
    if (record.status === 200) await api('health/blood-pressure/' + measurementId + '?version=' + record.data.version, 'DELETE');
});
