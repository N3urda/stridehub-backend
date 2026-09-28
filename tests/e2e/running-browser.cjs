/* Run only against the isolated seed-running.php fixture DB on :8082. No mocked routes. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const credentials = JSON.parse(fs.readFileSync(process.env.TRAINING_CREDENTIALS_FILE || 'var/runtime/credentials.json'));
const base = process.env.RUNNING_BASE_URL || 'http://localhost:8082';
(async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE});
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}}); page.setDefaultTimeout(30000);
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    try {
        assert.equal((await fetch(base + '/api/v1/running/overview')).status, 401);
        const response = await fetch(base + '/api/v1/running/overview?from=all', {headers: {Authorization: 'Bearer ' + credentials.apiKey}});
        const data = await response.json(); assert.equal(response.status, 200); assert.equal(data.summary.count, 561); assert.equal(data.activities.items.length, 50);
        await page.goto(base + '/admin/running'); await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
        await Promise.all([page.waitForURL(url => !url.pathname.includes('/login')), page.locator('button[type=submit]').click()]);
        await page.goto(base + '/admin/running?activityId=activity-demo-0'); await page.locator('#detail-content:not([hidden])').waitFor(); await page.locator('#overview:not([hidden])').waitFor();
        assert.ok(!(await page.content()).includes(credentials.apiKey));
        await page.locator('#filters [name=preset]').selectOption('all'); await page.getByRole('button', {name: '更新看板'}).click(); await page.locator('#status').filter({hasText: '561 次跑步'}).waitFor();
        assert.equal(await page.locator('#activities tr').count(), 50); assert.equal(await page.locator('#split-table tr').count(), 24);
        assert.ok(await page.locator('canvas').count() >= 9); assert.equal(await page.locator('#bests button').count(), 4);
        await page.getByRole('button', {name: '下一页'}).click(); await page.locator('#page-label').filter({hasText: '第 2 / 12 页'}).waitFor();
        await page.locator('#filters [name=sportType]').selectOption('TrailRun'); await page.getByRole('button', {name: '更新看板'}).click(); await page.locator('#status').filter({hasText: '63 次跑步'}).waitFor();
        await page.locator('#timeline-axis').selectOption('distanceKm');
        const chartResult = await page.evaluate(() => { const c = echarts.getInstanceByDom(document.getElementById('timeline-chart')); c.dispatchAction({type: 'dataZoom', start: 20, end: 70}); return c.getOption().dataZoom[0].start; }); assert.equal(chartResult, 20);
        fs.mkdirSync('/tmp/stridehub-running', {recursive: true}); await page.screenshot({path: '/tmp/stridehub-running/desktop.png', fullPage: true});
        await page.setViewportSize({width: 390, height: 844}); await page.screenshot({path: '/tmp/stridehub-running/mobile.png', fullPage: true});
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        await page.goto(base + '/admin/running?activityId=activity-demo-missing'); await page.locator('#detail-content:not([hidden])').waitFor();
        await page.locator('#timeline-empty:not([hidden])').waitFor(); assert.equal(await page.locator('#detail-title img').count(), 0); assert.ok((await page.locator('#detail-stats').innerText()).includes('未记录'));
        await page.screenshot({path: '/tmp/stridehub-running/missing.png', fullPage: true});
        await page.setViewportSize({width: 1440, height: 1000});
        await page.goto(base + '/activities'); await page.getByRole('link', {name: '跑步分析 ↗', exact: true}).waitFor(); await page.getByRole('link', {name: '跑步分析 ↗', exact: true}).click(); await page.locator('#running-app').waitFor();
        await page.goto(base + '/activities/activity-demo-0'); await page.getByRole('link', {name: '跑步深度复盘：分段、心率与长期趋势 ↗'}).waitFor(); await page.getByRole('link', {name: '跑步深度复盘：分段、心率与长期趋势 ↗'}).click(); await page.locator('#detail-content:not([hidden])').waitFor();
        assert.deepEqual(errors, []);
        console.log('PASS real HTTP + browser: 561-run totals, >500 pagination, sport filters, best efforts, 24 splits, linked charts/zoom, missing sensors, XSS-safe text, 390px layout, native records/detail links.');
    } catch (e) { fs.mkdirSync('/tmp/stridehub-running', {recursive: true}); await page.screenshot({path: '/tmp/stridehub-running/failure.png', fullPage: true}); console.error('FAIL', e.message); process.exitCode = 1; }
    finally { await browser.close(); }
})();
