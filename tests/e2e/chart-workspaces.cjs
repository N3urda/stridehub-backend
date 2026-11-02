// Read-only acceptance on the disposable chart redesign preview, never production.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve('var/runtime/release-20261103');
const creds=JSON.parse(fs.readFileSync(path.join(root,'preview-credentials.private.json')));
assert.equal(creds.baseUrl,'http://127.0.0.1:18093');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000},hasTouch:true}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(creds.baseUrl+'/admin/insights');await page.locator('[name=_username]').fill(creds.username);await page.locator('[name=_password]').fill(creds.password);await page.locator('button[type=submit]').click();await page.waitForLoadState('networkidle');
 for(const width of [1440,390,320]){
  await page.setViewportSize({width,height:900});
  for(const route of ['insights','health','running','weight','nutrition','plans']){
   await page.goto(creds.baseUrl+'/admin/'+route);await page.waitForLoadState('networkidle');
   const charts=page.locator('.viz-plot');assert.ok(await charts.count()>=2,route+' has data charts');
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,route+' no overflow at '+width);
   const first=charts.first();await first.scrollIntoViewIfNeeded();await first.focus();await first.press('Home');await first.press('End');
   const readout=page.locator('.chart-readout').first();assert.ok((await readout.innerText()).trim().length>5);
   if(width===390){await first.tap();await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:path.join(root,'mobile-'+route+'.png'),fullPage:true});}
  }
 }
 await page.goto(creds.baseUrl+'/admin/insights');await page.waitForLoadState('networkidle');await page.locator('[data-days="7"]').click();await page.locator('#overview-refresh:enabled').waitFor();assert.ok(await page.locator('#overview-sleep-chart rect').count()<=28);assert.match(await page.locator('#overview-range').innerText(),/至/);
 await page.setViewportSize({width:1440,height:1000});await page.locator('[data-days="30"]').click();await page.locator('#overview-refresh:enabled').waitFor();await page.screenshot({path:path.join(root,'desktop-overview.png'),fullPage:true});
 assert.deepEqual(errors,[]);console.log('PASS 6 chart workspaces at 1440/390/320px, touch, keyboard, date ranges and no JS errors');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
