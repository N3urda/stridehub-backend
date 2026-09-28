(() => {
    'use strict';
    const app = document.getElementById('training-app');
    if (!app) return;
    const $ = (selector, scope = document) => scope.querySelector(selector);
    const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));
    const state = {failed: new Set(), profile: null, sessions: [], races: [], checkins: [], fuels: [], activities: [], comparisonId: null, comparisonRequest: 0, briefingRequest: 0};
    const forms = {session: $('#session-form'), race: $('#race-form'), checkin: $('#checkin-form'), fuel: $('#fuel-form'), profile: $('#profile-form')};
    const resources = {session: 'sessions', race: 'races', checkin: 'check-ins', fuel: 'fuel-logs', profile: 'profile'};
    const types = {easy: '轻松跑', long: '长距离', tempo: '节奏跑', interval: '间歇跑', recovery: '恢复跑', race: '比赛', rest: '休息'};
    const statuses = {planned: '计划中', completed: '已完成', skipped: '已跳过', cancelled: '已取消'};
    const freshTitles = {session: '安排训练', race: '添加比赛目标', checkin: '记录身体状态', fuel: '记录一次补给'};
    const tabs = {session: 'schedule', race: 'races', checkin: 'wellbeing', fuel: 'fuel', profile: 'profile'};
    const el = (tag, className, text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = String(text); return node; };
    const append = (parent, ...children) => { children.filter(Boolean).forEach(child => parent.append(child)); return parent; };
    const button = (text, handler, style = 'button small') => { const node = el('button', style, text); node.type = 'button'; node.addEventListener('click', handler); return node; };
    const value = (form, name) => form.elements.namedItem(name)?.value ?? '';
    const number = (form, name) => Number(value(form, name));
    const optionalNumber = (form, name) => value(form, name).trim() === '' ? null : number(form, name);
    const timezone = () => state.profile?.timezone || 'Asia/Shanghai';
    const list = result => Array.isArray(result?.items) ? result.items : [];
    const human = input => typeof input === 'string' ? input : (input?.message || input?.summary || input?.label || JSON.stringify(input));
    const fmt = (input, options = {}) => { if (!input) return '—'; const date = new Date(input); if (Number.isNaN(date.valueOf())) return String(input); return new Intl.DateTimeFormat('zh-CN', {timeZone: timezone(), month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', ...options}).format(date); };
    function wallParts(date, zone = timezone()) {
        const parts = new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).formatToParts(date);
        const values = Object.fromEntries(parts.map(p => [p.type, p.value]));
        return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}:${values.second}`;
    }
    const today = () => wallParts(new Date()).slice(0, 10);
    function offsetAt(date, zone = timezone()) {
        const minutes = Math.round((Date.parse(wallParts(date, zone) + 'Z') - Math.floor(date.valueOf() / 1000) * 1000) / 60000);
        return `${minutes < 0 ? '-' : '+'}${String(Math.floor(Math.abs(minutes) / 60)).padStart(2, '0')}:${String(Math.abs(minutes) % 60).padStart(2, '0')}`;
    }
    function offsetForWall(local) {
        let date = new Date(local + ':00Z');
        for (let i = 0; i < 3; i++) date = new Date(local + ':00' + offsetAt(date));
        return offsetAt(date);
    }
    function notify(message, error = false) { const node = $('#global-status'); node.textContent = message; node.className = `notice${error ? ' error' : ''}`; node.hidden = !message; }
    async function api(path, method = 'GET', body) {
        const headers = {Accept: 'application/json'};
        if (method !== 'GET') headers['X-CSRF-Token'] = app.dataset.csrf;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        let response;
        try { response = await fetch(app.dataset.apiBase + path, {method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)}); }
        catch { throw new Error('网络连接失败。你的输入仍保留，请检查连接后重试。'); }
        let data;
        try { data = response.status === 204 ? null : await response.json(); }
        catch { throw new Error('服务器未返回 JSON。登录可能已过期；请在另一标签页重新登录后重试，当前草稿会保留。'); }
        if (!response.ok) { const error = new Error(data?.message || `请求失败（${response.status}）`); error.status = response.status; throw error; }
        return data;
    }
    function selectTab(name, focus = false) {
        $$('.tab-panel').forEach(panel => { panel.hidden = panel.id !== 'panel-' + name; });
        $$('.sh-tabs [data-tab-target]').forEach(tab => { if (tab.dataset.tabTarget === name) tab.setAttribute('aria-current', 'page'); else tab.removeAttribute('aria-current'); });
        if (focus) $('#panel-' + name)?.scrollIntoView({block: 'start', behavior: 'instant'});
    }
    $$('[data-tab-target]').forEach(node => node.addEventListener('click', event => { event.preventDefault(); selectTab(node.dataset.tabTarget); }));
    function formStatus(kind, message, error = false) { const node = $('.form-status', forms[kind]); node.replaceChildren(); node.className = `form-status full ${error ? 'error' : 'success'}`; if (message) node.append(document.createTextNode(message)); }
    function markDirty(kind) { forms[kind]._revision = (forms[kind]._revision || 0) + 1; forms[kind]._dirty = true; const status = $('#' + kind + '-save-state'); status.textContent = '有未保存的修改'; status.classList.add('dirty'); if (kind === 'checkin') renderToday(); }
    function markSaved(kind, record) { const form = forms[kind]; form._record = record; form._dirty = false; const node = $('#' + kind + '-save-state'); node.textContent = record?.version ? `已保存 · 版本 ${record.version}` : '尚未保存'; node.classList.remove('dirty'); }
    function allowReplace(kind) { return !forms[kind]._dirty || window.confirm('表单中有未保存的修改。要放弃这份草稿并继续吗？'); }
    function put(form, name, input) { const field = form.elements.namedItem(name); if (field && 'value' in field) field.value = input ?? ''; }
    function locationFrom(form) { const latitude = optionalNumber(form, 'latitude'); const longitude = optionalNumber(form, 'longitude'); if ((latitude === null) !== (longitude === null)) throw new Error('地点需要同时填写纬度和经度，或将两项都留空。'); if (latitude !== null && !value(form, 'locationLabel').trim()) throw new Error('填写经纬度时，请同时填写地点名称。'); return latitude === null ? null : {label: value(form, 'locationLabel').trim(), latitude, longitude}; }
    function fillLocation(form, location) { put(form, 'locationLabel', location?.label); put(form, 'latitude', location?.latitude); put(form, 'longitude', location?.longitude); }
    function rowField(labelText, name, type, initial, attributes = {}) {
        const label = el('label', '', labelText); const input = el('input'); input.type = type; input.dataset.field = name; input.value = initial ?? ''; Object.entries(attributes).forEach(([key, val]) => input.setAttribute(key, val)); label.append(input); return label;
    }
    function addStep(record = {}, dirty = true) {
        const row = el('div', 'structured-row');
        append(row, el('span', 'row-title', '训练分段'), rowField('分段名称', 'kind', 'text', record.kind || '', {required: '', maxlength: '80', placeholder: '例如：热身'}), rowField('分钟', 'minutes', 'number', record.minutes ?? 10, {required: '', min: '0.1', max: '1440', step: '0.1'}), rowField('距离（公里，可选）', 'distanceKm', 'number', record.distanceKm, {min: '0', max: '1000', step: '0.01'}), rowField('目标（可选）', 'target', 'text', record.target, {maxlength: '200', placeholder: '例如：轻松交谈'}));
        row.append(append(el('div', 'row-actions'), button('移除此段', () => { row.remove(); markDirty('session'); })));
        $('#step-rows').append(row); if (dirty) markDirty('session');
    }
    function addFuelPlan(record = {}, dirty = true) {
        const row = el('div', 'structured-row');
        append(row, el('span', 'row-title', '补给安排'), rowField('出发后分钟', 'minute', 'number', record.minute ?? 30, {required: '', min: '0', max: '1440'}), rowField('食物 / 饮品', 'item', 'text', record.item, {required: '', maxlength: '200'}), rowField('碳水（克，可选）', 'carbsGrams', 'number', record.carbsGrams, {min: '0', max: '1000', step: '0.1'}), rowField('液体（毫升，可选）', 'fluidMl', 'number', record.fluidMl, {min: '0', max: '10000'}));
        row.append(append(el('div', 'row-actions'), button('移除此补给', () => { row.remove(); markDirty('session'); })));
        $('#fuel-plan-rows').append(row); if (dirty) markDirty('session');
    }
    function readRows(selector, numericFields) {
        return $$('.structured-row', $(selector)).map(row => Object.fromEntries($$('[data-field]', row).filter(input => input.value !== '').map(input => [input.dataset.field, numericFields.includes(input.dataset.field) ? Number(input.value) : input.value.trim()])));
    }
    function fillForm(kind, record = null) {
        const form = forms[kind]; form.reset(); formStatus(kind, '');
        if (kind === 'session') {
            $('#step-rows').replaceChildren(); $('#fuel-plan-rows').replaceChildren();
            const local = today() + 'T' + (state.profile?.usualStartTime || '06:30');
            put(form, 'startLocal', local); put(form, 'offset', offsetForWall(local)); put(form, 'durationMinutes', state.profile?.usualDurationMinutes || 60);
        }
        if (['checkin', 'fuel', 'race'].includes(kind)) put(form, 'date', today());
        if (record) {
            Object.entries(record).forEach(([key, input]) => { if (['runningDays', 'location', 'feedback', 'steps', 'fuelPlan'].includes(key)) return; const field = form.elements.namedItem(key); if (field?.type === 'checkbox') field.checked = Boolean(input); else if (field && 'value' in field) field.value = input ?? ''; });
            if (kind === 'profile') { fillLocation(form, record.location); $$('[name=runningDays]', form).forEach(field => { field.checked = (record.runningDays || []).includes(Number(field.value)); }); }
            if (kind === 'session') {
                const match = String(record.startAt).match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/);
                if (match) { put(form, 'startLocal', match[1]); put(form, 'offset', match[2] === 'Z' ? '+00:00' : match[2]); }
                fillLocation(form, record.location); (record.steps || []).forEach(step => addStep(step, false)); (record.fuelPlan || []).forEach(fuel => addFuelPlan(fuel, false));
                form.elements.hasFeedback.checked = Boolean(record.feedback); put(form, 'rpe', record.feedback?.rpe ?? ''); put(form, 'thermalFeeling', record.feedback?.thermalFeeling ?? ''); put(form, 'feedbackNotes', record.feedback?.notes || '');
            }
        }
        markSaved(kind, record);
        if (kind !== 'profile') $('#' + kind + '-form-title').textContent = record ? ({session: '编辑训练', checkin: '编辑身体状态', race: '编辑比赛目标', fuel: '编辑补给记录'}[kind]) : freshTitles[kind];
        if (kind === 'checkin') renderToday();
        if (kind === 'session') updateTimeHelp();
    }
    function updateTimeHelp() { $('#session-time-help').textContent = `跑步设置时区：${timezone()}。此训练将按所填 UTC 时差保存；跨时区或夏令时请确认时差。`; }
    function payloadFor(kind) {
        const form = forms[kind];
        if (kind === 'profile') {
            const zone = value(form, 'timezone').trim(); try { new Intl.DateTimeFormat('zh-CN', {timeZone: zone}).format(); } catch { throw new Error('请输入有效的 IANA 时区，例如 Asia/Shanghai。'); }
            return {timezone: zone, location: locationFrom(form), thermalPreference: value(form, 'thermalPreference'), usualStartTime: value(form, 'usualStartTime'), usualDurationMinutes: number(form, 'usualDurationMinutes'), runningDays: $$('[name=runningDays]:checked', form).map(field => Number(field.value)), notificationsEnabled: form.elements.notificationsEnabled.checked, eveningReminderTime: value(form, 'eveningReminderTime'), preRunReminderMinutes: number(form, 'preRunReminderMinutes')};
        }
        if (kind === 'session') {
            const offset = value(form, 'offset');
            if (!/^[+-](?:0\d|1[0-4]):[0-5]\d$/.test(offset) || (/^[+-]14:/.test(offset) && !offset.endsWith(':00'))) throw new Error('UTC 时差格式应为 +08:00，范围为 −14:00 至 +14:00。');
            const startAt = value(form, 'startLocal') + (value(form, 'startLocal').length === 16 ? ':00' : '') + offset;
            if (Number.isNaN(Date.parse(startAt))) throw new Error('训练日期与时间无效。');
            return {title: value(form, 'title').trim(), startAt, durationMinutes: number(form, 'durationMinutes'), distanceKm: optionalNumber(form, 'distanceKm'), type: value(form, 'type'), status: value(form, 'status'), raceId: value(form, 'raceId') || null, location: locationFrom(form), steps: readRows('#step-rows', ['minutes', 'distanceKm']), fuelPlan: readRows('#fuel-plan-rows', ['minute', 'carbsGrams', 'fluidMl']), notes: value(form, 'notes'), feedback: form.elements.hasFeedback.checked ? {...(value(form, 'rpe') === '' ? {} : {rpe: number(form, 'rpe')}), ...(value(form, 'thermalFeeling') === '' ? {} : {thermalFeeling: value(form, 'thermalFeeling')}), ...(value(form, 'feedbackNotes') === '' ? {} : {notes: value(form, 'feedbackNotes')})} : null};
        }
        if (kind === 'race') return {name: value(form, 'name').trim(), date: value(form, 'date'), distanceKm: number(form, 'distanceKm'), targetTimeMinutes: optionalNumber(form, 'targetTimeMinutes'), notes: value(form, 'notes')};
        if (kind === 'checkin') return {date: value(form, 'date'), sleepHours: optionalNumber(form, 'sleepHours'), fatigue: number(form, 'fatigue'), soreness: number(form, 'soreness'), pain: form.elements.pain.checked, notes: value(form, 'notes')};
        return {date: value(form, 'date'), sessionId: value(form, 'sessionId') || null, minute: number(form, 'minute'), item: value(form, 'item').trim(), carbsGrams: number(form, 'carbsGrams'), fluidMl: number(form, 'fluidMl'), giComfort: value(form, 'giComfort'), notes: value(form, 'notes')};
    }
    async function showConflict(kind, error) {
        formStatus(kind, error.status === 409 ? '保存冲突：服务器记录已发生变化，或该日期 / ID 已有记录。你的草稿仍保留。' : error.message, true);
        const record = forms[kind]._record || (kind === 'profile' ? {id: 'default'} : null);
        if (error.status !== 409 || !record) return;
        const status = $('.form-status', forms[kind]);
        status.append(button('查看服务器最新版本（保留草稿）', async event => {
            const trigger = event.currentTarget; trigger.disabled = true;
            try {
                const latest = await api('/' + resources[kind] + (kind === 'profile' ? '' : '/' + encodeURIComponent(record.id)));
                const details = el('details'); append(details, el('summary', '', '服务器版本 ' + latest.version), el('pre', 'row-note', JSON.stringify(latest, null, 2)));
                details.style.maxWidth = '100%'; details.style.overflow = 'auto';
                details.append(button('用此版本替换表单', () => { if (allowReplace(kind)) fillForm(kind, latest); })); status.append(details);
            } catch (failure) { status.append(el('span', '', failure.message)); }
            finally { trigger.disabled = false; }
        }));
    }
    async function submit(kind, event) {
        event.preventDefault(); const form = forms[kind]; if (form._busy || !form.reportValidity()) return;
        if (kind === 'profile' && (!state.profile || !form._record)) { formStatus(kind, '设置版本尚未确定。请刷新数据，或先查看服务器版本后合并草稿。', true); if (state.profile) await showConflict(kind, {status: 409}); return; }
        let body; try { body = payloadFor(kind); } catch (error) { formStatus(kind, error.message, true); return; }
        const record = form._record; if (record) body.version = record.version;
        const method = kind === 'profile' || record ? 'PUT' : 'POST';
        const path = '/' + resources[kind] + (record && kind !== 'profile' ? '/' + encodeURIComponent(record.id) : '');
        form._busy = true; const controls = $$('input, select, textarea, button', form); controls.forEach(control => { control.disabled = true; }); formStatus(kind, '正在保存…');
        try {
            const saved = await api(path, method, body); fillForm(kind, saved); formStatus(kind, '已保存。');
            if (kind === 'profile') { state.profile = saved; updateContext(); renderAll(); }
            try { await reloadResource(kind); } catch (error) { formStatus(kind, '保存成功，但列表刷新失败。' + error.message, true); }
            refreshBriefing();
            if (kind === 'session' && state.comparisonId === saved.id) showComparison(saved.id);
        } catch (error) { await showConflict(kind, error); }
        finally { form._busy = false; controls.forEach(control => { control.disabled = false; }); }
    }
    Object.entries(forms).forEach(([kind, form]) => {
        form._dirty = false; form.addEventListener('submit', event => submit(kind, event)); form.addEventListener('input', () => markDirty(kind)); form.addEventListener('change', () => markDirty(kind));
    });
    ['rpe', 'thermalFeeling', 'feedbackNotes'].forEach(name => { forms.session.elements.namedItem(name).addEventListener('input', () => { forms.session.elements.hasFeedback.checked = true; }); });
    window.addEventListener('beforeunload', event => { if (Object.values(forms).some(form => form._dirty)) { event.preventDefault(); event.returnValue = ''; } });
    $$('[data-reset]').forEach(node => node.addEventListener('click', () => { const kind = node.dataset.reset; if (!forms[kind]._busy && allowReplace(kind)) fillForm(kind); }));
    $('#add-step').addEventListener('click', () => addStep()); $('#add-fuel-plan').addEventListener('click', () => addFuelPlan());
    $('#new-session').addEventListener('click', () => { if (!forms.session._busy && allowReplace('session')) { fillForm('session'); forms.session.elements.title.focus(); } });
    $('#quick-add-session').addEventListener('click', () => { if (!forms.session._busy && !forms.session._dirty) fillForm('session'); forms.session.elements.title.focus(); });
    forms.session.elements.startLocal.addEventListener('change', () => { try { put(forms.session, 'offset', offsetForWall(value(forms.session, 'startLocal'))); } catch { /* Native validity reports incomplete dates. */ } });
    $('#session-filter').addEventListener('submit', event => { event.preventDefault(); if (value(event.currentTarget, 'from') && value(event.currentTarget, 'to') && value(event.currentTarget, 'from') > value(event.currentTarget, 'to')) { notify('筛选起始日期不能晚于结束日期。', true); return; } notify(''); renderSessions(); });
    async function editRecord(kind, id) {
        if (forms[kind]._busy || !allowReplace(kind)) return;
        const form = forms[kind]; const revision = form._revision || 0; const request = form._editRequest = (form._editRequest || 0) + 1;
        try { const record = await api('/' + resources[kind] + '/' + encodeURIComponent(id)); if (form._editRequest !== request || form._busy || ((form._revision || 0) !== revision && !allowReplace(kind))) return; fillForm(kind, record); selectTab(tabs[kind]); forms[kind].scrollIntoView({block: 'start', behavior: 'instant'}); $('input', forms[kind])?.focus({preventScroll: true}); }
        catch (error) { notify('读取记录失败：' + error.message, true); }
    }
    async function deleteRecord(kind, record, trigger) {
        if (forms[kind]._busy) return;
        const description = record.title || record.name || record.item || record.date;
        const draft = forms[kind]._record?.id === record.id && forms[kind]._dirty ? '\n此记录还有未保存的编辑，删除后将一并丢弃。' : '';
        if (!window.confirm(`确定删除「${description}」吗？${draft}`)) return;
        trigger.disabled = true; const revision = forms[kind]._revision || 0;
        try {
            await api('/' + resources[kind] + '/' + encodeURIComponent(record.id) + '?version=' + record.version, 'DELETE');
            if (forms[kind]._record?.id === record.id) { if ((forms[kind]._revision || 0) === revision) fillForm(kind); else { forms[kind]._record = null; markDirty(kind); formStatus(kind, '原记录已删除；删除期间输入的内容已保留，可另存为新记录。'); } }
            if (kind === 'session' && state.comparisonId === record.id) { state.comparisonId = null; $('#comparison').replaceChildren(el('p', 'empty-state', '这条训练已删除。请选择其他训练进行对比。')); }
            await reloadResource(kind); notify('记录已删除。'); refreshBriefing();
        } catch (error) { notify((error.status === 409 ? '删除未执行：记录已更新，请刷新列表后再决定。' : '删除失败：') + error.message, true); }
        finally { trigger.disabled = false; }
    }
    function recordNode(kind, record, title, meta, date) {
        const node = el('article', 'record');
        if (date) { const badge = el('div', 'record-date'); append(badge, document.createTextNode(date.slice(5, 7) + '月'), el('strong', '', date.slice(8, 10))); node.append(badge); }
        const content = el('div', 'record-body'); const heading = append(el('div', 'record-heading'), el('h3', '', title));
        if (record.status) heading.append(el('span', 'pill ' + record.status, statuses[record.status] || record.status));
        append(content, heading, el('p', 'record-meta', meta)); if (record.notes) content.append(el('p', 'row-note', record.notes));
        const actions = append(el('div', 'record-actions'), button('编辑', () => editRecord(kind, record.id)), button('删除', event => deleteRecord(kind, record, event.currentTarget), 'button small danger'));
        if (kind === 'session') actions.prepend(button('对比 / 简报', () => { showComparison(record.id); refreshBriefing(record.id); $('#comparison-card').scrollIntoView({block: 'start', behavior: 'instant'}); }));
        content.append(actions); node.append(content); return node;
    }
    function renderSessions() {
        const container = $('#session-list'); container.replaceChildren(); const filter = $('#session-filter');
        if (state.failed.has('sessions')) { container.append(el('p', 'empty-state', '训练列表读取失败。请点击「刷新数据」重试，表单草稿会保留。')); return; }
        const rows = state.sessions.filter(row => { const date = wallParts(new Date(row.startAt)).slice(0, 10); return (!value(filter, 'from') || date >= value(filter, 'from')) && (!value(filter, 'to') || date <= value(filter, 'to')) && (!value(filter, 'status') || row.status === value(filter, 'status')); }).sort((a, b) => new Date(a.startAt) - new Date(b.startAt));
        if (!rows.length) container.append(el('p', 'empty-state', state.sessions.length ? '当前筛选没有训练。调整日期或状态，查看其他安排。' : '还没有训练安排。先创建一次轻松跑，为下一次出发留个位置。'));
        rows.forEach(row => container.append(recordNode('session', row, row.title, `${fmt(row.startAt)} · ${types[row.type] || row.type}\n${row.durationMinutes} 分钟${row.distanceKm === null ? '' : ' · ' + row.distanceKm + ' 公里'}${row.activityId ? ' · 已关联真实活动' : ''}`, wallParts(new Date(row.startAt)).slice(0, 10))));
    }
    function renderRaces() {
        const container = $('#race-list'); container.replaceChildren(); if (state.failed.has('races')) { container.append(el('p', 'empty-state', '比赛目标读取失败，请刷新数据重试。')); return; } if (!state.races.length) container.append(el('p', 'empty-state', '还没有比赛目标。填写比赛日期和距离，再把日常训练与目标关联起来。'));
        [...state.races].sort((a, b) => a.date.localeCompare(b.date)).forEach(row => container.append(recordNode('race', row, row.name, `${row.date} · ${row.distanceKm} 公里${row.targetTimeMinutes ? ' · 目标 ' + row.targetTimeMinutes + ' 分钟' : ''}`, row.date)));
    }
    function renderCheckins() {
        const container = $('#checkin-list'); container.replaceChildren(); if (state.failed.has('checkins')) { container.append(el('p', 'empty-state', '身体状态读取失败，请刷新数据重试。')); renderToday(); return; } if (!state.checkins.length) container.append(el('p', 'empty-state', '还没有身体状态记录。今天睡得怎样？保存第一份记录，让建议更贴近你的恢复状态。'));
        [...state.checkins].sort((a, b) => b.date.localeCompare(a.date)).forEach(row => container.append(recordNode('checkin', row, row.date + (row.pain ? ' · 有疼痛' : ''), `睡眠 ${row.sleepHours ?? '未记录'} 小时 · 疲劳 ${row.fatigue}/5 · 酸痛 ${row.soreness}/5`, row.date))); renderToday();
    }
    function renderToday() {
        const container = $('#today-checkin'); container.replaceChildren(); if (state.failed.has('checkins')) { container.append(el('p', 'empty-state', '暂时无法读取今日状态，请刷新数据重试。')); return; } const row = state.checkins.find(item => item.date === today());
        if (row) container.append(append(el('div', 'today-saved'), el('strong', '', '今日状态已保存'), el('p', '', `睡眠 ${row.sleepHours ?? '—'} 小时 · 疲劳 ${row.fatigue}/5 · 酸痛 ${row.soreness}/5${row.pain ? ' · 有疼痛' : ''}`)));
        else container.append(el('p', 'empty-state', '今天还未保存身体状态。简报不会把未填写的信息当成状态良好。'));
        if (forms.checkin._dirty) container.append(el('p', 'pending-note', '身体状态表单有未保存修改，尚未用于简报。'));
    }
    function renderFuels() {
        const container = $('#fuel-list'); container.replaceChildren(); if (state.failed.has('fuels')) { container.append(el('p', 'empty-state', '补给记录读取失败，请刷新数据重试。')); return; } if (!state.fuels.length) container.append(el('p', 'empty-state', '还没有补给练习记录。跑后记下吃了什么、喝了多少，以及肠胃感受。'));
        [...state.fuels].sort((a, b) => b.date.localeCompare(a.date) || a.minute - b.minute).forEach(row => container.append(recordNode('fuel', row, row.item, `${row.date} · 第 ${row.minute} 分钟\n碳水 ${row.carbsGrams} 克 · 液体 ${row.fluidMl} 毫升 · ${({good: '肠胃舒适', mild: '轻微不适', poor: '明显不适'})[row.giComfort] || row.giComfort}`, row.date)));
    }
    function updateOptions(select, rows, empty, label) {
        const previous = select.value; select.replaceChildren(new Option(empty, '')); rows.forEach(row => select.add(new Option(label(row), row.id)));
        if (previous && !rows.some(row => String(row.id) === previous)) select.add(new Option('原关联记录（已不可用）', previous)); select.value = previous;
    }
    function refreshOptions() { updateOptions(forms.session.elements.raceId, state.races, '不关联比赛', row => row.name + ' · ' + row.date); updateOptions(forms.fuel.elements.sessionId, state.sessions, '不关联训练', row => row.title + ' · ' + fmt(row.startAt)); }
    function renderActivities() {
        const container = $('#activity-summary'); container.replaceChildren();
        if (state.failed.has('activities')) { container.append(el('p', 'empty-state', '真实活动读取失败，请刷新数据重试。')); return; }
        if (!state.activities.length) container.append(el('p', 'empty-state', '尚无已导入的真实跑步活动。你仍可安排训练、记录状态和补给；导入跑步活动后，即可关联并对比。'));
        else {
            const distance = state.activities.reduce((sum, row) => sum + (Number(row.distanceKm) || 0), 0); const metrics = el('div', 'metrics-grid');
            [[state.activities.length, '可选真实跑步活动'], [distance.toFixed(1), '可选活动总公里'], [state.sessions.filter(row => row.activityId).length, '已关联训练']].forEach(([amount, label]) => metrics.append(append(el('div', 'metric'), el('strong', '', amount), el('span', '', label))));
            append(container, metrics, el('p', 'muted', '在训练日历中选择「对比 / 简报」，再从真实活动候选中关联本次跑步。'));
        }
    }
    function updateContext() { $('#runner-context').textContent = `${today()} · ${timezone()} · ${state.profile?.location?.label || (state.profile?.location ? '已配置跑步地点' : '尚未配置跑步地点')}`; updateTimeHelp(); }
    function renderAll() { renderSessions(); renderRaces(); renderCheckins(); renderFuels(); renderActivities(); refreshOptions(); }
    async function reloadResource(kind) {
        if (kind === 'profile') return;
        const result = await api('/' + resources[kind]); const key = {session: 'sessions', race: 'races', checkin: 'checkins', fuel: 'fuels'}[kind]; state[key] = list(result); state.failed.delete(key); renderAll();
    }
    function appendList(parent, heading, values) {
        if (!Array.isArray(values) || !values.length) return; const section = el('div'); const ul = el('ul', 'advice-list'); values.forEach(text => ul.append(el('li', '', human(text)))); append(section, el('h3', '', heading), ul); parent.append(section);
    }
    async function refreshBriefing(sessionId) {
        const request = ++state.briefingRequest; const container = $('#briefing'); const refresh = $('#refresh-briefing'); refresh.disabled = true;
        try {
            const briefing = await api('/briefing' + (sessionId ? '?sessionId=' + encodeURIComponent(sessionId) : ''));
            if (request !== state.briefingRequest) return; container.replaceChildren();
            const session = briefing.session;
            if (!session) { append(container, el('h2', '', '下一次出发，由你来安排。'), el('p', 'briefing-summary', '目前没有待跑训练。添加训练，或在跑步设置中保存每周习惯，开始获取出发简报。'), button('去安排训练', () => selectTab('schedule'), 'button subtle')); return; }
            const habitual = briefing.source === 'habit' || briefing.source === 'habitual' || briefing.habitual || session.habitual || session.isHabitual || session.source === 'habit' || !session.id;
            append(container, el('span', 'pill', habitual ? '依据跑步习惯 · 尚未保存为训练' : (statuses[session.status] || '训练简报')), el('h2', '', session.title || '习惯跑步建议'), el('p', 'briefing-date', `${fmt(session.startAt)} · ${timezone()} · ${session.durationMinutes} 分钟${session.distanceKm ? ' · ' + session.distanceKm + ' 公里' : ''}`));
            const forecast = briefing.forecast || {}; const advice = briefing.advice || {}; const quality = el('div');
            if (advice.summary) container.append(el('p', 'briefing-summary', advice.summary));
            const hours = Array.isArray(forecast.sessionHours) ? forecast.sessionHours : (forecast.hours || []);
            if (forecast.status !== 'available') container.append(el('p', 'notice', forecast.message || '当前天气不可用。请参考现场天气，稍后刷新简报。'));
            if (hours.length) {
                const strip = el('div', 'weather-strip'); strip.setAttribute('aria-label', '训练全程逐小时天气');
                hours.forEach(hour => {
                    const weather = el('div', 'weather-hour'); let time = hour.time || hour.startAt || ''; if (/^\d{4}-\d{2}-\d{2}T/.test(time) && !/(Z|[+-]\d{2}:\d{2})$/.test(time)) time = time.slice(5, 16).replace('T', ' '); else time = fmt(time);
                    append(weather, el('span', '', time), el('strong', '', hour.temperature == null ? '—' : hour.temperature + '°'), el('span', '', `体感 ${hour.apparentTemperature ?? '—'}°C`), el('span', '', `降水 ${hour.precipitationProbability ?? '—'}% · ${hour.precipitation ?? '—'} mm`), el('span', '', `风 ${hour.windSpeed ?? '—'} km/h`)); strip.append(weather);
                }); container.append(strip);
            }
            appendList(container, '穿什么', advice.clothing); appendList(container, '需要留意', advice.warnings);
            if (Array.isArray(advice.alternatives) && advice.alternatives.length) {
                const alternatives = el('div', 'info-block'); alternatives.append(el('h3', '', '可以考虑的出发时间'));
                advice.alternatives.forEach(candidate => { const line = el('p', '', `${candidate.label || fmt(candidate.startAt)}${candidate.startAt ? ' · ' + fmt(candidate.startAt) : ''}`); if (candidate.reasons?.length) line.append(el('span', '', '：' + candidate.reasons.join('；'))); alternatives.append(line); });
                alternatives.append(el('p', '', '建议不会自动修改训练。可在训练日历中编辑开始时间并保存。')); container.append(alternatives);
            }
            const detail = el('details'); detail.append(el('summary', '', '为什么这样建议')); appendList(detail, '判断依据', advice.reasons); appendList(detail, '个人偏好与历史体感', advice.personalization); appendList(detail, '数据完整性', advice.dataQuality); container.append(detail);
            quality.className = 'data-quality'; quality.textContent = `天气来源：${forecast.source || '未提供'} · ${forecast.fetchedAt ? '获取于 ' + fmt(forecast.fetchedAt) : '尚无天气数据'}\n简报生成：${fmt(briefing.generatedAt)}。${briefing.checkIn ? '已参考保存的身体状态。' : '暂无适用的已保存身体状态。'}`; container.append(quality);
            if (session.id) container.append(button('编辑这次训练', () => editRecord('session', session.id), 'button subtle'));
        } catch (error) { if (request === state.briefingRequest) { container.replaceChildren(el('h2', '', '简报暂时无法读取'), el('p', 'notice error', error.message)); } }
        finally { if (request === state.briefingRequest) refresh.disabled = false; }
    }
    $('#refresh-briefing').addEventListener('click', () => refreshBriefing());
    const metricText = (value, unit) => value == null ? '—' : Number(value).toFixed(unit === '公里' ? 2 : 1).replace(/\.0$/, '') + ' ' + unit;
    async function showComparison(id) {
        state.comparisonId = id; const request = ++state.comparisonRequest; const container = $('#comparison'); container.replaceChildren(el('p', 'muted', '正在读取实际活动和训练对比…'));
        try {
            const comparison = await api('/sessions/' + encodeURIComponent(id) + '/comparison'); if (request !== state.comparisonRequest || state.comparisonId !== id) return;
            container.replaceChildren(); const planned = comparison.planned || state.sessions.find(row => row.id === id); const actual = comparison.actual;
            if (!planned) throw new Error('服务器没有返回对应的训练。');
            append(container, el('h3', '', planned.title), el('p', 'muted', fmt(planned.startAt)));
            const table = el('table', 'compare-table'); const head = el('tr'); ['', '计划', '实际'].forEach(text => head.append(el('th', '', text))); table.append(append(el('thead'), head)); const body = el('tbody');
            [['时长', 'durationMinutes', '分钟'], ['距离', 'distanceKm', '公里']].forEach(([label, key, unit]) => { const row = el('tr'); append(row, el('th', '', label), el('td', '', metricText(planned[key], unit)), el('td', '', metricText(actual?.[key], unit))); body.append(row); }); table.append(body); container.append(table);
            if (actual) {
                container.append(el('p', 'muted', '已关联：' + (actual.name || actual.title || actual.id)));
                const delta = comparison.delta;
                if (delta) container.append(el('p', 'row-note', `实际 − 计划：时长 ${metricText(delta.durationMinutes, '分钟')} · 距离 ${metricText(delta.distanceKm, '公里')}`));
                if (actual.averageHeartRate) container.append(el('p', 'row-note', `平均心率 ${actual.averageHeartRate} bpm`));
            } else container.append(el('p', 'empty-state', '尚未关联真实跑步活动。关联后可查看实际时长、距离与计划的差异。'));
            if (comparison.suggestion) { if (Array.isArray(comparison.suggestion)) appendList(container, '训练建议', comparison.suggestion); else container.append(el('p', 'info-block', human(comparison.suggestion))); }
            const candidates = Array.isArray(comparison.candidates) ? comparison.candidates : state.activities;
            if (candidates.length) {
                const linkForm = el('form', 'comparison-link'); const label = el('label', '', actual ? '选择另一条真实活动' : '选择真实跑步活动'); const select = el('select'); select.required = true; select.append(new Option('请选择活动', ''));
                candidates.forEach(row => select.add(new Option(`${row.name || row.title || row.id} · ${fmt(row.startAt)} · ${row.distanceKm ?? '—'} km`, row.id))); if (actual) select.value = actual.id;
                label.append(select); const linkButton = el('button', 'button', actual ? '更新关联' : '关联并标为已完成'); linkButton.type = 'submit'; const status = el('p', 'form-status'); status.setAttribute('role', 'status');
                append(linkForm, label, linkButton, status); container.append(linkForm);
                linkForm.addEventListener('submit', async event => {
                    event.preventDefault(); if (!linkForm.reportValidity()) return; linkButton.disabled = true;
                    try {
                        await api('/sessions/' + encodeURIComponent(id) + '/link', 'POST', {version: planned.version, activityId: select.value});
                        status.textContent = '已关联。';
                        if (forms.session._record?.id === id) {
                            if (forms.session._dirty) { formStatus('session', '活动已关联。表单草稿仍保留，但服务器版本已更新；保存时如遇冲突，请查看服务器最新版本。', true); }
                            else fillForm('session', await api('/sessions/' + encodeURIComponent(id)));
                        }
                        await reloadResource('session'); await showComparison(id); refreshBriefing(id);
                    } catch (error) { status.className = 'form-status error'; status.textContent = (error.status === 409 ? '记录已更新，关联未执行。请重新打开此训练的对比后重试。' : error.message); }
                    finally { linkButton.disabled = false; }
                });
            } else container.append(el('p', 'muted', '目前没有可关联的真实跑步活动。请先从管理后台导入。'));
            container.append(button('记录跑后体感', () => editRecord('session', id)));
        } catch (error) { if (request === state.comparisonRequest) container.replaceChildren(el('p', 'notice error', error.message)); }
    }
    $('#refresh-data').addEventListener('click', async event => {
        const trigger = event.currentTarget; trigger.disabled = true;
        const collections = [['profile', '/profile'], ['sessions', '/sessions'], ['races', '/races'], ['checkins', '/check-ins'], ['fuels', '/fuel-logs'], ['activities', '/activities']];
        const results = await Promise.allSettled(collections.map(async ([key, path]) => { const data = await api(path); state[key] = key === 'profile' ? data : list(data); state.failed.delete(key); }));
        if (state.profile) { if (!forms.profile._dirty && !forms.profile._busy) fillForm('profile', state.profile); updateContext(); }
        const failures = results.filter(result => result.status === 'rejected');
        renderAll(); refreshBriefing();
        notify(failures.length ? '部分列表刷新失败，已保留原有数据与草稿。请稍后重试。' : '列表和简报已刷新，未保存的表单保持原样。', failures.length > 0);
        trigger.disabled = false;
    });
    async function bootstrap() {
        const profileControls = $$('input, select, textarea, button', forms.profile); profileControls.forEach(control => { control.disabled = true; });
        Object.keys(forms).filter(kind => kind !== 'profile').forEach(kind => fillForm(kind));
        const requests = [['profile', '/profile'], ['sessions', '/sessions'], ['races', '/races'], ['checkins', '/check-ins'], ['fuels', '/fuel-logs'], ['activities', '/activities']];
        const results = await Promise.allSettled(requests.map(async ([key, path]) => { const result = await api(path); state[key] = key === 'profile' ? result : list(result); }));
        const failures = results.flatMap((result, index) => { if (result.status !== 'rejected') return []; state.failed.add(requests[index][0]); return [requests[index][1] + '：' + result.reason.message]; });
        if (state.profile) { if (!forms.profile._dirty) fillForm('profile', state.profile); else { showConflict('profile', {status: 409}); } updateContext(); if (!forms.session._dirty) fillForm('session'); }
        else { $('#profile-save-state').textContent = '读取失败'; $('#runner-context').textContent = '设置读取失败，请检查连接后刷新页面。'; }
        profileControls.forEach(control => { control.disabled = false; });
        renderAll();
        for (const kind of ['checkin', 'fuel', 'race']) { if (!forms[kind]._dirty) fillForm(kind); }
        const todayRecord = state.checkins.find(row => row.date === today()); if (todayRecord && !forms.checkin._dirty) fillForm('checkin', todayRecord);
        if (failures.length) notify('部分数据未能读取；空列表不代表没有记录。\n' + failures.join('\n'), true);
        await refreshBriefing();
    }
    bootstrap().catch(error => notify('初始化失败：' + error.message, true));
})();
