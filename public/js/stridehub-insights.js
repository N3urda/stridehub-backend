(() => {
    'use strict';
    const app = document.getElementById('insights-app'); if (!app) return;
    const {node, svg, request, dateInZone, shiftDay, num, minutes, time, status, metric, bars, stages, calendar} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.base.replace(/\/$/, '');
    const state = {days: 30, generation: 0, zone: 'Asia/Shanghai', today: '', from: '', syncing: false, sleepRecords: [], dailyRecords: []};
    const api = route => request(base + route);
    const newest = (rows, predicate = () => true) => rows.filter(predicate).sort((a, b) => String(b.date || b.measuredAt).localeCompare(String(a.date || a.measuredAt)))[0];
    const errors = new Map();
    function sectionFailure(id, message) { $(id).replaceChildren(node('p', 'empty-note', message)); }
    function renderSleep(records) {
        state.sleepRecords = records; renderCoverage();
        const sleep = newest(records); const duration = $('latest-sleep-duration'); duration.replaceChildren();
        if (sleep) {
            $('latest-sleep-date').textContent = `${sleep.date} · 最近一晚已记录睡眠`;
            if (Number.isFinite(sleep.totalMinutes)) duration.append(document.createTextNode(Math.floor(sleep.totalMinutes / 60)), node('span', '', '小时'), document.createTextNode(Math.round(sleep.totalMinutes % 60)), node('span', '', '分'));
            else duration.append(node('span', '', '总时长未提供'));
            $('latest-sleep-window').textContent = `${time(sleep.startedAt, state.zone)} 入睡 · ${time(sleep.endedAt, state.zone)} 醒来`;
        } else { duration.append(document.createTextNode('—'), node('span', '', '尚无记录')); $('latest-sleep-date').textContent = '这段时间还没有睡眠记录'; $('latest-sleep-window').textContent = '手表同步到 Zepp 后，在这里同步即可查看。'; }
        stages($('sleep-stage-bar'), $('sleep-stage-legend'), sleep);
        const values = records.filter(r => Number.isFinite(r.totalMinutes));
        $('sleep-average').textContent = values.length ? `${values.length} 晚 · 平均 ${minutes(Math.round(values.reduce((n, r) => n + r.totalMinutes, 0) / values.length))}` : '缺失的夜晚保持空白';
        bars($('overview-sleep-chart'), calendar(state.from, state.today, records, 'hours', r => Number.isFinite(r.totalMinutes) ? r.totalMinutes / 60 : null), 'hours', {color: '#9c9de8', unit: ' 小时', label: '睡眠时长趋势，未记录的夜晚留空'});
    }
    function renderDaily(records) {
        state.dailyRecords = records; renderCoverage();
        const active = newest(records, r => r.activity?.steps != null), target = $('activity-metrics'); target.replaceChildren();
        metric(target, '步数', num(active?.activity?.steps), '步', active?.date || '尚未同步');
        metric(target, '活动热量', num(active?.activity?.caloriesKcal), 'kcal', active ? '设备记录' : '未提供');
        $('activity-source').textContent = active?.date || 'Zepp';
        bars($('steps-chart'), calendar(state.from, state.today, records, 'steps', r => r.activity?.steps), 'steps', {color: '#66b9a6', unit: ' 步', label: '每天步数，空缺表示未提供'});
        $('activity-caption').textContent = active ? `最近记录 ${active.date}，不等同于今天的实时步数。` : '同步后显示手表记录的步数和活动热量。';
        const hr = newest(records, r => r.heartRate?.restingBpm != null || r.heartRate?.averageBpm != null);
        const oxygen = newest(records, r => r.oxygen?.averagePercent != null), stress = newest(records, r => r.stress?.average != null);
        const vitals = $('vital-metrics'); vitals.replaceChildren();
        metric(vitals, hr?.heartRate?.restingBpm != null ? '静息心率' : '日平均心率', num(hr?.heartRate?.restingBpm ?? hr?.heartRate?.averageBpm), 'bpm', hr?.date || '未提供');
        metric(vitals, '平均血氧', num(oxygen?.oxygen?.averagePercent, 1), '%', oxygen?.date || '未提供');
        metric(vitals, '压力', num(stress?.stress?.average), '', stress?.date || '未提供');
        $('vitals-caption').textContent = hr?.heartRate?.sampleCount ? `心率日记录包含 ${num(hr.heartRate.sampleCount)} 个有效采样；各指标日期可能不同。` : '设备没有提供的指标保持未知，不补零或生成健康评分。';
    }
    function renderCoverage() {
        const records = state.dailyRecords, sleeps = state.sleepRecords;
        const days = new Set([...records.map(r => r.date), ...sleeps.map(r => r.date)]);
        $('data-day-count').textContent = `${days.size} 天已存记录`;
        const names = {sleep: '睡眠', activity: '日常活动', heartRate: '心率', oxygen: '血氧', stress: '压力', body: '身体成分'};
        $('data-coverage').replaceChildren(...Object.entries(names).map(([key, label]) => { const dates = new Set(records.filter(r => r.coverage?.[key] === 'available').map(r => r.date)); if (key === 'sleep') sleeps.forEach(r => dates.add(r.date)); return node('div', 'coverage-item' + (dates.size ? '' : ' missing'), `${label} · ${dates.size ? dates.size + ' 天' : '未提供'}`); }));
    }
    function renderRunning(results) {
        const imported = results[0], cloud = results[1].records;
        const hasCloud = cloud.length > 0;
        const data = hasCloud ? {summary: {distanceKm: cloud.some(r => r.distanceMeters != null) ? cloud.filter(r => r.distanceMeters != null).reduce((n,r) => n + r.distanceMeters / 1000, 0) : null, count: cloud.length, movingSeconds: cloud.some(r => r.durationSeconds != null) ? cloud.filter(r => r.durationSeconds != null).reduce((n,r) => n + r.durationSeconds, 0) : null}, activities: {items: cloud.map(r => ({id:r.id,name:r.sportName || '运动',startAt:r.startedAt,distanceKm:r.distanceMeters == null ? null : r.distanceMeters / 1000,movingSeconds:r.durationSeconds}))}} : imported;
        $('running-caption').textContent = hasCloud ? 'Zepp 云端运动 · 不与文件导入重复相加' : '这段时间已导入的跑步积累';
        const s = data.summary; $('running-metrics').replaceChildren();
        metric($('running-metrics'), hasCloud ? '累计运动' : '累计跑步', num(s.distanceKm, 1), 'km'); metric($('running-metrics'), '训练次数', num(s.count), '次'); metric($('running-metrics'), '运动时间', num(s.movingSeconds == null ? null : s.movingSeconds / 3600, 1), '小时');
        $('recent-activities').replaceChildren();
        for (const item of data.activities.items.slice(0, 3)) {
            const row = node('a', 'activity-row'); row.href = base + '/running?' + (hasCloud ? 'zeppWorkout=' : 'activityId=') + encodeURIComponent(item.id);
            const mark = node('span', 'activity-mark'); const icon = svg('svg', {viewBox: '0 0 24 24', 'aria-hidden': 'true'}); icon.append(svg('circle', {cx: 15, cy: 4, r: 2}), svg('path', {d: 'm8 8 4-2 4 5 4 1M12 6l-2 7 5 3-2 6M10 13l-4 4H2'})); mark.append(icon);
            const text = node('div'); text.append(node('strong', '', item.name), node('p', '', time(item.startAt, state.zone) + ' · ' + Math.round(item.movingSeconds / 60) + ' 分钟'));
            const distance = node('span', 'activity-distance', num(item.distanceKm, 2)); distance.append(node('small', '', ' km')); row.append(mark, text, distance); $('recent-activities').append(row);
        }
        if (!data.activities.items.length) $('recent-activities').append(node('p', 'empty-note', '这段时间还没有导入的跑步记录。Zepp 日常活动与运动文件分别保存。'));
    }
    function renderPlans(sessions, plans) {
        const sessionsNext = sessions.filter(s => !['cancelled', 'completed', 'skipped'].includes(s.status) && s.startAt && dateInZone(new Date(s.startAt), state.zone) >= state.today).slice(0, 2);
        const rows = sessionsNext.map(s => ({title: s.title, date: dateInZone(new Date(s.startAt), state.zone).slice(5, 10), text: `${s.durationMinutes ?? '—'} 分钟 · 训练课表`}));
        for (const p of plans.filter(p => p.status === 'active' && (!p.endDate || p.endDate >= state.today) && p.startDate <= state.today).slice(0, 2)) rows.push({title: p.title, date: '进行中', text: p.goal || p.sourceLabel || '生活计划'});
        $('overview-plans').replaceChildren();
        if (!rows.length) $('overview-plans').append(node('p', 'empty-note', '下一份计划，从了解你开始。\n让 AI 结合你的真实记录安排训练、减重或作息，保存后就会出现在这里。'));
        for (const row of rows.slice(0, 3)) { const el = node('div', 'plan-row'), text = node('div'); text.append(node('strong', '', row.title), node('p', '', row.text)); el.append(node('span', 'plan-time', row.date), text); $('overview-plans').append(el); }
    }
    function renderSync(data) {
        const descriptions = {syncing: '正在同步 Zepp，当前展示上次已保存的记录。', partial: '部分来源已同步，部分指标暂时无法读取。', no_data: '本次未返回有效数据，已有历史记录仍保留。', not_configured: '尚未连接 Zepp。请在服务端连接已有账号授权。', auth_required: 'Zepp 授权已过期，需要重新连接账号。', error: '本次同步失败，历史数据仍然保留。', empty: '已连接，但这段时间未返回数据。'};
        $('sync-top-status').textContent = data.state === 'syncing' ? 'Zepp 正在同步' : data.state === 'partial' ? 'Zepp 部分同步 · 部分指标缺失' : data.state === 'error' ? 'Zepp 同步失败 · 历史记录保留' : data.state === 'auth_required' ? 'Zepp 需要重新连接' : !data.configured ? 'Zepp 尚未连接' : data.lastSuccessAt ? '已同步 ' + time(data.lastSuccessAt, state.zone) : '等待首次同步';
        $('sync-top-status').classList.toggle('needs-attention', ['partial', 'error', 'auth_required', 'not_configured'].includes(data.state));
        const sourceLabels = {band: '睡眠、活动与心率', blood_oxygen: '血氧', all_day_stress: '压力', PaiHealthInfo: 'PAI', spo2: '血氧', stress: '压力', pai: 'PAI', readiness: '恢复状态', body: '身体成分'};
        $('sync-sources').replaceChildren(...Object.entries(data.sources || {}).map(([key, source]) => node('p', 'small subtle', `${sourceLabels[key] || key}：${source.message || ({success:'已读取', no_data:'未返回记录', auth_required:'Zepp 未允许访问', unavailable:'当前来源未提供'}[source.state]) || source.state}`)));
        $('sync-description').textContent = [descriptions[data.state] || (data.lastSuccessAt ? '最近成功同步 ' + time(data.lastSuccessAt, state.zone) : '已连接，等待首次同步。'), data.nextAction].filter(Boolean).join(' ');
        $('overview-sync').disabled = !data.configured || state.syncing || data.state === 'syncing';
    }
    async function load() {
        const generation = ++state.generation; errors.clear(); state.sleepRecords = []; state.dailyRecords = []; $('overview-refresh').disabled = true; status($('overview-status'), '正在读取记录…');
        try { const today = await api('/training/api/today'); if (generation !== state.generation) return; state.zone = today.timezone; state.today = today.date; }
        catch (e) { if (generation !== state.generation) return; errors.set('时区', e.message); state.today = dateInZone(new Date(), state.zone); }
        state.from = shiftDay(state.today, 1 - state.days);
        $('overview-date').textContent = new Intl.DateTimeFormat('zh-CN', {timeZone: state.zone, month: 'long', day: 'numeric', weekday: 'long'}).format(new Date());
        $('overview-range').textContent = `${state.from} 至 ${state.today} · ${state.zone}`;
        const query = `?from=${state.from}&to=${state.today}`;
        const jobs = [
            ['睡眠', api('/health/api/sleep' + query), renderSleep, 'overview-sleep-chart'],
            ['Zepp 日记录', api('/health/api/zepp/days' + query), data => renderDaily(data.records), 'activity-metrics'],
            ['运动', Promise.all([api('/running/api/overview' + query), api('/health/api/zepp/workouts' + query)]), renderRunning, 'running-metrics'],
            ['连接状态', api('/health/api/zepp/status'), renderSync, 'sync-description'],
            ['计划', Promise.all([api(`/training/api/sessions?from=${state.today}&to=${shiftDay(state.today, 30)}`), api('/health/api/plans')]), data => renderPlans(data[0].items, data[1].plans), 'overview-plans']
        ];
        await Promise.all(jobs.map(async ([label, promise, render, id]) => { try { const data = await promise; if (generation === state.generation) render(label === '睡眠' ? data.records : data); } catch (e) { if (generation === state.generation) { errors.set(label, e.message); sectionFailure(id, '暂时无法读取。请刷新后重试。'); if (label === '睡眠') { $('latest-sleep-date').textContent = '睡眠读取失败'; $('latest-sleep-duration').textContent = '—'; $('latest-sleep-window').textContent = e.message; } } } }));
        if (generation !== state.generation) return;
        $('overview-refresh').disabled = false;
        status($('overview-status'), errors.size ? [...errors].map(([key, value]) => key + '：' + value).join('\n') : '', !!errors.size);
    }
    document.querySelectorAll('[data-days]').forEach(button => button.addEventListener('click', () => { state.days = Number(button.dataset.days); document.querySelectorAll('[data-days]').forEach(b => { b.classList.toggle('selected', b === button); b.setAttribute('aria-pressed', String(b === button)); }); load(); }));
    $('overview-refresh').addEventListener('click', load);
    $('overview-sync').addEventListener('click', async () => {
        if (state.syncing) return; state.syncing = true; $('overview-sync').disabled = true; $('overview-sync').textContent = '正在从 Zepp 同步…'; status($('overview-status'), '正在读取云端记录，完成后会刷新本页。');
        try { const results = await Promise.allSettled(['/health/api/zepp/sync', '/health/api/zepp/workouts/sync'].map(path => request(base + path, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: JSON.stringify({days: state.days})}))); await load(); const failed = results.filter(r => r.status === 'rejected'); if (failed.length) status($('overview-status'), failed.map(r => r.reason.message).join(' ') + ' 其他已同步记录仍保留。', true); }
        catch (e) { status($('overview-status'), e.message + ' 历史记录仍保留；刷新可核对最新状态。', true); }
        finally { state.syncing = false; $('overview-sync').disabled = false; $('overview-sync').textContent = '同步 Zepp 数据'; }
    });
    load();
})();
