(() => {
    'use strict';
    const app = document.getElementById('plans-app'); if (!app) return;
    const {node, request, status, time, shiftDay, dateInZone, metric, num} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.base.replace(/\/$/, '');
    const state = {plans: [], sessions: [], workspace: null, from: '', to: '', category: 'all', zone: 'Asia/Shanghai', today: '', generation: 0, busy: false, blocked: false, loading: false};
    const categoryNames = {weight: '减重计划', sleep: '作息计划', nutrition: '饮食计划', recovery: '恢复计划', general: '生活计划'};
    const statuses = {draft: '草稿', active: '进行中', paused: '已暂停', completed: '已完成', cancelled: '已取消', planned: '已安排', skipped: '已跳过'};
    function render() {
        const stream = $('plan-stream'); stream.replaceChildren();
        if (['all', 'training', 'nutrition'].includes(state.category) && state.workspace) {
            const card = node('section', 'hub-plan-card agenda-card'), rows = state.sessions;
            const showTraining = state.category !== 'nutrition', showNutrition = state.category !== 'training';
            const heading = node('div', 'section-title'); heading.append(node('h2', '', state.category === 'all' ? '每日训练与饮食' : showTraining ? '训练日程' : '饮食日程'), node('span', 'subtle small', `${state.from} → ${state.to} · ${state.zone}`)); card.append(heading);
            const calendar = node('div', 'training-calendar'), types = {easy: '轻松跑', long: '长距离', tempo: '节奏跑', interval: '间歇', recovery: '恢复跑', race: '比赛', rest: '休息'};
            for (let date = state.from; date <= state.to; date = shiftDay(date, 1)) {
                const day = node('article', 'training-day' + (date === state.today ? ' is-today' : ''));
                const weekday = new Intl.DateTimeFormat('zh-CN', {weekday: 'short', timeZone: 'UTC'}).format(new Date(date + 'T12:00:00Z'));
                const header = node('div', 'agenda-day-heading'); header.append(node('strong', '', date.slice(5)), node('span', '', date === state.today ? '今天' : weekday)); day.append(header);
                const sessions = showTraining ? rows.filter(r => dateInZone(new Date(r.startAt), state.zone) === date) : [];
                for (const s of sessions) {
                    const item = node('div', 'training-event agenda-training ' + s.status), badge = node('span', 'agenda-status', statuses[s.status]);
                    item.append(badge, node('strong', '', s.title), node('div', '', `${types[s.type]} · ${s.durationMinutes} 分钟${s.distanceKm == null ? '' : ' · ' + s.distanceKm + ' km'}`), node('div', 'subtle', new Intl.DateTimeFormat('zh-CN', {timeZone: state.zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(s.startAt))));
                    if (s.steps.length || s.notes) { const details = node('details', 'agenda-details'); details.append(node('summary', '', '训练安排')); s.steps.forEach(step => details.append(node('p', '', `${step.kind} · ${step.minutes} 分钟${step.distanceKm == null ? '' : ' / ' + step.distanceKm + ' km'}${step.target ? ' · ' + step.target : ''}`))); if (s.notes) details.append(node('p', '', s.notes)); item.append(details); }
                    if (s.feedback?.rpe != null) item.append(node('div', 'actual-feedback', `实际体感 RPE ${s.feedback.rpe}`));
                    const link = node('a', '', '执行与对账 →'); link.href = base + '/training/today?sessionId=' + encodeURIComponent(s.id); item.append(link); day.append(item);
                }
                const plans = showNutrition ? state.workspace.nutrition.plans.filter(p => p.date === date) : [];
                for (const plan of plans) {
                    const item = node('div', 'training-event agenda-nutrition'); item.append(node('span', 'agenda-status', plan.status === 'active' ? '饮食计划' : '饮食 · ' + statuses[plan.status]), node('strong', '', `${num(plan.targets.energyKcal)} kcal`), node('div', '', `蛋白质目标 ${num(plan.targets.proteinGrams, 1)} g`));
                    if (plan.meals.length) item.append(node('p', 'subtle', `${plan.meals.length} 餐已安排`));
                    const link = node('a', '', '查看餐食 →'); link.href = base + '/nutrition?' + new URLSearchParams({from: state.from, to: state.to, date}); item.append(link); day.append(item);
                }
                const actual = state.workspace.nutrition.days.find(d => d.date === date);
                if (showNutrition && actual?.mealCount > 0) day.append(node('p', 'agenda-actual', `已记录 ${actual.mealCount} 餐 · ${num(actual.actualLogged.energyKcal)} kcal`));
                if (!sessions.length && !plans.length && !(showNutrition && actual?.mealCount > 0)) day.append(node('p', 'agenda-empty', '暂无安排'));
                calendar.append(day);
            }
            card.append(calendar); stream.append(card);
        }
        if (['all', 'weight'].includes(state.category) && state.workspace) {
            const goals = state.workspace.weight.goals.filter(g => g.startDate <= state.to && g.endDate >= state.from);
            if (!goals.length && state.category === 'weight') { const empty = node('section', 'hub-plan-card'); empty.append(node('h2', '', '所选日期还没有减重目标'), node('p', 'empty-note', '设置目标后，可在这里对照最近实测和阶段安排。')); const link = node('a', 'text-link', '查看减重页面 →'); link.href = base + '/weight'; empty.append(link); stream.append(empty); }
            goals.forEach(g => { const card = node('section', 'hub-plan-card agenda-goal'); card.dataset.weightGoalId = g.id; const head = node('div', 'plan-head'); head.append(node('h2', '', g.title), node('span', 'plan-badge', statuses[g.status])); card.append(node('span', 'plan-type', '减重目标'), head, node('p', 'subtle small', `${g.startDate} → ${g.endDate}`));
                card.append(node('p', 'plan-goal', `${num(g.startWeightKg, 2)} → ${num(g.targetWeightKg, 2)} kg`)); const p = g.progress;
                card.append(node('p', 'subtle small', p.measuredAt ? `截至 ${p.measuredAt} 实测 ${num(p.currentWeightKg, 2)} kg · 距目标 ${num(p.remainingKg, 2)} kg` : '目标期间尚无实测体重'));
                g.milestones.filter(m => m.date >= state.from && m.date <= state.to).forEach(m => card.append(node('p', 'agenda-milestone', `${m.date} 阶段目标 · ${num(m.weightKg, 2)} kg`)));
                const link = node('a', 'text-link', '查看趋势与目标 →'); link.href = base + '/weight'; card.append(link); stream.append(card);
            });
        }
        const plans = state.plans.filter(p => state.category === 'all' || p.category === state.category || (state.category === 'other' && !['weight', 'sleep', 'nutrition'].includes(p.category)));
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
        if (!plans.length && ['sleep', 'other'].includes(state.category)) { const empty = node('section', 'hub-plan-card'); empty.append(node('h2', '', state.category === 'all' ? '尚无生活计划' : '这个分类还没有计划'), node('p', 'empty-note', '已保存的生活计划将在这里显示；饮食与减重的量化目标可在对应页面查看。')); stream.append(empty); }
    }
    async function toggle(plan, entry) {
        if (state.busy || state.blocked || state.loading) return; ++state.generation; state.busy = true; lockRange(); render(); status($('plans-status'), '正在保存完成状态…');
        const desired = entry.status === 'completed' ? 'pending' : 'completed';
        try {
            const items = plan.items.map(i => i.id === entry.id ? {...i, status: desired} : i);
            const saved = await request(base + '/health/api/plans/' + encodeURIComponent(plan.id), {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: JSON.stringify({version: plan.version, items})});
            const confirmed = await request(base + '/health/api/plans/' + encodeURIComponent(plan.id));
            if (saved.version !== confirmed.version || confirmed.items.find(i => i.id === entry.id)?.status !== desired) throw new Error('计划又发生了变化，请刷新核对。');
            state.plans = state.plans.map(p => p.id === plan.id ? confirmed : p); status($('plans-status'), '已保存并核验。');
        } catch (error) { state.blocked = true; status($('plans-status'), error.message + ' 请刷新读取最新计划，再核对完成状态；本次操作不会自动重试。', true); }
        finally { state.busy = false; lockRange(); render(); }
    }
    function lockRange() {
        document.querySelectorAll('#plans-range input,#plans-range button,#plans-prev,#plans-next,#plans-current,#plans-refresh').forEach(el => { el.disabled = state.busy || state.loading; });
    }
    function summary() {
        const target = $('plans-summary'); target.replaceChildren(); if (!state.workspace) return;
        const planned = state.sessions.filter(s => s.type !== 'rest' && !['cancelled', 'skipped'].includes(s.status));
        metric(target, '训练安排', planned.length, '次', `${planned.filter(s => s.status === 'completed').length} 次已完成`);
        metric(target, '计划训练时间', num(planned.reduce((n, s) => n + s.durationMinutes, 0)), '分钟', '计划值');
        metric(target, '已设饮食计划', state.workspace.nutrition.plans.filter(p => p.status === 'active').length, '天', '生效的每日计划');
        metric(target, '实际饮食记录', state.workspace.nutrition.meals.length, '餐', '不代表全天记录完整');
        $('plans-period').textContent = `${state.from} 至 ${state.to} · ${state.zone}`;
    }
    async function load() {
        if (state.busy || state.loading) return; const generation = ++state.generation; state.loading = true; lockRange(); render(); status($('plans-status'), '正在读取计划…');
        try {
            const range = $('plans-range');
            if (!range.elements.from.value) { const today = await request(base + '/training/api/today'); state.zone = today.timezone; state.today = today.date; range.elements.from.value = state.today; range.elements.to.value = shiftDay(state.today, 6); }
            const from = range.elements.from.value, to = range.elements.to.value;
            if (!from || !to || from > to || (new Date(to) - new Date(from)) / 86400000 > 92) throw new Error('请选择最多 93 天的有效范围。');
            const workspace = await request(base + '/health/api/workspace?' + new URLSearchParams({from, to}));
            if (generation !== state.generation) return;
            state.workspace = workspace; state.sessions = workspace.sessions; state.plans = workspace.healthPlans.filter(p => p.startDate <= to && (!p.endDate || p.endDate >= from));
            state.zone = workspace.timezone; state.today = workspace.today; state.from = from; state.to = to; state.blocked = false;
            const url = new URL(location.href); url.searchParams.set('from', from); url.searchParams.set('to', to); url.searchParams.set('category', state.category); window.history.replaceState(null, '', url);
            summary(); render(); status($('plans-status'), '');
        } catch (e) { if (generation === state.generation) { state.blocked = true; status($('plans-status'), e.message + ' 页面保留上次成功读取的日期和内容。', true); } }
        finally { if (generation === state.generation) { state.loading = false; lockRange(); render(); } }
    }
    const query = new URL(location.href).searchParams, range = $('plans-range');
    const validDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
    if (validDate(query.get('from')) && validDate(query.get('to'))) { range.elements.from.value = query.get('from'); range.elements.to.value = query.get('to'); }
    if (['all', 'training', 'nutrition', 'weight', 'sleep', 'other'].includes(query.get('category'))) state.category = query.get('category');
    function selectCategory(category) { state.category = category; document.querySelectorAll('[data-category]').forEach(b => { b.classList.toggle('selected', b.dataset.category === category); b.setAttribute('aria-pressed', String(b.dataset.category === category)); }); const url = new URL(location.href); url.searchParams.set('category', category); window.history.replaceState(null, '', url); render(); }
    selectCategory(state.category);
    for (const [id, direction] of [['plans-prev', -1], ['plans-next', 1]]) $(id).addEventListener('click', () => { if (!state.workspace || state.loading || state.busy) return; const days = Math.round((Date.parse(state.to) - Date.parse(state.from)) / 86400000) + 1; range.elements.from.value = shiftDay(state.from, days * direction); range.elements.to.value = shiftDay(state.to, days * direction); load(); });
    $('plans-current').addEventListener('click', () => { if (!state.today || state.loading || state.busy) return; range.elements.from.value = state.today; range.elements.to.value = shiftDay(state.today, 6); load(); });
    $('plans-refresh').addEventListener('click', load);
    document.querySelectorAll('[data-category]').forEach(button => button.addEventListener('click', () => selectCategory(button.dataset.category)));
    $('plans-range').addEventListener('submit', e => { e.preventDefault(); load(); });
    load();
})();
