/* Real HTTP/browser acceptance. Requires a disposable localhost:8082 runtime backed by
 * var/runtime/today-e2e-database/dreeve.db. Only labelled DEMO records are created.
 * No network route mocks. Personal :8081 is never used. */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8082', 'Explicit disposable STRIDEHUB_TEST_URL required');
const root = process.cwd();
const database = path.join(root, 'var/runtime/today-e2e-database/dreeve.db');
assert.ok(fs.existsSync(database));
const credentials = JSON.parse(fs.readFileSync(process.env.TRAINING_CREDENTIALS_FILE || 'var/runtime/credentials.json', 'utf8'));
const today = new Date().toISOString().slice(0, 10);
const prefix = 'DEMO-today-';
const ids = ['activity-DEMO-TODAY-warmup', 'activity-DEMO-TODAY-main', 'activity-DEMO-TODAY-occupied'];
const shots = path.join(root, 'var/runtime/today-screenshots');
fs.mkdirSync(shots, {recursive: true});
let browser, page, passed = 0;
async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api/v1/' + route, {method, headers: {Authorization: 'Bearer ' + credentials.apiKey, ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: response.status, data: response.status === 204 ? null : await response.json()};
}
function report(name) { passed++; console.log('PASS ' + name); }
function seedActivities(remove = false) {
    const code = `import sqlite3,sys,json,datetime,zoneinfo\np=sys.argv[1]\nassert p.endswith('/var/runtime/today-e2e-database/dreeve.db')\nc=sqlite3.connect(p)\nc.execute("DELETE FROM Activity WHERE activityId LIKE 'activity-DEMO-TODAY-%'")\nif sys.argv[2]=='seed':\n for item in json.load(sys.stdin):\n  t=datetime.datetime.fromisoformat(item.pop('startAt')).astimezone(zoneinfo.ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S')\n  r={'startDateTime':t,'sportType':'Run','distance':0,'elevation':0,'averageSpeed':3.3,'maxSpeed':4.5,'movingTimeInSeconds':600,'elapsedTimeInSeconds':600,'totalImageCount':0,'markedForDeletion':0,'importSource':'fileImport',**item}\n  c.execute('INSERT INTO Activity ('+','.join(r)+') VALUES ('+','.join('?' for _ in r)+')',list(r.values()))\nc.commit()\nc.close()`;
    const result = spawnSync('python3', ['-c', code, database, remove ? 'remove' : 'seed'], {input: JSON.stringify([
        {activityId: ids[0], name: 'DEMO 热身 · 延后四小时', startAt: today + 'T10:00:00+00:00', distance: 2000, movingTimeInSeconds: 600, elapsedTimeInSeconds: 600, averageHeartRate: 130},
        {activityId: ids[1], name: 'DEMO 主课', startAt: today + 'T10:15:00+00:00', distance: 8000, movingTimeInSeconds: 2400, elapsedTimeInSeconds: 2400, averageHeartRate: 160},
        {activityId: ids[2], name: 'DEMO 已属于另一课', startAt: today + 'T09:00:00+00:00', distance: 5000, movingTimeInSeconds: 1800, elapsedTimeInSeconds: 1800, averageHeartRate: 150}
    ]), encoding: 'utf8'});
    assert.equal(result.status, 0, 'Fixture seed or cleanup failed');
}
async function cleanup() {
    // Refuse to delete anything other than this script's exact label namespace.
    for (const kind of ['check-ins', 'sessions']) {
        const collection = await api('training/' + kind);
        for (const row of collection.data.items || []) {
            if (!row.id.startsWith(prefix)) continue;
            const response = await api('training/' + kind + '/' + row.id + '?version=' + row.version, 'DELETE');
            assert.equal(response.status, 204);
        }
    }
    seedActivities(true);
}
(async () => {
    const initial = await api('training/sessions'); assert.equal(initial.status, 200); assert.deepEqual(initial.data.items, [], 'Disposable sessions must initially be empty');
    const profile = await api('training/profile');
    assert.equal((await api('training/profile', 'PUT', {version: profile.data.version, timezone: 'UTC', location: null, notificationsEnabled: false})).status, 200);
    const create = async (suffix, hour, distance) => {
        const result = await api('training/sessions', 'POST', {id: prefix + suffix, title: 'DEMO ' + suffix, startAt: today + 'T' + hour + ':00:00+00:00', type: 'easy', durationMinutes: 60, distanceKm: distance, notes: 'DEMO preserve original plan'});
        assert.equal(result.status, 201); return result.data;
    };
    await create('late-split', '06', 10); await create('second', '07', 5); await create('occupied', '08', 5);
    seedActivities();
    assert.equal((await api('training/sessions/' + prefix + 'occupied/link', 'POST', {version: 1, activityIds: [ids[2]]})).status, 200);
    const health = await api('health/context');
    assert.equal((await api('health/context', 'PUT', {version: health.data.version, constraints: [{id: 'DEMO-today-limit', description: 'DEMO 这是合成的待核对约束', sourceType: 'user', status: 'active'}]})).status, 200);
    const queue = await api('training/reconciliation');
    assert.ok(queue.data.items.some(item => item.planned.id === prefix + 'late-split'));
    const comparison = await api('training/sessions/' + prefix + 'late-split/comparison');
    assert.deepEqual(comparison.data.candidates.map(item => item.id), ids.slice(0, 2));
    assert.equal(comparison.data.candidates[0].startOffsetMinutes, 240);
    assert.equal((await api('training/sessions/' + prefix + 'late-split')).data.status, 'planned');
    report('real imported fixtures produce late-run candidates without automatic completion or occupied record reuse');
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport: {width: 390, height: 844}, timezoneId: 'America/Los_Angeles'});
    page = await context.newPage(); page.setDefaultTimeout(15000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (request.url().includes('/admin/training/api') && request.headers().authorization) errors.push('Browser must not receive bearer credentials'); });
    page.on('dialog', dialog => dialog.accept());
    await page.goto(base + '/admin/training/today?sessionId=' + prefix + 'late-split');
    await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url => !url.pathname.includes('/login')), page.locator('button[type=submit]').click()]);
    await page.goto(base + '/admin/training/today?sessionId=' + prefix + 'late-split');
    await page.locator('#today-date').filter({hasText: today + ' · UTC'}).waitFor();
    await page.locator('[data-activity-id="' + ids[0] + '"]').waitFor();
    assert.equal(await page.locator('#today-checkin-form [name=pain]').inputValue(), '');
    assert.equal(await page.locator('#today-checkin-form [name=fatigue]').inputValue(), '');
    assert.ok(!(await page.content()).includes(credentials.apiKey));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.locator('#today-briefing').getByText(/尚未配置|无法查询天气/).waitFor();
    await page.locator('#today-health').getByText('DEMO 这是合成的待核对约束', {exact: false}).waitFor();
    const noCsrf = await page.evaluate(async () => (await fetch('/admin/training/api/sessions/DEMO-today-late-split/feedback', {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({version: 1, feedback: {rpe: 4}})})).status);
    assert.equal(noCsrf, 403);
    report('private mobile page, runner timezone, unknown fields, missing weather, health constraints, CSRF and no bearer in browser');
    await page.locator('#today-checkin-form [name=sleepHours]').fill('6.5');
    await page.locator('#today-feedback-form [name=notes]').fill('DEMO unsaved feedback');
    await page.locator('#today-refresh').click(); await page.locator('#today-refresh:enabled').waitFor();
    await page.locator('#today-feedback-session').selectOption(prefix + 'second');
    await page.locator('#today-feedback-session').selectOption(prefix + 'late-split');
    assert.equal(await page.locator('#today-checkin-form [name=sleepHours]').inputValue(), '6.5');
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO unsaved feedback');
    await page.locator('#today-checkin-form button[type=submit]').click();
    await page.locator('#today-checkin-status').getByText(/已保存并核验/).waitFor();
    const checks = (await api('training/check-ins?from=' + today + '&to=' + today)).data.items;
    assert.equal(checks[0].sleepHours, 6.5); assert.equal(checks[0].pain, null); assert.equal(checks[0].fatigue, null);
    // The private fixture DB is discarded after acceptance; date-ID check-in intentionally remains.
    report('partial check-in persists without invented pain/fatigue and drafts survive refresh/session switching');
    await page.locator('#today-feedback-form [name=rpe]').fill('4');
    await page.locator('#today-feedback-form [name=pain]').selectOption('false');
    await page.locator('#today-feedback-form details summary').click();
    await page.locator('#today-feedback-form [name="fuel.fluidMl"]').fill('250');
    await page.locator('#today-feedback-form button[type=submit]').click();
    await page.locator('#today-feedback-status').getByText(/已保存并核验/).waitFor();
    let saved = (await api('training/sessions/' + prefix + 'late-split')).data;
    assert.equal(saved.status, 'planned'); assert.equal(saved.notes, 'DEMO preserve original plan'); assert.equal(saved.feedback.fuel.fluidMl, 250); assert.equal(saved.feedback.pain, false);
    report('quick feedback and fueling persist, preserve plan, and do not fabricate completion');
    await page.locator('[data-activity-id="' + ids[0] + '"]').waitFor();
    assert.equal(await page.locator('#today-reconciliation input:checked').count(), 0);
    await page.locator('[data-activity-id="' + ids[0] + '"]').check(); await page.locator('[data-activity-id="' + ids[1] + '"]').check();
    await page.locator('[data-action=review-links]').click(); await page.locator('[data-action=confirm-links]').click();
    await page.locator('#today-reconciliation').getByText(/已保存并核验关联/).waitFor();
    let actual = (await api('training/sessions/' + prefix + 'late-split/comparison')).data;
    assert.deepEqual(actual.planned.activityIds, ids.slice(0, 2)); assert.equal(actual.planned.status, 'completed');
    assert.equal(actual.actual.distanceKm, 10); assert.equal(actual.actual.durationMinutes, 50); assert.equal(actual.actual.averageHeartRate, 154); assert.equal(actual.delta.durationMinutes, -10);
    assert.equal((await api('training/sessions/' + prefix + 'second/link', 'POST', {version: 1, activityIds: [ids[0]]})).status, 409);
    report('explicit split-record confirmation, weighted aggregate, plan delta, and exclusive links');
    await page.locator('#today-feedback-form [name=notes]').fill('DEMO retained after conflict');
    saved = (await api('training/sessions/' + prefix + 'late-split')).data;
    assert.equal((await api('training/sessions/' + prefix + 'late-split', 'PUT', {version: saved.version, notes: 'DEMO concurrent route note'})).status, 200);
    await page.locator('#today-feedback-form button[type=submit]').click();
    await page.locator('#today-feedback-status').getByText(/冲突|已更新|已修改/).waitFor();
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO retained after conflict');
    assert.equal(await page.locator('#today-feedback-form button[type=submit]').isDisabled(), true);
    assert.equal((await api('training/sessions/' + prefix + 'late-split')).data.feedback.notes, 'DEMO unsaved feedback');
    report('real concurrent edit returns 409 and preserves draft without overwriting saved feedback');
    await page.screenshot({path: path.join(shots, 'mobile-today.png'), fullPage: true});
    await page.setViewportSize({width: 1440, height: 1000});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: path.join(shots, 'desktop-today.png'), fullPage: true});
    assert.deepEqual(errors, []); report('desktop/mobile layout and no browser runtime errors');
    await page.goto(base + '/admin/training');
    await page.getByRole('button', {name: '训练日历', exact: true}).click();
    await page.locator('#session-list article').filter({has: page.getByRole('heading', {name: 'DEMO late-split', exact: true})}).getByRole('button', {name: '编辑', exact: true}).click();
    await page.locator('#session-save-state').filter({hasText: '版本 4'}).waitFor();
    await page.locator('#session-form [name=notes]').fill('DEMO updated through full editor');
    await page.locator('#session-form button[type=submit]').click();
    await page.locator('#session-save-state').filter({hasText: '版本 5'}).waitFor();
    saved = (await api('training/sessions/' + prefix + 'late-split')).data;
    assert.equal(saved.feedback.pain, false); assert.equal(saved.feedback.fuel.fluidMl, 250); assert.deepEqual(saved.activityIds, ids.slice(0, 2));
    await page.getByRole('button', {name: '身体状态', exact: true}).click();
    assert.equal(await page.locator('#checkin-form [name=pain]').inputValue(), '');
    assert.equal(await page.locator('#checkin-form [name=fatigue]').inputValue(), '');
    report('full training editor preserves new feedback, split links and unknown check-in fields');
    // Separate empty-state pass after removing only this test's synthetic sessions/activities.
    await cleanup();
    await page.goto(base + '/admin/training/today'); await page.locator('#today-sessions').getByText(/今天没有已安排|今天还没有|今日还没有|今天尚未|今天尚无|还没有安排/).waitFor();
    await page.setViewportSize({width: 390, height: 844});
    await page.screenshot({path: path.join(shots, 'mobile-empty.png'), fullPage: true});
    report('real empty state does not invent a workout or rest day');
    console.log(JSON.stringify({passed, result: 'PASS', source: 'real HTTP; synthetic fixtures only in disposable database'}));
})().catch(async error => { if (page) await page.screenshot({path: path.join(shots, 'failure.png'), fullPage: true}).catch(() => {}); console.error(error.message); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); });
