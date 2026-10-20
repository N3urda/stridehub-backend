/* Real HTTP/UI tests, synthetic data, dedicated database and localhost port only. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn, spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = 'http://127.0.0.1:8091';
const output = path.resolve('var/runtime/workspace-browser'); fs.mkdirSync(output, {recursive: true});
const password = 'DEMO-workspace-test-only', token = 'drv_' + '0'.repeat(64); // Synthetic, isolated test token.
const hash = spawnSync('php', ['-r', 'echo password_hash($argv[1], PASSWORD_BCRYPT);', password], {encoding: 'utf8'}); assert.equal(hash.status, 0);
const env = {...process.env, APP_ENV: 'test', APP_DEBUG: '1', TEST_TOKEN: 'workspace-browser', TZ: 'Asia/Shanghai', APP_URL: base, APP_SECRET: 'DEMO-workspace-browser-secret', ADMIN_USERNAME: 'DEMO', ADMIN_PASSWORD_HASH: hash.stdout, DREEVE_API_KEY: token, IMPORT_MODE: 'files', TRUSTED_PROXIES: '', DEV_MODE_IS_ENABLED: '0'};
const setup = spawnSync('php', ['tests/e2e/seed-workspace.php'], {env, encoding: 'utf8'}); assert.equal(setup.status, 0, setup.stderr);
const log = fs.openSync(path.join(output, 'server.log'), 'w');
const server = spawn('php', ['-S', '127.0.0.1:8091', '-t', 'public', 'tests/e2e/workspace-router.php'], {env, stdio: ['ignore', log, log]});
let browser, passed = 0; const report = text => { ++passed; console.log('PASS ' + text); };
async function api(route, method = 'GET', body) {
    const r = await fetch(base + '/api/v1/' + route, {method, headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
    const data = r.status === 204 ? {} : await r.json(); return {status: r.status, data};
}
(async () => {
    for (let n = 0; n < 40; ++n) { try { const r = await fetch(base + '/admin/login'); if (r.ok) break; } catch {} await new Promise(r => setTimeout(r, 150)); }
    assert.equal((await fetch(base + '/api/v1/health/workspace')).status, 401);
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
    const page = await browser.newPage({viewport: {width: 1440, height: 1100}, timezoneId: 'America/Los_Angeles'});
    const errors = []; page.on('pageerror', e => { errors.push(e.message); console.error('Browser error:', e.message); }); page.on('dialog', d => d.accept());
    await page.goto(base + '/admin/nutrition'); await page.locator('[name=_username]').fill('DEMO'); await page.locator('[name=_password]').fill(password);
    await Promise.all([page.waitForURL(/\/admin\/nutrition/), page.locator('button[type=submit]').click()]);
    await page.waitForLoadState('networkidle');
    if (!await page.locator('#nutrition-metrics .compact-metric').count()) console.error('Initial page status:', await page.locator('#data-status').innerText());
    await page.locator('#nutrition-metrics .compact-metric').first().waitFor();
    await page.locator('#meal-form button[type=submit]:enabled').waitFor();
    assert.match(await page.locator('#nutrition-metrics').innerText(), /—/);
    assert.match(await page.locator('#nutrition-plan').innerText(), /尚未保存/);
    const initial = await api('health/workspace'); assert.equal(initial.status, 200);
    const today = initial.data.today;
    assert.equal(await page.locator('#meal-form [name=date]').inputValue(), today);
    report('authenticated empty data and runner timezone are displayed without synthetic nutrition values');
    const plan = await api('health/meal-plans/DEMO-plan', 'PUT', {version: 0, date: today, source: 'ai', rationale: 'DEMO fixture only', targets: {energyKcal: 2000, proteinGrams: 100}, meals: [{id: 'breakfast', mealType: 'breakfast', foods: 'DEMO 燕麦与牛奶', portion: '一份'}]});
    assert.equal(plan.status, 200);
    await page.locator('#data-refresh').click(); await page.locator('#data-refresh:enabled').waitFor();
    assert.match(await page.locator('#nutrition-plan').innerText(), /DEMO 燕麦/);
    await page.locator('#meal-form [name=foods]').fill('DEMO <img src=x onerror=alert(1)>');
    await page.locator('#meal-form [name=portion]').fill('一份');
    await page.locator('#meal-form [name=energyKcal]').fill('450');
    await page.locator('#meal-form [name=proteinGrams]').fill('20');
    await page.locator('#meal-form [name=nutritionSource]').selectOption('estimate');
    await page.locator('#meal-form button[type=submit]').click(); await page.locator('#meal-status').getByText('已保存并核验。').waitFor();
    assert.match(await page.locator('#nutrition-metrics').innerText(), /450/);
    assert.equal(await page.locator('#meals-table img').count(), 0);
    assert.equal((await api('health/meals')).data.records.length, 1);
    report('API meal plan and browser actual meal share totals, preserve source, and escape food text');
    await page.locator('#meals-table button').filter({hasText: '编辑'}).first().click();
    await page.locator('#meal-form [name=portion]').fill('待保存草稿');
    let meal = (await api('health/meals')).data.records[0];
    assert.equal((await api('health/meals/' + meal.id, 'PUT', {version: meal.version, notes: 'DEMO concurrent edit'})).status, 200);
    await page.locator('#meal-form button[type=submit]').click(); await page.locator('#meal-status.error').waitFor();
    assert.equal(await page.locator('#meal-form [name=portion]').inputValue(), '待保存草稿');
    assert.equal((await api('health/meals/' + meal.id)).data.notes, 'DEMO concurrent edit');
    await page.locator('#data-refresh').click(); await page.locator('#data-refresh:enabled').waitFor();
    assert.equal(await page.locator('#meal-form [name=portion]').inputValue(), '待保存草稿');
    report('real stale-version conflict preserves drafts and concurrent data');
    await page.locator('#meals-table button').filter({hasText: '编辑'}).first().click();
    await page.locator('#meal-form [name=portion]').fill('两份');
    await page.locator('#meal-form button[type=submit]').click(); await page.locator('#meal-status').getByText('已保存并核验。').waitFor();
    meal = (await api('health/meals')).data.records[0]; assert.equal(meal.portion, '两份');
    assert.equal((await api('health/meals/' + meal.id + '/history')).data.records.length, 3);
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({path: path.join(output, 'nutrition-desktop.png'), fullPage: true});
    await page.goto(base + '/admin/weight'); await page.locator('#goal-form button[type=submit]:enabled').waitFor();
    const weights = (await api('health/weight')).data;
    assert.equal((await api('health/weight/' + today, 'PUT', {version: weights.version, weightKg: 80})).status, 200);
    await page.locator('#goal-form [name=title]').fill('DEMO 阶段目标');
    await page.locator('#goal-form [name=startWeightKg]').fill('80'); await page.locator('#goal-form [name=targetWeightKg]').fill('75');
    await page.locator('#goal-form button[type=submit]').click(); await page.locator('#goal-status').getByText('已保存并核验。').waitFor();
    assert.match(await page.locator('#weight-goals').innerText(), /距目标 5/);
    await page.locator('#measurement-form [name=waistCm]').fill('85.2');
    await page.locator('#measurement-form button[type=submit]').click(); await page.locator('#measurement-status').getByText('已保存并核验。').waitFor();
    assert.match(await page.locator('#measurements-table').innerText(), /85.2/);
    const workspace = (await api('health/workspace')).data; assert.equal(workspace.weight.records.length, 1); assert.equal(workspace.measurements[0].bodyFatPercent, null);
    report('goals and measurements persist separately from actual weight, including unknown body fat');
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({path: path.join(output, 'weight-desktop.png'), fullPage: true});
    assert.equal((await api('training/sessions', 'POST', {id: 'DEMO-calendar', title: 'DEMO 轻松跑', startAt: today + 'T07:00:00+08:00', durationMinutes: 30, type: 'easy', distanceKm: 5, steps: [{kind: '热身', minutes: 10, target: '轻松体感'}]})).status, 201);
    await page.goto(base + '/admin/plans'); await page.locator('#plans-refresh:enabled').waitFor();
    assert.equal(await page.locator('.training-day').count(), 7); assert.match(await page.locator('.training-calendar').innerText(), /热身/);
    assert.equal(await page.locator('#ai-handoff').count(), 0);
    report('seven-day training calendar displays structured steps without a chat panel');
    for (const route of ['nutrition', 'weight', 'plans', 'running', 'insights']) {
        await page.goto(base + '/admin/' + route);
        await page.waitForLoadState('networkidle');
        for (const width of [390, 320]) {
            await page.setViewportSize({width, height: 900});
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, route + ' overflows ' + width);
        }
        assert.equal(await page.locator('.hub-primary-nav a').count(), 6);
        await page.evaluate(() => scrollTo(0, 0));
        await page.screenshot({path: path.join(output, route + '-mobile.png'), fullPage: true});
    }
    report('five data pages and six navigation entries fit 320px/390px screens');
    await page.goto(base + '/admin/nutrition'); await page.locator('#meal-form button[type=submit]:enabled').waitFor();
    await page.route('**/health/api/workspace*', r => r.fulfill({status: 503, contentType: 'application/json', body: JSON.stringify({message: 'DEMO failure'})}));
    await page.locator('#data-refresh').click(); await page.locator('#data-status.error').waitFor();
    assert.match(await page.locator('#nutrition-metrics').innerText(), /450/);
    report('failed refresh is visible and retains the prior data');
    assert.deepEqual(errors, []); report('no uncaught browser errors');
    console.log(`${passed} checks passed; synthetic screenshots: ${output}`);
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); server.kill('SIGTERM'); fs.closeSync(log); });
