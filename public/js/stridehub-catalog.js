(() => {
    'use strict';
    const app = document.getElementById('workout-catalog'); if (!app) return;
    const {node, request, num, metric, status, dateInZone, shiftDay} = window.StrideHubData;
    const {chart, table} = window.StrideHubWorkspaceUI, $ = id => document.getElementById(id), form = $('catalog-range');
    let busy = false, generation = 0, zone = 'Asia/Shanghai';
    async function mutate(id, method, body, version) {
        if (busy) return;
        busy = true; ++generation;
        app.querySelectorAll('button').forEach(b => b.disabled = true);
        try {
            await request(app.dataset.base + '/workout-links/' + encodeURIComponent(id) + (method === 'DELETE' ? '?version=' + version : ''), {method, headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: body ? JSON.stringify(body) : undefined});
            await load(true);
        } catch (e) { status($('catalog-status'), e.message + ' 请刷新统计后核对，未自动重试。', true); }
        finally { busy = false; app.querySelectorAll('button').forEach(b => b.disabled = false); }
    }
    async function load(afterWrite = false) {
        if (busy && !afterWrite) return;
        const current = ++generation; status($('catalog-status'), '正在读取…');
        try {
            if (!form.elements.to.value) {
                const profile = await request(app.dataset.base.replace('/health/api', '/training/api') + '/profile');
                if (current !== generation) return;
                zone = profile.timezone; form.elements.to.value = dateInZone(new Date(), zone); form.elements.from.value = shiftDay(form.elements.to.value, -89);
            }
            const data = await request(app.dataset.base + '/workouts?' + new URLSearchParams({from: form.elements.from.value, to: form.elements.to.value}));
            if (current !== generation) return;
            const s = data.summary; $('catalog-metrics').replaceChildren();
            metric($('catalog-metrics'), '运动次数', num(s.count), '次');
            metric($('catalog-metrics'), '总距离', num(s.distanceKm, 1), 'km');
            metric($('catalog-metrics'), '运动时长', num(s.movingSeconds == null ? null : s.movingSeconds / 3600, 1), '小时');
            metric($('catalog-metrics'), '平均心率', num(s.averageHeartRate, 1), 'bpm', '按有心率的运动时长加权');
            const charts=window.StrideHubCharts;
            charts.bars($('catalog-chart'), data.weeks, {series:[{key:'distanceKm',label:'周运动距离',color:'#178b91'}],unit:'km',label:'每周运动距离'});
            const days=new Map(), types=new Map();
            for (const row of data.records) { const date=dateInZone(new Date(row.startAt),zone); days.set(date,!Number.isFinite(row.durationMinutes)||(days.has(date)&&days.get(date)===null)?null:(days.get(date)||0)+row.durationMinutes); const type=row.sportType || '其他'; types.set(type,!Number.isFinite(row.durationMinutes)||(types.has(type)&&types.get(type)===null)?null:(types.get(type)||0)+row.durationMinutes); }
            const labels={Run:'跑步',TrailRun:'越野跑',VirtualRun:'室内跑步',Walk:'步行',Ride:'骑行',Swim:'游泳',Workout:'训练'};
            charts.ring($('catalog-types'),data.pendingDuplicates ? [] : [...types].map(([label,value])=>({label:labels[label]||label,value})),{unit:'分钟',label:'运动类型时间构成'});
            charts.heatmap($('catalog-calendar'),data.pendingDuplicates ? [] : window.StrideHubData.calendar(form.elements.from.value,form.elements.to.value,[...days].map(([date,value])=>({date,value})),'value',r=>r.value),{key:'value',unit:'分钟',label:'每日运动时长',onSelect:()=>{$('catalog-records').open=true;}});

            table($('catalog-table'), ['开始时间', '运动 / 来源', '距离 km', '时长 min', '心率 bpm', '记录与来源核对'], data.records.map(r => {
                const controls = node('div');
                for (const source of r.sources) {
                    const link = node('a', 'text-link', source.source === 'zepp' ? 'Zepp 详情' : '导入详情');
                    link.href = '?' + (source.source === 'zepp' ? 'zeppWorkout=' : 'activityId=') + encodeURIComponent(source.id); controls.append(link, document.createTextNode(' '));
                }
                r.duplicateCandidates.forEach(id => {
                    const candidate = data.records.find(row => row.id === id), button = node('button', '', `与 ${candidate?.name || id} 合并来源`);
                    button.type = 'button'; button.addEventListener('click', () => {
                        if (confirm(`请核对：\n${r.name} ${r.startAt} ${num(r.distanceKm, 2)} km\n${candidate?.name || id} ${candidate?.startAt || ''} ${num(candidate?.distanceKm, 2)} km\n确定是同一次运动？`)) mutate(r.id, 'PUT', {activityId: id, version: 0});
                    }); controls.append(button);
                });
                if (r.sourceLink) { const b = node('button', '', '拆分来源'); b.type = 'button'; b.addEventListener('click', () => mutate(r.sourceLink.id, 'DELETE', null, r.sourceLink.version)); controls.append(b); }
                return [r.startAt.replace('T', ' '), r.name + ' · ' + r.sources.map(s => s.source === 'zepp' ? 'Zepp' : '导入').join(' + '), num(r.distanceKm, 2), num(r.durationMinutes, 1), num(r.averageHeartRate, 1), controls];
            }));
            status($('catalog-status'), data.pendingDuplicates ? `${data.pendingDuplicates} 条记录可能与导入运动重复，请在明细中核对来源。` : '');
        } catch (e) { if (current === generation) status($('catalog-status'), e.message + ' 当前内容未刷新。', true); }
    }
    form.addEventListener('submit', e => { e.preventDefault(); load(); });
    load();
})();
