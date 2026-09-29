(() => {
    'use strict';
    const app = document.getElementById('today-app');
    if (!app) return;
    const $ = selector => document.querySelector(selector);
    const node = (tag, className, text) => { const result = document.createElement(tag); if (className) result.className = className; if (text !== undefined) result.textContent = String(text); return result; };
    const add = (parent, ...children) => { children.filter(Boolean).forEach(child => parent.append(child)); return parent; };
    const button = (label, action, handler, style = 'button small') => { const result = node('button', style, label); result.type = 'button'; result.dataset.action = action; result.addEventListener('click', handler); return result; };
    const human = value => typeof value === 'string' ? value : value?.message || value?.description || value?.summary || '';
    const messages = value => (Array.isArray(value) ? value : value ? [value] : []).map(human).filter(Boolean);
    const types = {easy: '轻松跑', long: '长距离', tempo: '节奏跑', interval: '间歇跑', recovery: '恢复跑', race: '比赛', rest: '休息'};
    const statuses = {planned: '计划中', completed: '已完成', skipped: '已跳过', cancelled: '已取消'};
    const forms = {checkin: $('#today-checkin-form'), feedback: $('#today-feedback-form')};
    const requestedSession = new URLSearchParams(location.search).get('sessionId');
    const state = {data: null, selected: requestedSession, records: new Map(), checkins: new Map(), feedback: new Map(), links: new Map(), comparisons: new Map(), refreshId: 0, briefingId: 0, comparisonId: 0};
    const timezone = () => state.data?.timezone || 'Asia/Shanghai';
    const format = (value, timeOnly = false) => { if (!value) return '未记录'; const date = new Date(value); if (Number.isNaN(date.valueOf())) return String(value); return new Intl.DateTimeFormat('zh-CN', {timeZone: timezone(), ...(timeOnly ? {} : {month: '2-digit', day: '2-digit'}), hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(date); };
    const localDate = value => { const parts = new Intl.DateTimeFormat('en-CA', {timeZone: timezone(), year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date(value)); const fields = Object.fromEntries(parts.map(part => [part.type, part.value])); return `${fields.year}-${fields.month}-${fields.day}`; };
    const amount = (value, unit) => value == null ? '未知' : `${Number(value).toFixed(2).replace(/\.?0+$/, '')} ${unit}`;
    const startOffset = minutes => { const absolute = Math.round(Math.abs(minutes)); if (!absolute) return '与计划开始时间一致'; const duration = absolute >= 60 ? `${Math.floor(absolute / 60)} 小时${absolute % 60 ? ' ' + absolute % 60 + ' 分钟' : ''}` : absolute + ' 分钟'; return `比计划${minutes < 0 ? '早' : '晚'} ${duration}`; };
    const linkedIds = record => record?.activityIds || (record?.activityId ? [record.activityId] : []);
    const currentRecord = () => state.records.get(state.selected);
    function latestRecord(record) { const cached = state.records.get(record.id); return cached && cached.version > record.version ? cached : record; }
    function rememberComparison(comparison) {
        const id = comparison.planned.id;
        if ((state.records.get(id)?.version ?? -1) > comparison.planned.version || (state.comparisons.get(id)?.planned.version ?? -1) > comparison.planned.version) return false;
        state.records.set(id, comparison.planned); state.comparisons.set(id, comparison); return true;
    }
    function notice(text, error = false) { const target = $('#today-status'); target.textContent = text; target.className = 'notice' + (error ? ' error' : ''); target.hidden = !text; }
    async function api(path, method = 'GET', body) {
        const headers = {Accept: 'application/json'};
        if (method !== 'GET') headers['X-CSRF-Token'] = app.dataset.csrf;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        let response;
        try { response = await fetch(app.dataset.apiBase + path, {method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)}); }
        catch { const error = new Error(method === 'GET' ? '网络连接失败，请稍后刷新；草稿仍在。' : '连接中断，保存结果尚未确定。'); error.uncertain = method !== 'GET'; throw error; }
        let data;
        try { data = response.status === 204 ? null : await response.json(); }
        catch { const error = new Error('未收到有效响应。登录可能已过期，请在另一个标签页重新登录；当前草稿仍在。'); error.uncertain = method !== 'GET'; throw error; }
        if (!response.ok) {
            const text = response.status === 401 ? '登录已过期。请在另一个标签页重新登录，当前草稿仍在。' : response.status === 403 ? '保存验证已失效。请保留当前页面，在另一个标签页重新登录后再试。' : data?.message || `请求失败（${response.status}）`;
            const error = new Error(text); error.status = response.status; error.uncertain = method !== 'GET' && response.status >= 500; throw error;
        }
        return data;
    }
    function checkinValues(record) { return {sleepHours: record?.sleepHours ?? '', fatigue: record?.fatigue ?? '', soreness: record?.soreness ?? '', pain: record?.pain == null ? '' : String(record.pain), notes: record?.notes || ''}; }
    function feedbackValues(record) { const feedback = record?.feedback; return {rpe: feedback?.rpe ?? '', thermalFeeling: feedback?.thermalFeeling ?? '', pain: feedback?.pain == null ? '' : String(feedback.pain), notes: feedback?.notes || '', 'fuel.carbsGrams': feedback?.fuel?.carbsGrams ?? '', 'fuel.fluidMl': feedback?.fuel?.fluidMl ?? '', 'fuel.giComfort': feedback?.fuel?.giComfort || 'unknown', 'fuel.notes': feedback?.fuel?.notes || ''}; }
    function draft(kind, record, key) {
        const store = kind === 'checkin' ? state.checkins : state.feedback;
        let value = store.get(key);
        if (!value) { value = {key, base: record, values: kind === 'checkin' ? checkinValues(record) : feedbackValues(record), dirty: new Set(), busy: false, blocked: false, message: '', error: false}; store.set(key, value); }
        else if (!value.dirty.size && !value.busy && !value.blocked) { value.base = record; value.values = kind === 'checkin' ? checkinValues(record) : feedbackValues(record); }
        return value;
    }
    function activeDraft(kind) { if (!state.data || kind === 'feedback' && !currentRecord()) return null; return kind === 'checkin' ? draft(kind, state.data.checkIn, state.data.date) : draft(kind, currentRecord(), state.selected); }
    function fillForm(kind) {
        const form = forms[kind]; const value = activeDraft(kind);
        form.hidden = !value;
        if (!value) return;
        for (const field of form.elements) { if (field.name && Object.hasOwn(value.values, field.name)) field.value = value.values[field.name]; field.disabled = value.busy; }
        const save = form.querySelector('[type=submit]'); save.disabled = value.busy || value.blocked || !value.dirty.size;
        const status = $('#today-' + kind + '-state'); status.textContent = value.dirty.size ? '草稿 · 尚未保存' : value.base?.version ? '已保存 · v' + value.base.version : '尚未填写'; status.classList.toggle('dirty', value.dirty.size > 0);
        const output = $('#today-' + kind + '-status'); output.replaceChildren(); output.className = 'form-status full' + (value.error ? ' error' : ' success');
        if (value.message) output.append(node('p', '', value.message));
        if (value.blocked) output.append(button('读取最新版本，保留草稿', 'review-' + kind, () => reviewDraft(kind, value)));
    }
    function capture(kind, event) {
        const value = activeDraft(kind); const field = event.target;
        if (!value || !field.name || !Object.hasOwn(value.values, field.name)) return;
        value.values[field.name] = field.value; value.dirty.add(field.name); value.message = value.blocked ? value.message : ''; fillFormState(kind, value);
    }
    function fillFormState(kind, value) {
        $('#today-' + kind + '-state').textContent = '草稿 · 尚未保存'; $('#today-' + kind + '-state').classList.add('dirty');
        forms[kind].querySelector('[type=submit]').disabled = value.busy || value.blocked || !value.dirty.size;
    }
    function fieldValue(name, value) {
        if (name === 'pain') return value === '' ? null : value === 'true';
        if (['sleepHours', 'fatigue', 'soreness', 'rpe', 'fuel.carbsGrams', 'fuel.fluidMl'].includes(name)) return String(value).trim() === '' ? null : Number(value);
        if (name === 'thermalFeeling') return value || null;
        return value;
    }
    function draftPatch(value) {
        const patch = {};
        for (const name of value.dirty) {
            if (name.startsWith('fuel.')) { patch.fuel ||= {}; patch.fuel[name.slice(5)] = fieldValue(name, value.values[name]); }
            else patch[name] = fieldValue(name, value.values[name]);
        }
        return patch;
    }
    async function readDraftRecord(kind, value) {
        if (kind === 'feedback') return api('/sessions/' + encodeURIComponent(value.key));
        const result = await api('/check-ins?from=' + encodeURIComponent(value.key) + '&to=' + encodeURIComponent(value.key));
        return result.items?.find(record => record.date === value.key) || null;
    }
    function matches(actual, expected) {
        return Object.entries(expected).every(([key, value]) => value && typeof value === 'object' && !Array.isArray(value) ? matches(actual?.[key] || {}, value) : JSON.stringify(actual?.[key]) === JSON.stringify(value));
    }
    function savedDraft(kind, value, record) {
        const previous = value.base;
        value.base = record; value.dirty.clear(); value.blocked = false; value.error = false; value.message = '已保存并核验 · 版本 ' + record.version;
        value.values = kind === 'checkin' ? checkinValues(record) : feedbackValues(record);
        if (kind === 'feedback') {
            const comparison = state.comparisons.get(record.id); const links = state.links.get(record.id);
            const context = row => Object.fromEntries(Object.entries(row).filter(([key]) => !['feedback', 'version', 'updatedAt'].includes(key)));
            // Advance only our verified, single-version feedback-only write. Unrelated changes
            // and previously blocked conflicts still require a fresh comparison and user review.
            if (comparison && previous && comparison.planned.version === previous.version && record.version === previous.version + 1 && matches(context(record), context(comparison.planned)) && matches(context(comparison.planned), context(record))) {
                rememberComparison({...comparison, planned: record});
                if (links && !links.blocked && links.baseVersion === previous.version) links.baseVersion = record.version;
            } else if (links && links.baseVersion < record.version) {
                links.blocked = true; links.confirming = false; links.error = true; links.message = '课次版本已变化，请刷新对账并核对当前关联；原选择仍保留。';
            }
            state.records.set(record.id, latestRecord(record));
            if (state.selected === record.id) renderComparison();
        } else if (state.data.date === value.key) state.data.checkIn = record;
    }
    function readableSavedValues(kind, record) {
        const output = node('div', 'today-server-values');
        if (!record) return add(output, node('p', '', '该日期还没有已保存记录。'));
        const pain = value => value == null ? '未记录' : value ? '有' : '没有';
        const quantity = (value, unit) => value == null ? '未记录' : amount(value, unit);
        const score = (value, maximum) => value == null ? '未记录' : `${value}/${maximum}`;
        const feedback = record.feedback || {}; const fuel = feedback.fuel || {};
        const rows = kind === 'checkin' ? [
            ['日期', record.date], ['昨晚睡眠', quantity(record.sleepHours, '小时')],
            ['疲劳程度', score(record.fatigue, 5)], ['肌肉酸痛', score(record.soreness, 5)],
            ['疼痛或不适', pain(record.pain)], ['状态备注', record.notes || '未记录'],
        ] : [
            ['主观强度', score(feedback.rpe, 10)],
            ['冷热体感', ({comfortable: '舒适', cold: '偏冷', hot: '偏热'})[feedback.thermalFeeling] || '未记录'],
            ['跑后疼痛或不适', pain(feedback.pain)], ['感受与观察', feedback.notes || '未记录'],
            ['碳水', quantity(fuel.carbsGrams, '克')], ['饮水', quantity(fuel.fluidMl, '毫升')],
            ['肠胃感受', ({good: '舒适', mild: '轻微不适', poor: '明显不适'})[fuel.giComfort] || '未记录'],
            ['补给备注', fuel.notes || '未记录'],
        ];
        rows.forEach(([label, value]) => output.append(node('p', '', `${label}：${value}`)));
        return output;
    }
    async function reviewDraft(kind, value) {
        if (value.busy) return; value.busy = true;
        try {
            const latest = await readDraftRecord(kind, value); const status = $('#today-' + kind + '-status');
            status.replaceChildren(node('p', '', '最新保存内容如下。你的草稿仍保留；核对后才可继续保存。'));
            status.append(readableSavedValues(kind, latest));
            status.append(button('已核对，保留草稿继续编辑', 'accept-' + kind, () => {
                value.base = latest; value.blocked = false; value.error = false; value.message = '已采用最新版本；仅保存你改动过的字段。'; fillForm(kind);
            }));
        } catch (error) { value.message = error.message; value.error = true; value.busy = false; fillForm(kind); }
        finally { value.busy = false; }
    }
    async function submitDraft(kind, event) {
        event.preventDefault(); const value = activeDraft(kind); if (!value || value.busy || value.blocked || !value.dirty.size || !forms[kind].reportValidity()) return;
        const patch = draftPatch(value); let payload, path, method;
        if (kind === 'feedback') { payload = {version: value.base.version, feedback: patch}; path = '/sessions/' + encodeURIComponent(value.key) + '/feedback'; method = 'PUT'; }
        else if (value.base) { payload = {version: value.base.version, ...patch}; path = '/check-ins/' + encodeURIComponent(value.base.id); method = 'PUT'; }
        else { payload = {id: 'checkin-' + value.key, date: value.key, fatigue: null, soreness: null, pain: null, ...patch}; path = '/check-ins'; method = 'POST'; }
        value.busy = true; value.message = '正在保存并核验…'; value.error = false; fillForm(kind);
        try {
            await api(path, method, payload);
            try { const latest = await readDraftRecord(kind, value); if (!latest || !matches(kind === 'feedback' ? latest.feedback : latest, patch)) throw new Error('读取内容与本次修改不一致。'); savedDraft(kind, value, latest); }
            catch (error) { value.blocked = true; value.error = true; value.message = '服务器已接收保存，但核验未完成。请先读取最新内容，勿重复提交。' + error.message; }
        } catch (error) {
            value.error = true;
            if (error.uncertain) {
                value.blocked = true;
                try { const latest = await readDraftRecord(kind, value); if (latest && matches(kind === 'feedback' ? latest.feedback : latest, patch)) savedDraft(kind, value, latest); else value.message = '已读取服务器，内容与草稿不一致。请核对最新版本后再决定是否保存；没有自动重试。'; }
                catch { value.message = '保存结果尚未确定，读取核验也失败。草稿已保留；先读取最新版本再继续。'; }
            } else { value.blocked = error.status === 409; value.message = error.status === 409 ? '保存冲突：记录已更新。草稿保留，请读取并核对最新版本。' : error.message; }
        } finally { value.busy = false; fillForm(kind); }
        if (!value.dirty.size) await refresh(false);
    }
    for (const [kind, form] of Object.entries(forms)) { form.addEventListener('input', event => capture(kind, event)); form.addEventListener('change', event => capture(kind, event)); form.addEventListener('submit', event => submitDraft(kind, event)); }
    function renderSessions() {
        const container = $('#today-sessions'); container.replaceChildren();
        const sessions = state.data.sessions || [];
        if (!sessions.length) container.append(node('p', 'empty-state', '今天没有已安排的课次。可以先记下身体状态，或打开完整课表安排训练。'));
        sessions.forEach(record => {
            const item = button('', '', () => selectSession(record.id), 'today-session'); item.dataset.sessionId = record.id; item.setAttribute('aria-pressed', String(record.id === state.selected));
            add(item, node('span', 'today-session-time', format(record.startAt, true)), add(node('span', 'today-session-copy'), node('span', 'today-session-title', record.title), node('span', 'today-session-meta', `${types[record.type] || record.type} · ${amount(record.durationMinutes, '分钟')}${record.distanceKm == null ? '' : ' · ' + amount(record.distanceKm, '公里')}`)), node('span', 'pill ' + record.status, statuses[record.status] || record.status)); container.append(item);
        });
        const select = $('#today-feedback-session'); select.replaceChildren();
        for (const record of state.records.values()) select.add(new Option(`${format(record.startAt)} · ${record.title}`, record.id));
        if (!state.records.size) select.add(new Option('暂无可选课次', ''));
        select.value = state.selected || '';
        const selected = currentRecord(); $('#today-selected-meta').textContent = selected ? `${statuses[selected.status] || selected.status} · ${amount(selected.durationMinutes, '分钟')} · ${amount(selected.distanceKm, '公里')}` : '有课次后再记录对应的跑后感受。';
    }
    function renderHealth() {
        const target = $('#today-health'); target.replaceChildren(); const constraints = state.data.health?.constraints || [];
        if (!constraints.length) target.append(node('p', 'muted', state.data.health?.version ? '没有适用于今天的已记录限制；请继续结合当前身体感受。' : '尚无健康背景记录；这不代表已获得运动许可。'));
        constraints.forEach(constraint => { const box = node('div', 'today-constraint'); add(box, node('p', '', constraint.description), node('p', 'muted', `${constraint.sourceType === 'clinician' ? '记录的医生建议' : '用户记录'}${constraint.sourceReportId ? ' · 来源 ' + constraint.sourceReportId : ''} · ${constraint.validFrom || '生效日期未注明'}${constraint.reviewOn ? ' · 复核日期 ' + constraint.reviewOn : ''}`)); target.append(box); });
    }
    function renderPending() {
        const combined = new Map();
        for (const record of state.data.pendingFeedback || []) combined.set(record.id, {record, labels: ['待补充跑后反馈']});
        for (const item of state.data.reconciliation?.items || []) { const record = item.planned; if (!record) continue; const existing = combined.get(record.id) || {record, labels: []}; existing.labels.push('待核对真实跑步'); combined.set(record.id, existing); }
        $('#today-pending-count').textContent = combined.size; const target = $('#today-pending-list'); target.replaceChildren();
        if (!combined.size) target.append(node('p', 'muted', '最近没有待处理的反馈或对账；这不代表导入记录完整。'));
        for (const {record, labels} of combined.values()) { const action = button('查看', '', () => { selectSession(record.id); $('#today-followup-card').scrollIntoView({block: 'start', behavior: 'instant'}); }); action.dataset.sessionId = record.id; target.append(add(node('div', 'today-pending-row'), add(node('div'), node('strong', '', record.title), node('p', 'muted', format(record.startAt) + ' · ' + labels.join(' / '))), action)); }
    }
    function renderQuality() { const target = $('#today-data-quality'); target.replaceChildren(); messages(state.data.dataQuality).forEach(text => target.append(node('p', '', text))); target.append(node('p', '', `日期按 ${timezone()} 计算。未导入或未填写的信息保持未知。更新于 ${format(state.data.generatedAt)}。`)); }
    async function loadBriefing() {
        const sequence = ++state.briefingId; const target = $('#today-briefing'); const record = currentRecord();
        if (!record || localDate(record.startAt) !== state.data.date) { target.replaceChildren(node('p', 'muted', record ? '当前查看的是其他日期的课次。选择今日安排，查看对应时段的天气。' : '今天还没有课次；出发简报会在安排训练后出现。')); return; }
        target.replaceChildren(node('p', 'muted', '正在读取这节课的天气…'));
        try {
            const briefing = await api('/briefing?sessionId=' + encodeURIComponent(record.id)); if (sequence !== state.briefingId) return;
            const forecast = briefing.forecast || {}; const advice = briefing.advice || {}; target.replaceChildren(node('p', 'briefing-date', `${record.title} · ${format(record.startAt)} · ${timezone()}`));
            if (advice.summary) target.append(node('p', 'briefing-summary', advice.summary));
            if (forecast.status !== 'available') target.append(node('p', 'notice', forecast.message || '天气暂不可用；仍可以记录感受和核对跑步。'));
            const hour = (forecast.sessionHours || forecast.hours || [])[0];
            if (hour) { const strip = node('div', 'today-weather'); [[hour.temperature, '°C', '气温'], [hour.apparentTemperature, '°C', '体感'], [hour.precipitationProbability, '%', '降水概率']].forEach(([value, unit, label]) => strip.append(add(node('div'), node('strong', '', value == null ? '未知' : value + unit), node('span', '', label)))); target.append(strip); }
            for (const [label, values] of [['穿衣参考', advice.clothing], ['需要留意', advice.warnings]]) { const texts = messages(values); if (!texts.length) continue; const list = node('ul', 'advice-list'); texts.forEach(text => list.append(node('li', '', text))); target.append(add(node('div'), node('h3', '', label), list)); }
            target.append(node('p', 'data-quality', `${forecast.source || '天气来源未提供'} · ${forecast.fetchedAt ? '获取于 ' + format(forecast.fetchedAt) : '尚无天气更新时间'}。仅对应当前选中的课次。`));
        } catch (error) { if (sequence === state.briefingId) target.replaceChildren(node('p', 'notice error', '天气简报暂不可用。' + error.message)); }
    }
    function linkDraft(comparison) {
        const record = comparison.planned; let value = state.links.get(record.id);
        if (!value) { value = {selected: new Set(linkedIds(record)), baseVersion: record.version, dirty: false, blocked: false, busy: false, confirming: false, message: '', error: false}; state.links.set(record.id, value); }
        else if (!value.dirty && !value.blocked && !value.busy) { value.selected = new Set(linkedIds(record)); value.baseVersion = record.version; }
        return value;
    }
    function comparisonRows(comparison) {
        const rows = new Map(); for (const record of [...(comparison.actualActivities || []), ...(comparison.candidates || [])]) rows.set(record.id, record);
        for (const id of linkedIds(comparison.planned)) if (!rows.has(id)) rows.set(id, {id, name: '已关联记录（原活动暂不可用）', missing: true});
        return rows;
    }
    function renderComparison() {
        const target = $('#today-reconciliation'); target.replaceChildren(); const comparison = state.comparisons.get(state.selected);
        if (!comparison) { target.append(node('p', 'muted', currentRecord() ? '正在读取这节课的真实跑步…' : '选中课次后，可核对真实跑步。')); return; }
        const value = linkDraft(comparison); const rows = comparisonRows(comparison); const current = new Set(linkedIds(comparison.planned));
        // Retain selections even when a refreshed candidate list no longer contains the record.
        for (const id of value.selected) if (!rows.has(id)) rows.set(id, {id, name: '草稿中选择的记录（当前不可用，请取消选择）', missing: true});
        add(target, node('h3', '', '计划 × 真实跑步'), node('p', 'muted', current.size ? `已关联 ${current.size} 条 · 实际 ${amount(comparison.actual?.distanceKm, '公里')} / ${amount(comparison.actual?.durationMinutes, '分钟')}` : '尚未关联真实跑步。候选不会自动选中。'));
        if (comparison.delta) target.append(node('p', 'row-note', `实际 − 计划：${amount(comparison.delta.distanceKm, '公里')} · ${amount(comparison.delta.durationMinutes, '分钟')}`));
        messages(comparison.dataQuality).forEach(text => target.append(node('p', 'row-note', text)));
        if (!rows.size) target.append(node('p', 'empty-state', '目前没有可关联的跑步记录。尚未导入的数据是未知，不等于没有跑步。'));
        for (const record of rows.values()) {
            const label = node('label', 'today-candidate'); const input = node('input'); input.type = 'checkbox'; input.dataset.activityId = record.id; input.checked = value.selected.has(record.id); input.disabled = value.busy;
            const info = node('span'); add(info, node('strong', '', record.name || record.id), node('small', '', record.missing ? '不能核验此活动；保留原关联或明确解除，不能当作零运动量。' : `${format(record.startAt)} · ${amount(record.distanceKm, '公里')} · ${amount(record.durationMinutes, '分钟')}`), node('small', '', current.has(record.id) ? '当前已关联' : messages(record.matchReasons).join(' · ') || '待你核对'));
            if (record.startOffsetMinutes != null) info.append(node('small', '', startOffset(record.startOffsetMinutes)));
            if (record.averageHeartRate != null) info.append(node('small', '', `平均心率 ${record.averageHeartRate} bpm`));
            input.addEventListener('change', () => { if (input.checked) value.selected.add(record.id); else value.selected.delete(record.id); value.dirty = true; value.confirming = false; renderComparison(); }); add(label, input, info); target.append(label);
        }
        const selected = [...value.selected].map(id => rows.get(id)); const missing = selected.some(record => record.missing); const distance = selected.reduce((sum, record) => sum + (record.distanceKm || 0), 0); const duration = selected.reduce((sum, record) => sum + (record.durationMinutes || 0), 0);
        target.append(node('p', 'today-match-summary', `已选择 ${selected.length} 条${missing ? ' · 部分活动不可用，合计未知' : ` · 合计 ${amount(distance, '公里')} / ${amount(duration, '分钟')}`}。分段文件之间的间隔不计为运动；合计不代表完成所有分段目标。`));
        if (value.message) target.append(node('p', 'form-status ' + (value.error ? 'error' : 'success'), value.message));
        const review = button('核对所选记录', 'review-links', () => { value.confirming = true; renderComparison(); }, 'button primary');
        const unchanged = value.selected.size === current.size && [...value.selected].every(id => current.has(id));
        review.disabled = value.busy || value.blocked || unchanged || selected.length > 20 || missing;
        const refreshButton = button('刷新对账，保留选择', 'refresh-comparison', () => loadComparison(state.selected, true)); refreshButton.disabled = value.busy;
        target.append(add(node('div', 'today-reconcile-actions'), review, refreshButton));
        if (selected.length > 20) target.append(node('p', 'form-status error', '每节课最多关联 20 条记录，请减少选择。'));
        if (value.confirming && !value.blocked) {
            const confirmation = node('div', 'today-confirm'); confirmation.setAttribute('role', 'group'); confirmation.setAttribute('aria-label', '确认跑步关联');
            add(confirmation, node('h3', '', selected.length ? `将这 ${selected.length} 条跑步关联到「${comparison.planned.title}」` : '解除这节课的全部活动关联'), node('p', 'muted', selected.length ? `总计 ${amount(distance, '公里')}，${amount(duration, '分钟')}。确认后标为已完成。` : '会移除全部关联；课次当前状态保留，不自动改回计划中。'));
            const list = node('ul'); selected.forEach(record => list.append(node('li', '', `${record.name} · ${format(record.startAt)}`))); confirmation.append(list);
            const confirm = button(selected.length ? '确认关联并标为完成' : '确认解除全部关联', 'confirm-links', () => saveLinks(comparison.planned.id), 'button primary'); confirm.disabled = value.busy;
            confirmation.append(add(node('div', 'form-actions'), confirm, button('返回选择', 'cancel-links', () => { value.confirming = false; renderComparison(); }))); target.append(confirmation);
        }
    }
    async function loadComparison(id, explicit = false) {
        if (!id) return; const sequence = ++state.comparisonId;
        try {
            const comparison = await api('/sessions/' + encodeURIComponent(id) + '/comparison');
            if (sequence !== state.comparisonId || state.selected !== id || !rememberComparison(comparison)) return;
            const value = linkDraft(comparison);
            if (explicit) { value.baseVersion = comparison.planned.version; value.blocked = false; value.confirming = false; value.error = false; value.message = '已读取最新对账，保留你的选择。请核对当前已关联记录后再确认。'; }
            if (sequence === state.comparisonId && state.selected === id) { renderComparison(); fillForm('feedback'); }
        } catch (error) { if (sequence === state.comparisonId && state.selected === id) { const target = $('#today-reconciliation'); target.replaceChildren(node('p', 'form-status error', '对账读取失败，选择草稿仍保留。' + error.message), button('重新读取对账', 'refresh-comparison', () => loadComparison(id, true))); } }
    }
    async function saveLinks(id) {
        const value = state.links.get(id); if (!value || value.busy || value.blocked) return;
        value.busy = true; value.message = '正在保存并核验关联…'; value.error = false; renderComparison(); const ids = [...value.selected];
        try {
            await api('/sessions/' + encodeURIComponent(id) + '/link', 'POST', {version: value.baseVersion, activityIds: ids});
            const latest = await api('/sessions/' + encodeURIComponent(id) + '/comparison');
            if (JSON.stringify([...linkedIds(latest.planned)].sort()) !== JSON.stringify([...ids].sort())) { const error = new Error('核验时关联已有变化。'); error.status = 409; throw error; }
            rememberComparison(latest); value.baseVersion = latest.planned.version; value.dirty = false; value.confirming = false; value.message = '已保存并核验关联 · 版本 ' + latest.planned.version;
        } catch (error) {
            value.error = true; value.blocked = true; value.confirming = false;
            if (error.uncertain) {
                try { const latest = await api('/sessions/' + encodeURIComponent(id) + '/comparison'); rememberComparison(latest);
                    if (JSON.stringify([...linkedIds(latest.planned)].sort()) === JSON.stringify([...ids].sort())) { value.baseVersion = latest.planned.version; value.dirty = false; value.blocked = false; value.error = false; value.message = '已读取并确认关联保存成功 · 版本 ' + latest.planned.version; }
                    else value.message = '已读取服务器，关联与草稿不同。请刷新对账并重新核对；没有自动重试。';
                } catch { value.message = '保存结果尚未确定，核验也未完成。选择已保留；请先刷新对账。'; }
            } else value.message = error.status === 409 ? '关联冲突：课次或活动已更新。选择仍保留，请先刷新对账，再核对并确认。' : '保存或核验未完成。选择已保留，请先刷新对账。' + error.message;
        } finally { value.busy = false; if (state.selected === id) { renderComparison(); fillForm('feedback'); } }
        if (!value.dirty && !value.blocked) await refresh(false);
    }
    async function selectSession(id) {
        state.selected = id || null; const url = new URL(location.href); if (id) url.searchParams.set('sessionId', id); else url.searchParams.delete('sessionId'); history.replaceState(null, '', url);
        renderSessions(); fillForm('feedback'); renderComparison(); await Promise.allSettled([loadBriefing(), loadComparison(id)]);
    }
    $('#today-feedback-session').addEventListener('change', event => selectSession(event.target.value));
    async function refresh(showNotice = true) {
        const sequence = ++state.refreshId; const trigger = $('#today-refresh'); trigger.disabled = true;
        try {
            const data = await api('/today'); if (sequence !== state.refreshId) return;
            const records = new Map(); let selectionNotice = '';
            for (const record of [...(data.sessions || []), ...(data.pendingFeedback || []), ...(data.reconciliation?.items || []).map(item => item.planned)]) if (record) records.set(record.id, record);
            if (state.selected && !records.has(state.selected)) {
                if (state.selected === requestedSession) {
                    try { const record = await api('/sessions/' + encodeURIComponent(state.selected)); records.set(record.id, record); }
                    catch (error) { selectionNotice = '指定课次暂不可用，未保存草稿仍保留在当前页面。' + error.message; }
                } else selectionNotice = '之前选择的课次已不在今日或近期列表中，可能已改期或删除。未保存草稿仍保留在当前页面。';
            }
            if (sequence !== state.refreshId) return;
            for (const [id, record] of records) records.set(id, latestRecord(record));
            state.records = records;
            state.data = {...data, sessions: (data.sessions || []).map(record => records.get(record.id)), pendingFeedback: (data.pendingFeedback || []).map(record => records.get(record.id))};
            for (const id of state.comparisons.keys()) if (!records.has(id)) state.comparisons.delete(id);
            if (!records.has(state.selected)) { state.selected = null; ++state.comparisonId; ++state.briefingId; }
            if (!state.selected) state.selected = data.sessions?.[0]?.id || data.pendingFeedback?.[0]?.id || data.reconciliation?.items?.[0]?.planned?.id || null;
            $('#today-date').textContent = `${data.date} · ${data.timezone}`; renderSessions(); renderHealth(); renderPending(); renderQuality(); fillForm('checkin'); fillForm('feedback'); renderComparison();
            if (selectionNotice) notice(selectionNotice, true);
            else if (showNotice) notice('今日数据已刷新。未保存的内容仍保留在当前页面。');
            await Promise.allSettled([loadBriefing(), loadComparison(state.selected)]);
        } catch (error) { notice('今日数据读取失败；保留原有内容与草稿。' + error.message, true); }
        finally { if (sequence === state.refreshId) trigger.disabled = false; }
    }
    $('#today-refresh').addEventListener('click', () => refresh());
    window.addEventListener('beforeunload', event => { if ([...state.checkins.values(), ...state.feedback.values()].some(value => value.dirty.size) || [...state.links.values()].some(value => value.dirty)) { event.preventDefault(); event.returnValue = ''; } });
    Object.values(forms).forEach(form => { for (const field of form.elements) field.disabled = true; });
    refresh(false);
})();
