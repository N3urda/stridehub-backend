(() => {
    'use strict';
    const app = document.getElementById('plans-app'); if (!app) return;
    const {node, request, status, time, shiftDay, dateInZone, metric, num} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.base.replace(/\/$/, '');
    const state = {plans: [], sessions: [], category: 'all', zone: 'Asia/Shanghai', today: '', generation: 0, busy: false, blocked: false, loading: false};
    const categoryNames = {weight: '减重计划', sleep: '作息计划', nutrition: '饮食计划', recovery: '恢复计划', general: '生活计划'};
    const statuses = {draft: '草稿', active: '进行中', paused: '已暂停', completed: '已完成', cancelled: '已取消', planned: '已安排', skipped: '已跳过'};
    function render() {
        const stream = $('plan-stream'); stream.replaceChildren();
        if (['all', 'training'].includes(state.category)) {
            const card = node('section', 'hub-plan-card'), rows = state.sessions;
            card.append(node('h2', '', '训练日程'));
            const metrics = node('div', 'compact-metrics'), scheduled = rows.filter(r => !['cancelled', 'skipped'].includes(r.status) && r.type !== 'rest');
            metric(metrics, '计划课次', scheduled.length, '次');
            metric(metrics, '已完成', scheduled.filter(r => r.status === 'completed').length, '次');
            metric(metrics, '计划时间', num(scheduled.reduce((n, r) => n + r.durationMinutes, 0)), '分钟');
            const distances = scheduled.filter(r => r.distanceKm != null);
            metric(metrics, '已设距离', distances.length ? num(distances.reduce((n, r) => n + r.distanceKm, 0), 1) : '—', 'km', `${distances.length} 节设置距离`);
            card.append(metrics);
            const calendar = node('div', 'training-calendar'), range = $('plans-range');
            const types = {easy: '轻松跑', long: '长距离', tempo: '节奏跑', interval: '间歇', recovery: '恢复跑', race: '比赛', rest: '休息'};
            if (range.elements.from.value && range.elements.to.value) for (let date = range.elements.from.value; date <= range.elements.to.value; date = shiftDay(date, 1)) {
                const day = node('div', 'training-day'); day.append(node('strong', '', date));
                const sessions = rows.filter(r => dateInZone(new Date(r.startAt), state.zone) === date);
                if (!sessions.length) day.append(node('p', 'subtle small', '无安排'));
                for (const s of sessions) {
                    const item = node('div', 'training-event'); item.append(node('strong', '', s.title), node('div', '', `${types[s.type]} · ${statuses[s.status]}`), node('div', '', `${time(s.startAt, state.zone)} · ${s.durationMinutes} 分钟${s.distanceKm == null ? '' : ' · ' + s.distanceKm + ' km'}`));
                    s.steps.forEach(step => item.append(node('div', '', `${step.kind} · ${step.minutes} 分钟${step.distanceKm == null ? '' : ' / ' + step.distanceKm + ' km'}${step.target ? ' · ' + step.target : ''}`)));
                    if (s.feedback?.rpe != null) item.append(node('div', '', `实际体感 RPE ${s.feedback.rpe}`));
                    if (s.notes) item.append(node('div', '', s.notes));
                    const link = node('a', '', '执行与对账'); link.href = base + '/training/today?sessionId=' + encodeURIComponent(s.id); item.append(link); day.append(item);
                }
                calendar.append(day);
            }
            card.append(calendar); stream.append(card);
        }
        const plans = state.plans.filter(p => state.category === 'all' || p.category === state.category || (state.category === 'other' && !['weight', 'sleep'].includes(p.category)));
        for (const plan of plans) {
            const card = node('section', 'hub-plan-card'); card.dataset.planId = plan.id;
            card.append(node('span', 'plan-type', `${categoryNames[plan.category] || '生活计划'} · ${plan.sourceLabel || ({ai: 'AI 制定', user: '自己记录', clinician: '专业人员'}[plan.source])}`));
            const head = node('div', 'plan-head'); head.append(node('h2', '', plan.title), node('span', 'plan-badge', statuses[plan.status] || plan.status)); card.append(head);
            card.append(node('p', 'subtle small', `${plan.startDate} 至 ${plan.endDate || '持续记录'} · 更新于 ${time(plan.updatedAt, state.zone)}`), node('p', 'plan-goal', plan.goal));
            if (plan.rationale) card.append(node('p', 'plan-rationale', '制定依据：' + plan.rationale));
            const list = node('ul', 'hub-plan-items');
            for (const entry of plan.items) {
                const li = node('li'), button = node('button', 'item-check' + (entry.status === 'completed' ? ' done' : ''), entry.status === 'completed' ? '✓' : entry.status === 'skipped' ? '−' : ''); button.type = 'button'; button.setAttribute('aria-label', (entry.status === 'completed' ? '撤销完成：' : '标记完成：') + entry.title); button.setAttribute('aria-pressed', String(entry.status === 'completed')); button.disabled = state.busy || state.blocked || state.loading || ['draft', 'paused', 'cancelled', 'completed'].includes(plan.status);
                button.addEventListener('click', () => toggle(plan, entry));
                const content = node('div', 'item-content'); content.append(node('strong', '', entry.title), node('p', '', [entry.date || '未指定日期', entry.time, entry.status === 'skipped' ? '已跳过' : '', entry.notes].filter(Boolean).join(' · '))); li.append(button, content); list.append(li);
            }
            card.append(list);
            if (plan.notes) { const details = node('details', 'data-details'); details.append(node('summary', '', '计划说明'), node('p', '', plan.notes)); card.append(details); }
            stream.append(card);
        }
        if (!plans.length && state.category !== 'training') { const empty = node('section', 'hub-plan-card'); empty.append(node('h2', '', state.category === 'all' ? '尚无生活计划' : '这个分类还没有计划'), node('p', 'empty-note', '已保存的生活计划将在这里显示；饮食与减重的量化目标可在对应页面查看。')); stream.append(empty); }
    }
    async function toggle(plan, entry) {
        if (state.busy || state.blocked || state.loading) return; ++state.generation; state.busy = true; render(); status($('plans-status'), '正在保存完成状态…');
        const desired = entry.status === 'completed' ? 'pending' : 'completed';
        try {
            const items = plan.items.map(i => i.id === entry.id ? {...i, status: desired} : i);
            const saved = await request(base + '/health/api/plans/' + encodeURIComponent(plan.id), {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: JSON.stringify({version: plan.version, items})});
            const confirmed = await request(base + '/health/api/plans/' + encodeURIComponent(plan.id));
            if (saved.version !== confirmed.version || confirmed.items.find(i => i.id === entry.id)?.status !== desired) throw new Error('计划又发生了变化，请刷新核对。');
            state.plans = state.plans.map(p => p.id === plan.id ? confirmed : p); status($('plans-status'), '已保存并核验。');
        } catch (error) { state.blocked = true; status($('plans-status'), error.message + ' 请刷新读取最新计划，再核对完成状态；本次操作不会自动重试。', true); }
        finally { state.busy = false; render(); }
    }
    async function load() {
        if (state.busy) return; const generation = ++state.generation; state.loading = true; render(); $('plans-refresh').disabled = true; status($('plans-status'), '正在读取计划…');
        try {
            const today = await request(base + '/training/api/today'); if (generation !== state.generation) return; state.zone = today.timezone; state.today = today.date;
            const range = $('plans-range');
            if (!range.elements.from.value) { range.elements.from.value = state.today; range.elements.to.value = shiftDay(state.today, 6); }
            if (range.elements.from.value > range.elements.to.value || (new Date(range.elements.to.value) - new Date(range.elements.from.value)) / 86400000 > 92) throw new Error('请选择最多 93 天的有效范围。');
            const results = await Promise.allSettled([request(base + '/health/api/plans'), request(base + '/training/api/sessions?' + new URLSearchParams({from: range.elements.from.value, to: range.elements.to.value}))]);
            if (generation !== state.generation) return;
            const failures = []; if (results[0].status === 'fulfilled') state.plans = results[0].value.plans; else failures.push('生活计划：' + results[0].reason.message);
            if (results[1].status === 'fulfilled') state.sessions = results[1].value.items; else failures.push('训练日程：' + results[1].reason.message);
            state.blocked = failures.length > 0; render(); status($('plans-status'), failures.length ? failures.join('\n') + '\n保留上次成功显示的内容，请刷新后再操作。' : '', !!failures.length);
        } catch (e) { if (generation === state.generation) { state.blocked = true; status($('plans-status'), e.message, true); } }
        finally { if (generation === state.generation) { state.loading = false; $('plans-refresh').disabled = false; render(); } }
    }
    $('plans-refresh').addEventListener('click', load);
    document.querySelectorAll('[data-category]').forEach(button => button.addEventListener('click', () => { state.category = button.dataset.category; document.querySelectorAll('[data-category]').forEach(b => { b.classList.toggle('selected', b === button); b.setAttribute('aria-pressed', String(b === button)); }); render(); }));
    $('plans-range').addEventListener('submit', e => { e.preventDefault(); load(); });
    load();
})();
