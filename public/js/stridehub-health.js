(() => {
    'use strict';
    const app = document.getElementById('health-app');
    if (!app) return;
    const $ = selector => document.querySelector(selector);
    const form = $('#pressure-form');
    const range = $('#health-range');
    const fields = ['measuredAt', 'systolic', 'diastolic', 'pulse', 'arm', 'posture', 'notes'];
    const labels = {unknown: '未记录', left: '左臂', right: '右臂', seated: '坐姿', standing: '站姿', lying: '卧姿'};
    const state = {records: [], sleeps: [], timezone: 'Asia/Shanghai', id: null, version: 0, dirty: false, busy: false, blocked: false, refresh: 0, shown: 20, sleepShown: 20, requestedRange: null, pressureRange: null, sleepRange: null};
    const sync = {status: null, posting: false, checking: false, timer: null, started: 0, attempts: 0, stopped: false, sequence: 0, notice: ''};
    const node = (tag, cls, text) => { const el = document.createElement(tag); if (cls) el.className = cls; if (text !== undefined) el.textContent = String(text); return el; };
    const button = (label, handler, cls = 'button small') => { const el = node('button', cls, label); el.type = 'button'; el.addEventListener('click', handler); return el; };
    const deviceZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const localInput = date => { const d = new Date(date); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19); };
    const dateKey = date => { const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {timeZone: state.timezone, year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(date).map(x => [x.type, x.value])); return `${p.year}-${p.month}-${p.day}`; };
    const displayTime = value => value ? new Intl.DateTimeFormat('zh-CN', {timeZone: state.timezone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(value)) : '未知';
    const minutes = value => value == null ? '未知' : `${Math.floor(value / 60)} 小时 ${Math.round(value % 60)} 分`;
    const output = (selector, text, error = false) => { const el = $(selector); el.textContent = text; el.classList.toggle('health-error', error); el.hidden = !text; };
    const rangeLabel = value => `${value.from} 至 ${value.to}`;
    const dayCount = value => Math.round((Date.parse(value.to) - Date.parse(value.from)) / 86400000) + 1;
    function recentRange(days) { const today = dateKey(new Date()); const before = new Date(today + 'T12:00:00Z'); before.setUTCDate(before.getUTCDate() - days + 1); return {from: before.toISOString().slice(0, 10), to: today}; }
    function renderRange() {
        if (!state.requestedRange) return;
        const changed = range.elements.from.value !== state.requestedRange.from || range.elements.to.value !== state.requestedRange.to;
        output('#health-applied-range', `已应用：${rangeLabel(state.requestedRange)}。${changed ? '日期输入尚未应用，点击“查看这段时间”后更新。' : '各区域下方标明实际载入范围。'}`);
        for (const shortcut of document.querySelectorAll('[data-health-days]')) {
            const value = recentRange(Number(shortcut.dataset.healthDays));
            shortcut.setAttribute('aria-pressed', String(!changed && value.from === state.requestedRange.from && value.to === state.requestedRange.to));
        }
    }
    async function fetchWithin(url, options = {}, timeout = 20000) {
        const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeout);
        try { return await fetch(url, {...options, signal: controller.signal}); }
        finally { clearTimeout(timer); }
    }
    async function renewPageToken() {
        const response = await fetchWithin(location.pathname, {credentials: 'same-origin', cache: 'no-store'});
        const html = new DOMParser().parseFromString(await response.text(), 'text/html');
        const token = html.querySelector('#health-app')?.dataset.csrf;
        if (!response.ok || !token) { const error = new Error('登录已过期。请在另一个标签页重新登录，再回到这里保存；草稿仍保留。'); error.status = 403; throw error; }
        app.dataset.csrf = token;
    }
    async function request(path, method = 'GET', body, absolute = false) {
        // Re-login in another tab can replace the session. Refresh only the token, never the form.
        if (method !== 'GET') await renewPageToken();
        let response;
        try { response = await fetchWithin(absolute ? path : app.dataset.apiBase + path, {method, credentials: 'same-origin', headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(method === 'GET' ? {} : {'X-CSRF-Token': app.dataset.csrf})}, body: body === undefined ? undefined : JSON.stringify(body)}); }
        catch { const error = new Error(method === 'GET' ? '网络连接失败，请重试。' : '连接中断，操作结果尚未确定。请读取最新记录后核对。'); error.uncertain = method !== 'GET'; throw error; }
        let data;
        try { data = response.status === 204 ? null : await response.json(); }
        catch { const error = new Error('登录可能已过期。请在另一个标签页重新登录；当前草稿仍保留。'); error.uncertain = method !== 'GET'; throw error; }
        if (!response.ok) { const error = new Error(response.status === 403 ? '页面校验已过期。请保留草稿，在另一个标签页重新登录后再试。' : data.message || [data.lastError, data.nextAction].filter(Boolean).join(' ') || `请求失败（${response.status}）`); error.status = response.status; error.data = data; error.uncertain = method !== 'GET' && response.status >= 500; throw error; }
        return data;
    }
    function updateEditor() {
        for (const field of form.elements) field.disabled = state.busy;
        form.querySelector('[type=submit]').disabled = state.busy || state.blocked || !state.dirty;
        $('#pressure-review').hidden = !state.blocked;
        $('#pressure-save-state').textContent = state.dirty ? '草稿 · 尚未保存' : state.version ? '已保存' : '新记录';
        $('#pressure-new').disabled = state.busy;
    }
    function resetEditor(record = null) {
        state.id = record?.id || null; state.version = record?.version || 0; state.dirty = false; state.blocked = false;
        form.reset();
        for (const name of fields) form.elements[name].value = name === 'measuredAt' ? localInput(record?.measuredAt || new Date()) : record?.[name] ?? (['arm', 'posture'].includes(name) ? 'unknown' : '');
        output('#pressure-form-status', ''); updateEditor();
    }
    function mayDiscard() { return !state.dirty || window.confirm('当前有未保存的血压草稿，确定放弃它吗？'); }
    function edit(record) { if (state.busy || !mayDiscard()) return; resetEditor(record); $('#pressure-editor').open = true; $('#pressure-editor').scrollIntoView({behavior: 'auto', block: 'start'}); form.elements.measuredAt.focus(); }
    function values() { return {measuredAt: new Date(form.elements.measuredAt.value).toISOString().replace(/\.\d{3}Z$/, 'Z'), systolic: Number(form.elements.systolic.value), diastolic: Number(form.elements.diastolic.value), pulse: form.elements.pulse.value === '' ? null : Number(form.elements.pulse.value), arm: form.elements.arm.value, posture: form.elements.posture.value, notes: form.elements.notes.value}; }
    function same(record, expected) { return fields.every(key => key === 'measuredAt' ? new Date(record[key]).getTime() === new Date(expected[key]).getTime() : record[key] === expected[key]); }
    function newId() { const bytes = crypto.getRandomValues(new Uint8Array(16)); return 'bp-' + [...bytes].map(x => x.toString(16).padStart(2, '0')).join(''); }
    async function save(event) {
        event.preventDefault(); if (state.busy || state.blocked || !state.dirty) return;
        const payload = values();
        if (payload.systolic <= payload.diastolic) { output('#pressure-form-status', '收缩压应高于舒张压，请核对输入。', true); return; }
        state.id ||= newId(); state.busy = true; updateEditor(); output('#pressure-form-status', '正在保存并核对…');
        let acknowledged = false;
        try {
            const saved = await request('/blood-pressure/' + state.id, 'PUT', {version: state.version, ...payload});
            acknowledged = true;
            const confirmed = await request('/blood-pressure/' + state.id);
            if (confirmed.version !== saved.version || !same(confirmed, payload)) { const error = new Error('记录在保存后又发生变化。请读取最新记录，核对后再保存。'); error.status = 409; throw error; }
            resetEditor(confirmed); output('#pressure-form-status', '已保存并核验。'); await refresh();
        } catch (error) { state.blocked = acknowledged || error.status === 409 || error.uncertain; output('#pressure-form-status', error.message, true); }
        finally { state.busy = false; updateEditor(); }
    }
    async function review() {
        if (state.busy || !state.id) return;
        state.busy = true; updateEditor();
        try {
            const current = await request('/blood-pressure/' + state.id);
            if (same(current, values())) { resetEditor(current); output('#pressure-form-status', '已确认：服务器保存的内容与当前草稿一致。'); }
            else { state.version = current.version; state.blocked = false; output('#pressure-form-status', `已读取最新记录：${displayTime(current.measuredAt)}，${current.systolic}/${current.diastolic} mmHg，脉搏 ${current.pulse ?? '未知'}，${labels[current.arm]}，${labels[current.posture]}，备注：${current.notes || '无'}。你的草稿仍保留；核对后再次点“保存血压”才会覆盖。`); }
            await refresh();
        } catch (error) {
            if (error.status === 404) { state.id = null; state.version = 0; state.blocked = false; output('#pressure-form-status', '服务器已无此记录。草稿仍保留，可再次保存为新记录。'); }
            else output('#pressure-form-status', error.message, true);
        } finally { state.busy = false; updateEditor(); }
    }
    async function remove(record) {
        if (state.busy || !window.confirm(`删除 ${displayTime(record.measuredAt)} 的 ${record.systolic}/${record.diastolic} mmHg 记录？`)) return;
        if (state.id === record.id && !mayDiscard()) return;
        state.busy = true; updateEditor();
        try { await request('/blood-pressure/' + record.id + '?version=' + record.version, 'DELETE'); if (state.id === record.id) resetEditor(); await refresh(); output('#pressure-status', '记录已删除。'); }
        catch (error) { output('#pressure-status', error.message + ' 请刷新记录后核对，再决定是否删除。', true); }
        finally { state.busy = false; updateEditor(); }
    }
    function metric(target, label, value, detail) { const el = node('div', 'health-metric'); el.append(node('span', '', label), node('strong', '', value)); if (detail) el.append(node('span', '', detail)); target.append(el); }
    const svgNode = (tag, attributes = {}, text) => { const el = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const [k,v] of Object.entries(attributes)) el.setAttribute(k, v); if (text !== undefined) el.textContent = text; return el; };
    function chartFrame(target, label, max, min = 0) {
        target.replaceChildren(); const width = Math.max(260, Math.min(720, target.clientWidth || 720)); const left = 46, right = width - 20; const svg = svgNode('svg', {viewBox: `0 0 ${width} 240`, role: 'img', 'aria-label': label}); svg.append(svgNode('title', {}, label));
        const y = value => 194 - (value - min) / Math.max(1, max - min) * 164;
        for (let i = 0; i <= 4; i++) { const value = min + (max - min) / 4 * i; svg.append(svgNode('line', {x1: left, x2: right, y1: y(value), y2: y(value), class: 'grid'}), svgNode('text', {x: 38, y: y(value) + 4, 'text-anchor': 'end'}, Math.round(value))); }
        target.append(svg); return {svg, y, left, right};
    }
    function pressureChart(records) {
        const target = $('#pressure-chart');
        if (!records.length) { target.replaceChildren(node('p', 'health-empty', '这段时间还没有血压记录。点击“记一次血压”开始记录。')); return; }
        const sorted = [...records].sort((a,b) => new Date(a.measuredAt) - new Date(b.measuredAt));
        const start = +new Date(sorted[0].measuredAt), end = +new Date(sorted.at(-1).measuredAt);
        const max = Math.ceil(Math.max(...records.map(r => r.systolic)) / 20) * 20 + 20;
        const min = Math.max(0, Math.floor(Math.min(...records.map(r => r.diastolic)) / 20) * 20 - 20);
        const {svg, y, left, right} = chartFrame(target, '收缩压与舒张压趋势，单位 mmHg；下方逐次记录可读取全部数值。', max, min);
        const x = r => start === end ? (left + right) / 2 : left + 5 + (+new Date(r.measuredAt) - start) / (end - start) * (right - left - 10);
        for (const [field, cls] of [['systolic','high'], ['diastolic','low']]) {
            svg.append(svgNode('polyline', {points: sorted.map(r => `${x(r)},${y(r[field])}`).join(' '), fill: 'none', 'stroke-width': 2, class: cls}));
            for (const r of sorted) { const dot = svgNode('circle', {cx: x(r), cy: y(r[field]), r: 3, fill: 'white', 'stroke-width': 2, class: cls}); dot.append(svgNode('title', {}, `${displayTime(r.measuredAt)} ${r[field]} mmHg`)); svg.append(dot); }
        }
        svg.append(svgNode('text', {x: left, y: 220}, displayTime(sorted[0].measuredAt)), svgNode('text', {x: right, y: 220, 'text-anchor': 'end'}, sorted.length > 1 ? displayTime(sorted.at(-1).measuredAt) : ''));
        const legend = node('div', 'health-legend'); legend.append(node('span', 'high', '收缩压 / mmHg'), node('span', 'low', '舒张压 / mmHg')); target.append(legend);
    }
    function renderPressure() {
        const records = state.records; const target = $('#pressure-records'); target.replaceChildren();
        if (state.pressureRange) output('#pressure-range', `当前显示：${rangeLabel(state.pressureRange)} · ${records.length} 次测量。每次测量单独保留。`);
        const summary = $('#pressure-summary'); summary.replaceChildren();
        if (records.length) { const latest = records[0]; metric(summary, '期间最近一次', `${latest.systolic} / ${latest.diastolic}`, `mmHg · ${displayTime(latest.measuredAt)}`); metric(summary, '该次脉搏', latest.pulse == null ? '未知' : latest.pulse, latest.pulse == null ? '未填写' : '次/分'); metric(summary, '期间测量次数', records.length, '每次测量单独保留'); }
        pressureChart(records);
        for (const record of records.slice(0, state.shown)) {
            const row = node('article', 'health-record'); row.dataset.recordId = record.id;
            const content = node('div', 'health-record-content'); content.append(node('p', 'health-record-title', `${record.systolic} / ${record.diastolic} mmHg`), node('p', 'health-record-meta', `${displayTime(record.measuredAt)} · 脉搏 ${record.pulse == null ? '未知' : record.pulse + ' 次/分'} · ${labels[record.arm]} · ${labels[record.posture]}`));
            if (record.notes) content.append(node('p', 'health-record-notes', record.notes));
            const actions = node('div', 'health-record-actions'); actions.append(button('修改', () => edit(record)), button('删除', () => remove(record), 'button small danger')); row.append(content, actions); target.append(row);
        }
        if (records.length > state.shown) target.append(button(`查看更多（还有 ${records.length - state.shown} 次）`, () => { state.shown += 20; renderPressure(); }));
    }
    function renderSleep() {
        const records = state.sleeps; const summary = $('#sleep-summary'); const target = $('#sleep-records'); summary.replaceChildren(); target.replaceChildren();
        const measured = records.filter(r => r.totalMinutes != null);
        if (state.sleepRange) {
            const covered = new Set(records.map(record => record.date)).size;
            output('#sleep-range', `当前显示：${rangeLabel(state.sleepRange)} · ${dayCount(state.sleepRange)} 天中有 ${covered} 天留有记录，${measured.length} 条有睡眠时长。空缺日期不代表没有睡眠，不计入平均。`);
        }
        if (records.length) { const latest = records[0]; metric(summary, '期间最近一晚', minutes(latest.totalMinutes), `${latest.date} · Zepp / Amazfit`); metric(summary, '有时长记录的平均时长', measured.length ? minutes(Math.round(measured.reduce((n,r) => n + r.totalMinutes, 0) / measured.length)) : '未知', `${measured.length} 条记录有时长，空缺日期不参与`); }
        const chart = $('#sleep-chart');
        if (!measured.length) chart.replaceChildren(node('p', 'health-empty', '这段时间暂无可用睡眠时长。可以扩大日期范围，或完成 Zepp 同步后重试。'));
        else {
            const sorted = [...measured].sort((a,b) => a.date.localeCompare(b.date)); const from = Date.parse(state.sleepRange.from), to = Date.parse(state.sleepRange.to);
            const {svg, y, left, right} = chartFrame(chart, '睡眠时长趋势，纵轴为小时；空缺日期保持空白。', Math.max(1, Math.ceil(Math.max(...measured.map(r => r.totalMinutes / 60)))));
            const width = Math.max(2, Math.min(32, (right - left) * .8 / Math.max(1, (to - from) / 86400000 + 1)));
            for (const record of sorted) { const x = from === to ? (left + right) / 2 : left + 5 + (Date.parse(record.date) - from) / (to - from) * (right - left - 10); const bar = svgNode('rect', {x: x - width/2, y: y(record.totalMinutes / 60), width, height: 194 - y(record.totalMinutes / 60), rx: 2, class: 'sleep-total'}); bar.append(svgNode('title', {}, `${record.date} ${minutes(record.totalMinutes)}`)); svg.append(bar); }
            svg.append(svgNode('text', {x: left, y: 220}, state.sleepRange.from.slice(5)), svgNode('text', {x: right, y: 220, 'text-anchor': 'end'}, state.sleepRange.to.slice(5)), svgNode('text', {x: 46, y: 18}, '小时'));
        }
        for (const record of records.slice(0, state.sleepShown)) {
            const row = node('article', 'health-record'); const content = node('div', 'health-record-content');
            content.append(node('p', 'health-record-title', `${record.date} · ${minutes(record.totalMinutes)}`), node('p', 'health-record-meta', `${displayTime(record.startedAt)} 入睡 — ${displayTime(record.endedAt)} 醒来 · 设备时区 ${record.timeZone || '未知'} · Zepp / Amazfit`));
            const stages = node('div', 'sleep-stages'); for (const [key,label] of [['deepMinutes','深睡'],['lightMinutes','浅睡'],['remMinutes','REM'],['awakeMinutes','清醒']]) stages.append(node('span','',`${label} ${record[key] == null ? '未知' : record[key] + ' 分'}`)); content.append(stages);
            content.append(node('p', 'health-record-meta', `睡眠评分 ${record.score ?? '未知'} · 静息心率 ${record.restingHeartRate == null ? '未知' : record.restingHeartRate + ' 次/分'} · 夜间清醒次数 ${record.wakeCount ?? '未知'}`)); row.append(content); target.append(row);
        }
        if (records.length > state.sleepShown) target.append(button(`查看更多（还有 ${records.length - state.sleepShown} 晚）`, () => { state.sleepShown += 20; renderSleep(); }));
    }
    function renderSync(status) {
        const known = {not_configured: '尚未连接 Zepp', idle: '等待首次同步', success: '同步完成', no_data: '上次同步检查未找到有效睡眠，历史记录仍保留', error: '同步未完成', syncing: '正在同步', failed: '同步未完成', auth_required: 'Zepp 登录已失效'};
        const last = status.lastSuccessAt ? `最近成功：${displayTime(status.lastSuccessAt)}。` : '尚无成功同步记录。';
        const title = sync.posting ? '正在同步最近 90 天的睡眠，请稍候' : known[status.state] || (status.configured ? 'Zepp 已连接' : '尚未连接 Zepp');
        const nextAction = status.state === 'no_data' ? status.nextAction?.replace('所选日期内', '本次同步检查范围内') : status.nextAction;
        output('#sleep-status', `${title}。${last}${status.lastError ? ' ' + status.lastError : ''}${nextAction ? ' ' + nextAction : ''}`, !!status.lastError && !sync.posting);
        const count = key => Number.isInteger(status[key]) && status[key] >= 0 ? status[key] : 0;
        output('#sleep-sync-result', status.lastAttemptAt ? `最近尝试：${displayTime(status.lastAttemptAt)} · 新增 ${count('imported')} 条 · 更新 ${count('updated')} 条 · 未变化 ${count('unchanged')} 条 · 跳过 ${count('skipped')} 条。${status.state === 'syncing' || sync.posting ? '同步尚未结束，数量以完成后为准。' : '跳过项可能是范围外或无法解析的记录。'}` : '');
        $('#sleep-setup').textContent = status.configured ? '连接已配置。后台会定期同步；手动同步可立即检查新记录。' : '尚未配置 Zepp 连接。请在运行 StrideHub 的服务器完成 Zepp 登录连接，再刷新本页。';
        updateSyncControls();
    }
    function updateSyncControls() {
        $('#sleep-sync').disabled = sync.posting || !sync.status?.configured || sync.status.state === 'syncing';
        $('#sleep-sync-refresh').hidden = !(sync.notice || sync.status?.state === 'syncing' || sync.status?.lastError);
        $('#sleep-sync-refresh').disabled = sync.checking;
        output('#sleep-sync-notice', sync.notice, !!sync.notice);
    }
    function stopPolling() { clearTimeout(sync.timer); sync.timer = null; }
    function schedulePoll() {
        if (sync.timer || sync.stopped) return;
        sync.started ||= Date.now();
        const remaining = 120000 - (Date.now() - sync.started);
        if (remaining <= 0) {
            sync.stopped = true;
            sync.notice = '自动检查已暂停。同步可能仍在服务器进行；点击“刷新同步状态”继续查看，已有记录保留。'; updateSyncControls(); return;
        }
        const delay = Math.min(remaining, Math.min(15000, 2000 * 2 ** Math.min(sync.attempts++, 3)));
        sync.timer = setTimeout(() => { sync.timer = null; if (Date.now() - sync.started >= 120000) schedulePoll(); else checkSyncStatus(); }, delay);
    }
    function acceptSync(status) {
        sync.status = status; renderSync(status);
        if (status.state === 'syncing' || sync.posting) schedulePoll();
        else { stopPolling(); sync.started = 0; sync.attempts = 0; sync.stopped = false; }
    }
    async function checkSyncStatus(resume = false) {
        if (resume) { stopPolling(); sync.started = Date.now(); sync.attempts = 0; sync.stopped = false; }
        const sequence = ++sync.sequence; const wasSyncing = sync.status?.state === 'syncing';
        sync.checking = true; updateSyncControls();
        try {
            const status = await request('/sleep/status');
            if (sequence !== sync.sequence) return;
            sync.notice = ''; acceptSync(status);
            if (wasSyncing && status.state !== 'syncing' && !sync.posting) await refresh(false);
        } catch (error) {
            if (sequence !== sync.sequence) return;
            sync.notice = '无法确认同步状态。' + error.message + ' 已有记录保留，可点击“刷新同步状态”重试。';
            if (sync.started) schedulePoll();
        } finally { if (sequence === sync.sequence) { sync.checking = false; updateSyncControls(); } }
    }
    async function synchronize() {
        if (sync.posting || !sync.status?.configured || sync.status.state === 'syncing') return;
        stopPolling(); sync.posting = true; sync.notice = ''; sync.started = Date.now(); sync.attempts = 0; sync.stopped = false; renderSync(sync.status); schedulePoll();
        try {
            const status = await request('/sleep/sync', 'POST', {days: 90});
            ++sync.sequence; sync.checking = false; sync.posting = false; acceptSync(status); await refresh(false);
        } catch (error) {
            ++sync.sequence; sync.checking = false; sync.posting = false;
            if (error.data?.state) { sync.notice = '已有睡眠记录仍保留。'; acceptSync(error.data); await refresh(false); }
            else {
                sync.notice = error.message + ' 正在核对服务器状态，已有记录保留。'; updateSyncControls();
                await checkSyncStatus(true);
                if (sync.status?.state !== 'syncing' && !sync.notice) sync.notice = '本次同步请求未得到确认。上方显示刚读取的服务器状态，请核对最近尝试时间；需要时可再次同步。';
            }
        } finally { sync.posting = false; updateSyncControls(); }
    }
    function query(value) { return '?from=' + value.from + '&to=' + value.to; }
    async function refresh(withSync = true) {
        if (!state.requestedRange) return;
        const run = ++state.refresh; const requested = {...state.requestedRange}; $('#health-refresh').disabled = true;
        const statusCheck = withSync ? checkSyncStatus(sync.stopped) : Promise.resolve();
        const results = await Promise.allSettled([request('/blood-pressure' + query(requested)), request('/sleep' + query(requested))]);
        if (run !== state.refresh) return;
        const errors = [];
        if (results[0].status === 'fulfilled') { state.records = results[0].value.records; state.pressureRange = requested; renderPressure(); output('#pressure-status', ''); }
        else { errors.push('血压读取失败。'); output('#pressure-status', `未能载入 ${rangeLabel(requested)}。${state.pressureRange ? '仍显示 ' + rangeLabel(state.pressureRange) + ' 的上次结果。' : '尚无已载入结果。'}${results[0].reason.message}`, true); }
        if (results[1].status === 'fulfilled') { state.sleeps = results[1].value.records; state.sleepRange = requested; renderSleep(); output('#sleep-history-status', ''); }
        else { errors.push('睡眠读取失败。'); output('#sleep-history-status', `未能载入 ${rangeLabel(requested)}。${state.sleepRange ? '仍显示 ' + rangeLabel(state.sleepRange) + ' 的上次结果。' : '尚无已载入结果。'}${results[1].reason.message}`, true); }
        output('#health-status', errors.join(' ') || '', errors.length > 0); $('#health-refresh').disabled = false;
        await statusCheck;
    }
    form.addEventListener('input', () => { state.dirty = true; updateEditor(); }); form.addEventListener('change', () => { state.dirty = true; updateEditor(); }); form.addEventListener('submit', save);
    $('#pressure-new').addEventListener('click', () => edit(null)); $('#pressure-cancel').addEventListener('click', () => { if (mayDiscard()) { resetEditor(); $('#pressure-editor').open = false; } }); $('#pressure-review').addEventListener('click', review);
    $('#health-refresh').addEventListener('click', () => refresh());
    range.addEventListener('input', renderRange);
    function applyRange(value) { state.requestedRange = value; state.shown = 20; state.sleepShown = 20; renderRange(); return refresh(); }
    range.addEventListener('submit', event => { event.preventDefault(); if (!range.reportValidity()) return; if (range.elements.from.value > range.elements.to.value) { output('#health-status', '开始日期不能晚于结束日期。', true); return; } applyRange({from: range.elements.from.value, to: range.elements.to.value}); });
    for (const shortcut of document.querySelectorAll('[data-health-days]')) shortcut.addEventListener('click', () => { const value = recentRange(Number(shortcut.dataset.healthDays)); range.elements.from.value = value.from; range.elements.to.value = value.to; applyRange(value); });
    $('#sleep-sync').addEventListener('click', synchronize);
    $('#sleep-sync-refresh').addEventListener('click', () => checkSyncStatus(true));
    window.addEventListener('beforeunload', event => { if (state.dirty || state.busy) { event.preventDefault(); event.returnValue = ''; } });
    async function start() {
        resetEditor(); $('#pressure-timezone').textContent = `按当前手机/浏览器时区 ${deviceZone} 录入。`;
        try { const profile = await request(app.dataset.profileUrl, 'GET', undefined, true); state.timezone = profile.timezone; }
        catch { output('#health-status', '无法读取跑步时区，暂按 Asia/Shanghai 显示。', true); }
        const initial = recentRange(90); state.requestedRange = initial; range.elements.to.value = initial.to; range.elements.from.value = initial.from; renderRange();
        $('#health-timezone').textContent = `血压日期与具体时刻按 ${state.timezone} 显示；睡眠按设备当地醒来日期筛选。默认查看最近 90 天。`;
        await refresh();
    }
    let resizeTimer; window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { pressureChart(state.records); renderSleep(); }, 150); });
    window.addEventListener('pagehide', stopPolling);
    window.addEventListener('pageshow', event => { if (event.persisted && sync.status?.state === 'syncing') checkSyncStatus(true); });
    start();
})();
