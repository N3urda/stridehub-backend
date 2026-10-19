/* Disposable HTTP instance only. Faults drop actual responses after real writes;
 * no fixture response substitutes the server's persistence or conflict handling. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL || process.env.TRAINING_BASE_URL;
if (base !== 'http://localhost:8082' || !fs.existsSync('var/runtime/product-e2e/database/dreeve.db')) throw new Error('Set STRIDEHUB_TEST_URL=http://localhost:8082 and prepare the disposable product-e2e database. Personal instances are refused.');
const credentials = JSON.parse(fs.readFileSync(process.env.TRAINING_CREDENTIALS_FILE || 'var/runtime/credentials.json'));
const prefix = 'DEMO-recovery ' + Date.now();
const created = new Set();
const otherCreated = [];
const day = '2031-04-22';
const constraintId = 'DEMO-recovery-' + Date.now();
let browser, page, originalHealth;
async function api(path, method = 'GET', body, namespace = 'training') {
    const response = await fetch(base + '/api/v1/' + namespace + path, {method, headers: {Authorization: 'Bearer ' + credentials.apiKey, ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: response.status, data: response.status === 204 ? null : await response.json()};
}
async function login(target) {
    await target.goto(base + '/admin/training');
    if (await target.locator('[name=_username]').count()) {
        await target.locator('[name=_username]').fill(credentials.username);
        await target.locator('[name=_password]').fill(credentials.password);
        await Promise.all([target.waitForURL(url => !url.pathname.includes('/login')), target.locator('button[type=submit]').click()]);
    }
}
const saved = version => page.locator('#session-save-state').filter({hasText: '版本 ' + version}).waitFor();
async function sessionRecords() {
    const result = await api('/sessions');
    assert.equal(result.status, 200, 'Read sessions from the disposable API instance');
    const records = result.data.items.filter(row => row.title.startsWith(prefix));
    records.forEach(row => created.add(row.id));
    return records;
}
async function setupSession(title) {
    await page.getByRole('button', {name: '训练日历', exact: true}).click();
    await page.locator('#session-form [name=title]').fill(title);
    await page.locator('#session-form [name=startLocal]').fill(day + 'T06:30');
    await page.locator('#session-form [name=offset]').fill('+08:00');
    await page.locator('#session-form [name=durationMinutes]').fill('40');
}
async function cleanup() {
    for (const [resource, id] of otherCreated) { const current = await api('/' + resource + '/' + id); if (current.status === 200) await api('/' + resource + '/' + id + '?version=' + current.data.version, 'DELETE'); }
    await sessionRecords();
    for (const id of created) { const current = await api('/sessions/' + id); if (current.status === 200) await api('/sessions/' + id + '?version=' + current.data.version, 'DELETE'); }
    if (originalHealth) {
        const latest = await api('/context', 'GET', undefined, 'health');
        await api('/context', 'PUT', {constraints: latest.data.constraints.filter(item => item.id !== constraintId), version: latest.data.version}, 'health');
    }
}
(async () => {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport: {width: 390, height: 844}});
    page = await context.newPage(); page.setDefaultTimeout(15000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await login(page); await page.goto(base + '/admin/training'); await page.waitForFunction(() => !!document.querySelector('#profile-form')._record);

    // A lost create response must never turn the next save into a second record.
    await setupSession(prefix + ' lost response');
    let dropped = false;
    await page.route('**/admin/training/api/sessions', async route => {
        if (!dropped && route.request().method() === 'POST') { dropped = true; await route.fetch(); await route.abort('failed'); }
        else await route.continue();
    });
    await page.locator('#session-form [type=submit]').click();
    await page.waitForFunction(() => !document.querySelector('#session-form')._busy);
    if (!(await page.locator('#session-save-state').innerText()).includes('版本 1')) {
        const review = page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true});
        if (await review.count()) await review.click();
        else await page.locator('#session-form [type=submit]').click();
        await page.waitForFunction(() => !document.querySelector('#session-form')._busy);
    }
    assert.equal((await sessionRecords()).length, 1, 'A lost successful create response must not create a duplicate');
    await saved(1); await page.unroute('**/admin/training/api/sessions');
    let record = (await sessionRecords())[0];
    console.log('PASS lost create response is read back, with one persisted record');

    // Acknowledged writes still need verification; failed reads retain the draft.
    let blockRead = true;
    await page.route('**/admin/training/api/sessions/' + record.id, async route => {
        if (route.request().method() === 'GET' && blockRead) await route.abort('failed'); else await route.continue();
    });
    await page.locator('#session-form [name=notes]').fill(prefix + ' verified draft');
    await page.locator('#session-form [type=submit]').click();
    await page.waitForFunction(() => !document.querySelector('#session-form')._busy);
    assert.equal(await page.locator('#session-form [type=submit]').isDisabled(), true);
    assert.equal(await page.locator('#session-form [name=notes]').inputValue(), prefix + ' verified draft');
    assert.equal((await api('/sessions/' + record.id)).data.version, 2);
    blockRead = false;
    await page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click();
    await saved(2); await page.unroute('**/admin/training/api/sessions/' + record.id);
    console.log('PASS read-back failure blocks repeated writes and recovers without losing the draft');

    // Explicitly resolving a conflict only carries over locally edited fields.
    await page.locator('#session-form [name=title]').fill(prefix + ' local title');
    const external = await api('/sessions/' + record.id, 'PUT', {version: 2, notes: prefix + ' other editor', feedback: {pain: true, notes: 'External feedback'}});
    assert.equal(external.status, 200);
    await page.locator('#session-form [type=submit]').click();
    await page.locator('#session-form .form-status').filter({hasText: '保存冲突'}).waitFor();
    assert.equal(await page.locator('#session-form [type=submit]').isDisabled(), true);
    await page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click();
    await page.locator('#session-form').getByText(prefix + ' other editor', {exact: false}).waitFor();
    await page.locator('#session-form').getByRole('button', {name: '已核对，保留草稿继续编辑', exact: true}).click();
    await page.locator('#session-form [type=submit]').click(); await saved(4);
    record = (await api('/sessions/' + record.id)).data;
    assert.equal(record.title, prefix + ' local title'); assert.equal(record.notes, prefix + ' other editor'); assert.equal(record.feedback.pain, true);
    console.log('PASS explicit conflict review preserves concurrent fields and local edits');

    // Replacing the login session must not require losing this page's draft.
    await page.locator('#session-form [name=notes]').fill(prefix + ' after login');
    await context.clearCookies(); const loginPage = await context.newPage(); await login(loginPage); await loginPage.close();
    await page.locator('#session-form [type=submit]').click(); await saved(5);
    assert.equal((await api('/sessions/' + record.id)).data.notes, prefix + ' after login');
    console.log('PASS fresh session token allows saving the existing draft after re-login');

    // New edits made while a verification is blocked remain a separate draft.
    blockRead = true;
    await page.route('**/admin/training/api/sessions/' + record.id, async route => {
        if (route.request().method() === 'GET' && blockRead) await route.abort('failed'); else await route.continue();
    });
    await page.locator('#session-form [name=notes]').fill(prefix + ' saved before recovery');
    await page.locator('#session-form [type=submit]').click();
    await page.waitForFunction(() => !document.querySelector('#session-form')._busy);
    await page.locator('#session-form [name=notes]').fill(prefix + ' newer unsaved draft');
    blockRead = false;
    await page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click();
    await page.waitForFunction(() => !document.querySelector('#session-form')._busy);
    assert.equal(await page.locator('#session-form [name=notes]').inputValue(), prefix + ' newer unsaved draft');
    assert.equal((await api('/sessions/' + record.id)).data.notes, prefix + ' saved before recovery');
    await page.locator('#session-form [type=submit]').click(); await saved(7);
    await page.unroute('**/admin/training/api/sessions/' + record.id);
    console.log('PASS verifying an earlier save preserves newer unsaved edits');

    // The shared create/recovery flow also protects the other editable resources.
    for (const config of [
        {kind: 'race', resource: 'races', tab: '比赛目标', field: 'name', title: prefix + ' race'},
        {kind: 'checkin', resource: 'check-ins', tab: '身体状态', field: 'notes', title: prefix + ' checkin'},
        {kind: 'fuel', resource: 'fuel-logs', tab: '补给练习', field: 'item', title: prefix + ' fuel'},
    ]) {
        await page.getByRole('button', {name: config.tab, exact: true}).click();
        const editor = page.locator('#' + config.kind + '-form');
        if (config.kind === 'checkin') await editor.locator('[data-reset=checkin]').click();
        await editor.locator('[name=' + config.field + ']').fill(config.title);
        await editor.locator('[name=date]').fill('2031-04-23');
        let lost = false;
        await page.route('**/admin/training/api/' + config.resource, async route => {
            if (!lost && route.request().method() === 'POST') { lost = true; const response = await route.fetch(); const body = await response.json(); if (body.id) otherCreated.push([config.resource, body.id]); await route.abort('failed'); } else await route.continue();
        });
        await editor.locator('[type=submit]').click();
        await page.locator('#' + config.kind + '-save-state').filter({hasText: '版本 1'}).waitFor();
        const rows = (await api('/' + config.resource)).data.items.filter(item => item[config.field] === config.title);
        assert.equal(rows.length, 1); assert.equal(rows[0].date, '2031-04-23');
        await page.unroute('**/admin/training/api/' + config.resource);
    }
    console.log('PASS lost create responses for races, daily check-ins and fuel logs');

    const existingCheckin = await api('/check-ins', 'POST', {id: 'DEMO-recovery-checkin-' + Date.now(), date: '2031-04-24', pain: true, fatigue: 4, soreness: 3, notes: prefix + ' existing health note'});
    assert.equal(existingCheckin.status, 201); otherCreated.push(['check-ins', existingCheckin.data.id]);
    await page.getByRole('button', {name: '身体状态', exact: true}).click();
    await page.locator('#checkin-form [data-reset=checkin]').click();
    await page.locator('#checkin-form [name=date]').fill('2031-04-24');
    await page.locator('#checkin-form [name=sleepHours]').fill('8');
    await page.locator('#checkin-form [type=submit]').click();
    await page.locator('#checkin-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click();
    await page.locator('#checkin-form').getByRole('button', {name: '已核对，保留草稿继续编辑', exact: true}).click();
    await page.locator('#checkin-form [type=submit]').click();
    await page.locator('#checkin-save-state').filter({hasText: '版本 2'}).waitFor();
    const mergedCheckin = (await api('/check-ins/' + existingCheckin.data.id)).data;
    assert.equal(mergedCheckin.sleepHours, 8); assert.equal(mergedCheckin.pain, true, 'Unfilled pain must survive a same-date create conflict');
    assert.equal(mergedCheckin.fatigue, 4); assert.equal(mergedCheckin.soreness, 3); assert.equal(mergedCheckin.notes, prefix + ' existing health note');
    console.log('PASS same-date new check-in conflict preserves untouched existing observations');

    await page.goto(base + '/admin/training?sessionId=' + record.id + '&tab=profile');
    await page.waitForFunction(() => !!document.querySelector('#session-form')._record);
    assert.equal(await page.locator('#panel-profile').isVisible(), true, 'An explicit settings tab must survive a session deep link');
    await page.reload(); await page.waitForFunction(() => !!document.querySelector('#session-form')._record);
    assert.equal(await page.locator('#panel-profile').isVisible(), true);
    console.log('PASS explicit tab is respected on session deep-link refresh');

    const deletedSource = await api('/sessions', 'POST', {title: prefix + ' deleted source', startAt: day + 'T09:00:00+08:00', durationMinutes: 30, type: 'easy'});
    assert.equal(deletedSource.status, 201); created.add(deletedSource.data.id);
    await page.goto(base + '/admin/training?sessionId=' + deletedSource.data.id); await saved(1);
    await api('/sessions/' + deletedSource.data.id + '?version=1', 'DELETE');
    await page.locator('#session-form [name=notes]').fill(prefix + ' rescue draft');
    await page.locator('#session-form [type=submit]').click();
    await page.locator('#session-form').getByRole('button', {name: '查看服务器最新版本（保留草稿）', exact: true}).click();
    await page.locator('#session-form').getByRole('button', {name: '确认另存为新记录', exact: true}).click();
    await page.locator('#session-form [type=submit]').click(); await saved(1);
    const rescued = (await sessionRecords()).find(row => row.notes === prefix + ' rescue draft');
    assert.ok(rescued); assert.notEqual(rescued.id, deletedSource.data.id); assert.equal((await api('/sessions/' + deletedSource.data.id)).status, 404);
    console.log('PASS deleted record draft can be explicitly saved under a new identity');

    // Cross-page entry selects the specific session; its briefing shows actual constraints.
    originalHealth = (await api('/context', 'GET', undefined, 'health')).data;
    const constraint = {id: constraintId, description: prefix + ' future constraint', sourceType: 'user', status: 'active', validFrom: day, reviewOn: '2031-05-01'};
    assert.equal((await api('/context', 'PUT', {version: originalHealth.version, constraints: [...originalHealth.constraints, constraint]}, 'health')).status, 200);
    await page.goto(base + '/admin/training?sessionId=' + record.id);
    await page.waitForFunction(id => document.querySelector('#session-form')._record?.id === id, record.id);
    assert.equal(await page.locator('#session-form [name=title]').inputValue(), prefix + ' local title');
    await page.getByRole('button', {name: '出发简报', exact: true}).click();
    await page.locator('#briefing').getByText(constraint.description, {exact: true}).waitFor();
    assert.ok((await page.locator('#briefing').innerText()).includes('2031-05-01'));
    await page.goto(base + '/admin/training?tab=profile'); await page.locator('#panel-profile:not([hidden]) #profile-form [name=timezone]').waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    console.log('PASS session/settings deep links and date-specific health constraint source text');

    // A lost delete response is confirmed by reading 404, not by repeating DELETE.
    await page.goto(base + '/admin/training?sessionId=' + record.id); await saved(7);
    let deleteCount = 0;
    await page.route('**/admin/training/api/sessions/' + record.id + '?version=*', async route => {
        if (route.request().method() === 'DELETE') { deleteCount++; await route.fetch(); await route.abort('failed'); } else await route.continue();
    });
    page.once('dialog', dialog => dialog.accept());
    await page.locator('#session-list .record').filter({hasText: prefix + ' local title'}).getByRole('button', {name: '删除', exact: true}).click();
    await page.getByText('记录已删除。', {exact: true}).waitFor();
    assert.equal(deleteCount, 1); assert.equal((await api('/sessions/' + record.id)).status, 404);
    assert.deepEqual(errors, []);
    console.log('PASS lost delete response is verified without another deletion');
})().catch(error => { console.error('FAIL ' + error.message.replaceAll(credentials.apiKey, '[redacted]').replaceAll(credentials.password, '[redacted]')); process.exitCode = 1; }).finally(async () => { try { await cleanup(); } catch (error) { console.error('CLEANUP: ' + error.message); process.exitCode = 1; } finally { if (browser) await browser.close(); } });
