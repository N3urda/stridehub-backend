const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require('playwright');
(async () => {
 const browser = await chromium.launch({headless: true, executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try {
  const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
  const types = ['steps','calories','heart_rate','resting_heart_rate','spo2','stress','weight','abnormal_heart_beat','workouts'];
  let syncs = 0;
  await page.route('http://mi.test/**', route => {
   const url = new URL(route.request().url()); let data;
   if (url.pathname.endsWith('/sync')) { syncs++; data = {state:'success'}; }
   else if (url.pathname.endsWith('/status')) data = {configured:true,state:'success',recordCount:7,nextAction:'已同步'};
   else if (url.pathname.endsWith('/sources')) data = {records:[]};
   else if (url.pathname.endsWith('/data')) data = {counts:Object.fromEntries(types.map(t=>[t,t==='steps'?2:0])),daily:[{date:'2026-11-06',steps:20,distanceMeters:10,stepCalories:null,activityCalories:0}],methodology:'来源独立',status:{sources:Object.fromEntries(types.map(t=>[t,{state:t==='steps'?'success':'no_data'}]))}};
   else if (url.pathname.endsWith('/records')) {
    const steps = url.searchParams.get('type') === 'steps', offset = Number(url.searchParams.get('offset'));
    data = {records:steps?[{recordedAt:'2026-11-06T10:00:00+08:00',type:'steps',sourceDeviceKey:'a'.repeat(64),values:{steps:20,note:'<img src=x onerror=alert(1)>'}}]:[],total:steps?2:0,nextOffset:steps && !offset?1:null};
   } else return route.fulfill({contentType:'text/html',body:'<html></html>'});
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://mi.test/');
  await page.setContent(`<main id="health-app" data-api-base="http://mi.test/api" data-csrf="fixture"><button id="mi-fitness-sync"></button><button id="mi-fitness-refresh"></button><p id="mi-fitness-status"></p><div id="sleep-source-records"></div><button id="health-refresh"></button><select id="mi-data-type">${types.map(t=>`<option value="${t}">${t}</option>`).join('')}</select><button id="mi-data-more" hidden></button><p id="mi-data-record-status"></p><div id="mi-data-records"></div><div id="mi-data-summary"></div><div id="mi-data-daily"></div></main>`);
  await page.addScriptTag({content:fs.readFileSync('public/js/stridehub-mi-fitness.js','utf8')});
  await page.evaluate(() => document.getElementById('health-app').dispatchEvent(new CustomEvent('health-records-refreshed',{detail:{from:'2026-11-01',to:'2026-11-06'}})));
  await page.getByText('共 2 条，已展示 1 条；展开查看全部字段。').waitFor();
  assert.equal(await page.locator('#mi-data-summary p').count(),9);
  await page.locator('#mi-data-records summary').click();
  assert.match(await page.locator('#mi-data-records pre').textContent(),/onerror/);
  assert.equal(await page.locator('#mi-data-records img').count(),0);
  await page.locator('#mi-data-more').click();
  await page.getByText('共 2 条，已展示 2 条；展开查看全部字段。').waitFor();
  assert.equal(await page.locator('#mi-data-more').isHidden(),true);
  await page.locator('#mi-data-type').selectOption('heart_rate');
  await page.getByText('这段时间暂无此类记录。佩戴手环并同步手机 App 后再刷新。').waitFor();
  assert.equal(await page.locator('#mi-data-records details').count(),0);
  await page.locator('#mi-fitness-sync').click();
  await page.waitForFunction(() => !document.getElementById('mi-fitness-sync').disabled);
  assert.equal(syncs,1); assert.deepEqual(errors,[]);
  console.log('PASS: type switching, pagination, missing data, literal source fields and sync action');
 } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });
