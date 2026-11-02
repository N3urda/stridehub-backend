// Real Chromium interaction with synthetic chart values. No server or personal records.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
    try {
        const page = await browser.newPage({viewport: {width: 390, height: 844}, hasTouch: true});
        const errors = []; page.on('pageerror', e => errors.push(e.message));
        await page.setContent('<main style="width:350px"><div id="bars"></div><div id="trend"></div><div id="heat"></div><div id="ring"></div><div id="targets"></div></main>');
        await page.addStyleTag({path: path.resolve('public/css/stridehub-charts.css')});
        await page.addScriptTag({path: path.resolve('public/js/stridehub-charts.js')});
        await page.evaluate(() => {
            const c=window.StrideHubCharts, $=id=>document.getElementById(id);
            const rows=[{date:'2026-11-01',value:0},{date:'2026-11-02',value:null},{date:'2026-11-03',value:12}];
            c.bars($('bars'),rows,{series:[{key:'value',label:'步数'}],unit:'步',onSelect:r=>window.selected=r.date});
            c.heatmap($('heat'),rows,{key:'value',unit:'步',label:'步数'});
            c.trend($('trend'),[{date:'2026-01-01',value:80},{date:'2026-01-02',value:79},{date:'2026-02-01',value:78}],{series:[{key:'value',label:'体重'}],unit:'kg'});
            c.ring($('ring'),[{label:'跑步',value:30},{label:'骑行',value:null}],{unit:'分钟'});
            c.comparison($('targets'),[{label:'摄入',value:0,target:2000,unit:'kcal'},{label:'蛋白质',value:null,target:100,unit:'g'}]);
            window.sleep=c.sleepRows([{date:'2026-11-01',totalMinutes:450,deepMinutes:100,lightMinutes:300,remMinutes:null},{date:'2026-11-02',totalMinutes:400,deepMinutes:200,lightMinutes:300}], '2026-11-01','2026-11-03');
        });
        assert.equal(await page.locator('#bars rect').count(), 2, 'missing day is not a zero bar');
        const plot=page.locator('#bars .chart-interaction'); await plot.focus(); await plot.press('Home');
        assert.match(await page.locator('#bars .chart-readout').innerText(),/0 步/);
        await plot.press('ArrowRight'); assert.match(await page.locator('#bars .chart-readout').innerText(),/未记录/);
        await plot.press('End'); await plot.press('Enter'); assert.equal(await page.evaluate(()=>window.selected),'2026-11-03');
        const fills=await page.locator('#heat rect').evaluateAll(ns=>ns.map(n=>n.getAttribute('fill')));assert.equal(new Set(fills).size,3,'zero, missing and positive have different colors');
        const xs=await page.locator('#trend circle').evaluateAll(ns=>ns.map(n=>+n.getAttribute('cx'))); assert.ok(xs[2]-xs[1]>(xs[1]-xs[0])*20,'date spacing is elapsed time');
        assert.equal(await page.locator('#trend polyline').count(),1,'line stops at a long observation gap');
        assert.match(await page.locator('#ring').innerText(),/缺失项目未纳入合计/);
        assert.equal(await page.locator('#targets .viz-fill').count(),1,'unknown actual has no invented progress');
        const sleep=await page.evaluate(()=>window.sleep); assert.equal(sleep[0].remMinutes,null);assert.equal(sleep[0].unknownMinutes,50/60);assert.equal(sleep[1].deepMinutes,null);assert.equal(sleep[1].unknownMinutes,400/60);assert.equal(sleep[2].unknownMinutes,null);
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
        assert.deepEqual(errors,[]);console.log('PASS chart arithmetic, sparse dates, zero/unknown distinction, keyboard selection, 390px layout');
    } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
