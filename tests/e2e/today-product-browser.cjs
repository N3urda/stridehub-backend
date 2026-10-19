/* Real HTTP/browser product flow; disposable :8082, DEMO data only.
 * Aborted/held GETs explicitly inject network faults and reorder real API responses. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8082');
assert.ok(fs.existsSync('var/runtime/product-e2e/database/dreeve.db'));
const credentials = JSON.parse(fs.readFileSync('var/runtime/credentials.json'));
const prefix = 'DEMO-product-today-';
const created = [];
let browser, passed = 0;
const report = text => { console.log('PASS ' + text); passed++; };
async function api(path, method = 'GET', body) {
    const response = await fetch(base + '/api/v1/training' + path, {method, headers: {Authorization: 'Bearer ' + credentials.apiKey, ...(body ? {'Content-Type': 'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined});
    const data = response.status === 204 ? null : await response.json();
    assert.ok(response.ok, `${method} ${path}: ${response.status}`); return data;
}
const day = (date, delta) => { const value = new Date(date + 'T12:00:00Z'); value.setUTCDate(value.getUTCDate() + delta); return value.toISOString().slice(0, 10); };
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise, resolve}; };
async function bounded(promise, label) {
    let timer;
    try { return await Promise.race([promise, new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('Timed out: ' + label)), 10000); })]); }
    finally { clearTimeout(timer); }
}
async function login(page) {
    await page.goto(base + '/admin/login');
    await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url => !url.pathname.includes('/login')), page.locator('button[type=submit]').click()]);
}
(async () => {
    const initial = await api('/today'), date = initial.date;
    const create = async (suffix, start, extra = {}) => {
        const id = prefix + suffix; const row = await api('/sessions', 'POST', {id, title: 'DEMO ' + suffix, type: 'easy', startAt: start + 'T00:00:00+08:00', durationMinutes: 30, ...extra}); created.push(id); return row;
    };
    const course = await create('course', date, {steps: [{kind: '热身', minutes: 10, target: '轻松交谈'}, {kind: '主训练', minutes: 30, target: 'DEMO <img src=x onerror=alert(1)>'}], fuelPlan: [{minute: 20, item: 'DEMO 水', fluidMl: 200}], notes: 'DEMO 保留路线备注'});
    const old = await create('older', day(date, -50));
    for (let i = 1; i <= 24; i++) await create('pending-' + i, day(date, -i));
    const rest = await create('rest', day(date, -3), {type: 'rest', status: 'completed', feedback: {}});
    const queue = await api('/follow-ups'); assert.ok(queue.olderPendingCount >= 1); assert.ok(queue.totalPages > 1);
    assert.ok(!queue.items.some(item => item.session.id === rest.id));
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport: {width: 390, height: 844}, timezoneId: 'America/Los_Angeles'});
    const page = await context.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
    await login(page); await page.goto(base + '/admin/training/today?sessionId=' + course.id);
    await page.locator('#today-course').getByText('轻松交谈', {exact: true}).waitFor();
    assert.match(await page.locator('#today-course').innerText(), /分段合计 40 分钟/);
    assert.match(await page.locator('#today-course').innerText(), /200 毫升/);
    assert.match(await page.locator('#today-course').innerText(), /保留路线备注/);
    assert.equal(await page.locator('#today-course img').count(), 0);
    assert.ok((await page.getByRole('link', {name: '编辑这节课'}).getAttribute('href')).includes('sessionId=' + course.id));
    report('today exposes ordered steps, targets, fuel, notes and a duration mismatch without altering the plan');
    await page.locator('#today-feedback-form [name=notes]').fill('DEMO preserve this draft');
    await page.locator('[data-action=pending-next]').click(); await page.locator('#today-pending-pages').getByText('2 / 2', {exact: true}).waitFor();
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO preserve this draft');
    await page.locator('[data-action=pending-older]').click(); await page.locator(`#today-pending-list [data-session-id="${old.id}"]`).waitFor();
    await page.locator(`#today-pending-list [data-session-id="${old.id}"]`).click();
    await page.locator('#today-course').getByText('DEMO older', {exact: true}).waitFor();
    await page.locator('#today-feedback-session').selectOption(course.id);
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO preserve this draft');
    report('paged and older follow-ups remain reachable and switching sessions preserves separate drafts');
    await page.route('**/admin/training/api/follow-ups?**', route => route.abort('failed'));
    await page.locator('[data-pending-days="7"]').click(); await page.locator('#today-pending-status').getByText(/读取失败.*保留/).waitFor();
    assert.ok(await page.locator(`#today-pending-list [data-session-id="${old.id}"]`).count());
    await page.unroute('**/admin/training/api/follow-ups?**');
    await page.locator('#today-pending-range [name=from]').fill(day(date, -500)); await page.locator('#today-pending-range [name=to]').fill(date);
    await page.locator('#today-pending-range [type=submit]').click(); await page.locator('#today-pending-status').getByText(/不超过 366 天/).waitFor();
    report('failed or invalid date searches retain clearly labelled previous results (injected read failure)');

    // These older sessions fall outside the default Today payload and span two pages.
    let historical;
    for (let i = 1; i <= 22; i++) historical = await create('history-' + i, day(date, -30 - i));
    await page.locator('#today-pending-range [name=from]').fill(day(date, -90));
    await page.locator('#today-pending-range [name=to]').fill(day(date, -31));
    await page.locator('#today-pending-range [type=submit]').click();
    await page.locator(`#today-pending-list [data-session-id="${historical.id}"]`).waitFor();
    await page.locator(`#today-pending-list [data-session-id="${historical.id}"]`).click();
    await page.locator('#today-course').getByText('DEMO history-22', {exact: true}).waitFor();
    await page.locator('#today-feedback-form [name=notes]').fill('DEMO historical draft stays selected');
    await page.locator('[data-action=pending-next]').click();
    await page.locator('#today-pending-pages').getByText('2 / 2', {exact: true}).waitFor();
    assert.equal(await page.locator(`#today-pending-list [data-session-id="${historical.id}"]`).count(), 0);
    const historicalRead = page.waitForResponse(response => new URL(response.url()).pathname === '/admin/training/api/sessions/' + historical.id);
    await page.locator('#today-refresh').click(); await historicalRead;
    await page.waitForFunction(() => !document.querySelector('#today-refresh').disabled);
    assert.equal(await page.locator('#today-feedback-session').inputValue(), historical.id);
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO historical draft stays selected');
    assert.equal((await api('/sessions/' + historical.id)).feedback, null, 'refresh must not save the retained draft');
    await page.locator('#today-feedback-session').selectOption(course.id);
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO preserve this draft');
    report('a selected historical session outside the current page survives a full refresh without saving its draft');

    // Hold a real comparison response during global refresh, then hold a newer range query.
    // Releasing refresh first must never trigger an old-range request that cancels the user's choice.
    await page.locator('[data-pending-days="30"]').click();
    await page.locator('#today-pending-status').getByText(`当前显示 ${day(date, -29)} — ${date}`, {exact: false}).waitFor();
    const comparisonHeld = deferred(), releaseComparison = deferred(), pendingHeld = deferred(), releasePending = deferred();
    const pendingRequests = [];
    const comparisonPattern = '**/admin/training/api/sessions/' + course.id + '/comparison';
    const pendingPattern = '**/admin/training/api/follow-ups?**';
    await page.route(comparisonPattern, async route => {
        const response = await route.fetch(); comparisonHeld.resolve();
        await releaseComparison.promise; await route.fulfill({response});
    });
    await page.route(pendingPattern, async route => {
        const requestFrom = new URL(route.request().url()).searchParams.get('from'); pendingRequests.push(requestFrom);
        if (requestFrom !== day(date, -6)) { await route.continue(); return; }
        const response = await route.fetch(); pendingHeld.resolve();
        await releasePending.promise; await route.fulfill({response});
    });
    try {
        await page.locator('#today-refresh').click(); await bounded(comparisonHeld.promise, 'refresh comparison response');
        await page.locator('[data-pending-days="7"]').click(); await bounded(pendingHeld.promise, 'new seven-day response');
        releaseComparison.resolve();
        await page.waitForFunction(() => !document.querySelector('#today-refresh').disabled);
        assert.deepEqual(pendingRequests, [day(date, -6)], 'global refresh must not start a superseded 30-day query');
        releasePending.resolve();
        await page.locator('#today-pending-status').getByText(`当前显示 ${day(date, -6)} — ${date}`, {exact: false}).waitFor();
        assert.equal(await page.locator('#today-pending-range [name=from]').inputValue(), day(date, -6));
        assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO preserve this draft');
    } finally {
        releaseComparison.resolve(); releasePending.resolve();
        await page.unroute(comparisonPattern); await page.unroute(pendingPattern);
    }
    report('a delayed refresh cannot supersede a newer date query (real API responses released out of order)');

    const ancient = await create('ancient', day(date, -900));
    const middle = await create('middle-year', day(date, -450));
    await page.locator('[data-pending-days="30"]').click();
    await page.locator('#today-pending-status').getByText(`当前显示 ${day(date, -29)} — ${date}`, {exact: false}).waitFor();
    await page.locator('[data-action=pending-older]').click();
    await page.locator(`#today-pending-list [data-session-id="${ancient.id}"]`).waitFor();
    assert.equal(await page.locator('#today-pending-range [name=from]').inputValue(), day(date, -900));
    assert.equal(await page.locator('#today-pending-range [name=to]').inputValue(), day(date, -535));
    await page.locator('[data-action=pending-later]').click();
    await page.locator(`#today-pending-list [data-session-id="${middle.id}"]`).waitFor();
    assert.equal(await page.locator('#today-pending-range [name=from]').inputValue(), day(date, -534));
    assert.equal(await page.locator('#today-pending-range [name=to]').inputValue(), day(date, -169));
    await page.locator('[data-action=pending-later]').click();
    await page.locator(`#today-pending-list [data-session-id="${old.id}"]`).waitFor();
    assert.equal(await page.locator('#today-pending-range [name=from]').inputValue(), day(date, -168));
    assert.equal(await page.locator('#today-pending-range [name=to]').inputValue(), date);
    // Return to the middle window to exercise the explicit recent-history escape route.
    await page.locator('#today-pending-range [name=from]').fill(day(date, -534));
    await page.locator('#today-pending-range [name=to]').fill(day(date, -169));
    await page.locator('#today-pending-range [type=submit]').click();
    await page.locator(`#today-pending-list [data-session-id="${middle.id}"]`).waitFor();
    await page.locator('[data-action=pending-recent]').click();
    await page.locator('#today-pending-status').getByText(`当前显示 ${day(date, -29)} — ${date}`, {exact: false}).waitFor();
    assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), 'DEMO preserve this draft');
    report('multi-year backlog can advance through bounded windows and return to recent work without losing drafts');

    await context.clearCookies(); const other = await context.newPage(); await login(other); await other.close();
    await page.locator('#today-feedback-form [type=submit]').click(); await page.locator('#today-feedback-status').getByText(/已保存并核验/).waitFor();
    assert.equal((await api('/sessions/' + course.id)).feedback.notes, 'DEMO preserve this draft');
    report('re-login updates CSRF without reloading the original draft and feedback persists');
    fs.mkdirSync('var/runtime/product-e2e/screenshots', {recursive: true});
    for (const width of [320, 390, 1280]) {
        await page.setViewportSize({width, height: 900});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'overflow at ' + width);
        await page.screenshot({path: `var/runtime/product-e2e/screenshots/today-${width}.png`, fullPage: true});
    }
    assert.deepEqual(errors, []); report('320/390/1280px layouts fit with no JavaScript errors');
    console.log(`${passed} product flow groups passed`);
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
    if (browser) await browser.close();
    for (const id of created.reverse()) { const row = await api('/sessions/' + id); await api('/sessions/' + id + '?version=' + row.version, 'DELETE'); }
});
