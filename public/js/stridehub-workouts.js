(() => {
    'use strict';
    const app = document.getElementById('zepp-workouts-app'); if (!app) return;
    const {node, svg, request, num, time, dateInZone, shiftDay, metric, status} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.base.replace(/\/$/, '');
    const state = {records: [], shown: 12, selected: null, zone: 'Asia/Shanghai', generation: 0, detailGeneration: 0, syncing: false, firstLoad: true};
    const pace = r => Number.isFinite(r.distanceMeters) && r.distanceMeters > 0 && Number.isFinite(r.durationSeconds) ? r.durationSeconds / (r.distanceMeters / 1000) : null;
    const paceLabel = value => Number.isFinite(value) ? `${Math.floor(Math.round(value) / 60)}′${String(Math.round(value) % 60).padStart(2, '0')}″` : '—';
    function render() {
        const rows = state.records, target = $('workouts-summary'); target.replaceChildren();
        metric(target, '已保存运动', num(rows.length), '次');
        const distances = rows.filter(r => Number.isFinite(r.distanceMeters)), durations = rows.filter(r => Number.isFinite(r.durationSeconds));
        metric(target, '有记录的距离', distances.length ? num(distances.reduce((n, r) => n + r.distanceMeters, 0) / 1000, 1) : '—', 'km');
        metric(target, '有记录的时长', durations.length ? num(durations.reduce((n, r) => n + r.durationSeconds, 0) / 3600, 1) : '—', '小时');
        $('workouts-list').replaceChildren();
        if (!rows.length) $('workouts-list').append(node('p', 'empty-note', '这段时间还没有已保存的 Zepp 运动。先将手表同步到 Zepp App，再同步到这里。'));
        for (const row of rows.slice(0, state.shown)) {
            const button = node('button', 'workout-list-item' + (state.selected === row.id ? ' selected' : '')); button.type = 'button'; button.dataset.workoutId = row.id; button.setAttribute('aria-pressed', String(state.selected === row.id));
            const name = node('div'); name.append(node('strong', '', row.sportName || '运动'), node('span', '', time(row.startedAt, state.zone)));
            const numbers = node('div'); numbers.append(node('strong', '', num(row.distanceMeters == null ? null : row.distanceMeters / 1000, 2) + ' km'), node('span', '', num(row.durationSeconds == null ? null : row.durationSeconds / 60) + ' 分钟'));
            button.append(name, numbers); button.addEventListener('click', () => detail(row.id)); $('workouts-list').append(button);
        }
        $('workouts-more').hidden = rows.length <= state.shown;
    }
    function lineChart(samples, key, label, unit, color) {
        const wrap = node('section', 'workout-chart'), heading = node('div', 'section-title'); heading.append(node('h3', '', label), node('span', 'subtle small', `${samples.length} 个采样 · ${unit}`)); wrap.append(heading);
        const valid = samples.filter(p => p.at && Number.isFinite(p[key]));
        if (!valid.length) { wrap.append(node('p', 'empty-note', '这次运动未提供该项传感器记录。')); return wrap; }
        const minimum = valid.reduce((n,p) => Math.min(n,p[key]), Infinity), maximum = valid.reduce((n,p) => Math.max(n,p[key]), -Infinity), margin = Math.max(1, (maximum - minimum) * .1), min = Math.max(0, minimum - margin), max = maximum + margin;
        const start = new Date(valid[0].at).getTime(), end = Math.max(start + 1000, new Date(valid.at(-1).at).getTime());
        const x = p => 44 + (new Date(p.at).getTime() - start) / (end - start) * 500, y = p => 110 - (p[key] - min) / (max - min) * 88;
        const chart = svg('svg', {viewBox: '0 0 560 145', role: 'img', 'aria-label': `${label}，${valid.length} 个有效采样，断档保持空白`});
        for (const v of [min, (min + max) / 2, max]) { const yy = 110 - (v - min) / (max - min) * 88; chart.append(svg('line', {x1: 44, x2: 545, y1: yy, y2: yy, class: 'chart-grid'}), svg('text', {x: 36, y: yy + 4, 'text-anchor': 'end'}, key === 'secondsPerKilometer' ? paceLabel(v) : num(v, max < 10 ? 1 : 0))); }
        const step = Math.max(1, Math.ceil(valid.length / 600)); let segment = [], previous = null;
        const flush = () => { if (segment.length) chart.append(svg('polyline', {points: segment.join(' '), fill: 'none', stroke: color, 'stroke-width': 1.5})); segment = []; };
        valid.forEach((point, i) => { const ts = new Date(point.at).getTime(); if (previous !== null && ts - previous > 90000) flush(); if (i % step === 0 || i === valid.length - 1) segment.push(`${x(point)},${y(point)}`); previous = ts; }); flush();
        chart.append(svg('text', {x: 44, y: 137}, '开始'), svg('text', {x: 544, y: 137, 'text-anchor': 'end'}, num((end - start) / 60000, 1) + ' 分钟')); wrap.append(chart); return wrap;
    }
    async function detail(id, updateUrl = true) {
        const generation = ++state.detailGeneration; state.selected = id; render(); const target = $('workout-detail'); target.replaceChildren(node('p', 'empty-note', '正在读取运动明细…'));
        if (updateUrl) { const url = new URL(location.href); url.searchParams.set('zeppWorkout', id); history.replaceState(null, '', url); }
        try {
            const row = await request(base + '/' + encodeURIComponent(id)); if (generation !== state.detailGeneration) return;
            target.replaceChildren(); target.append(node('span', 'source-pill', 'Zepp 原始记录'), node('h2', '', row.sportName || '运动'), node('p', 'subtle small', time(row.startedAt, state.zone)));
            const metrics = node('div', 'compact-metrics'); metric(metrics, '距离', num(row.distanceMeters == null ? null : row.distanceMeters / 1000, 2), 'km'); metric(metrics, '运动时长', num(row.durationSeconds == null ? null : row.durationSeconds / 60, 1), '分钟'); metric(metrics, '平均心率', num(row.averageHeartRateBpm), 'bpm'); metric(metrics, '平均配速', paceLabel(pace(row)), '/km'); metric(metrics, '平均步频', num(row.averageCadenceSpm), '步/分'); metric(metrics, '消耗热量', num(row.caloriesKcal), 'kcal'); target.append(metrics);
            const streams = row.streams || {};
            target.append(lineChart(streams.heartRate || [], 'bpm', '运动心率', 'bpm', '#db9187'), lineChart(streams.pace || [], 'secondsPerKilometer', '配速变化', '/km', '#6f89db'));
            const more = node('details', 'data-details'); more.append(node('summary', '', '步频、海拔与更多来源指标'), lineChart(streams.cadence || [], 'stepsPerMinute', '步频', '步/分', '#62b09d'), lineChart(streams.altitude || [], 'meters', '海拔', 'm', '#baa171'));
            const list = node('div', 'compact-metrics'); for (const [key, label, unit] of [['maxHeartRateBpm','最高心率','bpm'],['ascentMeters','累计爬升','m'],['trainingLoad','设备训练负荷',''],['aerobicTrainingEffect','有氧训练效果',''],['anaerobicTrainingEffect','无氧训练效果',''],['vo2Max','设备 VO₂max','']]) metric(list, label, num(row[key], 1), unit); more.append(list); target.append(more);
            target.append(node('p', 'subtle small', `来源明细${row.detailState === 'available' || row.detailState === 'complete' ? '已保存' : '状态：' + row.detailState} · ${row.streamCounts?.location || 0} 个轨迹点。未知值保持 —；原始明细可由 AI 按需读取。`));
        } catch (e) { if (generation !== state.detailGeneration) return; target.replaceChildren(node('p', 'empty-note', e.message)); const retry = node('button', 'hub-button', '重新读取明细'); retry.type = 'button'; retry.addEventListener('click', () => detail(id)); target.append(retry); }
    }
    async function load() {
        const generation = ++state.generation; $('workouts-refresh').disabled = true; status($('workouts-status'), '正在读取运动记录…'); const period = $('workouts-period').value, today = dateInZone(new Date(), state.zone), query = period === 'all' ? '' : `?from=${shiftDay(today, 1 - Number(period))}&to=${today}`;
        const [result, sync] = await Promise.allSettled([request(base + query), request(base + '/status')]); if (generation !== state.generation) return;
        if (result.status === 'fulfilled') { state.records = result.value.records; render(); status($('workouts-status'), ''); const initial = state.firstLoad ? new URLSearchParams(location.search).get('zeppWorkout') : null; state.firstLoad = false; const id = initial || (state.records.some(r => r.id === state.selected) ? state.selected : state.records[0]?.id); if (id) detail(id, true); else { ++state.detailGeneration; $('workout-detail').replaceChildren(node('p','empty-note','记录会在同步后自动出现在这里。')); } }
        else status($('workouts-status'), result.reason.message + ' 当前可能仍为上次读取的记录。', true);
        if (sync.status === 'fulfilled') { $('workouts-sync').disabled = !sync.value.configured || state.syncing || sync.value.state === 'syncing'; $('workouts-sync-status').textContent = [sync.value.state === 'syncing' ? '正在同步，当前展示历史记录' : '', sync.value.lastSuccessAt ? '最近同步 ' + time(sync.value.lastSuccessAt, state.zone) : '尚未同步', sync.value.lastError, sync.value.nextAction].filter(Boolean).join(' · '); }
        $('workouts-refresh').disabled = false;
    }
    $('workouts-more').addEventListener('click', () => { state.shown += 12; render(); }); $('workouts-refresh').addEventListener('click', load); $('workouts-period').addEventListener('change', () => { state.shown = 12; load(); });
    $('workouts-sync').addEventListener('click', async () => { if (state.syncing) return; state.syncing = true; $('workouts-sync').disabled = true; status($('workouts-status'), '正在从 Zepp 同步最近 90 天运动及明细…'); try { await request(base + '/sync', {method: 'POST', headers: {'Content-Type': 'application/json','X-CSRF-Token':app.dataset.csrf},body:JSON.stringify({days:90})}); await load(); } catch (e) { status($('workouts-status'),e.message + ' 已保存记录仍保留。',true); } finally { state.syncing=false; $('workouts-sync').disabled=false; } });
    (async()=>{try{state.zone=(await request(app.dataset.profile)).timezone;}catch{/* API failures are shown by the record requests. */}await load();})();
})();
