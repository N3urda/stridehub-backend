/* UI contract test with synthetic responses only. Does not connect to a personal instance. */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../..');
const session = {id: 'test-today', version: 1, title: '轻松跑 · 合成测试', startAt: '2026-09-28T06:30:00+08:00', durationMinutes: 40, distanceKm: 6, type: 'easy', status: 'planned', activityIds: [], feedback: {pain: true, fuel: {carbsGrams: 20, fluidMl: 300, giComfort: 'good', notes: 'existing'}}};
const second = {...session, id: 'test-second', title: '另一节合成训练'};
const runs = [{id: 'activity-a', name: '前半段', startAt: session.startAt, distanceKm: 3, durationMinutes: 20, averageHeartRate: null, matchReasons: ['同一天'], startOffsetMinutes: 0}, {id: 'activity-b', name: '后半段', startAt: '2026-09-28T07:00:00+08:00', distanceKm: 3, durationMinutes: 20, averageHeartRate: 140, matchReasons: ['同一天'], startOffsetMinutes: 30}];
let checkIn = null;
let conflict = false;
let uncertainFeedback = false;
let todayGate = null;
let comparisonGate = null;
let emptyToday = false;
const writes = [];
const comparison = row => ({planned: row, actual: row.activityIds.length ? {distanceKm: 6, durationMinutes: 40} : null, actualActivities: runs.filter(run => row.activityIds.includes(run.id)), candidates: runs.filter(run => !row.activityIds.includes(run.id)), delta: null, suggestion: null, dataQuality: []});
const today = () => ({date: '2026-09-28', timezone: 'Asia/Shanghai', generatedAt: '2026-09-28T10:00:00+08:00', sessions: [session, second], checkIn, health: {version: 0, constraints: []}, pendingFeedback: [], reconciliation: {items: [comparison(session), comparison(second)]}, dataQuality: ['没有心率的数据保持未知。']});
(async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    try {
        const page = await browser.newPage({viewport: {width: 390, height: 844}, timezoneId: 'America/Los_Angeles'});
        const errors = []; page.on('pageerror', error => errors.push(error.message));
        await page.route('http://stridehub.test/**', async route => {
            const request = route.request(); const url = new URL(request.url()); const routePath = url.pathname;
            const json = (body, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(body)});
            if (routePath.startsWith('/admin/training/api/')) {
                const endpoint = routePath.slice('/admin/training/api'.length);
                if (request.method() !== 'GET') {
                    const body = request.postDataJSON(); writes.push({endpoint, body});
                    assert.equal(request.headers()['x-csrf-token'], 'synthetic-csrf');
                    if (endpoint.endsWith('/link')) {
                        if (conflict) { conflict = false; session.version++; return json({message: 'Synthetic conflict'}, 409); }
                        if (body.version !== session.version) return json({message: 'Synthetic stale link version'}, 409);
                        session.activityIds = body.activityIds; session.status = body.activityIds.length ? 'completed' : session.status; session.version++; return json(session);
                    }
                    if (endpoint.endsWith('/feedback')) {
                        if (body.version !== session.version) return json({message: 'Synthetic feedback conflict'}, 409);
                        session.feedback = {...session.feedback, ...body.feedback}; session.version++;
                        if (uncertainFeedback === 'server') { uncertainFeedback = false; return json({message: 'Synthetic error after commit'}, 503); }
                        if (uncertainFeedback) { uncertainFeedback = false; return route.abort('connectionreset'); }
                        return json(session);
                    }
                    if (endpoint === '/check-ins') { checkIn = {...body, version: 1}; return json(checkIn, 201); }
                    if (endpoint.startsWith('/check-ins/')) {
                        if (body.version !== checkIn.version) return json({message: 'Synthetic check-in conflict'}, 409);
                        checkIn = {...checkIn, ...body, version: checkIn.version + 1}; return json(checkIn);
                    }
                }
                if (endpoint === '/today') { const snapshot = structuredClone(today()); if (emptyToday) { snapshot.sessions = []; snapshot.pendingFeedback = []; snapshot.reconciliation.items = []; } if (todayGate) { const gate = todayGate; todayGate = null; gate.started(); await gate.wait; } return json(snapshot); }
                if (endpoint.startsWith('/briefing')) return json({session, forecast: {status: 'unavailable', message: '天气暂不可用'}, advice: {}, generatedAt: today().generatedAt});
                if (endpoint === '/check-ins') return json({items: checkIn ? [checkIn] : []});
                if (endpoint.startsWith('/check-ins/')) return json(checkIn);
                const target = endpoint.includes(second.id) ? second : session;
                if (endpoint.endsWith('/comparison')) { const snapshot = structuredClone(comparison(target)); if (comparisonGate) { const gate = comparisonGate; comparisonGate = null; gate.started(); await gate.wait; } return json(snapshot); }
                if (endpoint.startsWith('/sessions/')) return json(target);
                return json({message: 'Unhandled synthetic endpoint: ' + endpoint}, 404);
            }
            if (routePath.endsWith('.js') || routePath.endsWith('.css')) return route.fulfill({contentType: routePath.endsWith('.js') ? 'text/javascript' : 'text/css', body: fs.readFileSync(path.join(root, 'public', routePath), 'utf8')});
            const template = path.join(root, 'templates/html/admin/page/training-today.html.twig');
            const html = fs.existsSync(template) ? fs.readFileSync(template, 'utf8').replace(/\{\{.*?\}\}/g, expression => expression.includes('csrf_token') ? 'synthetic-csrf' : '/' + (expression.match(/'([^']+)'/)?.[1] || '').replace(/^\//, '')) : '<main>Today page is not implemented.</main>';
            return route.fulfill({contentType: 'text/html', body: html});
        });
        await page.goto('http://stridehub.test/admin/training/today');
        await page.locator('#today-checkin-form [name=sleepHours]').waitFor({timeout: 1500});
        assert.equal(await page.locator('#today-checkin-form [name=fatigue]').inputValue(), '');
        assert.equal(await page.locator('#today-checkin-form [name=pain]').inputValue(), '');
        await page.locator('#today-checkin-form [name=sleepHours]').fill('7.5');
        await page.locator('#today-feedback-form [name=notes]').fill('保留这份未保存的草稿');
        await page.locator('#today-refresh').click();
        await page.locator('[data-session-id="test-second"]').first().click();
        await page.locator('[data-session-id="test-today"]').first().click();
        assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), '保留这份未保存的草稿');
        assert.equal(await page.locator('#today-checkin-form [name=sleepHours]').inputValue(), '7.5');
        assert.equal(await page.locator('[data-activity-id]:checked').count(), 0);
        assert.ok((await page.locator('#today-reconciliation').innerText()).includes('比计划晚 30 分钟'));
        await page.locator('#today-checkin-form button[type=submit]').click();
        await page.waitForFunction(() => document.querySelector('#today-checkin-status').textContent.includes('已保存'));
        const savedCheckIn = writes.find(row => row.endpoint === '/check-ins').body;
        assert.equal(savedCheckIn.fatigue, null); assert.equal(savedCheckIn.soreness, null); assert.equal(savedCheckIn.pain, null);
        await page.locator('#today-checkin-form [name=fatigue]').selectOption('2');
        checkIn.version++;
        await page.locator('#today-checkin-form button[type=submit]').click();
        await page.locator('[data-action=review-checkin]').click();
        await page.locator('[data-action=accept-checkin]').waitFor();
        const checkinReview = await page.locator('#today-checkin-status').innerText();
        assert.ok(checkinReview.includes('昨晚睡眠：7.5 小时'));
        assert.ok(checkinReview.includes('疲劳程度：未记录'));
        assert.ok(!checkinReview.includes('sleepHours'));
        await page.locator('[data-action=accept-checkin]').click();
        await page.locator('#today-checkin-form button[type=submit]').click();
        await page.waitForFunction(() => document.querySelector('#today-checkin-status').textContent.includes('已保存'));
        await page.locator('[data-activity-id="activity-a"]').check();
        await page.locator('[data-activity-id="activity-b"]').check();
        await page.locator('[data-action=review-links]').click();
        conflict = true;
        await page.locator('[data-action=confirm-links]').click();
        await page.waitForFunction(() => document.querySelector('#today-reconciliation').textContent.includes('冲突'));
        assert.equal(await page.locator('[data-activity-id]:checked').count(), 2);
        assert.equal(await page.locator('[data-action=review-links]').isDisabled(), true);
        await page.locator('[data-action=refresh-comparison]').click();
        await page.locator('[data-action=review-links]').click();
        await page.locator('[data-action=confirm-links]').click();
        await page.waitForFunction(() => document.querySelector('#today-reconciliation').textContent.includes('已关联 2 条'));
        assert.deepEqual(writes.filter(row => row.endpoint.endsWith('/link')).at(-1).body.activityIds, ['activity-a', 'activity-b']);
        await page.locator('#today-feedback-form button[type=submit]').click();
        await page.waitForFunction(() => document.querySelector('#today-feedback-status').textContent.includes('冲突'));
        assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), '保留这份未保存的草稿');
        await page.locator('[data-action=review-feedback]').click();
        await page.locator('[data-action=accept-feedback]').waitFor();
        const feedbackReview = await page.locator('#today-feedback-status').innerText();
        assert.ok(feedbackReview.includes('跑后疼痛或不适：有'));
        assert.ok(feedbackReview.includes('饮水：300 毫升'));
        assert.ok(!feedbackReview.includes('fluidMl') && !feedbackReview.includes('"pain"') && !feedbackReview.includes('"fuel"'));
        await page.locator('[data-action=accept-feedback]').click();
        uncertainFeedback = true;
        await page.locator('#today-feedback-form button[type=submit]').click();
        await page.waitForFunction(() => document.querySelector('#today-feedback-status').textContent.includes('已保存'));
        assert.deepEqual(writes.filter(row => row.endpoint.endsWith('/feedback')).at(-1).body.feedback, {notes: '保留这份未保存的草稿'});
        assert.equal(session.feedback.pain, true); assert.equal(session.feedback.fuel.fluidMl, 300);
        assert.equal(writes.filter(row => row.endpoint.endsWith('/feedback')).length, 2, 'Uncertain writes must be read back, not blindly retried');
        await page.locator('#today-feedback-form [name=rpe]').fill('5');
        uncertainFeedback = 'server';
        await page.locator('#today-feedback-form button[type=submit]').click();
        await page.waitForFunction(() => document.querySelector('#today-feedback-status').textContent.includes('已保存') && document.querySelector('#today-feedback-state').textContent.includes('v5'));
        assert.equal(session.feedback.rpe, 5);
        assert.equal(writes.filter(row => row.endpoint.endsWith('/feedback')).length, 3, 'HTTP 503 after commit must be read back, not retried');
        // A verified feedback save must immediately advance the same session's link version,
        // even if the subsequent Today refresh is still in flight and the user acts quickly.
        session.version = 1; session.activityIds = []; session.status = 'planned'; session.feedback = null;
        await page.goto('http://stridehub.test/admin/training/today');
        await page.locator('[data-activity-id="activity-a"]').waitFor();
        let releaseToday; let todayStarted;
        const todayHeld = new Promise(resolve => { todayStarted = resolve; });
        todayGate = {started: todayStarted, wait: new Promise(resolve => { releaseToday = resolve; })};
        await page.locator('#today-feedback-form [name=notes]').fill('快速操作测试');
        await page.locator('#today-feedback-form button[type=submit]').click();
        await todayHeld;
        await page.waitForFunction(() => document.querySelector('#today-feedback-status').textContent.includes('已保存'));
        await page.locator('[data-activity-id="activity-a"]').check();
        await page.locator('[data-activity-id="activity-b"]').check();
        await page.locator('[data-action=review-links]').click();
        await page.locator('[data-action=confirm-links]').click();
        const quickLink = writes.filter(row => row.endpoint.endsWith('/link')).at(-1);
        releaseToday();
        assert.equal(quickLink.body.version, 2, 'Immediate link after verified feedback must use version 2, not stale version 1');
        await page.waitForFunction(() => document.querySelector('#today-reconciliation').textContent.includes('已关联 2 条'));
        // An older comparison finishing after a newer one must not roll back the record cache.
        session.version = 10; session.activityIds = []; session.status = 'planned'; session.feedback = null;
        await page.goto('http://stridehub.test/admin/training/today');
        await page.locator('[data-activity-id="activity-a"]').waitFor();
        let releaseComparison; let comparisonStarted;
        const comparisonHeld = new Promise(resolve => { comparisonStarted = resolve; });
        comparisonGate = {started: comparisonStarted, wait: new Promise(resolve => { releaseComparison = resolve; })};
        await page.locator('[data-action=refresh-comparison]').click(); await comparisonHeld;
        session.version = 11;
        await page.locator('[data-action=refresh-comparison]').click();
        await page.waitForFunction(() => document.querySelector('#today-feedback-state').textContent.includes('v11'));
        const staleResponse = page.waitForResponse(response => response.url().endsWith('/comparison') && response.request().method() === 'GET');
        releaseComparison(); await staleResponse;
        await page.locator('#today-feedback-form [name=notes]').fill('较新的版本不能被迟到响应覆盖');
        await page.locator('#today-feedback-form button[type=submit]').click();
        assert.equal(writes.filter(row => row.endpoint.endsWith('/feedback')).at(-1).body.version, 11, 'Late old comparison must not roll back the saved record version');
        await page.waitForFunction(() => document.querySelector('#today-feedback-status').textContent.includes('已保存'));
        await page.locator('#today-feedback-form [name=notes]').fill('课次离开列表后仍应保留的草稿');
        emptyToday = true; await page.locator('#today-refresh').click();
        await page.waitForFunction(() => document.querySelector('#today-feedback-form').hidden);
        assert.equal(await page.locator('#today-feedback-session').inputValue(), '', 'Removed sessions must not remain valid choices after refresh');
        assert.ok((await page.locator('#today-status').innerText()).includes('已不在今日或近期列表'));
        emptyToday = false; await page.locator('#today-refresh').click();
        await page.locator('#today-feedback-form [name=notes]').waitFor();
        assert.equal(await page.locator('#today-feedback-form [name=notes]').inputValue(), '课次离开列表后仍应保留的草稿');
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, '390 px page must not overflow');
        assert.deepEqual(errors, []);
        if (process.env.TODAY_UI_SCREENSHOT) await page.screenshot({path: process.env.TODAY_UI_SCREENSHOT, fullPage: true});
        console.log('PASS Today synthetic contract: unknown values, preserved drafts, explicit split links, conflict reread, uncertain write readback, immediate feedback-to-link version sync, stale-response rejection, refreshed session removal, 390 px overflow.');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
