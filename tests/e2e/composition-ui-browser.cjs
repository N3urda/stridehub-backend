/* Browser-only regression: synthetic read-model responses; no server, account or database writes. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../..');
const output = path.join(root, 'var/runtime/composition-ui-browser'); fs.mkdirSync(output, {recursive: true});
const metrics = [
    {key: 'WEIGHT', label: '体重', value: 75.25, unit: 'kg', digits: 2, kind: 'weight_record'},
    {key: 'BODY_FAT', label: '体脂率', value: 23.1, unit: '%', digits: 1, kind: 'estimate', grade: '偏胖', highlight: true, reference: 'DEMO 参考', score: 88},
    {key: 'BONE', label: '骨量', value: 3.4, unit: 'kg', digits: 1, kind: 'estimate', grade: '偏高', highlight: false, note: '公式估算，不能判断骨密度或骨病。'},
    {key: 'BODY_AGE', label: '身体年龄', value: 40, unit: '岁', digits: 0, kind: 'estimate', grade: '偏大', highlight: false},
    {key: 'WATER', label: '水分', value: null, unit: '%', digits: 2, kind: 'estimate'},
    {key: 'DEMO_UNKNOWN', label: 'DEMO_UNKNOWN', value: 0, unit: '', digits: 2, kind: 'estimate'},
];
function row(id, date, weight = 75.25) { return {id, measurementId: id, projectionStatus: 'daily_projection', capture: {measuredAt: date + 'T00:00:00Z', localDate: date, weightKg: weight, profile: {heightCm: 170, male: true}, derived: {values: {DEMO_UNKNOWN: 0}, source: '<img src=x onerror="window.injected=true">'}}, composition: {sourceLabel: 'DEMO 体脂秤', algorithmVersion: 'DEMO-only', capturedHeightCm: 170, weightKg: weight, bodyFatPercent: 23.1, capturedBmi: 26.04, reportBmi: null, metrics}}; }
const a = row('demo-a', '2026-10-30'), b = row('demo-b', '2026-10-31', 75.55), c = row('demo-c', '2026-11-01', 75.65);
let rows = [a], total = 1, failure = false, putCount = 0, browser;
const height = {version: 0, status: 'capture', source: null, candidates: [{findingFingerprint: 'demo-fingerprint', reportId: 'demo-report', findingIndex: 0, heightCm: 171.5, reportDate: '2026-09-01', reportTitle: 'DEMO report', sourceLabel: 'DEMO', needsReview: false}]};
const html = `<html lang="zh-CN"><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><section id="body-composition" data-base="/admin/health/api" data-csrf="DEMO"><button id="composition-refresh">刷新</button><p id="composition-status"></p><select id="composition-capture"></select><button id="composition-more" hidden>更多</button><div id="composition-content"></div><form id="composition-height-form"><select id="composition-height-choice"></select><button type="submit">保存</button></form><p id="composition-height-source"></p><p id="composition-height-status"></p></section></body></html>`;
(async () => {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
    const page = await browser.newPage({viewport: {width: 1280, height: 1000}}), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('http://composition.test/**', async route => {
        const url = new URL(route.request().url());
        if (url.pathname === '/') return route.fulfill({contentType: 'text/html', body: html});
        if (url.pathname.endsWith('/height-selection')) {
            if (route.request().method() === 'PUT') { putCount++; return route.fulfill({status: 503, contentType: 'application/json', body: '{"message":"DEMO uncertain save"}'}); }
            return route.fulfill({contentType: 'application/json', body: JSON.stringify(height)});
        }
        if (failure) return route.fulfill({status: 503, contentType: 'application/json', body: '{"message":"DEMO read failed"}'});
        const selected = url.searchParams.get('selected');
        return route.fulfill({contentType: 'application/json', body: JSON.stringify({from: url.searchParams.get('from'), to: url.searchParams.get('to'), records: rows, total, nextOffset: null, snapshot: 'demo', selectedRecord: selected === a.id ? a : null, heightSelection: height})});
    });
    await page.goto('http://composition.test/');
    await page.addStyleTag({path: path.join(root, 'public/css/stridehub-composition.css')});
    await page.addScriptTag({path: path.join(root, 'public/js/stridehub-data.js')});
    await page.addScriptTag({path: path.join(root, 'public/js/stridehub-composition.js')});
    const range = async (from = '2026-10-01', to = '2026-11-01') => page.evaluate(({from, to}) => document.dispatchEvent(new CustomEvent('body-composition-range', {detail: {from, to, timezone: 'Asia/Shanghai'}})), {from, to});
    const ready = () => page.locator('#composition-capture:enabled').waitFor();
    await range(); await ready();
    assert.match(await page.locator('.composition-summary').innerText(), /75.25 kg/);
    assert.equal(await page.locator('.composition-metric').count(), 6);
    assert.equal(await page.locator('[data-metric="BONE"]').evaluate(el => el.classList.contains('reference-outside')), false);
    assert.match(await page.locator('[data-metric="WATER"] .composition-value').innerText(), /—/);
    assert.equal(await page.locator('[data-metric="DEMO_UNKNOWN"] .composition-value').innerText(), '0');
    assert.equal(await page.locator('#composition-content img').count(), 0);
    assert.equal(await page.evaluate(() => window.injected), undefined);
    assert.equal(await page.locator('#composition-content pre').count(), 0);
    console.log('PASS complete cards, exact weight, missing/zero values, neutral bone, Chinese source details and XSS safety');

    rows = [b, a]; total = 2;
    await page.locator('#composition-refresh').click(); await ready();
    assert.equal(await page.locator('#composition-capture').inputValue(), b.id);
    assert.match(await page.locator('.composition-summary').innerText(), /75.55 kg/);
    await page.locator('#composition-capture').selectOption(a.id);
    rows = [c, b]; total = 3; // The chosen older record is now beyond page one.
    await page.locator('#composition-refresh').click(); await ready();
    assert.equal(await page.locator('#composition-capture').inputValue(), a.id);
    assert.match(await page.locator('#composition-status').innerText(), /手动选择的历史/);
    await range('2026-10-02'); await ready();
    assert.equal(await page.locator('#composition-capture').inputValue(), c.id);
    assert.match(await page.locator('.composition-summary').innerText(), /75.65 kg/);
    console.log('PASS default follows new capture; explicit historical choice survives refresh beyond first page; changed range resets to latest');

    failure = true; await range('2026-09-01');
    await page.locator('#composition-status.composition-error').waitFor();
    assert.match(await page.locator('#composition-status').innerText(), /仍显示 2026-10-02 至 2026-11-01/);
    failure = false; await page.locator('#composition-refresh').click(); await ready();
    await page.locator('#composition-height-choice').selectOption('demo-fingerprint');
    await page.locator('#composition-height-form button').click();
    await page.locator('#composition-height-status.composition-error').waitFor();
    assert.equal(putCount, 1);
    assert.equal(await page.locator('#composition-height-form button').isDisabled(), true);
    await page.locator('#composition-refresh').click(); await ready();
    assert.equal(await page.locator('#composition-height-form button').isDisabled(), false);
    console.log('PASS failed range read identifies retained range; uncertain write cannot be repeated before a fresh read');
    await page.setViewportSize({width: 320, height: 900});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.screenshot({path: path.join(output, 'mobile.png'), fullPage: true});
    assert.deepEqual(errors, []);
    console.log('PASS 320px layout and browser runtime');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => { await browser?.close(); });
