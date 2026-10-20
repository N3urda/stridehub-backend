/* Browser checks against an isolated real HTTP instance. All seeded records are synthetic. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8084', 'Only the dedicated redesign test instance is allowed');
const credentials = JSON.parse(fs.readFileSync('var/runtime/credentials.json'));
const output = path.resolve('var/runtime/redesign-e2e/screenshots'); fs.mkdirSync(output,{recursive:true});
const db = path.resolve('var/runtime/redesign-e2e/database/dreeve.db'); assert.ok(fs.existsSync(db));
let browser, passed=0; const report = text => { passed++; console.log('PASS ' + text); };
async function api(route, method='GET', body) { const r=await fetch(base+'/api/v1/'+route,{method,headers:{Authorization:'Bearer '+credentials.apiKey,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined}); return {status:r.status,data:await r.json()}; }
function seed(rows) { const r=spawnSync('python3',['-c',`import json,sqlite3,sys\np=sys.argv[1]\nassert p.endswith('/var/runtime/redesign-e2e/database/dreeve.db')\nc=sqlite3.connect(p)\nfor r in json.load(sys.stdin):\n c.execute('INSERT OR REPLACE INTO TrainingRecord(kind,id,payload,version,updatedAt,naturalKey) VALUES(?,?,?,?,?,NULL)', (r['kind'],r['id'],json.dumps(r['payload']),1,'2026-10-19T12:00:00Z'))\nc.commit()`,db],{input:JSON.stringify(rows),encoding:'utf8'}); assert.equal(r.status,0,r.stderr); }
(async()=>{
 const reset=spawnSync('python3',['-c',`import sqlite3,sys
p=sys.argv[1]
assert p.endswith('/var/runtime/redesign-e2e/database/dreeve.db')
c=sqlite3.connect(p)
c.execute("DELETE FROM TrainingRecord WHERE id LIKE 'DEMO-%'")
c.execute("DELETE FROM Setting WHERE name='weightHistory'")
c.commit()`,db],{encoding:'utf8'}); assert.equal(reset.status,0,reset.stderr);

 for(const route of ['health/zepp/days','health/plans','health/weight']) {const r=await fetch(base+'/api/v1/'+route); assert.equal(r.status,401); assert.match(r.headers.get('cache-control'),/no-store/);} report('new personal data endpoints require authentication and disable caching');
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE || undefined});
 const context=await browser.newContext({viewport:{width:1440,height:1040},timezoneId:'Asia/Shanghai'}); const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 await page.goto(base+'/'); await page.locator('[name=_username]').fill(credentials.username); await page.locator('[name=_password]').fill(credentials.password); await Promise.all([page.waitForURL(/\/admin\/insights/),page.locator('button[type=submit]').click()]);
 await page.locator('#overview-refresh:enabled').waitFor(); assert.equal(await page.locator('#overview-status').isVisible(),false, await page.locator('#overview-status').textContent()); assert.match(await page.locator('#latest-sleep-date').innerText(),/还没有睡眠/); assert.ok(!(await page.content()).includes(credentials.apiKey)); report('root and login land on unified private overview; empty records are explicit');
 const date=(await api('training/today')).data.date;
 const plan=await api('health/plans/DEMO-redesign','PUT',{version:0,title:'DEMO 稳定记录与作息',category:'sleep',goal:'DEMO 建立可持续的记录习惯',startDate:date,status:'active',source:'ai',sourceLabel:'DEMO AI',rationale:'DEMO 仅为界面验证，不是健康建议',items:[{id:'record',title:'记录晨起体重',date,status:'pending',notes:'DEMO'}]}); assert.equal(plan.status,200);
 const previousDate=new Date(date+'T12:00:00Z');previousDate.setUTCDate(previousDate.getUTCDate()-1);assert.equal((await api('training/sessions','POST',{id:'DEMO-timezone',title:'DEMO 跨时区晨跑',startAt:previousDate.toISOString().slice(0,10)+'T23:30:00Z',durationMinutes:30,type:'easy'})).status,201);
 const rows=[];for(let n=0;n<14;n++){const day=new Date(date+'T12:00:00Z');day.setUTCDate(day.getUTCDate()-n);const d=day.toISOString().slice(0,10); const sleep={date:d,sourceDate:d,source:'zepp',sourceLabel:'DEMO Zepp',timeZone:'+08:00',startedAt:d+'T00:10:00+08:00',endedAt:d+'T08:00:00+08:00',totalMinutes:420+n,deepMinutes:90,lightMinutes:260+n,remMinutes:70,awakeMinutes:10,score:null,restingHeartRate:null,wakeCount:null,lastSyncedAt:date+'T12:00:00+08:00'};rows.push({kind:'sleep',id:'DEMO-sleep-'+d,payload:sleep});}
 seed(rows);await page.locator('#overview-refresh').click();await page.locator('#overview-refresh:enabled').waitFor(); assert.match(await page.locator('#latest-sleep-duration').innerText(),/7/);assert.equal(await page.locator('#sleep-stage-bar .stage-piece').count(),4);assert.match(await page.locator('#overview-plans').innerText(),/DEMO 跨时区晨跑/);assert.equal(await page.locator('#overview-sleep-chart rect').count(),30);assert.match(await page.locator('#data-coverage').innerText(),/睡眠 · 14 天/);report('actual API records drive sleep composition, history and AI plan preview');
 await page.screenshot({path:path.join(output,'overview-desktop.png'),fullPage:true});
 for(const width of [390,320]){await page.setViewportSize({width,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
 await page.screenshot({path:path.join(output,'overview-mobile.png'),fullPage:true});
 await page.getByRole('button',{name:'7 天',exact:true}).click();await page.locator('#overview-refresh:enabled').waitFor();assert.equal(await page.locator('#overview-sleep-chart rect').count(),7);report('date period changes records and charts; overview fits 320px and 390px');
 await page.goto(base+'/admin/plans');await page.locator('[data-plan-id="DEMO-redesign"] .item-check').waitFor();await page.getByRole('button',{name:'标记完成：记录晨起体重'}).click();await page.locator('#plans-status').getByText('已保存并核验。').waitFor();assert.equal((await api('health/plans/DEMO-redesign')).data.items[0].status,'completed');
 let current=(await api('health/plans/DEMO-redesign')).data;await api('health/plans/DEMO-redesign','PUT',{version:current.version,notes:'DEMO concurrent edit'});await page.getByRole('button',{name:'撤销完成：记录晨起体重'}).click();await page.locator('#plans-status.error').waitFor();assert.equal((await api('health/plans/DEMO-redesign')).data.items[0].status,'completed');await page.locator('#plans-refresh').click();await page.locator('#plans-refresh:enabled').waitFor();assert.equal(await page.getByRole('button',{name:'撤销完成：记录晨起体重'}).isEnabled(),true);report('AI plan item saves/read-back and real version conflict never overwrites concurrent edit');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(output,'plans-mobile.png'),fullPage:true});
 await page.goto(base+'/admin/health');await page.locator('#weight-form button[type=submit]:enabled').waitFor();await page.locator('#weight-form [name=weightKg]').fill('72.5');await page.locator('#weight-form [name=notes]').fill('DEMO <img src=x onerror=alert(1)>');await page.locator('#weight-form button[type=submit]').click();await page.locator('#weight-status').getByText('已保存并核验。').waitFor();assert.equal((await api('health/weight')).data.records[0].weightKg,72.5);assert.equal(await page.locator('#body-weight img').count(),0);
 await page.locator('#weight-form [name=weightKg]').fill('72.2');await page.locator('#health-refresh').click();await page.locator('#health-refresh:enabled').waitFor();assert.equal(await page.locator('#weight-form [name=weightKg]').inputValue(),'72.2');let weights=(await api('health/weight')).data;await api('health/weight/'+date,'PUT',{version:weights.version,weightKg:72.8});await page.locator('#weight-form button[type=submit]').click();await page.locator('#weight-review:not([hidden])').waitFor();assert.equal((await api('health/weight')).data.records[0].weightKg,72.8);await page.locator('#weight-review').click();await page.locator('#weight-status').getByText(/72.8/).waitFor();assert.equal(await page.locator('#weight-form [name=weightKg]').inputValue(),'72.2');report('weight shares persisted history, preserves draft across refresh and requires reread on conflict');
 await page.locator('#weight-form button[type=submit]').click();await page.locator('#weight-status').getByText('已保存并核验。').waitFor();
 let releaseOld, oldReady;const ready=new Promise(r=>oldReady=r),hold=new Promise(r=>releaseOld=r);let held=false;
 await page.route('**/admin/health/api/weight',async route=>{if(!held && route.request().method()==='GET'){held=true;const response=await route.fetch();oldReady();await hold;await route.fulfill({response});}else await route.continue();});
 await page.locator('#weight-form [name=weightKg]').fill('72.4');await page.locator('#health-refresh').click();await ready;
 let releaseSave,saveReady;const saving=new Promise(r=>saveReady=r),saveHold=new Promise(r=>releaseSave=r);
 await page.route('**/admin/health/api/weight/*',async route=>{if(route.request().method()==='PUT'){const response=await route.fetch();saveReady();await saveHold;await route.fulfill({response});}else await route.continue();});
 await page.locator('#weight-form button[type=submit]').click();await saving;assert.equal(await page.locator('#weight-form [name=weightKg]').isDisabled(),true);releaseSave();await page.locator('#weight-status').getByText('已保存并核验。').waitFor();releaseOld();await page.waitForTimeout(150);assert.match(await page.locator('#weight-metrics').innerText(),/72.4/);assert.equal((await api('health/weight')).data.records[0].weightKg,72.4);await page.unroute('**/admin/health/api/weight');await page.unroute('**/admin/health/api/weight/*');report('slow save locks draft fields and a late old refresh cannot roll back the verified new weight');
 for(const width of [320,390,1440]){await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
 assert.equal(await page.locator('#body-sleep-visual .stage-piece').count(),4);await page.screenshot({path:path.join(output,'body-desktop.png'),fullPage:true});
 // Failed refresh must be visible while preserving last known content.
 await page.goto(base+'/admin/insights');await page.locator('#overview-refresh:enabled').waitFor();await page.route('**/admin/health/api/sleep?*',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({message:'DEMO unavailable'})}));await page.locator('#overview-refresh').click();await page.locator('#overview-status.error').waitFor();assert.match(await page.locator('#latest-sleep-date').innerText(),/读取失败/);await page.unroute('**/admin/health/api/sleep?*');report('partial data failure is visible and never presented as zero sleep');
 assert.deepEqual(errors,[]); report('all browser paths complete without uncaught errors'); console.log(`${passed} checks passed; screenshots: ${output}`);
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{
 const reset=spawnSync('python3',['-c',`import sqlite3,sys
p=sys.argv[1]
assert p.endswith('/var/runtime/redesign-e2e/database/dreeve.db')
c=sqlite3.connect(p)
c.execute("DELETE FROM TrainingRecord WHERE id LIKE 'DEMO-%'")
c.execute("DELETE FROM Setting WHERE name='weightHistory'")
c.commit()`,db],{encoding:'utf8'}); assert.equal(reset.status,0,reset.stderr);
if(browser)await browser.close();});
