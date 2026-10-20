(() => {
    'use strict';
    const app = document.getElementById('plans-app'); if (!app) return;
    const {node, request, status, time, shiftDay} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.base.replace(/\/$/, '');
    const state = {plans: [], sessions: [], category: 'all', zone: 'Asia/Shanghai', today: '', generation: 0, busy: false, blocked: false, loading: false};
    const categoryNames = {weight: '减重计划', sleep: '作息计划', nutrition: '饮食计划', recovery: '恢复计划', general: '生活计划'};
    const statuses = {draft: '草稿', active: '进行中', paused: '已暂停', completed: '已完成', cancelled: '已取消', planned: '已安排', skipped: '已跳过'};
    function render() {
        const stream = $('plan-stream'); stream.replaceChildren();
        if (['all', 'training'].includes(state.category)) {
            const card = node('section', 'hub-plan-card'); const heading = node('div', 'plan-head'); heading.append(node('h2', '', '我的训练日程'), node('span', 'plan-badge', '未来 30 天')); card.append(heading);
            const rows = state.sessions;
            if (!rows.length) card.append(node('p', 'empty-note', '还没有已保存的训练安排。让 AI 结合近期记录制定课表，保存后会显示日期、时长和具体训练内容。'));
            const list = node('ul', 'hub-plan-items');
            for (const session of rows) {
                const item = node('li'), content = node('div', 'item-content'); content.append(node('strong', '', session.title), node('p', '', `${time(session.startAt, state.zone)} · ${session.durationMinutes} 分钟 · ${statuses[session.status] || session.status}`));
                if (session.notes) content.append(node('p', '', session.notes));
                const label = node('span', 'item-check' + (session.status === 'completed' ? ' done' : ''), session.status === 'completed' ? '✓' : ''); label.setAttribute('aria-hidden', 'true'); item.append(label, content); list.append(item);
            }
            card.append(list); const link = node('a', 'text-link', '查看训练与跑后反馈'); link.href = base + '/training/today'; card.append(link); stream.append(card);
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
        if (!plans.length && state.category !== 'training') { const empty = node('section', 'hub-plan-card'); empty.append(node('h2', '', state.category === 'all' ? '生活计划，等待你的下一个目标。' : '这个分类还没有计划'), node('p', 'empty-note', '睡得规律一点，或慢慢接近理想体重。告诉 AI 你想改变什么，它保存的目标、依据和每日安排会在这里展示。')); stream.append(empty); }
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
            const today = await request(base + '/training/api/today'); state.zone = today.timezone; state.today = today.date;
            const results = await Promise.allSettled([request(base + '/health/api/plans'), request(base + '/training/api/sessions?from=' + state.today + '&to=' + shiftDay(state.today, 30))]);
            if (generation !== state.generation) return;
            const failures = []; if (results[0].status === 'fulfilled') state.plans = results[0].value.plans; else failures.push('生活计划：' + results[0].reason.message);
            if (results[1].status === 'fulfilled') state.sessions = results[1].value.items; else failures.push('训练日程：' + results[1].reason.message);
            state.blocked = failures.length > 0; render(); status($('plans-status'), failures.length ? failures.join('\n') + '\n保留上次成功显示的内容，请刷新后再操作。' : '', !!failures.length);
        } catch (e) { status($('plans-status'), e.message, true); }
        finally { if (generation === state.generation) { state.loading = false; $('plans-refresh').disabled = false; render(); } }
    }
    $('plans-refresh').addEventListener('click', load);
    document.querySelectorAll('[data-category]').forEach(button => button.addEventListener('click', () => { state.category = button.dataset.category; document.querySelectorAll('[data-category]').forEach(b => { b.classList.toggle('selected', b === button); b.setAttribute('aria-pressed', String(b === button)); }); render(); }));
    $('copy-handoff').addEventListener('click', async () => { try { await navigator.clipboard.writeText($('handoff-prompt').textContent); $('copy-status').textContent = '已复制，粘贴到连接了 StrideHub 的 Codex 中即可。'; } catch { $('copy-status').textContent = '浏览器未允许复制，请选中上面的文字手动复制。'; } });
    load();
})();
