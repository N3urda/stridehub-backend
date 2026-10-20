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
    function chart(target, rows, series, {zero = false, unit = '', onSelect, selectedDate} = {}) {
        charts.set(target, {rows, series, zero, unit, onSelect, selectedDate});
        target.replaceChildren();
        const values = rows.flatMap(r => series.map(s => r[s.key])).filter(Number.isFinite);
        if (!values.length) { target.append(node('p', 'empty-note chart-empty-state', '这段时间还没有可绘制的数值。保存记录后，趋势会显示在这里。')); return; }
        const width = Math.max(240, Math.min(760, target.clientWidth || 760)), height = 235, left = 48, right = 15, top = 16, bottom = 35;
        let low = zero ? 0 : Math.min(...values), high = Math.max(...values);
        const padding = Math.max((high - low) * .15, zero ? 1 : .5);
        if (!zero) low -= padding; high += padding;
        const firstDate = Date.parse(rows[0].date), span = Date.parse(rows.at(-1).date) - firstDate;
        const x = i => span === 0 ? (width + left - right) / 2 : left + (Date.parse(rows[i].date) - firstDate) * (width - left - right) / span;
        const y = value => top + (high - value) * (height - top - bottom) / (high - low);
        const plot = node('div', 'chart-interaction'); plot.tabIndex = 0; plot.setAttribute('role', 'group');
        plot.setAttribute('aria-label', '按日期查看趋势；左右方向键切换日期，Home 和 End 跳到首尾' + (onSelect ? '，回车查看当日明细' : ''));
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
            image.append(svg('path', {d, fill: 'none', stroke: s.color, 'stroke-width': 2.5, 'stroke-dasharray': s.dashed ? '5 4' : 'none'}));
            rows.forEach((r, i) => { if (!Number.isFinite(r[s.key])) return; const point = svg('circle', {cx: x(i), cy: y(r[s.key]), r: 3, fill: s.color}); point.append(svg('title', {}, `${r.date} · ${s.label} ${num(r[s.key], 2)} ${unit}`)); image.append(point); });
        }
        [...new Set(width < 400 ? [0, rows.length - 1] : [0, Math.floor((rows.length - 1) / 2), rows.length - 1])].forEach(i => image.append(svg('text', {x: x(i), y: height - 10, 'text-anchor': rows.length === 1 ? 'middle' : i === 0 ? 'start' : i === rows.length - 1 ? 'end' : 'middle'}, rows[i].date.slice(5))));
        const guide = svg('line', {y1: top, y2: height - bottom, class: 'chart-guide'}); image.append(guide);
        const dots = series.map(s => { const dot = svg('circle', {r: 5, fill: s.color, stroke: '#fff', 'stroke-width': 2}); image.append(dot); return dot; });
        const readout = node('div', 'chart-readout'); readout.id = target.id + '-readout'; readout.setAttribute('aria-live', 'off'); plot.setAttribute('aria-describedby', readout.id);
        let index = rows.findIndex(r => r.date === selectedDate);
        if (index < 0) index = rows.findLastIndex(r => series.some(s => Number.isFinite(r[s.key])));
        function inspect(next, keyboard = false) {
            index = Math.max(0, Math.min(rows.length - 1, next)); const row = rows[index];
            charts.get(target).selectedDate = row.date;
            guide.setAttribute('x1', x(index)); guide.setAttribute('x2', x(index));
            readout.setAttribute('aria-live', keyboard ? 'polite' : 'off'); readout.replaceChildren(node('strong', '', row.date));
            series.forEach((s, n) => {
                const v = row[s.key], text = node('span', '', `${s.label} ${num(v, 2)} ${unit}`); text.style.setProperty('--series-color', s.color); readout.append(text);
                dots[n].style.display = Number.isFinite(v) ? '' : 'none'; dots[n].setAttribute('cx', x(index)); dots[n].setAttribute('cy', Number.isFinite(v) ? y(v) : 0);
            });
            if (row.sampleDays != null) readout.append(node('small', '', `七日窗口内有 ${row.sampleDays} 天实测`));
        }
        function nearest(event) {
            const px = new DOMPoint(event.clientX, event.clientY).matrixTransform(image.getScreenCTM().inverse()).x;
            return rows.reduce((best, row, i) => Math.abs(x(i) - px) < Math.abs(x(best) - px) ? i : best, 0);
        }
        image.addEventListener('pointermove', event => { if (event.pointerType !== 'touch') inspect(nearest(event)); });
        let touchStart;
        image.addEventListener('pointerdown', e => { touchStart = {x: e.clientX, y: e.clientY}; });
        image.addEventListener('pointerup', e => { if (!touchStart || Math.hypot(e.clientX - touchStart.x, e.clientY - touchStart.y) > 10) return; inspect(nearest(e)); if (onSelect) onSelect(rows[index].date); });
        plot.addEventListener('keydown', e => {
            if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) { e.preventDefault(); inspect(e.key === 'Home' ? 0 : e.key === 'End' ? rows.length - 1 : index + (e.key === 'ArrowLeft' ? -1 : 1), true); }
            else if (e.key === 'Enter' && onSelect) { e.preventDefault(); onSelect(rows[index].date); }
        });
        const legend = node('div', 'chart-legend'); series.forEach(s => { const label = node('span', '', s.label); label.style.setProperty('--series-color', s.color); legend.append(label); });
        const hint = node('p', 'subtle small chart-hint', onSelect ? '轻点日期查看当日记录 · 键盘左右切换，回车选择' : '指向或轻点图表查看数值 · 支持键盘左右切换');
        plot.append(image); target.append(legend, plot, readout, hint); inspect(index);
    }
    let chartResize;
    window.addEventListener('resize', () => {
        cancelAnimationFrame(chartResize);
        chartResize = requestAnimationFrame(() => { for (const [target, config] of charts) if (target.isConnected) chart(target, config.rows, config.series, config); });
    });
    window.StrideHubWorkspaceUI = {chart, table};
    const app = document.getElementById('data-workspace'); if (!app) return;
    const $ = id => document.getElementById(id), base = app.dataset.base, range = $('data-range');
    const state = {data: null, selectedDay: '', generation: 0, busy: false, loading: false, drafts: new Set(), edits: {}, failedWrites: new Set(), weightVersion: null, weightRecords: []};
    const api = (route, options) => request(base + '/' + route, options);
    const write = (route, method, body) => api(route, {method, headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: body === undefined ? undefined : JSON.stringify(body)});
    const formNumber = (form, key) => form.elements[key].value === '' ? null : Number(form.elements[key].value);
    const action = (label, fn) => { const b = node('button', '', label); b.type = 'button'; b.disabled = state.busy || state.loading; b.addEventListener('click', fn); return b; };
    function lock() {
        document.querySelectorAll('.data-form input,.data-form select,.data-form button,.data-table-scroll button,.goal-card button,.meal-actual-card button,[data-open-editor],[data-range-days],#nutrition-prev,#nutrition-next,#nutrition-date,#data-range input,#data-range button,#data-refresh').forEach(el => { el.disabled = state.busy || state.loading || (!state.data && (el.closest('.data-form') !== null || el.matches('[data-open-editor]'))); });
        if (state.data && $('nutrition-date')) { $('nutrition-prev').disabled ||= $('nutrition-date').value <= state.data.from; $('nutrition-next').disabled ||= $('nutrition-date').value >= state.data.to; }
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
        if (state.drafts.has(prefix) && !confirm('当前表单有未保存的内容。读取这条记录并替换草稿？')) return;
        for (const el of form.elements) { if (!el.name) continue; const value = row[el.name] ?? row.nutrients?.[el.name]; el.value = value ?? ''; }
        state.edits[prefix] = {id: row.id, version: row.version}; state.drafts.delete(prefix); state.failedWrites.delete(prefix);
        $(prefix + '-status').textContent = `正在编辑版本 ${row.version}；保存只更新本表单字段。`;
        if ($('record-history')) history(resource, row.id);
        openEditor(prefix);
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
    function renderNutrition(redrawChart = true) {
        const data = state.data.nutrition, date = $('nutrition-date').value, day = data.days.find(d => d.date === date);
        const meals = data.meals.filter(m => m.date === date);
        $('nutrition-day-label').textContent = (date === state.data.today ? '今天' : date) + ' · ' + new Intl.DateTimeFormat('zh-CN', {weekday: 'long', timeZone: 'UTC'}).format(new Date(date + 'T12:00:00Z'));
        $('nutrition-prev').disabled = state.busy || state.loading || date <= state.data.from;
        $('nutrition-next').disabled = state.busy || state.loading || date >= state.data.to;
        $('nutrition-metrics').replaceChildren();
        for (const [key, label] of Object.entries(nutrientNames)) {
            const value = day?.actualLogged[key], target = day?.targets[key], partial = value == null && day?.knownSubtotal[key] != null;
            const detail = partial ? `已知小计 ${num(day.knownSubtotal[key], 1)} · ${day.coverage[key]}/${day.mealCount} 餐有数值` : `目标 ${num(target, 1)} · 记录与目标差 ${num(day?.delta[key], 1)}`;
            metric($('nutrition-metrics'), label + ' · 已记录', num(value, 1), key === 'energyKcal' ? 'kcal' : 'g', detail);
            const card = $('nutrition-metrics').lastElementChild;
            if (value != null && target > 0) { const bar = node('progress'); bar.max = target; bar.value = Math.min(value, target); bar.setAttribute('aria-label', `${label}已记录 ${num(value, 1)}，目标 ${num(target, 1)}`); card.append(bar); }
        }
        $('nutrition-coverage').textContent = `已记录 ${meals.length} 餐 · ${day?.estimatedMeals || 0} 餐含估算` + (meals.length && day?.actualLogged.energyKcal == null ? ' · 部分餐次缺少能量数值' : '');
        const p = data.plans.find(p => p.id === day?.planId), target = $('nutrition-plan'); target.replaceChildren();
        if (!p) { target.append(node('p', 'empty-note', '当天尚未保存生效的饮食计划。'), node('p', 'subtle small', '已有饮食记录仍可查看；保存计划后会在此对照。')); }
        else {
            target.append(node('p', 'subtle small', `${p.date} · ${p.sourceLabel || (p.source === 'ai' ? 'AI 制定' : '自行制定')}`));
            if (!p.meals.length) target.append(node('p', 'empty-note', '已设置营养目标，尚未安排具体餐食。'));
            p.meals.forEach(m => { const row = node('div', 'meal-plan-card'); row.append(node('h3', '', mealNames[m.mealType]), node('p', '', m.foods), node('p', 'subtle', m.portion || '份量未指定')); target.append(row); });
            if (p.notes) target.append(node('p', 'subtle small', p.notes));
        }
        $('day-meal-count').textContent = meals.length + ' 餐'; $('day-meals').replaceChildren();
        if (!meals.length) $('day-meals').append(node('p', 'empty-note', '当天还没有餐食记录。'), node('p', 'subtle small', '可以先记录食物和份量，营养数值留空。'));
        for (const m of meals) {
            const card = node('article', 'meal-actual-card'), heading = node('div', 'meal-card-heading');
            heading.append(node('h3', '', mealNames[m.mealType]), node('span', 'source-pill' + (m.nutritionSource === 'estimate' ? ' estimate' : ' neutral'), sourceNames[m.nutritionSource]));
            card.append(heading, node('p', '', m.foods), node('p', 'subtle small', m.portion || '份量未填写'));
            card.append(node('p', 'meal-nutrients', `${num(m.nutrients.energyKcal)} kcal · 蛋白质 ${num(m.nutrients.proteinGrams, 1)} g`), controls('meal', 'meals', m));
            $('day-meals').append(card);
        }
        if (redrawChart) chart($('nutrition-chart'), data.days.map(d => ({date: d.date, target: d.targets.energyKcal, actual: d.actualLogged.energyKcal})), [{key: 'target', label: '计划目标', color: '#a6b2cf', dashed: true}, {key: 'actual', label: '已记录摄入', color: '#328775'}], {zero: true, unit: 'kcal', selectedDate: date, onSelect: date => selectDay(date, false)});
        $('meal-count').textContent = `${data.meals.length} 餐`;
        table($('meals-table'), ['日期 / 餐次', '食物 / 份量', 'kcal', '蛋白质 g', '碳水 g', '脂肪 g', '来源', '操作'], data.meals.map(m => [
            `${m.date} ${mealNames[m.mealType]}`, `${m.foods}${m.portion ? ' · ' + m.portion : ''}`, ...Object.keys(nutrientNames).map(k => num(m.nutrients[k], 1)), sourceNames[m.nutritionSource] + (m.sourceLabel ? ' · ' + m.sourceLabel : ''), controls('meal', 'meals', m),
        ]));
        const days = [...data.days].reverse().filter(d => $('nutrition-show-empty').checked || d.mealCount > 0 || d.planId);
        table($('nutrition-days'), ['日期', '已记录餐次', '目标 kcal', '已记录 kcal', '记录与目标差', '估算餐次'], days.map(d => [action(d.date, () => { selectDay(d.date); $('nutrition-date').focus(); $('nutrition-metrics').scrollIntoView({behavior: 'smooth', block: 'center'}); }), d.mealCount, num(d.targets.energyKcal), num(d.actualLogged.energyKcal), num(d.delta.energyKcal), d.estimatedMeals]));
    }
    function renderWeight() {
        const {records, trend, goals} = state.data.weight, latest = records[0], lastTrend = trend.at(-1);
        $('weight-data-metrics').replaceChildren();
        metric($('weight-data-metrics'), '最近实测', num(latest?.weightKg, 2), 'kg', latest?.date || '所选范围暂无记录');
        metric($('weight-data-metrics'), '最近测量日的七日均值', num(lastTrend?.average7DaysKg, 2), 'kg', lastTrend ? `${lastTrend.date} · ${lastTrend.sampleDays} 天实测样本` : '未记录');
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
        [...goals].sort((a, b) => Number(b.status === 'active') - Number(a.status === 'active') || b.startDate.localeCompare(a.startDate)).forEach(g => {
            const card = node('div', 'goal-card'); card.append(node('h3', '', `${g.title} · ${stateNames[g.status]}`), node('p', 'subtle', `${g.startDate} → ${g.endDate} · ${g.startWeightKg} → ${g.targetWeightKg} kg`));
            const p = g.progress; card.append(node('p', '', p.measuredAt ? `${p.measuredAt} 实测 ${num(p.currentWeightKg, 2)} kg · 距目标 ${num(p.remainingKg, 2)} kg · 进度 ${num(p.percent, 1)}%` : '目标期间暂无实测体重'));
            if (p.measuredAt) card.append(node('p', 'subtle small', '进度截至上述测量日期；达到目标后仍由你确认完成。'));
            if (p.percent != null) { const bar = node('progress'); bar.max = 100; bar.value = Math.max(0, Math.min(100, p.percent)); bar.setAttribute('aria-label', '目标完成进度'); card.append(bar); }
            g.milestones.forEach(m => card.append(node('p', 'subtle', `阶段目标 ${m.date} · ${num(m.weightKg, 2)} kg`)));
            if (g.notes) card.append(node('p', 'subtle', g.notes));
            card.append(controls('goal', 'weight-goals', g)); $('weight-goals').append(card);
        });
        table($('weight-data-table'), ['日期', '实测 kg', '七日均值 kg', '样本天数', '来源 / 备注'], [...trend].reverse().map(r => [r.date, num(r.weightKg, 2), num(r.average7DaysKg, 2), r.sampleDays, `${r.sourceLabel || r.source} ${r.notes}`]));
        $('measurement-summary').replaceChildren();
        const waist = state.data.measurements.find(r => r.waistCm != null), fat = state.data.measurements.find(r => r.bodyFatPercent != null);
        metric($('measurement-summary'), '最近腰围', num(waist?.waistCm, 1), 'cm', waist?.date || '所选范围未记录');
        metric($('measurement-summary'), '最近体脂率', num(fat?.bodyFatPercent, 1), '%', fat?.date || '所选范围未记录');
        table($('measurements-table'), ['日期', '来源', '腰围 cm', '体脂率 %', '操作'], state.data.measurements.map(r => [r.date, r.sourceLabel || (r.source === 'user' ? '手动记录' : '导入'), num(r.waistCm, 1), num(r.bodyFatPercent, 1), controls('measurement', 'measurements', r)]));
    }
    const validDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
    const initialQuery = new URL(location.href).searchParams;
    if (validDate(initialQuery.get('from')) && validDate(initialQuery.get('to'))) { range.elements.from.value = initialQuery.get('from'); range.elements.to.value = initialQuery.get('to'); }
    if ($('nutrition-date') && validDate(initialQuery.get('date'))) $('nutrition-date').value = initialQuery.get('date');
    function updateRangeState() {
        const data = state.data; if (!data) return;
        document.querySelectorAll('[data-range-days]').forEach(b => { const active = data.to === data.today && data.from === shiftDay(data.today, 1 - Number(b.dataset.rangeDays)); b.classList.toggle('selected', active); b.setAttribute('aria-pressed', String(active)); });
        const url = new URL(location.href); url.searchParams.set('from', data.from); url.searchParams.set('to', data.to);
        if ($('nutrition-date')) url.searchParams.set('date', $('nutrition-date').value);
        window.history.replaceState(null, '', url);
    }
    function selectDay(date, redrawChart = true) {
        if (!state.data || state.busy || state.loading) return;
        if (!validDate(date) || date < state.data.from || date > state.data.to) {
            $('nutrition-date').value = state.selectedDay;
            status($('data-status'), `查看日期应在 ${state.data.from} 至 ${state.data.to} 之间；当前仍展示 ${state.selectedDay}。`, true);
            return;
        }
        $('nutrition-date').value = state.selectedDay = date;
        if ($('data-status').textContent.startsWith('查看日期应在')) status($('data-status'), '');
        if (!state.drafts.has('meal') && !state.edits.meal) $('meal-form').elements.date.value = date;
        renderNutrition(redrawChart); updateRangeState();
    }
    document.querySelectorAll('[data-range-days]').forEach(button => button.addEventListener('click', () => {
        if (!state.data || state.loading || state.busy) return;
        range.elements.from.value = shiftDay(state.data.today, 1 - Number(button.dataset.rangeDays)); range.elements.to.value = state.data.today; load();
    }));
    async function openEditor(prefix) {
        const dialog = $(prefix + '-editor'); if (!dialog || state.busy || state.loading || !state.data) return;
        if (prefix === 'meal' && !state.drafts.has(prefix) && !state.edits[prefix]) $('meal-form').elements.date.value = $('nutrition-date').value;
        if (prefix === 'goal' && !state.drafts.has(prefix) && !state.edits[prefix] && !$('goal-form').elements.startWeightKg.value) $('goal-form').elements.startWeightKg.value = state.data.weight.records[0]?.weightKg ?? '';
        const titles = {meal: '一餐', goal: '减重目标', measurement: '身体测量'};
        if (titles[prefix]) $(prefix + '-editor-title').textContent = (state.edits[prefix]?.version > 0 ? '编辑' : '记录') + titles[prefix];
        if (!dialog.open) dialog.showModal();
        if (prefix === 'weight-entry' && state.weightVersion === null) await reviewWeight();
    }
    document.querySelectorAll('[data-open-editor]').forEach(button => button.addEventListener('click', () => openEditor(button.dataset.openEditor)));
    document.querySelectorAll('[data-close-editor]').forEach(button => button.addEventListener('click', () => button.closest('dialog').close()));
    function showExistingWeight() {
        const form = $('weight-entry-form'), row = state.weightRecords.find(r => r.date === form.elements.date.value);
        $('weight-existing').textContent = row ? `${row.date} 已记录 ${num(row.weightKg, 2)} kg。保存前会请你核对是否替换。` : '每天保留一条体重记录。';
    }
    async function reviewWeight() {
        if (state.busy) return;
        state.busy = true; lock(); status($('weight-entry-status'), '正在读取最新体重记录…');
        try {
            const data = await api('weight'); state.weightRecords = data.records; state.weightVersion = data.version; state.failedWrites.delete('weight-entry'); showExistingWeight();
            status($('weight-entry-status'), '已读取最新记录。请核对后保存。');
        } catch (error) { state.failedWrites.add('weight-entry'); status($('weight-entry-status'), error.message + ' 草稿已保留，读取成功后才能保存。', true); }
        finally { state.busy = false; lock(); }
    }
    $('weight-entry-review')?.addEventListener('click', reviewWeight);
    $('weight-entry-form')?.addEventListener('input', () => { state.drafts.add('weight-entry'); showExistingWeight(); });
    $('weight-entry-form')?.addEventListener('submit', async event => {
        event.preventDefault(); if (state.busy || state.loading || !state.data) return;
        if (!state.weightVersion || state.failedWrites.has('weight-entry')) { status($('weight-entry-status'), '请先读取最新记录，核对后再保存。', true); return; }
        const form = event.currentTarget, date = form.elements.date.value, weightKg = Number(form.elements.weightKg.value), sourceLabel = form.elements.sourceLabel.value, notes = form.elements.notes.value;
        const old = state.weightRecords.find(r => r.date === date);
        if (old && !confirm(`${date} 已记录 ${num(old.weightKg, 2)} kg，确定更新为 ${num(weightKg, 2)} kg？`)) return;
        state.busy = true; ++state.generation; lock(); status($('weight-entry-status'), '正在保存并核验…');
        try {
            const saved = await write('weight/' + date, 'PUT', {version: state.weightVersion, weightKg, source: 'manual', sourceLabel, notes});
            const confirmed = await api('weight');
            if (saved.version !== confirmed.version) throw new Error('保存后体重记录又发生变化，请读取最新记录核对。');
            state.weightRecords = confirmed.records; state.weightVersion = confirmed.version; state.drafts.delete('weight-entry');
            form.reset(); form.elements.date.value = state.data.today; await load(true); showExistingWeight(); status($('weight-entry-status'), '已保存并核验。');
        } catch (error) { state.failedWrites.add('weight-entry'); status($('weight-entry-status'), error.message + ' 草稿已保留，请读取最新记录后核对，未自动重试。', true); }
        finally { state.busy = false; lock(); }
    });

    async function load(afterWrite = false) {
        if (state.busy && !afterWrite) return;
        const generation = ++state.generation; state.loading = true; lock(); status($('data-status'), '正在读取数据…');
        const query = range.elements.from.value && range.elements.to.value ? '?' + new URLSearchParams({from: range.elements.from.value, to: range.elements.to.value}) : '';
        try {
            const data = await api('workspace' + query); if (generation !== state.generation) return;
            state.data = data; range.elements.from.value = data.from; range.elements.to.value = data.to;
            $('data-zone').textContent = `${data.from} 至 ${data.to} · ${data.timezone} · 读取时间 ` + new Intl.DateTimeFormat('zh-CN', {timeZone: data.timezone, dateStyle: 'short', timeStyle: 'short'}).format(new Date(data.generatedAt));
            document.querySelectorAll('.data-form [type=date]').forEach(el => { if (!el.value && !state.drafts.has(el.form.id.replace('-form', ''))) el.value = el.name === 'endDate' ? shiftDay(data.today, 60) : data.today; });
            if ($('nutrition-date')) { const input = $('nutrition-date'); input.min = data.from; input.max = data.to; if (!validDate(input.value) || input.value < data.from || input.value > data.to) input.value = data.today >= data.from && data.today <= data.to ? data.today : data.to; state.selectedDay = input.value; }
            app.dataset.view === 'nutrition' ? renderNutrition() : renderWeight();
            updateRangeState();
            status($('data-status'), '');
        } catch (e) { if (generation === state.generation) status($('data-status'), e.message + ' 当前内容未刷新，保留上次读取的记录。', true); }
        finally { if (generation === state.generation) { state.loading = false; lock(); } }
    }
    function connectForm(prefix, resource, payload) {
        const form = $(prefix + '-form'); if (!form) return;
        const reset = () => { form.reset(); state.edits[prefix] = null; state.drafts.delete(prefix); state.failedWrites.delete(prefix); for (const el of form.querySelectorAll('[type=date]')) el.value = el.name === 'endDate' ? shiftDay(state.data.today, 60) : prefix === 'meal' ? $('nutrition-date').value : state.data.today; $(prefix + '-status').textContent = ''; };
        $(prefix + '-reset').addEventListener('click', () => { if (state.data && !state.busy && (!state.drafts.has(prefix) || confirm('清空当前未保存的内容，开始新记录？'))) reset(); });
        form.addEventListener('input', () => { state.drafts.add(prefix); });
        form.addEventListener('submit', async e => {
            e.preventDefault(); if (state.busy || state.loading || !state.data) return;
            if (state.failedWrites.has(prefix)) { $(prefix + '-status').textContent = '上次写入结果需要核对。请刷新后选中最新记录继续编辑；新建记录请先核对明细，避免重复。'; return; }
            const editState = state.edits[prefix] ||= {id: crypto.randomUUID(), version: 0};
            state.busy = true; ++state.generation; lock(); status($(prefix + '-status'), '正在保存…');
            try {
                const saved = await write(resource + '/' + editState.id, 'PUT', {version: editState.version, ...payload(form)});
                const confirmed = await api(resource + '/' + editState.id);
                if (saved.version !== confirmed.version) throw new Error('保存后记录又发生变化，请刷新核对。');
                reset(); await load(true); status($(prefix + '-status'), '已保存并核验。');
            } catch (err) { state.failedWrites.add(prefix); status($(prefix + '-status'), err.message + '\n草稿已保留。刷新后选中最新记录核对；不会自动重试。', true); }
            finally { state.busy = false; lock(); }
        });
    }
    connectForm('meal', 'meals', f => ({date: f.elements.date.value, mealType: f.elements.mealType.value, foods: f.elements.foods.value, portion: f.elements.portion.value, nutrients: Object.fromEntries(Object.keys(nutrientNames).map(k => [k, formNumber(f, k)])), nutritionSource: f.elements.nutritionSource.value, sourceLabel: f.elements.sourceLabel.value, notes: f.elements.notes.value, source: 'user'}));
    connectForm('goal', 'weight-goals', f => ({title: f.elements.title.value, startDate: f.elements.startDate.value, endDate: f.elements.endDate.value, startWeightKg: formNumber(f, 'startWeightKg'), targetWeightKg: formNumber(f, 'targetWeightKg'), status: f.elements.status.value, notes: f.elements.notes.value, source: 'user'}));
    connectForm('measurement', 'measurements', f => ({date: f.elements.date.value, waistCm: formNumber(f, 'waistCm'), bodyFatPercent: formNumber(f, 'bodyFatPercent'), sourceLabel: f.elements.sourceLabel.value, notes: f.elements.notes.value, source: 'user'}));
    $('nutrition-date')?.addEventListener('change', () => { if (state.data) selectDay($('nutrition-date').value); });
    $('nutrition-prev')?.addEventListener('click', () => selectDay(shiftDay($('nutrition-date').value, -1)));
    $('nutrition-next')?.addEventListener('click', () => selectDay(shiftDay($('nutrition-date').value, 1)));
    $('nutrition-show-empty')?.addEventListener('change', () => renderNutrition(false));
    $('data-refresh').addEventListener('click', () => load());
    range.addEventListener('submit', e => { e.preventDefault(); load(); });
    window.addEventListener('beforeunload', e => { if (state.drafts.size) { e.preventDefault(); e.returnValue = ''; } });
    load();
})();
