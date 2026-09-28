/* Real HTTP/browser smoke test. No mocked routes. Run against a disposable database.
 * Requires playwright, TRAINING_CREDENTIALS_FILE (JSON username/password/apiKey), and
 * optionally TRAINING_BASE_URL, PLAYWRIGHT_MODULE, CHROMIUM_EXECUTABLE, TRAINING_SCREENSHOTS_DIR.
 * Credentials never enter page JavaScript or test output. Only records created by this run are deleted.
 */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.TRAINING_BASE_URL || 'http://localhost:8081';
const credentials = JSON.parse(fs.readFileSync(process.env.TRAINING_CREDENTIALS_FILE || path.join(process.cwd(), 'var/runtime/credentials.json'), 'utf8'));
const screenshots = process.env.TRAINING_SCREENSHOTS_DIR || '/tmp/stridehub-browser';
const demo = 'DEMO E2E ' + Date.now();
const created = {'fuel-logs': [], 'check-ins': [], sessions: [], races: []};
const results = [];
const browserErrors = [];
let originalProfile;
let browser;
let page;
let cleaned = false;
const dateInShanghai = date => new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).format(date);
const today = dateInShanghai(new Date());
const tomorrow = dateInShanghai(new Date(Date.now() + 86400000));
async function api(endpoint, method = 'GET', body, authorization = true) {
    const headers = {Accept: 'application/json'};
    if (authorization) headers.Authorization = 'Bearer ' + credentials.apiKey;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + '/api/v1/training' + endpoint, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
    const data = response.status === 204 ? null : await response.json();
    return {status: response.status, data};
}
async function record(kind, text) {
    const response = await api('/' + kind); assert.equal(response.status, 200);
    const row = response.data.items.find(item => [item.title, item.name, item.item, item.notes].some(value => typeof value === 'string' && value.includes(text)));
    assert.ok(row, 'Created record must persist in the actual API');
    if (!created[kind].includes(row.id)) created[kind].push(row.id);
    return row;
}
async function saved(kind, version) { await page.locator('#' + kind + '-save-state').filter({hasText: '版本 ' + version}).waitFor(); }
async function tab(name) { await page.getByRole('button', {name, exact: true}).click(); }
function report(name) { results.push(name); console.log('PASS ' + name); }
async function cleanup() {
    if (cleaned) return; cleaned = true;
    const failures = [];
    for (const kind of ['fuel-logs', 'check-ins', 'sessions', 'races']) {
        // Recover records from this run even when an assertion failed before their ID was recorded.
        const collection = await api('/' + kind);
        for (const item of collection.data?.items || []) {
            if ([item.title, item.name, item.item, item.notes].some(value => typeof value === 'string' && value.includes(demo)) && !created[kind].includes(item.id)) created[kind].push(item.id);
        }
        for (const id of created[kind]) {
            const latest = await api('/' + kind + '/' + encodeURIComponent(id));
            if (latest.status === 404) continue;
            const removed = await api('/' + kind + '/' + encodeURIComponent(id) + '?version=' + latest.data.version, 'DELETE');
            if (![200, 204, 404].includes(removed.status)) failures.push(kind + ':' + removed.status);
        }
    }
    if (originalProfile) {
        const latest = await api('/profile');
        const {id, version, updatedAt, ...fields} = originalProfile;
        const restored = await api('/profile', 'PUT', {...fields, version: latest.data.version});
        if (restored.status !== 200) failures.push('profile:' + restored.status);
        else assert.equal(restored.data.notificationsEnabled, originalProfile.notificationsEnabled);
    }
    assert.equal(failures.length, 0, 'Cleanup failed: ' + failures.join(', '));
    console.log('CLEANUP DEMO records removed; original profile fields restored.');
}
(async () => {
    fs.mkdirSync(screenshots, {recursive: true});
    const initial = await api('/profile'); assert.equal(initial.status, 200); originalProfile = initial.data;
    assert.equal((await api('/sessions', 'GET', undefined, false)).status, 401);
    assert.equal((await api('/openapi.json', 'GET', undefined, false)).status, 401);
    assert.equal((await api('/openapi.json')).status, 200);
    report('Bearer API and OpenAPI authentication');
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport: {width: 1440, height: 1050}, timezoneId: 'America/Los_Angeles'});
    page = await context.newPage(); page.setDefaultTimeout(30000);
    page.on('pageerror', error => browserErrors.push(error.message));
    page.on('request', request => { if (request.url().includes('/training/api') && request.headers().authorization) browserErrors.push('Bearer credential unexpectedly present in browser request'); });
    await page.goto(base + '/admin/training'); await page.locator('[name=_username]').waitFor();
    await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url => !url.pathname.includes('/login')), page.locator('button[type=submit]').click()]);
    await page.goto(base + '/admin/training'); await page.getByText('下一次出发，由你来安排。').waitFor();
    assert.ok(!((await page.content()).includes(credentials.apiKey)), 'No API secret in rendered HTML');
    await page.getByText('尚无已导入的真实跑步活动。你仍可安排训练、记录状态和补给；导入跑步活动后，即可关联并对比。').waitFor();
    const csrf = await page.evaluate(async () => {
        const payload = JSON.stringify({version: 0, notes: 'should not write'});
        const missing = await fetch('/admin/training/api/profile', {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: payload});
        const wrong = await fetch('/admin/training/api/profile', {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': 'incorrect'}, body: payload});
        return [missing.status, wrong.status];
    });
    assert.deepEqual(csrf, [403, 403]);
    await page.screenshot({path: path.join(screenshots, 'desktop-empty.png'), fullPage: true});
    report('Admin login, initial empty activity state, secret-free HTML, session CSRF rejection');

    await page.getByRole('link', {name: '管理后台', exact: true}).click();
    await page.getByRole('link', {name: '跑步工作台', exact: true}).click(); await page.locator('#training-app').waitFor();
    await page.getByRole('link', {name: '使用指南', exact: true}).click();
    assert.ok(page.url().endsWith('/admin/training/guide')); assert.ok((await page.locator('body').innerText()).includes('API'));
    await page.goto(base + '/admin/training'); await page.getByText('下一次出发，由你来安排。').waitFor();
    report('Existing admin navigation and local user guide');

    await tab('跑步设置');
    await page.locator('#profile-form [name=locationLabel]').fill('DEMO 上海');
    await page.locator('#profile-form [name=latitude]').fill('31.2304'); await page.locator('#profile-form [name=longitude]').fill('121.4737');
    await page.locator('#profile-form [name=timezone]').fill('Asia/Shanghai'); await page.locator('#profile-form [name=notificationsEnabled]').uncheck();
    await page.getByRole('button', {name: '保存跑步设置', exact: true}).click(); await saved('profile', originalProfile.version + 1);
    assert.equal((await api('/profile')).data.location.label, 'DEMO 上海'); report('Runner location/timezone persisted with notifications disabled');

    await tab('比赛目标'); await page.locator('#race-form [name=name]').fill(demo + ' 秋季半马'); await page.locator('#race-form [name=date]').fill(tomorrow);
    await page.getByRole('button', {name: '保存比赛目标', exact: true}).click(); await saved('race', 1); let race = await record('races', demo);
    await page.locator('#race-form [name=targetTimeMinutes]').fill('118'); await page.getByRole('button', {name: '保存比赛目标', exact: true}).click(); await saved('race', 2);
    assert.equal((await api('/races/' + race.id)).data.targetTimeMinutes, 118); report('Race create and update with fractional half-marathon distance');

    await tab('身体状态'); await page.locator('#checkin-form [name=date]').fill(today); await page.locator('#checkin-form [name=sleepHours]').fill('7.5'); await page.locator('#checkin-form [name=fatigue]').selectOption('3'); await page.locator('#checkin-form [name=notes]').fill(demo + ' 身体状态');
    await page.getByRole('button', {name: '保存身体状态', exact: true}).click(); await saved('checkin', 1); const checkin = await record('check-ins', demo);
    await page.locator('#checkin-form [name=soreness]').selectOption('2'); await page.getByRole('button', {name: '保存身体状态', exact: true}).click(); await saved('checkin', 2);
    await page.locator('#checkin-form [name=notes]').fill(demo + ' 未保存草稿'); await tab('出发简报'); await page.getByText('身体状态表单有未保存修改，尚未用于简报。').waitFor();
    assert.equal((await api('/check-ins/' + checkin.id)).data.notes, demo + ' 身体状态'); report('Wellbeing create/update and separate saved versus pending state');

    await tab('训练日历'); const maliciousTitle = demo + ' <img src=x onerror=alert(1)> 轻松跑';
    await page.locator('#session-form [name=title]').fill(maliciousTitle); await page.locator('#session-form [name=startLocal]').fill(tomorrow + 'T06:30'); await page.locator('#session-form [name=durationMinutes]').fill('65'); await page.locator('#session-form [name=distanceKm]').fill('10'); await page.locator('#session-form [name=raceId]').selectOption(race.id);
    await page.getByRole('button', {name: '＋ 添加一段', exact: true}).click(); await page.locator('#step-rows [data-field=kind]').fill('DEMO 热身'); await page.locator('#step-rows [data-field=minutes]').fill('10.5');
    await page.getByRole('button', {name: '＋ 添加补给', exact: true}).click(); await page.locator('#fuel-plan-rows [data-field=item]').fill('DEMO 能量胶'); await page.locator('#fuel-plan-rows [data-field=carbsGrams]').fill('25');
    await page.getByRole('button', {name: '保存训练', exact: true}).click(); await saved('session', 1); let session = await record('sessions', demo);
    assert.equal(session.startAt, tomorrow + 'T06:30:00+08:00'); assert.equal(session.steps[0].minutes, 10.5); assert.equal(session.fuelPlan[0].carbsGrams, 25); assert.equal(await page.locator('#session-list img').count(), 0);
    await page.locator('#session-list').getByRole('button', {name: '对比 / 简报', exact: true}).click(); await page.getByText('尚未关联真实跑步活动。关联后可查看实际时长、距离与计划的差异。').waitFor();
    report('Structured session and fuel plan saved, timezone independent of LA browser, escaped title, actual empty comparison');

    await tab('出发简报'); await page.locator('#briefing h2').filter({hasText: maliciousTitle}).waitFor(); await page.locator('#refresh-briefing:enabled').waitFor();
    const briefing = await api('/briefing?sessionId=' + session.id); assert.equal(briefing.status, 200); assert.equal(briefing.data.session.id, session.id); assert.equal(briefing.data.forecast.source, 'Open-Meteo');
    console.log('WEATHER ' + JSON.stringify({status: briefing.data.forecast.status, hourlyCount: briefing.data.forecast.sessionHours?.length || 0, message: briefing.data.forecast.message}));
    await page.screenshot({path: path.join(screenshots, 'desktop-briefing.png'), fullPage: true}); report('Real next-day Shanghai weather briefing (provider availability reported)');

    await tab('补给练习'); await page.locator('#fuel-form [name=date]').fill(today); await page.locator('#fuel-form [name=sessionId]').selectOption(session.id); await page.locator('#fuel-form [name=item]').fill(demo + ' 运动饮料'); await page.locator('#fuel-form [name=carbsGrams]').fill('30'); await page.locator('#fuel-form [name=fluidMl]').fill('250');
    await page.getByRole('button', {name: '保存补给记录', exact: true}).click(); await saved('fuel', 1); const fuel = await record('fuel-logs', demo);
    await page.locator('#fuel-form [name=giComfort]').selectOption('mild'); await page.getByRole('button', {name: '保存补给记录', exact: true}).click(); await saved('fuel', 2); assert.equal((await api('/fuel-logs/' + fuel.id)).data.giComfort, 'mild'); report('Linked fueling practice create/update');

    await tab('训练日历'); await page.locator('#session-form [name=title]').fill(demo + ' 本地草稿');
    const external = await api('/sessions/' + session.id, 'PUT', {version: session.version, notes: demo + ' API 并发更新'}); assert.equal(external.status, 200);
    await page.getByRole('button', {name: '保存训练', exact: true}).click(); await page.locator('#session-form .form-status').filter({hasText: '保存冲突'}).waitFor(); assert.equal(await page.locator('#session-form [name=title]').inputValue(), demo + ' 本地草稿');
    await page.getByRole('button', {name: '刷新数据', exact: true}).click(); await page.getByText('列表和简报已刷新，未保存的表单保持原样。').waitFor(); assert.equal(await page.locator('#session-form [name=title]').inputValue(), demo + ' 本地草稿');
    await page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click(); await page.locator('#session-form').getByText('服务器版本 2', {exact: true}).click();
    page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', {name: '用此版本替换表单', exact: true}).click(); await saved('session', 2);
    await page.locator('#session-form [name=status]').selectOption('skipped'); await page.getByRole('button', {name: '保存训练', exact: true}).click(); await saved('session', 3);
    assert.equal((await api('/sessions/' + session.id)).data.notes, demo + ' API 并发更新'); report('Real bearer/browser conflict, draft preservation, explicit merge and skip');

    await page.locator('#session-form [name=status]').selectOption('planned'); await page.locator('#session-form [name=startLocal]').fill(tomorrow + 'T07:00'); await page.locator('#session-form [name=feedbackNotes]').fill('DEMO 实际体感'); await page.locator('#session-form [name=thermalFeeling]').selectOption('hot');
    await page.getByRole('button', {name: '保存训练', exact: true}).click(); await saved('session', 4); session = (await api('/sessions/' + session.id)).data; assert.equal(session.startAt, tomorrow + 'T07:00:00+08:00'); assert.equal(session.feedback.thermalFeeling, 'hot');
    await tab('身体状态'); page.once('dialog', dialog => dialog.accept()); await page.locator('#checkin-form [data-reset=checkin]').click();
    await page.reload(); await page.locator('#session-list').getByText(maliciousTitle, {exact: true}).waitFor({state: 'attached'}); await tab('训练日历');
    await page.locator('#session-list').getByRole('button', {name: '编辑', exact: true}).click(); await saved('session', 4); assert.equal(await page.locator('#session-form [name=startLocal]').inputValue(), tomorrow + 'T07:00'); report('Reschedule, feedback auto-enable, actual reload and persisted record');

    await page.setViewportSize({width: 390, height: 844}); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); await page.screenshot({path: path.join(screenshots, 'mobile-schedule.png'), fullPage: true}); await tab('出发简报'); await page.locator('#refresh-briefing:enabled').waitFor(); await page.screenshot({path: path.join(screenshots, 'mobile-briefing.png'), fullPage: true}); report('390px schedule and briefing without horizontal overflow');

    for (const [tabName, selector] of [['补给练习', '#fuel-list'], ['身体状态', '#checkin-list'], ['训练日历', '#session-list'], ['比赛目标', '#race-list']]) {
        await tab(tabName); page.once('dialog', dialog => dialog.accept()); await page.locator(selector).getByRole('button', {name: '删除', exact: true}).click(); await page.getByText('记录已删除。', {exact: true}).waitFor();
        await page.waitForFunction(selector => !document.querySelector(selector).textContent.includes('DEMO E2E'), selector);
    }
    for (const [kind, ids] of Object.entries(created)) for (const id of ids) assert.equal((await api('/' + kind + '/' + id)).status, 404);
    report('UI deletion for fuel, check-in, session and race, verified through actual API');
    const partial = await api('/sessions', 'POST', {title: demo + ' 部分反馈', startAt: tomorrow + 'T06:30:00+08:00', durationMinutes: 45, type: 'easy', status: 'completed', feedback: {notes: demo + ' 仅文字反馈'}});
    assert.equal(partial.status, 201); created.sessions.push(partial.data.id);
    await page.reload(); await tab('训练日历');
    await page.locator('#session-list').getByRole('button', {name: '编辑', exact: true}).click(); await saved('session', 1);
    assert.equal(await page.locator('#session-form [name=rpe]').inputValue(), '');
    assert.equal(await page.locator('#session-form [name=thermalFeeling]').inputValue(), '');
    await page.locator('#session-form [name=title]').fill(demo + ' 修改标题');
    await page.getByRole('button', {name: '保存训练', exact: true}).click(); await saved('session', 2);
    assert.deepEqual((await api('/sessions/' + partial.data.id)).data.feedback, {notes: demo + ' 仅文字反馈'});
    report('Partial Codex feedback survives browser edits without invented RPE or thermal feeling');
    assert.deepEqual(browserErrors, []); report('No browser JavaScript errors or bearer exposure');
})().then(async () => {
    await cleanup(); console.log(JSON.stringify({passed: results, screenshots, scope: 'real browser and HTTP API; no mocked routes'}));
}).catch(async error => {
    console.error('FAIL ' + error.message.replaceAll(credentials.apiKey, '[redacted]').replaceAll(credentials.password, '[redacted]'));
    if (page && !page.url().includes('/login')) await page.screenshot({path: path.join(screenshots, 'failure.png'), fullPage: true}).catch(() => {});
    try { await cleanup(); } catch (failure) { console.error('CLEANUP FAILURE ' + failure.message); }
    process.exitCode = 1;
}).finally(async () => { if (browser) await browser.close(); });
