/* Real browser regression against a migrated empty database: no athlete profile or activity fixtures. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_FIRST_USE_URL;
assert.equal(base, 'http://localhost:8085', 'Only the dedicated empty first-use instance is allowed');
const credentials = JSON.parse(fs.readFileSync('var/runtime/credentials.json'));
const db = path.resolve('var/runtime/firstuse-e2e/database/dreeve.db');
const output = path.resolve('var/runtime/firstuse-e2e/screenshots');
fs.mkdirSync(output, {recursive: true});
let browser;
function databaseCheck() {
    const result = spawnSync('python3', ['-c', `import sqlite3,sys
p=sys.argv[1]
assert p.endswith('/var/runtime/firstuse-e2e/database/dreeve.db')
c=sqlite3.connect(p)
assert c.execute('SELECT count(*) FROM Activity').fetchone()[0]==0
assert c.execute("SELECT count(*) FROM Setting WHERE settingsGroup='general' AND name IN ('birthday','firstName','lastName')").fetchone()[0]==0
print('No athlete profile or activities were created.')`, db], {encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
}
(async () => {
    databaseCheck();
    for (const endpoint of ['health/weight', 'health/plans', 'running/overview', 'training/profile']) {
        assert.equal((await fetch(base + '/api/v1/' + endpoint)).status, 401);
    }
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}, timezoneId: 'Asia/Shanghai'});
    const errors = [], failedApis = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('response', response => { if (response.url().includes('/api/') && response.status() >= 400) failedApis.push(response.status() + ' ' + new URL(response.url()).pathname); });
    await page.goto(base + '/');
    assert.match(page.url(), /\/admin\/login$/);
    await page.locator('[name=_username]').fill(credentials.username);
    await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(/\/admin\/insights$/), page.locator('button[type=submit]').click()]);
    await page.locator('#overview-refresh:enabled').waitFor();
    assert.match(await page.locator('#latest-sleep-date').innerText(), /还没有睡眠/);
    await page.screenshot({path: path.join(output, 'empty-overview.png'), fullPage: true});
    for (const route of ['insights', 'health', 'plans', 'running', 'training', 'training/today', 'upload']) {
        const response = await page.goto(base + '/admin/' + route);
        assert.equal(response.status(), 200, route);
        assert.equal(new URL(page.url()).pathname, '/admin/' + route, route + ' must not require legacy setup');
        assert.equal(await page.locator('.hub-primary-nav a').count(), 4);
        await page.waitForTimeout(300);
    }
    await page.locator('#upload-form').waitFor();
    await page.screenshot({path: path.join(output, 'empty-upload.png'), fullPage: true});
    await page.goto(base + '/admin/health');
    await page.locator('#weight-form button[type=submit]:enabled').waitFor();
    await page.locator('#weight-form [name=weightKg]').fill('72.4');
    await page.locator('#weight-form [name=notes]').fill('DEMO first-use measurement');
    await page.locator('#weight-form button[type=submit]').click();
    await page.locator('#weight-status').getByText('已保存并核验。').waitFor();
    for (const width of [390, 320]) {
        await page.setViewportSize({width, height: 844});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    await page.screenshot({path: path.join(output, 'first-measurement-mobile.png'), fullPage: true});
    assert.deepEqual(errors, []);
    assert.deepEqual(failedApis, []);
    databaseCheck();
    console.log('PASS: empty database root/login and 7 workspace pages including upload; authenticated empty APIs; first measured weight saves without birthday/name/activity; 320px layout; zero page errors or failed API reads.');
})().catch(error => {
    let message = String(error.stack || error);
    for (const value of [credentials.username, credentials.password, credentials.apiKey].filter(Boolean)) message = message.split(value).join('[redacted]');
    console.error(message);
    process.exitCode = 1;
}).finally(async () => {
    if (browser) await browser.close();
    const cleanup = spawnSync('python3', ['-c', `import sqlite3,sys
p=sys.argv[1]
assert p.endswith('/var/runtime/firstuse-e2e/database/dreeve.db')
c=sqlite3.connect(p)
c.execute("DELETE FROM Setting WHERE settingsGroup='general' AND name='weightHistory'")
c.commit()`, db], {encoding: 'utf8'});
    assert.equal(cleanup.status, 0, cleanup.stderr);
});
