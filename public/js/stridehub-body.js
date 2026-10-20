(() => {
    'use strict';
    const section = document.getElementById('body-weight'); if (!section) return;
    const {node, svg, request, num, dateInZone, shiftDay, time} = window.StrideHubData;
    const $ = id => document.getElementById(id), app = $('health-app'), base = section.dataset.healthBase.replace(/\/$/, ''), form = $('weight-form');
    const state = {version: null, records: [], daily: [], shown: 7, busy: false, dirty: false, blocked: false, zone: 'Asia/Shanghai', requestId: 0, weightRequest: 0, syncing: false};
    const note = text => { $('weight-status').textContent = text; };
    const check = () => { for (const input of form.querySelectorAll('input')) input.disabled = state.busy; form.querySelector('[type=submit]').disabled = state.busy || state.blocked || !state.version; $('weight-review').hidden = !state.blocked; };
    function drawWeights(records) {
        const target = $('weight-chart'); target.replaceChildren(); const rows = [...records].sort((a, b) => a.date.localeCompare(b.date));
        if (!rows.length) { target.append(node('p', 'empty-note', '从第一条记录开始。每次称重，都是了解变化的新线索。')); return; }
        const width = 600, height = 130, min = Math.floor(Math.min(...rows.map(r => r.weightKg)) - 1), max = Math.ceil(Math.max(...rows.map(r => r.weightKg)) + 1);
        const first = Date.parse(rows[0].date), last = Date.parse(rows.at(-1).date), x = r => first === last ? width / 2 : 42 + (Date.parse(r.date) - first) / (last - first) * (width - 58), y = r => 98 - (r.weightKg - min) / (max - min) * 78;
        const chart = svg('svg', {viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': `体重记录，纵轴 ${min} 至 ${max} 千克`});
        for (const value of [min, max]) { const yy = y({weightKg: value}); chart.append(svg('line', {x1: 38, x2: width, y1: yy, y2: yy, class: 'chart-grid'}), svg('text', {x: 0, y: yy + 4}, value + ' kg')); }
        if (rows.length > 1) chart.append(svg('polyline', {points: rows.map(r => `${x(r)},${y(r)}`).join(' '), fill: 'none', stroke: '#64b3a2', 'stroke-width': 2}));
        for (const row of rows) { const dot = svg('circle', {cx: x(row), cy: y(row), r: 4, fill: '#37a891', stroke: '#fff', 'stroke-width': 2}); dot.append(svg('title', {}, `${row.date}：${row.weightKg} kg`)); chart.append(dot); }
        chart.append(svg('text', {x: 42, y: 122}, rows[0].date), svg('text', {x: width, y: 122, 'text-anchor': 'end'}, rows.at(-1).date)); target.append(chart);
    }
    function renderWeight() {
        const rows = state.records, target = $('weight-metrics'); target.replaceChildren(); const latest = rows[0];
        window.StrideHubData.metric(target, '最近一次', num(latest?.weightKg, 2), 'kg', latest?.date || '尚未记录');
        if (rows.length > 1) { const delta = latest.weightKg - rows.at(-1).weightKg; window.StrideHubData.metric(target, '较最早记录', (delta > 0 ? '+' : '') + num(delta, 2), 'kg', rows.at(-1).date + ' 至 ' + latest.date); }
        drawWeights(rows); $('weight-history').replaceChildren(...rows.slice(0, 6).map(r => node('span', '', `${r.date} · ${num(r.weightKg, 2)} kg`)));
        check();
    }
    async function loadWeight() { const generation = ++state.weightRequest; const data = await request(base + '/weight'); if (generation !== state.weightRequest) return data; if (state.version === null || (!state.dirty && !state.blocked)) state.version = data.version; state.records = data.records; renderWeight(); return data; }
    async function renewToken() {
        const res = await fetch(location.pathname, {credentials: 'same-origin', cache: 'no-store'}); const doc = new DOMParser().parseFromString(await res.text(), 'text/html'); const token = doc.getElementById('health-app')?.dataset.csrf;
        if (!res.ok || !token) throw new Error('登录已过期。请在另一个标签页重新登录，当前草稿保留。'); return token;
    }
    form.addEventListener('input', () => { state.dirty = true; });
    form.addEventListener('submit', async event => {
        event.preventDefault(); if (state.busy || state.blocked || !state.version) return;
        const date = form.elements.date.value, value = Number(form.elements.weightKg.value), notes = form.elements.notes.value;
        const existing = state.records.find(r => r.date === date);
        if (existing && !window.confirm(`${date} 已记录 ${num(existing.weightKg, 2)} kg。确认用 ${num(value, 2)} kg 更新这一天？`)) return;
        ++state.weightRequest; state.busy = true; check(); note('正在保存并核验…');
        try {
            const token = await renewToken();
            await request(base + '/weight/' + date, {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': token}, body: JSON.stringify({version: state.version, weightKg: value, notes, source: 'manual'})});
            const confirmed = await request(base + '/weight'); const record = confirmed.records.find(r => r.date === date);
            if (!record || Math.abs(record.weightKg - value) > .001 || record.notes !== notes) throw new Error('保存后记录发生变化，请读取最新内容核对。');
            state.version = confirmed.version; state.records = confirmed.records; state.dirty = false; renderWeight(); note('已保存并核验。');
        } catch (error) { state.blocked = true; note(error.message + ' 草稿保留，不会自动重试。'); }
        finally { state.busy = false; check(); }
    });
    $('weight-review').addEventListener('click', async () => {
        if (state.busy) return; ++state.weightRequest; state.busy = true; check();
        try { const data = await request(base + '/weight'); state.records = data.records; state.version = data.version; const current = data.records.find(r => r.date === form.elements.date.value); state.blocked = false; renderWeight();
            if (current && Math.abs(current.weightKg - Number(form.elements.weightKg.value)) < .001 && current.notes === form.elements.notes.value) { state.dirty = false; note('已确认服务器记录与当前内容一致，无需重复保存。'); }
            else note(current ? `服务器最新记录为 ${num(current.weightKg, 2)} kg，备注：${current.notes || '无'}。你的草稿已保留，核对后再次保存。` : '当天尚无记录。草稿已保留，核对后可以保存。');
        } catch (e) { note(e.message); } finally { state.busy = false; check(); }
    });
    function drawHeart(target, samples) {
        const points = samples.filter(s => Number.isFinite(s.bpm) && s.at); if (!points.length) { target.append(node('p', 'subtle small', '这一天没有有效的逐分钟心率。')); return; }
        const min = Math.max(0, Math.floor(Math.min(...points.map(s => s.bpm)) / 10) * 10 - 10), max = Math.ceil(Math.max(...points.map(s => s.bpm)) / 10) * 10 + 10;
        const start = new Date(points[0].at).getTime(), end = Math.max(start + 60000, new Date(points.at(-1).at).getTime());
        const chart = svg('svg', {viewBox: '0 0 600 140', role: 'img', 'aria-label': `心率分钟记录，${points.length} 个有效采样`});
        const x = p => points.length === 1 ? 300 : 40 + (new Date(p.at).getTime() - start) / (end - start) * 550, y = p => 110 - (p.bpm - min) / (max - min) * 90;
        for (const value of [min, max]) chart.append(svg('text', {x: 0, y: y({bpm: value}) + 4}, value + ''));
        // Separate segments at missing minutes; never draw an uninterrupted line across a recording gap.
        let segment = [], previous = null; const flush = () => { if (segment.length > 1) chart.append(svg('polyline', {points: segment.join(' '), stroke: '#da9389', 'stroke-width': 1.3, fill: 'none'})); segment = []; };
        for (const point of points) { const stamp = new Date(point.at).getTime(); if (previous !== null && stamp - previous > 90000) flush(); segment.push(`${x(point)},${y(point)}`); const dot = svg('circle', {cx: x(point), cy: y(point), r: 1.4, fill: '#da9389'}); dot.append(svg('title', {}, `${time(point.at, state.zone)}：${point.bpm} bpm`)); chart.append(dot); previous = stamp; } flush();
        chart.append(svg('text', {x: 40, y: 135}, time(points[0].at, state.zone)), svg('text', {x: 590, y: 135, 'text-anchor': 'end'}, time(points.at(-1).at, state.zone))); target.append(chart);
    }
    function renderDaily() {
        const target = $('body-daily-records'); target.replaceChildren();
        if (!state.daily.length) target.append(node('p', 'empty-note', '还没有详细日记录。同步后可展开查看每天的心率、活动与数据来源。'));
        for (const day of state.daily.slice(0, state.shown)) {
            const details = node('details', 'data-day-entry'); const summary = node('summary', '', `${day.date} · ${num(day.activity?.steps)} 步 · ${day.heartRate?.sampleCount || 0} 个心率采样`); details.append(summary);
            const content = node('div'); details.append(content); let loaded = false;
            details.addEventListener('toggle', async () => {
                if (!details.open || loaded) return; loaded = true; content.replaceChildren(node('p', 'subtle small', '正在读取这一天的明细…'));
                try {
                    const record = await request(base + '/zepp/days/' + day.date); content.replaceChildren();
                    const list = node('dl'); const fields = [['步数', num(record.activity?.steps) + ' 步'], ['活动距离', num(record.activity?.distanceMeters == null ? null : record.activity.distanceMeters / 1000, 2) + ' km'], ['活动热量', num(record.activity?.caloriesKcal) + ' kcal'], ['日平均心率', num(record.heartRate?.averageBpm, 1) + ' bpm'], ['静息心率', num(record.heartRate?.restingBpm) + ' bpm'], ['平均血氧', num(record.oxygen?.averagePercent, 1) + ' %'], ['平均压力', num(record.stress?.average)], ['来源时区', record.timeZone || '未提供']];
                    fields.forEach(([label, value]) => { const pair = node('div'); pair.append(node('dt', '', label), node('dd', '', value)); list.append(pair); }); content.append(list);
                    const chart = node('div', 'mini-chart'); drawHeart(chart, record.samples?.heartRate || []); content.append(chart);
                    content.append(node('p', 'subtle small', `Zepp 原始来源 · ${record.rawRecordIds?.length || 0} 份源记录 · 最近写入 ${time(record.lastSyncedAt, state.zone)}。未提供指标显示 —。`));
                } catch (e) { loaded = false; content.replaceChildren(node('p', 'subtle small', e.message + ' 收起后重新展开即可重试。')); }
            });
            target.append(details);
        }
        $('daily-more').hidden = state.daily.length <= state.shown;
    }
    async function loadDaily() {
        const generation = ++state.requestId; const range = $('health-range'); const today = dateInZone(new Date(), state.zone); const from = range.elements.from.value || shiftDay(today, -89), to = range.elements.to.value || today;
        const [records, sync] = await Promise.allSettled([request(base + `/zepp/days?from=${from}&to=${to}`), request(base + '/zepp/status')]); if (generation !== state.requestId) return;
        if (records.status === 'fulfilled') { state.daily = records.value.records; renderDaily(); } else $('body-daily-records').replaceChildren(node('p', 'empty-note', records.reason.message));
        if (sync.status === 'fulfilled') { const data = sync.value; $('body-zepp-sync').disabled = !data.configured || state.syncing || data.state === 'syncing'; $('body-zepp-status').textContent = [data.state === 'syncing' ? '正在同步，当前展示历史记录' : data.state === 'partial' ? '部分来源尚未取得' : '', data.lastSuccessAt ? `最近同步 ${time(data.lastSuccessAt, state.zone)}` : '尚无成功同步记录', data.lastError, data.nextAction].filter(Boolean).join(' · '); } else $('body-zepp-status').textContent = sync.reason.message;
    }
    $('daily-more').addEventListener('click', () => { state.shown += 7; renderDaily(); });
    $('body-zepp-sync').addEventListener('click', async () => {
        if (state.syncing) return; state.syncing = true; $('body-zepp-sync').disabled = true; $('body-zepp-status').textContent = '正在同步最近 90 天的详细记录…';
        try { const token = await renewToken(); await request(base + '/zepp/sync', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': token}, body: JSON.stringify({days: 90})}); state.syncing = false; $('health-refresh').click(); }
        catch (e) { $('body-zepp-status').textContent = e.message + ' 已有记录仍保留。'; }
        finally { state.syncing = false; $('body-zepp-sync').disabled = false; }
    });
    $('health-refresh').addEventListener('click', () => { if (state.busy) return; loadWeight().catch(e => note(e.message)); loadDaily(); });
    $('health-range').addEventListener('submit', () => { if ($('health-range').elements.from.value <= $('health-range').elements.to.value) loadDaily(); });
    window.addEventListener('beforeunload', e => { if (state.dirty || state.busy) { e.preventDefault(); e.returnValue = ''; } });
    check();
    (async () => { try { const profile = await request(app.dataset.profileUrl); state.zone = profile.timezone; } catch { /* Existing health page reports the profile failure. */ } form.elements.date.value = dateInZone(new Date(), state.zone); await Promise.allSettled([loadWeight().catch(e => note(e.message)), loadDaily()]); check(); })();
})();
