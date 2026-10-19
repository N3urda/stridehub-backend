(() => {
    'use strict';
    const app = document.getElementById('training-app');
    if (!app) return;
    const $ = (selector, scope = document) => scope.querySelector(selector);
    const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));
    const state = {failed: new Set(), profile: null, sessions: [], races: [], checkins: [], fuels: [], activities: [], comparisonId: null, comparisonRequest: 0, briefingRequest: 0, pendingDeletes: new Map(), deleting: new Set()};
    const entry = new URLSearchParams(location.search);
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
    async function renewPageToken() {
        let response;
        try { response = await fetch(location.pathname, {credentials: 'same-origin', cache: 'no-store'}); }
        catch { throw new Error('尚未提交：无法检查登录状态。草稿仍保留，请恢复连接后重试。'); }
        const html = new DOMParser().parseFromString(await response.text(), 'text/html');
        const token = html.querySelector('#training-app')?.dataset.csrf;
        if (!response.ok || !token) throw new Error('尚未提交：登录已过期。请在另一标签页重新登录，再回到这里保存；当前草稿仍保留。');
        app.dataset.csrf = token;
    }
    async function api(path, method = 'GET', body) {
        if (method !== 'GET') await renewPageToken();
        const headers = {Accept: 'application/json'};
        if (method !== 'GET') headers['X-CSRF-Token'] = app.dataset.csrf;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        let response;
        try { response = await fetch(app.dataset.apiBase + path, {method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)}); }
        catch { const error = new Error(method === 'GET' ? '网络连接失败，草稿仍保留。请稍后重试读取。' : '连接中断，保存结果尚未确定。请先读取服务器记录核对。'); error.uncertain = method !== 'GET'; throw error; }
        let data;
        try { data = response.status === 204 ? null : await response.json(); }
        catch { const error = new Error('未收到有效响应。请在另一标签页确认登录状态；当前草稿保留。'); error.uncertain = method !== 'GET'; throw error; }
        if (!response.ok) { const error = new Error(response.status === 401 || response.status === 403 ? '登录或页面验证已失效。请在另一标签页重新登录，草稿仍保留。' : data?.message || `请求失败（${response.status}）`); error.status = response.status; error.uncertain = method !== 'GET' && response.status >= 500; throw error; }
        return data;
    }
    function selectTab(name, focus = false) {
        if (!$('#panel-' + name)) name = 'overview';
        $$('.tab-panel').forEach(panel => { panel.hidden = panel.id !== 'panel-' + name; });
        $$('.sh-tabs [data-tab-target]').forEach(tab => { if (tab.dataset.tabTarget === name) tab.setAttribute('aria-current', 'page'); else tab.removeAttribute('aria-current'); });
        if (focus) $('#panel-' + name)?.scrollIntoView({block: 'start', behavior: 'instant'});
        const url = new URL(location.href); url.searchParams.set('tab', name); history.replaceState(null, '', url);
    }
    $$('[data-tab-target]').forEach(node => node.addEventListener('click', event => { event.preventDefault(); selectTab(node.dataset.tabTarget); }));
    function formStatus(kind, message, error = false) { const node = $('.form-status', forms[kind]); node.replaceChildren(); node.className = `form-status full ${error ? 'error' : 'success'}`; if (message) node.append(document.createTextNode(message)); }
    function markDirty(kind) { forms[kind]._revision = (forms[kind]._revision || 0) + 1; forms[kind]._dirty = true; const status = $('#' + kind + '-save-state'); status.textContent = '有未保存的修改'; status.classList.add('dirty'); if (kind === 'checkin') renderToday(); }
    function updateFormControls(kind) { const form = forms[kind]; $$('input, select, textarea, button', form).forEach(control => { control.disabled = !!form._busy || !!form._loading; }); $('[type=submit]', form).disabled = !!form._busy || !!form._loading || !!form._blocked; }
    function markSaved(kind, record) { const form = forms[kind]; form._record = record; form._dirty = false; form._blocked = false; form._pending = null; form._createId = record?.id || null; const node = $('#' + kind + '-save-state'); node.textContent = record?.version ? `已保存 · 版本 ${record.version}` : '尚未保存'; node.classList.remove('dirty'); }
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
        // A new form also needs a baseline if a same-date record already exists.
        form._baseline = payloadFor(kind);
        updateFormControls(kind);
        if (kind !== 'profile') $('#' + kind + '-form-title').textContent = record ? ({session: '编辑训练', checkin: '编辑身体状态', race: '编辑比赛目标', fuel: '编辑补给记录'}[kind]) : freshTitles[kind];
        if (kind === 'checkin') renderToday();
        if (kind === 'session') {
            updateTimeHelp(); const url = new URL(location.href);
            if (record?.id) url.searchParams.set('sessionId', record.id); else url.searchParams.delete('sessionId');
            history.replaceState(null, '', url);
        }
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
            return {title: value(form, 'title').trim(), startAt, durationMinutes: number(form, 'durationMinutes'), distanceKm: optionalNumber(form, 'distanceKm'), type: value(form, 'type'), status: value(form, 'status'), raceId: value(form, 'raceId') || null, location: locationFrom(form), steps: readRows('#step-rows', ['minutes', 'distanceKm']), fuelPlan: readRows('#fuel-plan-rows', ['minute', 'carbsGrams', 'fluidMl']), notes: value(form, 'notes'), feedback: form.elements.hasFeedback.checked ? {...Object.fromEntries(Object.entries(form._record?.feedback || {}).filter(([key]) => !['rpe', 'thermalFeeling', 'notes'].includes(key))), ...(value(form, 'rpe') === '' ? {} : {rpe: number(form, 'rpe')}), ...(value(form, 'thermalFeeling') === '' ? {} : {thermalFeeling: value(form, 'thermalFeeling')}), ...(value(form, 'feedbackNotes') === '' ? {} : {notes: value(form, 'feedbackNotes')})} : null};
        }
        if (kind === 'race') return {name: value(form, 'name').trim(), date: value(form, 'date'), distanceKm: number(form, 'distanceKm'), targetTimeMinutes: optionalNumber(form, 'targetTimeMinutes'), notes: value(form, 'notes')};
        if (kind === 'checkin') return {date: value(form, 'date'), sleepHours: optionalNumber(form, 'sleepHours'), fatigue: optionalNumber(form, 'fatigue'), soreness: optionalNumber(form, 'soreness'), pain: value(form, 'pain') === '' ? null : value(form, 'pain') === 'true', notes: value(form, 'notes')};
        return {date: value(form, 'date'), sessionId: value(form, 'sessionId') || null, minute: number(form, 'minute'), item: value(form, 'item').trim(), carbsGrams: number(form, 'carbsGrams'), fluidMl: number(form, 'fluidMl'), giComfort: value(form, 'giComfort'), notes: value(form, 'notes')};
    }
    const object = input => input !== null && typeof input === 'object' && !Array.isArray(input);
    function equal(left, right) {
        if (left === right) return true;
        if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((item, index) => equal(item, right[index]));
        return object(left) && object(right) && Object.keys(left).length === Object.keys(right).length && Object.keys(left).every(key => Object.hasOwn(right, key) && equal(left[key], right[key]));
    }
    function mergeChanges(base, draft, latest) {
        if (equal(base, draft)) return latest;
        if (!object(base) || !object(draft)) return draft;
        const result = {...(object(latest) ? latest : {})};
        for (const key of new Set([...Object.keys(base), ...Object.keys(draft)])) {
            if (equal(base[key], draft[key])) continue;
            if (!Object.hasOwn(draft, key)) delete result[key];
            else result[key] = mergeChanges(base[key], draft[key], result[key]);
        }
        return result;
    }
    function writeBody(kind) {
        const form = forms[kind]; const draft = payloadFor(kind);
        if (!form._record || !form._baseline) return draft;
        return Object.fromEntries(Object.entries(draft).filter(([key, input]) => !equal(form._baseline[key], input)).map(([key, input]) => [key, mergeChanges(form._baseline[key], input, form._record[key])]));
    }
    function matches(record, payload) {
        return Object.entries(payload).filter(([key]) => !['id', 'version'].includes(key)).every(([key, input]) => key === 'startAt' ? Date.parse(record[key]) === Date.parse(input) : equal(record[key], input));
    }
    function recordPath(kind, id) { return '/' + resources[kind] + (kind === 'profile' ? '' : '/' + encodeURIComponent(id)); }
    function createId() { return 'web-' + [...crypto.getRandomValues(new Uint8Array(16))].map(byte => byte.toString(16).padStart(2, '0')).join(''); }
    async function latestFor(kind) {
        const form = forms[kind]; const id = form._pending?.id || form._record?.id || form._createId;
        if (kind === 'checkin' && !form._record) {
            const date = form._pending?.payload.date || value(form, 'date');
            const data = await api('/check-ins?from=' + encodeURIComponent(date) + '&to=' + encodeURIComponent(date));
            return list(data).find(record => record.date === date) || null;
        }
        try { return await api(recordPath(kind, id)); } catch (error) { if (error.status === 404) return null; throw error; }
    }
    function readableRecord(record) {
        const labels = {title: '训练名称', name: '比赛名称', date: '日期', startAt: '开始时间', durationMinutes: '预计分钟', distanceKm: '公里', type: '训练类型', status: '训练状态', notes: '备注', raceId: '关联比赛', sessionId: '关联训练', location: '地点', label: '名称', latitude: '纬度', longitude: '经度', steps: '分段训练', kind: '分段', minutes: '分钟', target: '目标', fuelPlan: '补给计划', minute: '出发后分钟', item: '食物 / 饮品', carbsGrams: '碳水（克）', fluidMl: '液体（毫升）', giComfort: '肠胃感受', feedback: '跑后反馈', rpe: '主观强度', thermalFeeling: '冷热体感', pain: '疼痛或不适', sleepHours: '睡眠小时', fatigue: '疲劳', soreness: '酸痛', timezone: '时区', runningDays: '跑步日', thermalPreference: '冷热偏好', usualStartTime: '常用开跑时间', usualDurationMinutes: '常用时长', notificationsEnabled: '训练提醒', eveningReminderTime: '晚间提醒时间', preRunReminderMinutes: '跑前提前分钟', activityIds: '已关联运动', updatedAt: '更新时间'};
        const texts = {comfortable: '舒适', cold: '偏冷', hot: '偏热', neutral: '正常', warm: '怕热', good: '舒适', mild: '轻微不适', poor: '明显不适', unknown: '未知', ...types, ...statuses};
        const lines = [];
        function walk(input, prefix = '') {
            for (const [key, entry] of Object.entries(input)) {
                if (['id', 'version', 'activityId'].includes(key)) continue;
                const label = prefix + (labels[key] || key);
                if (object(entry)) walk(entry, label + ' · ');
                else if (Array.isArray(entry)) {
                    if (!entry.length) lines.push(label + '：未记录');
                    else entry.forEach((item, index) => object(item) ? walk(item, label + ' ' + (index + 1) + ' · ') : lines.push(label + '：' + item));
                } else lines.push(label + '：' + (entry == null || entry === '' ? '未记录' : typeof entry === 'boolean' ? entry ? '是' : '否' : texts[entry] || entry));
            }
        }
        walk(record); const output = el('div', 'server-values'); lines.forEach(line => output.append(el('p', 'row-note', line))); return output;
    }
    async function completeSave(kind, record) {
        const form = forms[kind];
        if (form._pending && form._revision !== form._pending.revision) {
            form._record = record; form._baseline = form._pending.draft; form._pending = null; form._blocked = false; formStatus(kind, '上次保存已核验。你之后修改的草稿仍未保存。');
        } else { fillForm(kind, record); formStatus(kind, '已保存并核验。'); }
        if (kind === 'profile') { state.profile = record; updateContext(); renderAll(); }
        try { await reloadResource(kind); } catch (error) { formStatus(kind, '保存已核验，但列表刷新失败。' + error.message, true); }
        refreshBriefing(kind === 'session' ? record.id : undefined);
        if (kind === 'session' && state.comparisonId === record.id) showComparison(record.id);
    }
    function recoveryAction(kind) {
        $('.form-status', forms[kind]).append(button('查看服务器最新版本（保留草稿）', () => reviewRecord(kind)));
        updateFormControls(kind);
    }
    async function reviewRecord(kind) {
        const form = forms[kind]; if (form._busy) return;
        form._busy = true; updateFormControls(kind);
        try {
            const latest = await latestFor(kind);
            if (latest && form._pending && matches(latest, form._pending.payload)) { await completeSave(kind, latest); return; }
            const deleted = !latest && !!form._record;
            formStatus(kind, latest ? '最新保存内容如下。你的草稿仍保留；核对后才可继续保存。' : deleted ? '已核验：原记录已删除。草稿仍保留；确认另存后会使用新编号，不会恢复已删除的原记录。' : '已核验：服务器没有这条记录。草稿仍保留；确认后可保存为新记录。', true);
            const status = $('.form-status', form);
            if (latest) {
                const details = el('div', 'server-record');
                append(details, el('strong', '', '服务器版本 ' + latest.version), readableRecord(latest));
                details.append(button('用此版本替换表单', () => { if (allowReplace(kind)) { fillForm(kind, latest); formStatus(kind, '已载入服务器最新内容。'); } }));
                status.append(details);
            }
            status.append(button(deleted ? '确认另存为新记录' : '已核对，保留草稿继续编辑', () => {
                form._record = latest; form._blocked = false; form._pending = null;
                if (deleted) form._createId = null;
                if (kind === 'profile' && latest) state.profile = latest;
                formStatus(kind, latest ? '已采用最新版本。下一次保存仅合并你改动的字段；服务器其余内容保留。' : '草稿已保留，可保存为新记录。'); updateFormControls(kind);
            }));
        } catch (error) { formStatus(kind, '读取核验失败。草稿仍保留，请先恢复连接再核对。' + error.message, true); recoveryAction(kind); }
        finally { form._busy = false; updateFormControls(kind); }
    }
    async function showConflict(kind, error) {
        const form = forms[kind];
        formStatus(kind, error.status === 409 ? '保存冲突：服务器记录已发生变化，或该日期 / ID 已有记录。你的草稿仍保留。' : error.status === 404 ? '保存未完成：原记录已不存在。草稿保留，请读取核验后决定是否另存。' : (form._pending?.acknowledged ? '服务器已接收保存，核验尚未完成。请先读取最新内容，勿重复提交。' : '') + error.message, true);
        if ([404, 409].includes(error.status) || error.uncertain || form._pending?.acknowledged) { form._blocked = true; recoveryAction(kind); }
    }
    async function submit(kind, event) {
        event.preventDefault(); const form = forms[kind]; if (form._busy || form._loading || form._blocked || !form.reportValidity()) return;
        if (kind === 'profile' && (!state.profile || !form._record)) { await showConflict(kind, {status: 409}); return; }
        let body; try { body = writeBody(kind); } catch (error) { formStatus(kind, error.message, true); return; }
        const record = form._record;
        const id = record?.id || (kind === 'profile' ? 'default' : (form._createId ||= createId()));
        if (record) body.version = record.version; else if (kind !== 'profile') body.id = id;
        const method = kind === 'profile' || record ? 'PUT' : 'POST';
        const path = record || kind === 'profile' ? recordPath(kind, id) : '/' + resources[kind];
        form._pending = {id, payload: body, draft: payloadFor(kind), revision: form._revision, acknowledged: false};
        form._busy = true; updateFormControls(kind); formStatus(kind, '正在保存并核验…');
        try {
            await api(path, method, body); form._pending.acknowledged = true;
            const latest = await latestFor(kind);
            if (!latest || !matches(latest, body)) { const error = new Error('服务器已接收保存，但最新内容发生变化。请读取最新版本核对；没有自动重试。'); error.status = 409; throw error; }
            await completeSave(kind, latest);
        } catch (error) {
            if (error.uncertain) {
                try {
                    const latest = await latestFor(kind);
                    if (latest && matches(latest, body)) { await completeSave(kind, latest); return; }
                } catch { /* Keep the write blocked until the user can read it back. */ }
            }
            await showConflict(kind, error);
        } finally { form._busy = false; updateFormControls(kind); }
    }
    Object.entries(forms).forEach(([kind, form]) => {
        form._dirty = false; form.addEventListener('submit', event => submit(kind, event)); form.addEventListener('input', () => markDirty(kind)); form.addEventListener('change', () => markDirty(kind));
    });
    ['rpe', 'thermalFeeling', 'feedbackNotes'].forEach(name => { forms.session.elements.namedItem(name).addEventListener('input', () => { forms.session.elements.hasFeedback.checked = true; }); });
    window.addEventListener('beforeunload', event => { if (Object.values(forms).some(form => form._dirty || form._busy || form._blocked) || state.pendingDeletes.size || state.deleting.size) { event.preventDefault(); event.returnValue = ''; } });
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
    async function verifyDeletion(kind, record, revision) {
        try { await api(recordPath(kind, record.id)); }
        catch (error) {
            if (error.status !== 404) throw error;
            state.pendingDeletes.delete(kind + ':' + record.id);
            if (forms[kind]._record?.id === record.id) {
                if ((forms[kind]._revision || 0) === revision) fillForm(kind);
                else { forms[kind]._record = null; forms[kind]._createId = null; markDirty(kind); formStatus(kind, '原记录已删除；删除期间输入的内容已保留，可另存为新记录。'); }
            }
            if (kind === 'session' && state.comparisonId === record.id) { state.comparisonId = null; $('#comparison').replaceChildren(el('p', 'empty-state', '这条训练已删除。请选择其他训练进行对比。')); }
            try { await reloadResource(kind); notify('记录已删除。'); } catch (failure) { notify('删除已核验，但列表刷新失败。' + failure.message, true); }
            refreshBriefing(); return;
        }
        state.pendingDeletes.delete(kind + ':' + record.id);
        await reloadResource(kind); notify('已核验：记录仍存在。请核对刷新后的内容；需要删除时再次选择删除。', true);
    }
    async function deleteRecord(kind, record, trigger) {
        if (forms[kind]._busy) return;
        const key = kind + ':' + record.id; const pending = state.pendingDeletes.get(key);
        if (state.deleting.has(key)) return;
        const description = record.title || record.name || record.item || record.date;
        const draft = forms[kind]._record?.id === record.id && forms[kind]._dirty ? '\n此记录还有未保存的编辑，删除后将一并丢弃。' : '';
        if (!pending && !window.confirm(`确定删除「${description}」吗？${draft}`)) return;
        state.deleting.add(key); trigger.disabled = true; const revision = pending?.revision ?? forms[kind]._revision ?? 0;
        try {
            if (!pending) {
                try { await api(recordPath(kind, record.id) + '?version=' + record.version, 'DELETE'); }
                catch (error) { if (!error.uncertain) throw error; }
                state.pendingDeletes.set(key, {revision});
            }
            await verifyDeletion(kind, record, revision);
        } catch (error) {
            if (state.pendingDeletes.has(key)) {
                notify('删除结果尚未核验。请先读取最新记录，不要重复删除。' + error.message, true);
                $('#global-status').append(button('核对删除结果', () => deleteRecord(kind, record, trigger)));
            } else notify((error.status === 409 ? '删除未执行：记录已更新，请刷新列表后再决定。' : '删除未执行：') + error.message, true);
        } finally { state.deleting.delete(key); trigger.disabled = false; }
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
        [...state.checkins].sort((a, b) => b.date.localeCompare(a.date)).forEach(row => container.append(recordNode('checkin', row, row.date + (row.pain ? ' · 有疼痛' : ''), `睡眠 ${row.sleepHours ?? '未记录'} 小时 · 疲劳 ${row.fatigue == null ? '未记录' : row.fatigue + '/5'} · 酸痛 ${row.soreness == null ? '未记录' : row.soreness + '/5'}`, row.date))); renderToday();
    }
    function renderToday() {
        const container = $('#today-checkin'); container.replaceChildren(); if (state.failed.has('checkins')) { container.append(el('p', 'empty-state', '暂时无法读取今日状态，请刷新数据重试。')); return; } const row = state.checkins.find(item => item.date === today());
        if (row) container.append(append(el('div', 'today-saved'), el('strong', '', '今日状态已保存'), el('p', '', `睡眠 ${row.sleepHours ?? '—'} 小时 · 疲劳 ${row.fatigue == null ? '未记录' : row.fatigue + '/5'} · 酸痛 ${row.soreness == null ? '未记录' : row.soreness + '/5'}${row.pain ? ' · 有疼痛' : ''}`)));
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
            if (briefing.health?.constraints?.length) {
                const constraints = el('section', 'briefing-constraints'); constraints.setAttribute('aria-label', '本次训练适用的健康约束');
                constraints.append(el('h3', '', '先核对本次训练的约束'));
                for (const constraint of briefing.health.constraints) {
                    const item = el('div', 'briefing-constraint');
                    append(item, el('p', '', constraint.description), el('p', 'field-help', `${constraint.sourceType === 'clinician' ? '记录的医生建议' : '用户记录'}${constraint.sourceReportId ? ' · 来源报告 ' + constraint.sourceReportId : ''} · ${constraint.validFrom ? '生效 ' + constraint.validFrom : '生效日期未注明'}${constraint.reviewOn ? ' · 复核 ' + constraint.reviewOn : ''}`));
                    constraints.append(item);
                }
                container.append(constraints);
            }
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
                const actualActivities = comparison.actualActivities || [actual];
                container.append(el('p', 'muted', '已关联 ' + actualActivities.length + ' 条真实跑步：'));
                actualActivities.forEach(row => container.append(el('p', 'row-note', `${row.name || row.title || row.id} · ${fmt(row.startAt)} · ${metricText(row.distanceKm, '公里')} · ${metricText(row.durationMinutes, '分钟')}`)));
                const delta = comparison.delta;
                if (delta) container.append(el('p', 'row-note', `实际 − 计划：时长 ${metricText(delta.durationMinutes, '分钟')} · 距离 ${metricText(delta.distanceKm, '公里')}`));
                if (actual.averageHeartRate) container.append(el('p', 'row-note', `平均心率 ${actual.averageHeartRate} bpm`));
            } else container.append(el('p', 'empty-state', '尚未关联真实跑步活动。关联后可查看实际时长、距离与计划的差异。'));
            if (comparison.suggestion) { if (Array.isArray(comparison.suggestion)) appendList(container, '训练建议', comparison.suggestion); else container.append(el('p', 'info-block', human(comparison.suggestion))); }
            const todayLink = el('a', 'button', '打开今日训练，核对多条跑步记录'); todayLink.href = app.dataset.todayUrl + '?sessionId=' + encodeURIComponent(id); container.append(todayLink);
            appendList(container, '数据范围', comparison.dataQuality);
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
        selectTab(entry.get('tab') || (entry.get('sessionId') ? 'schedule' : 'overview'));
        const profileControls = $$('input, select, textarea, button', forms.profile); profileControls.forEach(control => { control.disabled = true; });
        Object.keys(forms).filter(kind => kind !== 'profile').forEach(kind => fillForm(kind));
        if (entry.get('sessionId')) { forms.session._loading = true; updateFormControls('session'); formStatus('session', '正在读取指定课次，完成后可编辑。'); }
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
        if (entry.get('sessionId')) {
            await editRecord('session', entry.get('sessionId'));
            forms.session._loading = false; updateFormControls('session');
            if (entry.has('tab')) selectTab(entry.get('tab'));
            await refreshBriefing(entry.get('sessionId'));
        } else await refreshBriefing();
    }
    bootstrap().catch(error => notify('初始化失败：' + error.message, true));
})();
