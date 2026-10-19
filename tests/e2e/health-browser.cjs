/* Real HTTP/mobile browser acceptance against a dedicated disposable database.
 * Zepp records below are synthetic; live-provider validation is separate. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8082');
const root = process.cwd();
const db = path.join(root, 'var/runtime/health-e2e/database/dreeve.db');
assert.ok(fs.existsSync(db));
const credentials = JSON.parse(fs.readFileSync('var/runtime/credentials.json'));
const shots = path.join(root, 'var/runtime/health-e2e/screenshots'); fs.mkdirSync(shots, {recursive:true});
let browser, passed = 0;
const report = text => { console.log('PASS ' + text); passed++; };
async function api(route, method='GET', body) {
    const response = await fetch(base + '/api/v1/' + route, {method, headers:{Authorization:'Bearer ' + credentials.apiKey, ...(body === undefined ? {} : {'Content-Type':'application/json'})}, body:body === undefined ? undefined : JSON.stringify(body)});
    return {status:response.status, data:response.status === 204 ? null : await response.json()};
}
function seedSleep(records) {
    const result = spawnSync('python3', ['-c', `import json,sqlite3,sys\np=sys.argv[1]\nassert p.endswith('/var/runtime/health-e2e/database/dreeve.db')\nc=sqlite3.connect(p)\nfor r in json.load(sys.stdin):\n i=r.pop('id'); c.execute('INSERT OR REPLACE INTO TrainingRecord(kind,id,payload,version,updatedAt,naturalKey) VALUES(?,?,?,?,?,NULL)', ('sleep',i,json.dumps(r),1,'2026-10-19T08:00:00Z'))\nc.commit()`, db], {input:JSON.stringify(records),encoding:'utf8'});
    assert.equal(result.status,0,'Fixture seed failed');
}
(async () => {
    const initial = await api('health/blood-pressure'); assert.equal(initial.status,200); assert.equal(initial.data.records.length,0,'Use a fresh disposable database');
    for (const route of ['health/blood-pressure','health/sleep','health/sleep/status']) {
        const response = await fetch(base+'/api/v1/'+route); assert.equal(response.status,401); assert.match(response.headers.get('cache-control'),/no-store/);
    }
    report('health history and sync status require authentication and never cache');
    browser = await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE || undefined});
    const context = await browser.newContext({viewport:{width:390,height:844},timezoneId:'America/Los_Angeles'});
    const page = await context.newPage(); const errors=[];
    page.on('pageerror',e=>errors.push(e.message)); page.on('dialog',d=>d.accept());
    await page.goto(base+'/admin/health');
    await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password);
    await Promise.all([page.waitForURL(url=>!url.pathname.includes('/login')),page.locator('button[type=submit]').click()]);
    await page.goto(base+'/admin/health');
    await page.locator('#pressure-chart').getByText(/还没有血压记录/).waitFor(); await page.locator('#sleep-status').getByText(/尚未连接/).waitFor();
    assert.equal(await page.locator('#sleep-sync').isDisabled(),true);
    assert.ok(!(await page.content()).includes(credentials.apiKey));
    for (const width of [390,320]) { await page.setViewportSize({width,height:844}); assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false); }
    const csrfStatus = await page.evaluate(async()=> (await fetch('/admin/health/api/blood-pressure/DEMO-csrf',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:0})})).status); assert.equal(csrfStatus,403);
    report('mobile empty state, first-use access, 320px fit, CSRF and no API key in browser');
    await page.locator('#pressure-new').click();
    await page.locator('[name=systolic]').fill('123'); await page.locator('[name=diastolic]').fill('79'); await page.locator('[name=arm]').selectOption('left');
    await page.locator('[name=notes]').fill('DEMO <img src=x onerror=alert(1)>');
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-form-status').getByText('已保存并核验。',{exact:true}).waitFor();
    let records=(await api('health/blood-pressure')).data.records; assert.equal(records.length,1); const firstId=records[0].id;
    assert.equal(records[0].pulse,null); assert.equal(records[0].systolic,123); assert.equal(records[0].arm,'left'); assert.equal(await page.locator('#pressure-records img').count(),0);
    report('real browser measurement saves exact offset instant, optional unknown pulse and escaped notes');
    await page.locator('#pressure-new').click(); await page.locator('[name=systolic]').fill('121'); await page.locator('[name=diastolic]').fill('78'); await page.locator('[name=pulse]').fill('62');
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-form-status').getByText('已保存并核验。',{exact:true}).waitFor();
    assert.equal((await api('health/blood-pressure')).data.records.length,2);
    await page.locator(`[data-record-id="${firstId}"]`).getByText('修改',{exact:true}).click();
    await page.locator('[name=systolic]').fill('125'); await page.locator('#health-refresh').click(); await page.locator('#health-refresh:enabled').waitFor(); assert.equal(await page.locator('[name=systolic]').inputValue(),'125');
    let current=(await api('health/blood-pressure/'+firstId)).data;
    assert.equal((await api('health/blood-pressure/'+firstId,'PUT',{version:current.version,notes:'DEMO parallel edit'})).status,200);
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-review:not([hidden])').waitFor();
    assert.equal(await page.locator('[name=systolic]').inputValue(),'125'); assert.equal(await page.locator('#pressure-form [type=submit]').isDisabled(),true);
    assert.equal((await api('health/blood-pressure/'+firstId)).data.systolic,123);
    await page.locator('#pressure-review').click(); await page.locator('#pressure-form-status').getByText(/DEMO parallel edit/).waitFor();
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-form-status').getByText('已保存并核验。',{exact:true}).waitFor(); assert.equal((await api('health/blood-pressure/'+firstId)).data.systolic,125);
    report('multiple readings per day, refresh preserves draft, real concurrent change requires review before overwrite');
    // One synthetic transport failure after a real server write, to validate uncertain-save recovery.
    let dropped=false;
    await page.route('**/admin/health/api/blood-pressure/*',async route=>{ if(!dropped && route.request().method()==='PUT'){dropped=true; await route.fetch(); await route.abort('failed');} else await route.continue(); });
    await page.locator('#pressure-new').click(); await page.locator('[name=systolic]').fill('119'); await page.locator('[name=diastolic]').fill('77');
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-review:not([hidden])').waitFor();
    assert.equal((await api('health/blood-pressure')).data.records.length,3);
    await page.locator('#pressure-review').click(); await page.locator('#pressure-form-status').getByText(/服务器保存的内容与当前草稿一致/).waitFor();
    assert.equal((await api('health/blood-pressure')).data.records.length,3); await page.unroute('**/admin/health/api/blood-pressure/*');
    report('lost write response recovers by read-back without duplicate measurements (injected transport fault)');
    await page.locator(`[data-record-id="${firstId}"]`).getByText('修改',{exact:true}).click();
    await page.locator('[name=notes]').fill('DEMO draft kept across re-login');
    await context.clearCookies();
    const login=await context.newPage(); await login.goto(base+'/admin/login');
    await login.locator('[name=_username]').fill(credentials.username); await login.locator('[name=_password]').fill(credentials.password);
    await Promise.all([login.waitForURL(url=>!url.pathname.includes('/login')),login.locator('button[type=submit]').click()]); await login.close();
    await page.locator('#pressure-form [type=submit]').click(); await page.locator('#pressure-form-status').getByText('已保存并核验。',{exact:true}).waitFor();
    assert.equal((await api('health/blood-pressure/'+firstId)).data.notes,'DEMO draft kept across re-login');
    report('new login session refreshes CSRF without reloading or losing the existing draft');
    const today=(await api('training/today')).data.date;
    const wake = new Date(Date.now()-3600*1000).toISOString().replace(/\.\d{3}Z$/,'Z'); const start = new Date(Date.now()-9*3600*1000).toISOString().replace(/\.\d{3}Z$/,'Z');
    seedSleep([{id:'DEMO-sleep',date:today,sourceDate:today,source:'zepp',sourceLabel:'Zepp / Amazfit',timeZone:'+00:00',startedAt:start,endedAt:wake,totalMinutes:460,deepMinutes:90,lightMinutes:300,remMinutes:70,awakeMinutes:20,score:null,restingHeartRate:null,wakeCount:null,lastSyncedAt:wake}]);
    const check=await api('training/check-ins','POST',{id:'DEMO-health-checkin',date:today,sleepHours:5.5,pain:null}); assert.equal(check.status,201);
    await page.locator('#health-refresh').click(); await page.locator('#sleep-records').getByText(/7 小时 40 分/).waitFor();
    assert.match(await page.locator('#sleep-records').innerText(),/睡眠评分 未知/); assert.equal(await page.locator('#sleep-chart rect').count(),1);
    await page.screenshot({path:path.join(shots,'health-mobile.png'),fullPage:true});
    const before=(await api('training/check-ins/DEMO-health-checkin')).data;
    const overview=(await api('training/today')).data; assert.equal(overview.observations.sleep.totalMinutes,460); assert.equal(overview.checkIn.sleepHours,5.5);
    assert.deepEqual((await api('training/check-ins/DEMO-health-checkin')).data,before);
    await page.goto(base+'/admin/training/today'); await page.locator('#today-observations').getByText(/Zepp 睡眠 7 小时 40 分/).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false, 'Today navigation must fit 320px');
    await page.getByRole('link',{name:'健康记录',exact:true}).click(); await page.locator('#pressure-chart svg').waitFor();
    report('synthetic sleep stages and unknown fields render; Today uses observations without replacing manual sleep');
    await page.setViewportSize({width:1440,height:1000}); assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:path.join(shots,'health-desktop.png'),fullPage:true});
    await page.locator(`[data-record-id="${firstId}"]`).getByText('删除',{exact:true}).click(); await page.locator('#pressure-status').getByText('记录已删除。',{exact:true}).waitFor(); assert.equal((await api('health/blood-pressure/'+firstId)).status,404);
    assert.deepEqual(errors,[]); report('confirmed delete persists, desktop layout fits and page has no JavaScript errors');
    console.log(`Completed ${passed} health acceptance groups.`);
})().catch(error=>{ console.error(error.message);process.exitCode=1; }).finally(async()=>{if(browser)await browser.close();});
