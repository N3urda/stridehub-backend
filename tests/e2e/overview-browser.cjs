/* Real HTTP overview regression. All data is DEMO; only the dedicated test DB is used. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {spawn, spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const base = 'http://127.0.0.1:8091';
const output = path.resolve('var/runtime/overview-browser');
const database = path.resolve('tests/dreeveworkspace-browser.db');
const password = 'DEMO-overview-test-only';
const token = 'drv_' + '0'.repeat(64);
let browser, server, log, passed = 0;
const report = message => { passed++; console.log('PASS ' + message); };
const shiftDay = (date, amount) => { const d = new Date(date + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + amount); return d.toISOString().slice(0, 10); };

async function assertPortAvailable() {
    // Check before seed-workspace.php resets its dedicated database.
    const probe = net.createServer();
    await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(8091, '127.0.0.1', resolve); });
    await new Promise(resolve => probe.close(resolve));
}
async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api/v1/' + route, {method, headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: response.status, data: response.status === 204 ? {} : await response.json()};
}
async function loaded(page) {
    await page.locator('#overview-refresh:enabled').waitFor();
}
async function refresh(page) {
    await page.locator('#overview-refresh').click();
    await loaded(page);
}
function seedRecords(rows) {
    const result = spawnSync('python3', ['-c', `import json,sqlite3,sys
p=sys.argv[1]
assert p.endswith('/tests/dreeveworkspace-browser.db')
c=sqlite3.connect(p)
for r in json.load(sys.stdin):
 assert r['id'].startswith('DEMO-overview-')
 c.execute('INSERT INTO TrainingRecord(kind,id,payload,version,updatedAt,naturalKey) VALUES(?,?,?,?,?,NULL)',(r['kind'],r['id'],json.dumps(r['payload']),1,'2026-10-18T00:00:00Z'))
c.commit()`, database], {input: JSON.stringify(rows), encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
}
async function failSource(page, pattern) {
    await page.route(pattern, route => route.fulfill({status: 503, contentType: 'application/json', body: JSON.stringify({message: 'DEMO source unavailable'})}));
}

(async () => {
    await assertPortAvailable();
    fs.mkdirSync(output, {recursive: true});
    const noCredentials = path.join(output, 'no-zepp-credentials.json');
    assert.equal(fs.existsSync(noCredentials), false, 'The test must not use a Zepp account');
    const hash = spawnSync('php', ['-r', 'echo password_hash($argv[1], PASSWORD_BCRYPT);', password], {encoding: 'utf8'});
    assert.equal(hash.status, 0, hash.stderr);
    const env = {...process.env, APP_ENV: 'test', APP_DEBUG: '1', TEST_TOKEN: 'workspace-browser', TZ: 'Asia/Shanghai', APP_URL: base,
        APP_SECRET: 'DEMO-overview-browser-secret', ADMIN_USERNAME: 'DEMO', ADMIN_PASSWORD_HASH: hash.stdout, DREEVE_API_KEY: token,
        IMPORT_MODE: 'files', TRUSTED_PROXIES: '', DEV_MODE_IS_ENABLED: '0', ZEPP_CREDENTIALS_FILE: noCredentials};
    const setup = spawnSync('php', ['tests/e2e/seed-workspace.php'], {env, encoding: 'utf8'});
    assert.equal(setup.status, 0, setup.stderr);
    log = fs.openSync(path.join(output, 'server.log'), 'w');
    server = spawn('php', ['-S', '127.0.0.1:8091', '-t', 'public', 'tests/e2e/workspace-router.php'], {env, stdio: ['ignore', log, log]});
    for (let attempt = 0; attempt < 80; attempt++) {
        if (server.exitCode !== null) throw new Error('The isolated HTTP server exited before startup');
        try { if ((await fetch(base + '/admin/login')).ok) break; } catch {}
        await new Promise(resolve => setTimeout(resolve, 150));
    }
    assert.equal((await fetch(base + '/api/v1/health/workspace')).status, 401);
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
    const context = await browser.newContext({viewport: {width: 1440, height: 1100}, timezoneId: 'America/Los_Angeles'});
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/admin/insights');
    await page.locator('[name=_username]').fill('DEMO');
    await page.locator('[name=_password]').fill(password);
    await Promise.all([page.waitForURL(/\/admin\/insights/), page.locator('button[type=submit]').click()]);
    await loaded(page);
    const today = (await api('training/today')).data.date;
    assert.match(await page.locator('#overview-range').innerText(), new RegExp(today));
    assert.match(await page.locator('#latest-sleep-date').innerText(), /没有|暂无|尚无/);
    assert.equal(await page.locator('#overview-sync').isDisabled(), true);
    assert.match(await page.locator('#sync-top-status').innerText(), /未连接|未配置/);
    assert.equal(await page.locator('.hub-primary-nav a').count(), 6);
    assert.equal(await page.locator('.hub-primary-nav [aria-current=page]').getAttribute('href'), '/admin/insights');
    const shortcuts = await page.locator('.overview-actions a').evaluateAll(links => links.map(link => link.getAttribute('href')));
    for (const target of ['/admin/weight?record=weight-entry', '/admin/nutrition?record=meal', '/admin/training/today', '/admin/health#collection-history']) assert.ok(shortcuts.includes(target), 'Missing shortcut ' + target);
    report('empty overview uses the account date, makes missing data explicit, and disables unconfigured sync');
    for (const code of [503, 401]) {
        const endpoint = base + '/admin/health/api/sleep?DEMO-request-status=' + code;
        await page.route(endpoint, route => route.fulfill({status: code, contentType: 'text/html', body: '<p>DEMO non-JSON response</p>'}));
        const failure = await page.evaluate(async url => {
            try { await window.StrideHubData.request(url); return {resolved: true}; }
            catch (error) { return {status: error.status, message: error.message}; }
        }, endpoint);
        assert.equal(failure.status, code);
        if (code === 503) assert.doesNotMatch(failure.message, /登录|过期/);
        else assert.match(failure.message, /登录.*过期|重新登录/);
        await page.unroute(endpoint);
    }
    report('non-JSON service failures keep their HTTP status and are distinct from expired login');
    for (const [target, editor, form] of [['weight?record=weight-entry', 'weight-entry-editor', 'weight-entry-form'], ['nutrition?record=meal', 'meal-editor', 'meal-form']]) {
        await page.locator(`.overview-actions a[href="/admin/${target}"]`).click();
        await page.locator(`#${editor}[open]`).waitFor();
        assert.equal(await page.locator(`#${form} [name=date]`).inputValue(), today);
        assert.equal(new URL(page.url()).searchParams.has('record'), false);
        assert.equal(await page.locator('#' + editor).evaluate(el => el.contains(document.activeElement)), true);
        await page.locator(`#${editor} [data-close-editor]`).click();
        await page.goto(base + '/admin/insights'); await loaded(page);
    }
    report('recording shortcuts open the right editor for today with keyboard focus');

    const oldDay = shiftDay(today, -15), tomorrow = shiftDay(today, 1);
    seedRecords([
        {kind: 'sleep', id: 'DEMO-overview-sleep', payload: {date: oldDay, sourceDate: oldDay, source: 'zepp', sourceLabel: 'DEMO Zepp', timeZone: '+08:00', startedAt: oldDay + 'T00:00:00+08:00', endedAt: oldDay + 'T07:11:00+08:00', totalMinutes: 431, deepMinutes: 91, lightMinutes: 270, remMinutes: 70, awakeMinutes: 10, lastSyncedAt: oldDay + 'T08:00:00+08:00'}},
        {kind: 'zepp-day', id: 'DEMO-overview-day', payload: {date: oldDay, sourceDate: oldDay, source: 'zepp', timeZone: '+08:00', activity: {steps: 9876, caloriesKcal: 432}, heartRate: {restingBpm: 57, sampleCount: 100}, oxygen: {averagePercent: 98}, stress: {average: 24}, coverage: {activity: 'available', heartRate: 'available', oxygen: 'available', stress: 'available', body: 'missing'}}},
        {kind: 'zepp-workout', id: 'DEMO-overview-workout', payload: {sportType: 1, sportName: 'DEMO 历史晨跑', startedAt: oldDay + 'T08:00:00+08:00', distanceMeters: 6540, durationSeconds: 2220, elapsedSeconds: 2220, averageHeartRateBpm: 140}},
    ]);
    assert.equal((await api('training/sessions', 'POST', {id: 'DEMO-overview-session', title: 'DEMO 明日训练', startAt: tomorrow + 'T07:00:00+08:00', durationMinutes: 30, type: 'easy', distanceKm: 5})).status, 201);
    assert.equal((await api('health/meal-plans/DEMO-overview-meals', 'PUT', {version: 0, date: tomorrow, source: 'user', targets: {energyKcal: 1900, proteinGrams: 90}})).status, 200);
    const weights = (await api('health/weight')).data;
    assert.equal((await api('health/weight/' + oldDay, 'PUT', {version: weights.version, weightKg: 72.35})).status, 200);
    await refresh(page);
    assert.match(await page.locator('#latest-sleep-date').innerText(), new RegExp(oldDay));
    assert.equal(await page.locator('#sleep-stage-bar .stage-piece').count(), 4);
    assert.match(await page.locator('#activity-metrics').innerText(), /9,876/);
    assert.match(await page.locator('#vital-metrics').innerText(), /57/);
    assert.match(await page.locator('#recent-activities').innerText(), /DEMO 历史晨跑/);
    assert.equal(await page.locator('#overview-sleep-chart rect').count(), 30);
    const nextTraining = page.locator('#overview-plans a').filter({hasText: 'DEMO 明日训练'});
    assert.equal(await nextTraining.count(), 1);
    const trainingUrl = new URL(await nextTraining.getAttribute('href'), base);
    assert.equal(trainingUrl.pathname, '/admin/plans');
    assert.equal(trainingUrl.searchParams.get('from'), tomorrow);
    assert.equal(trainingUrl.searchParams.get('to'), tomorrow);
    assert.equal(trainingUrl.searchParams.get('category'), 'training');
    const mealUrl = new URL(await page.locator('#overview-plans a').filter({hasText: '饮食目标与餐食'}).getAttribute('href'), base);
    assert.equal(mealUrl.searchParams.get('date'), tomorrow);
    report('real saved records show their measurement dates and upcoming plans link to the exact session or day');
    await page.screenshot({path: path.join(output, 'overview-desktop.png'), fullPage: true});
    await nextTraining.click();
    await page.locator('#plans-refresh:enabled').waitFor();
    assert.equal(await page.locator('.training-day').count(), 1);
    assert.match(await page.locator('.training-calendar').innerText(), /DEMO 明日训练/);
    assert.equal(await page.locator('[data-category=training]').getAttribute('aria-pressed'), 'true');
    await page.goto(base + '/admin/insights'); await loaded(page);
    report('upcoming training opens its saved day and category');

    // A new range must never relabel old values as data for that range.
    const failing = ['**/admin/health/api/sleep?*', '**/admin/health/api/zepp/days?*', '**/admin/health/api/workouts?*', '**/admin/health/api/zepp/status'];
    for (const pattern of failing) await failSource(page, pattern);
    await page.locator('[data-days="7"]').click();
    await loaded(page);
    assert.equal(await page.locator('[data-days="7"]').getAttribute('aria-pressed'), 'true');
    assert.equal(new URL(page.url()).searchParams.get('days'), '7');
    assert.equal(await page.locator('#sleep-stage-bar .stage-piece').count(), 0);
    assert.doesNotMatch(await page.locator('#sleep-average').innerText(), /7 小时|1 晚/);
    assert.equal(await page.locator('#steps-chart rect').count(), 0);
    assert.doesNotMatch(await page.locator('#activity-metrics').innerText(), /9,876/);
    assert.doesNotMatch(await page.locator('#vital-metrics').innerText(), /57|98|24/);
    assert.doesNotMatch(await page.locator('#recent-activities').innerText(), /DEMO 历史晨跑/);
    assert.match(await page.locator('#data-coverage').innerText(), /失败|无法读取/);
    assert.match(await page.locator('#sync-top-status').innerText(), /失败|无法读取|未知/);
    assert.equal(await page.locator('#overview-sync').isDisabled(), true);
    report('range changes clear every failed source group, distinguish failure from missing data, and disable unknown sync');
    for (const pattern of failing) await page.unroute(pattern);
    await page.reload(); await loaded(page);
    assert.equal(await page.locator('[data-days="7"]').getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#overview-sleep-chart rect').count(), 0);
    await page.locator('[data-days="30"]').click(); await loaded(page);
    assert.equal(await page.locator('#overview-sleep-chart rect').count(), 30);
    assert.match(await page.locator('#activity-metrics').innerText(), /9,876/);
    report('period selection survives reload and restored sources can recover after failure');

    // Explicitly simulated sync responses: no request reaches any Zepp account.
    let syncCalls = 0, configured = true;
    await page.route('**/admin/health/api/zepp/status', route => route.fulfill({contentType: 'application/json', body: JSON.stringify({configured, state: configured ? 'empty' : 'not_configured', sources: {}, lastSuccessAt: null})}));
    await page.route(/\/admin\/health\/api\/zepp\/(?:workouts\/)?sync$/, route => {
        syncCalls++;
        configured = false;
        return route.fulfill({contentType: 'application/json', body: JSON.stringify({state: 'no_data'})});
    });
    await refresh(page);
    assert.equal(await page.locator('#overview-sync').isEnabled(), true);
    await page.locator('#overview-sync').click();
    await page.waitForFunction(() => !document.querySelector('#overview-sync').textContent.includes('正在'));
    assert.equal(syncCalls, 2);
    assert.equal(await page.locator('#overview-sync').isDisabled(), true);
    assert.match(await page.locator('#sync-top-status').innerText(), /未连接|未配置/);
    report('a completed sync cannot re-enable the action after the refreshed connection becomes unavailable');

    for (const width of [390, 320]) {
        await page.setViewportSize({width, height: 900});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'overview overflows ' + width);
        await page.evaluate(() => scrollTo(0, 0));
        await page.screenshot({path: path.join(output, 'overview-mobile-' + width + '.png'), fullPage: true});
    }
    await page.screenshot({path: path.join(output, 'overview-mobile.png'), fullPage: true});
    report('overview fits 320px and 390px screens');

    const android = await browser.newContext({storageState: await context.storageState(), viewport: {width: 390, height: 780}, userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36 StrideHub/0.2.0 StrideHubAndroid', isMobile: true, hasTouch: true});
    const mobile = await android.newPage();
    mobile.on('pageerror', error => errors.push(error.message));
    await mobile.goto(base + '/admin/insights'); await loaded(mobile);
    // Native navigation owns the primary tabs; browser access remains available through in-page actions.
    assert.equal(await mobile.locator('body').evaluate(el => el.classList.contains('hub-native-app')), true);
    assert.equal(await mobile.locator('.sh-sidebar').isVisible(), false);
    assert.equal(await mobile.locator('.hub-topbar').isVisible(), false);
    assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await mobile.screenshot({path: path.join(output, 'overview-android-webview.png'), fullPage: true});
    report('Android WebView avoids duplicate primary navigation and remains usable on a small screen');
    assert.deepEqual(errors, []);
    report('no uncaught browser errors');
    console.log(`${passed} checks passed; DEMO screenshots: ${output}`);
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
    if (browser) await browser.close();
    if (server) server.kill('SIGTERM');
    if (log !== undefined) fs.closeSync(log);
});
