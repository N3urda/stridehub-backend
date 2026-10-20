(() => {
    'use strict';
    const {node, svg, request, dateInZone, shiftDay, num, status, metric} = window.StrideHubData;
    const nutrientNames = {energyKcal: '能量', proteinGrams: '蛋白质', carbohydrateGrams: '碳水', fatGrams: '脂肪'};
    const mealNames = {breakfast: '早餐', lunch: '午餐', dinner: '晚餐', snack: '加餐', fuel: '运动补给', other: '其他'};
    const sourceNames = {unknown: '未填写', label: '食品标签', database: '食物数据库', estimate: '估算'};
    const stateNames = {draft: '草稿', active: '进行中', paused: '暂停', completed: '已完成', cancelled: '已取消'};
    const charts = new Map();
    function table(target, headers, rows) {
        target.replaceChildren();
        if (!rows.length) { target.append(node('p', 'empty-note', '所选范围内尚无记录。')); return; }
        const el = node('table'), head = node('tr'), body = node('tbody');
        headers.forEach(h => head.append(node('th', '', h)));
        const thead = node('thead'); thead.append(head); el.append(thead, body);
        rows.forEach(values => { const row = node('tr'); values.forEach((v, i) => { const cell = node('td', i === 1 ? 'wrap-cell' : ''); cell.append(v instanceof Node ? v : document.createTextNode(String(v ?? '—'))); row.append(cell); }); body.append(row); });
        target.append(el);
    }
    function chart(target, rows, series, {zero = false, unit = ''} = {}) {
        charts.set(target, {rows, series, zero, unit});
        target.replaceChildren();
        const values = rows.flatMap(r => series.map(s => r[s.key])).filter(Number.isFinite);
        if (!values.length) { target.append(node('p', 'empty-note', '所选范围内尚无可展示的数据。')); return; }
        const width = Math.max(240, Math.min(760, target.clientWidth || 760)), height = 235, left = 48, right = 15, top = 16, bottom = 35;
        let low = zero ? 0 : Math.min(...values), high = Math.max(...values);
        const padding = Math.max((high - low) * .15, zero ? 1 : .5);
        if (!zero) low -= padding; high += padding;
        const firstDate = Date.parse(rows[0].date), span = Math.max(86400000, Date.parse(rows.at(-1).date) - firstDate);
        const x = i => left + (Date.parse(rows[i].date) - firstDate) * (width - left - right) / span;
        const y = value => top + (high - value) * (height - top - bottom) / (high - low);
        const image = svg('svg', {viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': series.map(s => s.label).join('、') + '日期趋势'});
        for (let i = 0; i < 4; i++) {
            const value = low + (high - low) * i / 3, py = y(value);
            image.append(svg('line', {x1: left, x2: width - right, y1: py, y2: py, class: 'grid'}), svg('text', {x: left - 8, y: py + 4, 'text-anchor': 'end'}, num(value, 1)));
        }
        for (const s of series) {
            let d = '', connected = false;
            rows.forEach((r, i) => {
                if (!Number.isFinite(r[s.key])) { connected = false; return; }
                d += `${connected ? 'L' : 'M'}${x(i)} ${y(r[s.key])} `; connected = true;
            });
            image.append(svg('path', {d, fill: 'none', stroke: s.color, 'stroke-width': 2, 'stroke-dasharray': s.dashed ? '5 4' : 'none'}));
            rows.forEach((r, i) => { if (!Number.isFinite(r[s.key])) return; const point = svg('circle', {cx: x(i), cy: y(r[s.key]), r: 3, fill: s.color}); point.append(svg('title', {}, `${r.date} · ${s.label} ${num(r[s.key], 2)} ${unit}`)); image.append(point); });
        }
        [...new Set(width < 400 ? [0, rows.length - 1] : [0, Math.floor((rows.length - 1) / 2), rows.length - 1])].forEach(i => image.append(svg('text', {x: x(i), y: height - 10, 'text-anchor': i === 0 ? 'start' : i === rows.length - 1 ? 'end' : 'middle'}, rows[i].date)));
        const legend = node('div', 'chart-legend'); series.forEach(s => { const label = node('span', '', s.label); label.style.setProperty('--series-color', s.color); legend.append(label); });
        target.append(legend, image);
    }
    let chartResize;
    window.addEventListener('resize', () => {
        cancelAnimationFrame(chartResize);
        chartResize = requestAnimationFrame(() => { for (const [target, config] of charts) if (target.isConnected) chart(target, config.rows, config.series, config); });
    });
    window.StrideHubWorkspaceUI = {chart, table};
    const app = document.getElementById('data-workspace'); if (!app) return;
    const $ = id => document.getElementById(id), base = app.dataset.base, range = $('data-range');
    const state = {data: null, generation: 0, busy: false, loading: false, dirty: false, edits: {}, failedWrite: false};
    const api = (route, options) => request(base + '/' + route, options);
    const write = (route, method, body) => api(route, {method, headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: body === undefined ? undefined : JSON.stringify(body)});
    const formNumber = (form, key) => form.elements[key].value === '' ? null : Number(form.elements[key].value);
    const action = (label, fn) => { const b = node('button', '', label); b.type = 'button'; b.disabled = state.busy || state.loading; b.addEventListener('click', fn); return b; };
    function lock() {
        document.querySelectorAll('.data-form input,.data-form select,.data-form button,.data-table-scroll button,.goal-card button,#data-range input,#data-range button,#data-refresh').forEach(el => { el.disabled = state.busy || state.loading || (!state.data && el.closest('.data-form') !== null); });
    }
    async function history(resource, id) {
        try {
            const data = await api(`${resource}/${encodeURIComponent(id)}/history`);
            $('record-history').replaceChildren(...data.records.map(r => { const details = node('details'); details.append(node('summary', '', `v${r.recordVersion} · ${r.action === 'delete' ? '删除' : '保存'} · ${r.updatedAt}`), node('pre', '', JSON.stringify(r.record, null, 2))); return details; }));
        } catch (e) { $('record-history').textContent = e.message; }
    }
    function edit(prefix, resource, row) {
        const form = $(prefix + '-form');
        if (state.busy || state.loading) return;
        for (const el of form.elements) { if (!el.name) continue; const value = row[el.name] ?? row.nutrients?.[el.name]; el.value = value ?? ''; }
        state.edits[prefix] = {id: row.id, version: row.version}; state.dirty = false; state.failedWrite = false;
        $(prefix + '-status').textContent = `正在编辑版本 ${row.version}；保存只更新本表单字段。`;
        if ($('record-history')) history(resource, row.id);
        form.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
    async function remove(resource, row) {
        if (state.busy || state.loading || !confirm('删除这条记录？修改历史会保留。')) return;
        state.busy = true; ++state.generation; lock();
        try { await write(`${resource}/${encodeURIComponent(row.id)}?version=${row.version}`, 'DELETE'); await load(true); }
        catch (e) { status($('data-status'), e.message + ' 请刷新后核对，未自动重试。', true); }
        finally { state.busy = false; lock(); }
    }
    function controls(prefix, resource, row) {
        const el = node('div'); el.append(action('编辑', () => edit(prefix, resource, row)), action('删除', () => remove(resource, row)));
        return el;
    }
    function renderNutrition() {
        const data = state.data.nutrition, day = data.days.find(d => d.date === $('nutrition-date').value);
        $('nutrition-metrics').replaceChildren();
        for (const [key, label] of Object.entries(nutrientNames)) {
            metric($('nutrition-metrics'), label + ' · 已记录', num(day?.actualLogged[key], 1), key === 'energyKcal' ? 'kcal' : 'g', `目标 ${num(day?.targets[key], 1)} · 差值 ${num(day?.delta[key], 1)}`);
        }
        const p = data.plans.find(p => p.id === day?.planId), target = $('nutrition-plan'); target.replaceChildren();
        if (!p) target.append(node('p', 'empty-note', '当天尚未保存生效的饮食计划。'));
        else {
            target.append(node('p', 'subtle small', `${p.date} · ${p.sourceLabel || (p.source === 'ai' ? 'AI 制定' : '自行制定')} · v${p.version}`));
            if (!p.meals.length) target.append(node('p', 'empty-note', '已设置营养目标，尚未安排具体餐食。'));
            p.meals.forEach(m => { const row = node('div', 'meal-plan-card'); row.append(node('h3', '', mealNames[m.mealType]), node('p', '', m.foods), node('p', 'subtle', m.portion || '份量未指定')); target.append(row); });
            if (p.notes) target.append(node('p', 'subtle small', p.notes));
        }
        chart($('nutrition-chart'), data.days.map(d => ({date: d.date, target: d.targets.energyKcal, actual: d.actualLogged.energyKcal})), [{key: 'target', label: '计划目标', color: '#a6b2cf', dashed: true}, {key: 'actual', label: '已记录摄入', color: '#659f94'}], {zero: true, unit: 'kcal'});
        $('meal-count').textContent = `${data.meals.length} 餐`;
        table($('meals-table'), ['日期 / 餐次', '食物 / 份量', 'kcal', '蛋白质 g', '碳水 g', '脂肪 g', '来源', '操作'], data.meals.map(m => [
            `${m.date} ${mealNames[m.mealType]}`, `${m.foods}${m.portion ? ' · ' + m.portion : ''}`, ...Object.keys(nutrientNames).map(k => num(m.nutrients[k], 1)), sourceNames[m.nutritionSource] + (m.sourceLabel ? ' · ' + m.sourceLabel : ''), controls('meal', 'meals', m),
        ]));
        table($('nutrition-days'), ['日期', '已记录餐次', '目标 kcal', '已记录 kcal', '差值 kcal', '估算餐次'], [...data.days].reverse().map(d => [d.date, d.mealCount, num(d.targets.energyKcal), num(d.actualLogged.energyKcal), num(d.delta.energyKcal), d.estimatedMeals]));
    }
    function renderWeight() {
        const {records, trend, goals} = state.data.weight, latest = records[0], lastTrend = trend.at(-1);
        $('weight-data-metrics').replaceChildren();
        metric($('weight-data-metrics'), '最近实测', num(latest?.weightKg, 2), 'kg', latest?.date || '所选范围暂无记录');
        metric($('weight-data-metrics'), '最近七日均值', num(lastTrend?.average7DaysKg, 2), 'kg', lastTrend ? `${lastTrend.sampleDays} 天实测样本` : '未记录');
        metric($('weight-data-metrics'), '期间变化', records.length > 1 ? num(latest.weightKg - records.at(-1).weightKg, 2) : '—', 'kg', `${records.length} 天有记录`);
        const points = new Map(trend.map(r => [r.date, {...r}]));
        const active = goals.find(g => g.status === 'active' && g.startDate <= state.data.today && g.endDate >= state.data.today);
        if (active) {
            for (const p of [{date: active.startDate, weightKg: active.startWeightKg}, ...active.milestones, {date: active.endDate, weightKg: active.targetWeightKg}]) {
                if (p.date >= state.data.from && p.date <= state.data.to) points.set(p.date, {...points.get(p.date), date: p.date, target: p.weightKg});
            }
        }
        chart($('weight-data-chart'), [...points.values()].sort((a, b) => a.date.localeCompare(b.date)), [{key: 'weightKg', label: '实测体重', color: '#8294d4'}, {key: 'average7DaysKg', label: '七日实测均值', color: '#56a68e'}, {key: 'target', label: '当前目标点', color: '#cb9b61', dashed: true}], {unit: 'kg'});
        $('weight-goals').replaceChildren();
        if (!goals.length) $('weight-goals').append(node('p', 'empty-note', '尚未设置减重目标。填写目标后展示进度，不会自动生成目标值。'));
        goals.forEach(g => {
            const card = node('div', 'goal-card'); card.append(node('h3', '', `${g.title} · ${stateNames[g.status]}`), node('p', 'subtle', `${g.startDate} → ${g.endDate} · ${g.startWeightKg} → ${g.targetWeightKg} kg`));
            const p = g.progress; card.append(node('p', '', p.measuredAt ? `${p.measuredAt} 实测 ${num(p.currentWeightKg, 2)} kg · 距目标 ${num(p.remainingKg, 2)} kg · 进度 ${num(p.percent, 1)}%` : '目标期间暂无实测体重'));
            if (p.percent != null) { const bar = node('progress'); bar.max = 100; bar.value = Math.max(0, Math.min(100, p.percent)); bar.setAttribute('aria-label', '目标完成进度'); card.append(bar); }
            g.milestones.forEach(m => card.append(node('p', 'subtle', `阶段目标 ${m.date} · ${num(m.weightKg, 2)} kg`)));
            if (g.notes) card.append(node('p', 'subtle', g.notes));
            card.append(controls('goal', 'weight-goals', g)); $('weight-goals').append(card);
        });
        table($('weight-data-table'), ['日期', '实测 kg', '七日均值 kg', '样本天数', '来源 / 备注'], [...trend].reverse().map(r => [r.date, num(r.weightKg, 2), num(r.average7DaysKg, 2), r.sampleDays, `${r.sourceLabel || r.source} ${r.notes}`]));
        table($('measurements-table'), ['日期', '来源', '腰围 cm', '体脂率 %', '操作'], state.data.measurements.map(r => [r.date, r.sourceLabel || (r.source === 'user' ? '手动记录' : '导入'), num(r.waistCm, 1), num(r.bodyFatPercent, 1), controls('measurement', 'measurements', r)]));
    }
    async function load(afterWrite = false) {
        if (state.busy && !afterWrite) return;
        const generation = ++state.generation; state.loading = true; lock(); status($('data-status'), '正在读取数据…');
        const query = range.elements.from.value && range.elements.to.value ? '?' + new URLSearchParams({from: range.elements.from.value, to: range.elements.to.value}) : '';
        try {
            const data = await api('workspace' + query); if (generation !== state.generation) return;
            state.data = data; range.elements.from.value = data.from; range.elements.to.value = data.to;
            $('data-zone').textContent = data.timezone + ' · 读取时间 ' + new Intl.DateTimeFormat('zh-CN', {timeZone: data.timezone, dateStyle: 'short', timeStyle: 'short'}).format(new Date(data.generatedAt));
            document.querySelectorAll('.data-form [type=date]').forEach(el => { if (!el.value && !state.dirty) el.value = el.name === 'endDate' ? shiftDay(data.today, 60) : data.today; });
            if ($('nutrition-date')) { const input = $('nutrition-date'); input.min = data.from; input.max = data.to; if (!input.value || input.value < data.from || input.value > data.to) input.value = data.today >= data.from && data.today <= data.to ? data.today : data.to; }
            app.dataset.view === 'nutrition' ? renderNutrition() : renderWeight();
            status($('data-status'), '');
        } catch (e) { if (generation === state.generation) status($('data-status'), e.message + ' 当前内容未刷新，保留上次读取的记录。', true); }
        finally { if (generation === state.generation) { state.loading = false; lock(); } }
    }
    function connectForm(prefix, resource, payload) {
        const form = $(prefix + '-form'); if (!form) return;
        const reset = () => { form.reset(); state.edits[prefix] = null; state.dirty = false; state.failedWrite = false; for (const el of form.querySelectorAll('[type=date]')) el.value = el.name === 'endDate' ? shiftDay(state.data.today, 60) : state.data.today; $(prefix + '-status').textContent = ''; };
        $(prefix + '-reset').addEventListener('click', () => { if (state.data && !state.busy) reset(); });
        form.addEventListener('input', () => { state.dirty = true; });
        form.addEventListener('submit', async e => {
            e.preventDefault(); if (state.busy || state.loading || !state.data) return;
            if (state.failedWrite) { $(prefix + '-status').textContent = '上次写入结果需要核对。请刷新后选中最新记录继续编辑；新建记录请先核对明细，避免重复。'; return; }
            const editState = state.edits[prefix] ||= {id: crypto.randomUUID(), version: 0};
            state.busy = true; ++state.generation; lock(); status($(prefix + '-status'), '正在保存…');
            try {
                const saved = await write(resource + '/' + editState.id, 'PUT', {version: editState.version, ...payload(form)});
                const confirmed = await api(resource + '/' + editState.id);
                if (saved.version !== confirmed.version) throw new Error('保存后记录又发生变化，请刷新核对。');
                reset(); await load(true); status($(prefix + '-status'), '已保存并核验。');
            } catch (err) { state.failedWrite = true; status($(prefix + '-status'), err.message + '\n草稿已保留。刷新后选中最新记录核对；不会自动重试。', true); }
            finally { state.busy = false; lock(); }
        });
    }
    connectForm('meal', 'meals', f => ({date: f.elements.date.value, mealType: f.elements.mealType.value, foods: f.elements.foods.value, portion: f.elements.portion.value, nutrients: Object.fromEntries(Object.keys(nutrientNames).map(k => [k, formNumber(f, k)])), nutritionSource: f.elements.nutritionSource.value, sourceLabel: f.elements.sourceLabel.value, notes: f.elements.notes.value, source: 'user'}));
    connectForm('goal', 'weight-goals', f => ({title: f.elements.title.value, startDate: f.elements.startDate.value, endDate: f.elements.endDate.value, startWeightKg: formNumber(f, 'startWeightKg'), targetWeightKg: formNumber(f, 'targetWeightKg'), status: f.elements.status.value, notes: f.elements.notes.value, source: 'user'}));
    connectForm('measurement', 'measurements', f => ({date: f.elements.date.value, waistCm: formNumber(f, 'waistCm'), bodyFatPercent: formNumber(f, 'bodyFatPercent'), sourceLabel: f.elements.sourceLabel.value, notes: f.elements.notes.value, source: 'user'}));
    $('nutrition-date')?.addEventListener('change', () => { if (state.data) renderNutrition(); });
    $('data-refresh').addEventListener('click', () => load());
    range.addEventListener('submit', e => { e.preventDefault(); load(); });
    window.addEventListener('beforeunload', e => { if (state.dirty) { e.preventDefault(); e.returnValue = ''; } });
    load();
})();
