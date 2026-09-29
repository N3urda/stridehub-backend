(() => {
    'use strict';
    const root = document.querySelector('#running-app'); if (!root) return;
    const $ = id => document.getElementById(id), form = $('filters'), charts = new Map();
    let overview, detail, page = 1, overviewRequest, detailRequest;
    const fmt = (v, digits = 1) => v == null || !Number.isFinite(Number(v)) ? '未记录' : Number(v).toLocaleString('zh-CN', {maximumFractionDigits: digits});
    const pace = v => v == null || v <= 0 ? '未记录' : `${Math.floor(Math.round(v) / 60)}:${String(Math.round(v) % 60).padStart(2, '0')}`;
    const duration = s => s == null ? '未记录' : `${Math.floor(s / 3600) ? Math.floor(s / 3600) + ':' : ''}${String(Math.floor(s / 60) % 60).padStart(2, '0')}:${String(Math.round(s) % 60).padStart(2, '0')}`;
    const localDate = date => [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
    const el = (tag, text, className) => { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; };
    function notes(id, items) { $(id).replaceChildren(...items.map(text => el('li', text))); }
    function cards(id, items) { $(id).replaceChildren(...items.map(([label, value, foot]) => { const n = el('div', null, 'stat'); n.append(el('span', label), el('strong', value), el('small', foot)); return n; })); }
    async function get(path, signal) {
        const response = await fetch(root.dataset.api + path, {signal, headers: {Accept: 'application/json'}});
        if (response.redirected || response.headers.get('content-type')?.includes('text/html')) throw Error('登录已过期，请刷新页面重新登录。');
        const data = await response.json(); if (!response.ok) throw Error(data.message || '读取失败，请重试。'); return data;
    }
    const palette = ['#0b8774', '#dd844b', '#688aa4', '#a387be', '#bb5468'];
    function chart(id, options, hasData = true) {
        const node = $(id); charts.get(id)?.dispose(); charts.delete(id); node.replaceChildren(); node.classList.toggle('chart-empty', !hasData);
        if (!hasData) { node.textContent = '当前范围没有足够的记录'; return; }
        if (!window.echarts) { node.textContent = '图表组件未加载，请刷新页面'; return; }
        const c = echarts.init(node, null, {renderer: 'canvas'}); charts.set(id, c);
        c.setOption({color: palette, textStyle: {fontFamily: 'system-ui, sans-serif', color: '#526d64'}, aria: {enabled: true}, animation: false, tooltip: {trigger: 'axis', renderMode: 'richText', confine: true}, grid: {left: 54, right: 42, top: 40, bottom: 45}, legend: {top: 0, textStyle: {fontSize: 11}}, ...options});
    }
    function line(name, data, extra = {}) { return {name, type: 'line', data, showSymbol: false, connectNulls: false, ...extra}; }
    const axis = (name, extra = {}) => ({type: 'value', name, nameTextStyle: {fontSize: 10}, splitLine: {lineStyle: {color: '#edf0eb'}}, ...extra});
    function showOverview(data) {
        overview = data; const s = data.summary;
        $('overview').hidden = false; $('empty').hidden = s.count !== 0; $('overview-charts').hidden = s.count === 0;
        $('status').textContent = `${data.filters.from} — ${data.filters.to} · ${data.filters.timezone} · ${s.count} 次跑步${data.availableRange.from ? ` · 已导入记录始于 ${data.availableRange.from}` : ''}`;
        $('status').classList.remove('error');
        cards('summary', [['累计跑量', fmt(s.distanceKm), 'km'], ['跑步次数', fmt(s.count, 0), `${s.activeDays} 个运动日`], ['运动时间', fmt(s.movingSeconds / 3600), '小时'], ['平均配速', pace(s.paceSecondsPerKm), 'min/km · 距离加权'], ['最长单次', fmt(s.longestRunKm), 'km'], ['累计爬升', fmt(s.elevationM, 0), 'm']]);
        const w = data.weekly, m = data.monthly, weekAxis = {type: 'category', data: w.map(r => r.date), axisLabel: {formatter: v => v.slice(5)}};
        chart('weekly-chart', {xAxis: weekAxis, yAxis: [axis('km'), axis('次数', {minInterval: 1})], series: [{name: '周跑量 km', type: 'bar', data: w.map(r => r.distanceKm), barMaxWidth: 30}, line('跑步次数', w.map(r => r.count), {yAxisIndex: 1})], dataZoom: [{type: 'inside'}]}, s.count > 0);
        chart('monthly-chart', {xAxis: {type: 'category', data: m.map(r => r.date)}, yAxis: axis('km'), series: [{name: '月跑量 km', type: 'bar', barMaxWidth: 38, data: m.map(r => r.distanceKm)}], dataZoom: [{type: 'inside'}]}, s.count > 0);
        chart('long-chart', {xAxis: weekAxis, yAxis: [axis('km'), axis('小时')], series: [line('最长单次 km', w.map(r => r.count ? r.longestRunKm : null)), {name: '运动时间 h', type: 'bar', yAxisIndex: 1, barMaxWidth: 25, data: w.map(r => +(r.movingSeconds / 3600).toFixed(2)), itemStyle: {opacity: 0.5}}]}, s.count > 0);
        chart('trend-chart', {xAxis: weekAxis, yAxis: [axis('配速 /km', {inverse: true, scale: true, axisLabel: {formatter: pace}}), axis('bpm', {scale: true})], series: [line('平均配速', w.map(r => r.paceSecondsPerKm), {tooltip: {valueFormatter: pace}}), line('平均心率', w.map(r => r.averageHeartRate), {yAxisIndex: 1})]}, s.count > 0);
        $('calendar-caption').textContent = `${data.dailyFrom} — ${data.filters.to}，每格一天，颜色表示当日跑量。`;
        chart('calendar-chart', {tooltip: {position: 'top', renderMode: 'richText', formatter: p => `${p.value[0]}\n${fmt(p.value[1])} km`}, calendar: {range: [data.dailyFrom, data.filters.to], top: 35, left: 35, right: 15, cellSize: ['auto', 21], yearLabel: {show: false}, dayLabel: {firstDay: 1, nameMap: ['日', '一', '二', '三', '四', '五', '六']}, monthLabel: {nameMap: 'cn'}, splitLine: {show: false}, itemStyle: {color: '#f5f7f2', borderColor: '#fff', borderWidth: 3}}, visualMap: {min: 0, max: Math.max(1, ...data.daily.map(r => r.distanceKm)), calculable: false, orient: 'horizontal', bottom: 0, left: 'center', inRange: {color: ['#eaf0e6', '#9bd2b2', '#087f70']}}, series: [{type: 'heatmap', coordinateSystem: 'calendar', data: data.daily.map(r => [r.date, r.distanceKm])}]}, s.count > 0);
        $('bests').replaceChildren(...data.bestEfforts.map(r => { const n = el('button', null, 'best'); n.type = 'button'; n.append(el('span', `${fmt(r.distanceM / 1000, 3)} km`), el('strong', duration(r.seconds)), el('small', r.date)); n.addEventListener('click', () => loadDetail(r.activityId)); return n; }));
        if (!data.bestEfforts.length) $('bests').append(el('p', '暂无已计算的最佳片段。导入并处理包含时间/距离流的运动后可查看。', 'muted'));
        $('activities').replaceChildren(...data.activities.items.map(r => { const tr = el('tr'), name = el('td', r.name); name.append(el('small', r.startAt.slice(0, 10))); tr.append(name, ...[fmt(r.distanceKm), duration(r.movingSeconds), pace(r.paceSecondsPerKm), fmt(r.averageHeartRate, 0), fmt(r.elevationM, 0)].map(v => el('td', v))); const td = el('td'), b = el('button', '复盘', 'secondary'); b.type = 'button'; b.addEventListener('click', () => loadDetail(r.id)); td.append(b); tr.append(td); return tr; }));
        const pages = Math.max(1, Math.ceil(data.activities.total / data.activities.pageSize));
        $('page-label').textContent = `第 ${page} / ${pages} 页 · 共 ${data.activities.total} 条`; $('previous').disabled = page <= 1; $('next').disabled = page >= pages; $('export').disabled = !data.activities.items.length;
        notes('overview-notes', data.notes);
    }
    async function loadOverview() {
        overviewRequest?.abort(); overviewRequest = new AbortController();
        $('status').textContent = '正在读取全部匹配记录…'; $('status').classList.remove('error'); $('overview').hidden = true;
        const values = new FormData(form), query = new URLSearchParams({from: values.get('preset') === 'all' ? 'all' : values.get('from'), to: values.get('to'), sportType: values.get('sportType'), page: String(page)});
        try { showOverview(await get('/overview?' + query, overviewRequest.signal)); }
        catch (error) { if (error.name !== 'AbortError') { $('status').textContent = error.message; $('status').classList.add('error'); } }
    }
    function timeline() {
        if (!detail) return;
        const rows = detail.analysis.timeline, mode = $('timeline-axis').value;
        const defs = [['paceSecondsPerKm', '配速 /km', true], ['heartRate', '心率 bpm', false], ['cadenceSpm', '步频 spm', false], ['altitudeM', '海拔 m', false], ['powerW', '功率 W', false]].filter(([field]) => rows.some(r => r[field] != null));
        $('timeline-chart').style.height = Math.max(260, defs.length * 145 + 60) + 'px'; $('timeline-empty').hidden = defs.length > 0;
        const grids = defs.map((_, i) => ({left: 68, right: 25, top: 30 + i * 145, height: 100}));
        chart('timeline-chart', {legend: {show: false}, grid: grids, axisPointer: {link: [{xAxisIndex: 'all'}]}, xAxis: defs.map((_, i) => ({type: 'value', gridIndex: i, min: 'dataMin', max: 'dataMax', axisLabel: {formatter: mode === 'seconds' ? duration : v => fmt(v) + ' km'}, splitLine: {show: false}})), yAxis: defs.map(([, label, inverse], i) => axis(label, {gridIndex: i, inverse, scale: true, axisLabel: {formatter: inverse ? pace : v => fmt(v, 0)}})), dataZoom: [{type: 'slider', xAxisIndex: defs.map((_, i) => i), bottom: 0, height: 22, filterMode: 'none'}, {type: 'inside', xAxisIndex: defs.map((_, i) => i), filterMode: 'none'}], series: defs.map(([field, label], i) => line(label, rows.filter(r => r[mode] != null).map(r => [r[mode], r[field]]), {xAxisIndex: i, yAxisIndex: i, sampling: 'lttb', tooltip: {valueFormatter: v => field === 'paceSecondsPerKm' ? pace(v) : fmt(v)}, lineStyle: {width: 1.7}}))}, defs.length > 0);
    }
    function showDetail(data) {
        detail = data; const a = data.activity, analysis = data.analysis, c = analysis.coverage, metric = analysis.metrics;
        $('detail-title').textContent = a.name; $('detail-meta').textContent = `${a.startAt.slice(0, 16).replace('T', ' ')} · ${a.device || '设备未记录'} · ${fmt(a.distanceKm)} km`;
        $('native-detail').href = root.dataset.detailBase + '/' + encodeURIComponent(a.id);
        $('detail-status').textContent = ''; $('detail-content').hidden = false;
        cards('detail-stats', [['运动配速', pace(a.paceSecondsPerKm), 'min/km · 摘要'], ['有效平均心率', fmt(metric.weightedHeartRate, 0), 'bpm · 时间加权'], ['最快完整公里', pace(data.splitSummary.fastestFullKmPace), 'min/km · 公里分段'], ['分段配速变异', metricLabel(data.splitSummary.paceVariationPercent), 'CV · 仅 0.95–1.05 km 分段'], ['后半程配速变化', metricLabel(data.splitSummary.secondHalfPaceChangePercent), '等距离 · 负数表示后程更快'], ['前后半程效率变化', metricLabel(metric.efficiencyChangePercent), '速度/心率 · 不满足条件时留空']]);
        $('timeline-axis').options[1].disabled = !analysis.timeline.some(r => r.distanceKm != null); if ($('timeline-axis').selectedOptions[0].disabled) $('timeline-axis').value = 'seconds'; timeline();
        const splits = data.splits;
        chart('splits-chart', {xAxis: {type: 'category', data: splits.map(s => String(s.number))}, yAxis: axis('min/km', {inverse: true, axisLabel: {formatter: pace}}), series: [{name: '实际配速', type: 'bar', data: splits.map(s => s.paceSecondsPerKm), tooltip: {valueFormatter: pace}}, line('GAP', splits.map(s => s.gapPaceSecondsPerKm), {tooltip: {valueFormatter: pace}})]}, splits.length > 0);
        chart('scatter-chart', {tooltip: {trigger: 'item', renderMode: 'richText', formatter: p => `${pace(p.value[0])} /km\n${p.value[1]} bpm`}, xAxis: axis('配速 /km', {inverse: true, scale: true, axisLabel: {formatter: pace}}), yAxis: axis('bpm', {scale: true}), series: [{type: 'scatter', symbolSize: 4, itemStyle: {opacity: .4}, data: analysis.scatter.map(p => [p.paceSecondsPerKm, p.heartRate])}]}, analysis.scatter.length > 0);
        chart('zones-chart', {xAxis: {type: 'category', data: analysis.zones.map(z => `${z.name}\n${z.from}–${Math.min(250, z.to)}`)}, yAxis: axis('分钟'), series: [{name: '有效时长 min', type: 'bar', barMaxWidth: 45, data: analysis.zones.map(z => +(z.seconds / 60).toFixed(2)), itemStyle: {color: p => palette[p.dataIndex % palette.length]}}]}, analysis.zones.length > 0 && c.heartRateSeconds > 0);
        chart('hr-chart', {xAxis: {type: 'category', data: analysis.heartRateDistribution.map(z => `${z.from}–${z.to}`)}, yAxis: axis('分钟'), series: [{name: '有效时长 min', type: 'bar', data: analysis.heartRateDistribution.map(z => +(z.seconds / 60).toFixed(2))}]}, c.heartRateSeconds > 0);
        $('split-table').replaceChildren(...splits.map(s => { const tr = el('tr'); tr.append(...[s.number, fmt(s.distanceKm, 3), duration(s.movingSeconds), pace(s.paceSecondsPerKm), pace(s.gapPaceSecondsPerKm), fmt(s.elevationM)].map(v => el('td', v))); return tr; }));
        $('lap-table').replaceChildren(...data.laps.map(s => { const tr = el('tr'); tr.append(...[s.number, s.name, fmt(s.distanceKm, 3), duration(s.movingSeconds), fmt(s.averageHeartRate, 0)].map(v => el('td', v))); return tr; }));
        $('coverage').replaceChildren(...[`原始采样 ${c.samples} 点`, `有效时长 ${duration(c.analyzedSeconds)}`, `心率覆盖 ${c.analyzedSeconds ? fmt(c.heartRateSeconds / c.analyzedSeconds * 100) + '%' : '未记录'}`, `记录断档 ${duration(c.gapSeconds)}`, `显式暂停 ${duration(c.pausedSeconds)}`, `无效区间 ${c.invalidIntervals}`, `传感器流 ${data.availableStreams.length} 类`].map(v => el('span', v)));
        notes('detail-notes', analysis.notes);
    }
    function metricLabel(value) { return value == null ? '—' : `${value > 0 ? '+' : ''}${fmt(value)}%`; }
    async function loadDetail(id, scroll = true) {
        detailRequest?.abort(); detailRequest = new AbortController(); $('detail').hidden = false; $('detail-content').hidden = true; $('detail-title').textContent = '读取跑步记录'; $('detail-meta').textContent = ''; $('detail-status').textContent = '正在分析原始记录…'; $('detail-status').classList.remove('error');
        const url = new URL(location.href); url.searchParams.set('activityId', id); history.replaceState(null, '', url);
        try { showDetail(await get('/activities/' + encodeURIComponent(id), detailRequest.signal)); if (scroll) $('detail').scrollIntoView({behavior: 'smooth', block: 'start'}); }
        catch (error) { if (error.name !== 'AbortError') { $('detail-status').textContent = error.message; $('detail-status').classList.add('error'); } }
    }
    function preset() { const days = form.elements.preset.value, now = new Date(); form.elements.to.value = localDate(now); if (days !== 'custom') { now.setDate(now.getDate() - (days === 'all' ? 365 : Number(days) - 1)); form.elements.from.value = localDate(now); } form.elements.from.disabled = days === 'all'; }
    form.elements.preset.addEventListener('change', preset);
    [form.elements.from, form.elements.to].forEach(input => input.addEventListener('input', () => { form.elements.preset.value = 'custom'; form.elements.from.disabled = false; }));
    form.addEventListener('submit', event => { event.preventDefault(); page = 1; loadOverview(); });
    $('previous').addEventListener('click', () => { if (page > 1) { --page; loadOverview(); } }); $('next').addEventListener('click', () => { ++page; loadOverview(); });
    $('timeline-axis').addEventListener('change', timeline);
    $('export').addEventListener('click', () => {
        if (!overview) return;
        const escape = value => '"' + String(value ?? '').replace(/^[=+@-]/, v => "'" + v).replaceAll('"', '""') + '"';
        const rows = [['名称', '时间', '距离 km', '运动秒数', '配速秒/km', '平均心率 bpm'], ...overview.activities.items.map(a => [a.name, a.startAt, a.distanceKm, a.movingSeconds, a.paceSecondsPerKm, a.averageHeartRate])];
        const url = URL.createObjectURL(new Blob(['\ufeff' + rows.map(row => row.map(escape).join(',')).join('\r\n')], {type: 'text/csv;charset=utf-8'})), a = el('a'); a.href = url; a.download = `stridehub-running-page-${page}.csv`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    const resize = new ResizeObserver(entries => { for (const entry of entries) charts.get(entry.target.id)?.resize(); });
    document.querySelectorAll('.chart,.timeline').forEach(node => resize.observe(node));
    addEventListener('pagehide', () => { overviewRequest?.abort(); detailRequest?.abort(); resize.disconnect(); charts.forEach(c => c.dispose()); });
    preset(); const initialOverview = loadOverview(); const initialId = new URLSearchParams(location.search).get('activityId'); if (initialId) initialOverview.finally(() => loadDetail(initialId));
})();
