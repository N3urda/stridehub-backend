const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
 const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try {
  const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
  const types = ['steps','calories','heart_rate','resting_heart_rate','spo2','stress','weight','abnormal_heart_beat','workouts'];
  let syncs = 0, starts = 0, configured = false, connectorAvailable = true;
  let pairing = {state: 'idle', attemptId: null};
  let holdNextStatus = false, releaseStatus = null, statusHeld = null;
  const writes = [];
  const routeFixture = async route => {
   const url = new URL(route.request().url()); let data;
   if (route.request().method() === 'POST') writes.push({path: url.pathname, csrf: route.request().headers()['x-csrf-token'], body: route.request().postDataJSON()});
   if (url.pathname.endsWith('/pair/status')) data = pairing;
   else if (url.pathname.endsWith('/pair/start')) {
    starts++;
    pairing = connectorAvailable ? {state: 'pending', attemptId: `attempt-${starts}`, expiresAt: new Date(Date.now() + 300000).toISOString()} : {state: 'unavailable', message: '连接服务尚未就绪。'};
    data = pairing;
   }
   else if (url.pathname.endsWith('/pair/cancel')) { pairing = {state: 'cancelled', attemptId: pairing.attemptId}; data = pairing; }
   else if (url.pathname.endsWith('/pair/qr')) return route.fulfill({contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><rect width="240" height="240" fill="white"/><path d="M20 20h60v60H20zM160 20h60v60h-60zM20 160h60v60H20z" stroke="#18242a" stroke-width="10" fill="none"/><text x="120" y="130" font-size="20" text-anchor="middle">演示二维码</text></svg>'});
   else if (url.pathname.endsWith('/sync')) { syncs++; data = {state:'success'}; }
   else if (url.pathname.endsWith('/status')) {
    data = {configured,state:configured?'success':'not_configured',recordCount:configured?7:0,nextAction:configured?'已同步':'点击“连接小米运动健康”扫码绑定，绑定后可同步数据。'};
    if (holdNextStatus) { holdNextStatus = false; await new Promise(resolve => { releaseStatus = resolve; statusHeld(); }); }
   }
   else if (url.pathname.endsWith('/sources')) data = {records:[]};
   else if (url.pathname.endsWith('/data')) data = {counts:Object.fromEntries(types.map(t=>[t,t==='steps'?2:0])),daily:[{date:'2026-11-06',steps:20,distanceMeters:10,stepCalories:null,activityCalories:0}],methodology:'来源独立',status:{sources:Object.fromEntries(types.map(t=>[t,{state:t==='steps'?'success':'no_data'}]))}};
   else if (url.pathname.endsWith('/records')) {
    const steps = url.searchParams.get('type') === 'steps', offset = Number(url.searchParams.get('offset'));
    data = {records:steps?[{recordedAt:'2026-11-06T10:00:00+08:00',type:'steps',sourceDeviceKey:'a'.repeat(64),values:{steps:20,note:'<img src=x onerror=alert(1)>'}}]:[],total:steps?2:0,nextOffset:steps && !offset?1:null};
   } else return route.fulfill({contentType:'text/html',body:'<html></html>'});
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  };
  await page.route('http://mi.test/**', routeFixture);
  await page.goto('http://mi.test/');
  const bindingMarkup = fs.readFileSync('templates/html/admin/page/health.html.twig', 'utf8').match(/<section id="mi-fitness-pair"[\s\S]*?<\/section>/)[0];
  const markup = `<body class="stridehub-app"><div class="sh-page-content"><main id="health-app" class="health-shell" data-api-base="http://mi.test/api" data-csrf="fixture"><div class="health-heading"><div><h1>身体与恢复</h1><p class="muted">界面测试 · 演示数据</p></div><button class="button" id="health-refresh">刷新记录</button></div><section class="health-panel sleep-panel"><h2>睡眠 · 多来源互补</h2><p class="muted">小米与 Zepp 按记录完整度互补，同一段睡眠只统计一次。</p><div class="form-actions"><button class="button primary" id="mi-fitness-connect" aria-expanded="false">连接小米运动健康</button><button class="button" id="mi-fitness-sync" disabled>同步小米数据（7 天）</button><button class="button" id="mi-fitness-refresh">刷新小米状态</button></div><p id="mi-fitness-status"></p>${bindingMarkup}<div id="sleep-source-records"></div></section><select id="mi-data-type">${types.map(t=>`<option value="${t}">${t}</option>`).join('')}</select><button id="mi-data-more" hidden></button><p id="mi-data-record-status"></p><div id="mi-data-records"></div><div id="mi-data-summary"></div><div id="mi-data-daily"></div></main></div></body>`;
  await page.setContent(markup);
  await page.addScriptTag({content:fs.readFileSync('public/js/stridehub-mi-fitness.js','utf8')});
  await page.getByText(/小米运动健康：尚未连接/).waitFor();
  assert.equal(await page.locator('#mi-fitness-sync').isDisabled(), true);
  assert.equal(await page.locator('#mi-fitness-pair').isHidden(), true);
  await page.locator('#mi-fitness-connect').click();
  await page.locator('#mi-fitness-pair-qr').waitFor({state: 'visible'});
  assert.equal(await page.locator('#mi-fitness-pair-qr').getAttribute('src'), 'http://mi.test/api/mi-fitness/pair/qr?attemptId=attempt-1');
  assert.equal(await page.locator('#mi-fitness-connect').getAttribute('aria-expanded'), 'true');
  assert.equal(await page.locator('#mi-fitness-connect').isDisabled(), true);
  assert.equal(syncs, 0);
  pairing = {...pairing, state: 'expired'};
  await page.locator('#mi-fitness-refresh').click();
  await page.getByText('二维码已过期，请重新获取。').waitFor();
  assert.equal(await page.locator('#mi-fitness-pair-qr').getAttribute('src'), null);
  await page.locator('#mi-fitness-pair-retry').click();
  await page.locator('#mi-fitness-pair-qr').waitFor({state: 'visible'});
  assert.match(await page.locator('#mi-fitness-pair-qr').getAttribute('src'), /attempt-2$/);
  await page.locator('#mi-fitness-pair-cancel').click();
  await page.getByText('已取消本次绑定。').waitFor();
  assert.equal(await page.locator('#mi-fitness-pair-qr').getAttribute('src'), null);
  assert.deepEqual(writes.find(write => write.path.endsWith('/pair/cancel')).body, {attemptId: 'attempt-2'});
  await page.locator('#mi-fitness-pair-retry').click();
  await page.locator('#mi-fitness-pair-qr').waitFor({state: 'visible'});
  configured = true; pairing = {...pairing, state: 'success'};
  // Verify the polling loop observes phone confirmation without another click.
  await page.getByText(/绑定成功。先在小米运动健康 App 上传数据/).waitFor();
  await page.waitForFunction(() => !document.getElementById('mi-fitness-sync').disabled);
  assert.equal(await page.locator('#mi-fitness-pair-qr').getAttribute('src'), null);
  assert.equal(syncs, 0, 'binding must not start an unlabeled automatic sync');
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
  assert.equal(syncs,1);
  connectorAvailable = false;
  await page.locator('#mi-fitness-connect').click();
  await page.getByText('连接服务尚未就绪。').waitFor();
  assert.equal(await page.locator('#mi-fitness-pair-qr').isHidden(), true);
  assert.equal(await page.locator('#mi-fitness-pair-retry').isVisible(), true);
  assert.ok(writes.every(write => write.csrf === 'fixture'));
  assert.deepEqual(writes.filter(write => write.path.endsWith('/pair/start')).map(write => write.body), [{}, {}, {}, {}]);

  // An older status request must not disable sync after a newer connected status.
  configured = false; holdNextStatus = true;
  const held = new Promise(resolve => { statusHeld = resolve; });
  await page.locator('#mi-fitness-refresh').click(); await held;
  configured = true;
  await page.locator('#mi-fitness-refresh').click();
  await page.waitForFunction(() => !document.getElementById('mi-fitness-sync').disabled);
  const oldResponse = page.waitForResponse(response => response.url().endsWith('/mi-fitness/status'));
  releaseStatus(); await (await oldResponse).finished();
  assert.equal(await page.locator('#mi-fitness-sync').isDisabled(), false);

  // Opening the page again resumes an existing attempt without creating a new QR.
  configured = false; pairing = {state: 'pending', attemptId: 'resumed-attempt', expiresAt: new Date(Date.now() + 300000).toISOString()};
  const resumed = await browser.newPage({viewport: {width: 1280, height: 980}});
  resumed.on('pageerror', e => errors.push(e.message));
  await resumed.route('http://mi.test/**', routeFixture);
  await resumed.goto('http://mi.test/'); await resumed.setContent(markup);
  for (const css of ['stridehub-training.css', 'stridehub-health.css', 'stridehub-insights.css', 'stridehub-shell.css']) await resumed.addStyleTag({content: fs.readFileSync('public/css/' + css, 'utf8')});
  await resumed.addScriptTag({content: fs.readFileSync('public/js/stridehub-mi-fitness.js', 'utf8')});
  await resumed.locator('#mi-fitness-pair-qr').waitFor({state: 'visible'});
  assert.equal(starts, 4);
  assert.match(await resumed.locator('#mi-fitness-pair-qr').getAttribute('src'), /resumed-attempt$/);
  assert.equal(await resumed.locator('#mi-fitness-sync').isDisabled(), true);
  if (process.env.MI_FITNESS_SCREENSHOT_DIR) {
   fs.mkdirSync(process.env.MI_FITNESS_SCREENSHOT_DIR, {recursive: true});
   await resumed.screenshot({path: process.env.MI_FITNESS_SCREENSHOT_DIR + '/mi-fitness-pair-desktop.png', fullPage: true});
   await resumed.setViewportSize({width: 390, height: 1200});
   await resumed.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
   assert.equal(await resumed.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
   await resumed.screenshot({path: process.env.MI_FITNESS_SCREENSHOT_DIR + '/mi-fitness-pair-mobile.png'});
  }
  configured = true; pairing = {...pairing, state: 'success'};
  await resumed.getByText(/绑定成功。先在小米运动健康 App 上传数据/).waitFor();
  await resumed.waitForFunction(() => !document.getElementById('mi-fitness-sync').disabled);
  await resumed.close();
  assert.deepEqual(errors,[]);
  console.log('PASS: QR binding, expiry/retry, cancellation, polling success, pending resume, stale status, connector unavailable, CSRF, manual sync, type switching, pagination and literal source fields');
 } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });
