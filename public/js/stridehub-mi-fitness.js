(() => {
    'use strict';
    const app = document.getElementById('health-app');
    if (!app) return;
    const sync = document.getElementById('mi-fitness-sync'), refresh = document.getElementById('mi-fitness-refresh'), status = document.getElementById('mi-fitness-status');
    const sources = document.getElementById('sleep-source-records');
    const connect = document.getElementById('mi-fitness-connect'), pairPanel = document.getElementById('mi-fitness-pair');
    const pairQr = document.getElementById('mi-fitness-pair-qr'), pairStatus = document.getElementById('mi-fitness-pair-status'), pairExpiry = document.getElementById('mi-fitness-pair-expiry');
    const pairCancel = document.getElementById('mi-fitness-pair-cancel'), pairRetry = document.getElementById('mi-fitness-pair-retry'), pairClose = document.getElementById('mi-fitness-pair-close');
    let posting = false, generation = 0, statusGeneration = 0;
    let configured = false, syncState = '', pairBusy = false, pairActive = false, pairAttempt = null, pairTimer = null, pairGeneration = 0, pairReadGeneration = 0, qrAttempt = null;
    async function request(path, body, timeout = body ? 900000 : 20000) {
        const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeout);
        try {
            const response = await fetch(app.dataset.apiBase + path, {method: body ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
                headers: {Accept: 'application/json', ...(body ? {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf} : {})}, ...(body ? {body: JSON.stringify(body)} : {})});
            const data = await response.json();
            if (!response.ok) throw new Error(data.lastError || data.message || '读取失败，请刷新状态。');
            return data;
        } finally { clearTimeout(timer); }
    }
    function updateActions() {
        sync.disabled = posting || pairBusy || pairActive || !configured || syncState === 'syncing';
        connect.disabled = posting || pairBusy || pairActive || syncState === 'syncing';
        connect.textContent = configured ? '重新扫码连接' : '连接小米运动健康';
        pairCancel.disabled = pairBusy;
        pairRetry.disabled = pairBusy;
    }
    async function readStatus() {
        const current = ++statusGeneration;
        try {
            const data = await request('/mi-fitness/status');
            if (current !== statusGeneration) return;
            configured = data.configured === true; syncState = data.state;
            const labels = {not_configured: '尚未连接', idle: '等待首次同步', syncing: '正在同步', success: '同步完成', partial: '部分记录未能读取', no_data: '本次未发现记录', error: '同步未完成', auth_required: '需要重新连接'};
            status.textContent = `小米运动健康：${labels[data.state] || data.state}。${data.lastError || data.nextAction} 已保存 ${data.recordCount} 段睡眠。`;
            updateActions();
        } catch { if (current === statusGeneration) { status.textContent = '无法读取小米状态，已有记录保留，请刷新重试。'; configured = false; updateActions(); } }
    }
    function clearPairTimer() { clearTimeout(pairTimer); pairTimer = null; }
    function showPairPanel(show) {
        pairPanel.hidden = !show;
        connect.setAttribute('aria-expanded', String(show));
    }
    function renderPair(data, reveal = false) {
        pairActive = data.state === 'pending' || data.state === 'starting';
        pairAttempt = data.attemptId || null;
        if (pairActive || reveal) showPairPanel(true);
        const labels = {pending: '等待扫码并在手机上确认授权。', starting: '正在生成二维码…', success: '绑定成功。先在小米运动健康 App 上传数据，再点击“同步小米数据（7 天）”。', expired: '二维码已过期，请重新获取。', cancelled: '已取消本次绑定。', error: '暂时无法完成绑定，请重试。', unavailable: '连接服务尚未就绪，请稍后重试。', not_configured: '尚未绑定小米账号。', idle: '尚未开始绑定。'};
        pairStatus.textContent = data.message || labels[data.state] || '请刷新连接状态后重试。';
        if (data.state === 'success') pairStatus.textContent = labels.success;
        pairExpiry.textContent = '';
        if (pairActive && data.expiresAt) {
            const expiry = new Date(data.expiresAt);
            if (!Number.isNaN(expiry.getTime())) pairExpiry.textContent = `二维码有效至 ${new Intl.DateTimeFormat('zh-CN', {hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).format(expiry)}。`;
        }
        pairQr.hidden = data.state !== 'pending' || !pairAttempt;
        if (!pairQr.hidden && qrAttempt !== pairAttempt) {
            qrAttempt = pairAttempt;
            pairQr.src = app.dataset.apiBase + '/mi-fitness/pair/qr?' + new URLSearchParams({attemptId: pairAttempt});
        } else if (pairQr.hidden) { pairQr.removeAttribute('src'); qrAttempt = null; }
        pairCancel.hidden = !pairActive;
        pairRetry.hidden = pairActive || data.state === 'success';
        pairClose.hidden = pairActive;
        updateActions();
    }
    function schedulePairPoll(current) {
        clearPairTimer();
        if (pairActive && current === pairGeneration) pairTimer = setTimeout(() => readPairStatus(current), 3000);
    }
    async function readPairStatus(current = pairGeneration) {
        const read = ++pairReadGeneration;
        try {
            const data = await request('/mi-fitness/pair/status');
            if (current !== pairGeneration || read !== pairReadGeneration) return;
            const wasActive = pairActive;
            renderPair(data);
            if (data.state === 'success' && wasActive) { await readStatus(); document.getElementById('health-refresh').click(); }
        } catch {
            if (current !== pairGeneration || read !== pairReadGeneration) return;
            if (!pairPanel.hidden) pairStatus.textContent = '暂时无法读取绑定状态，正在重试。';
        }
        schedulePairPoll(current);
    }
    async function startPair() {
        if (posting || pairBusy || pairActive) return;
        const current = ++pairGeneration;
        clearPairTimer(); pairBusy = true;
        renderPair({state: 'starting'}, true);
        try {
            const data = await request('/mi-fitness/pair/start', {}, 60000);
            if (current !== pairGeneration) return;
            renderPair(data, true);
            if (data.state === 'success') await readStatus();
        } catch (error) {
            if (current !== pairGeneration) return;
            renderPair({state: 'error', message: error.message}, true);
        } finally {
            if (current === pairGeneration) { pairBusy = false; updateActions(); schedulePairPoll(current); }
        }
    }
    async function readSources(range) {
        if (!range?.from || !range?.to) return;
        const current = ++generation;
        try {
            const data = await request('/sleep/sources?' + new URLSearchParams(range));
            if (current !== generation) return;
            sources.replaceChildren();
            for (const record of data.records) {
                const line = document.createElement('p');
                line.textContent = `${record.date} · ${record.sourceLabel || record.source} · ${record.isNap ? '小睡' : '主睡眠候选'} · ${record.totalMinutes ?? '未知'} 分钟 · 评分 ${record.score ?? '未知'}`;
                line.textContent += ` · 清醒 ${record.wakeCount ?? '未知'} 次${record.isIncomplete === true ? ' · 记录不完整' : ''}`;
                sources.append(line);
                if (record.sleepSegments?.length) {
                    const detail = document.createElement('details'), summary = document.createElement('summary');
                    summary.textContent = `查看 ${record.sleepSegments.length} 段睡眠时间`;
                    detail.append(summary);
                    const labels = {deep: '深睡', light: '浅睡', rem: 'REM', awake: '清醒', unclassified: '未分期睡眠', unknown: '阶段未知'};
                    const time = value => new Intl.DateTimeFormat('zh-CN', {timeZone: record.timeZone || 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(value));
                    for (const segment of record.sleepSegments) {
                        const item = document.createElement('p'); item.textContent = `${time(segment.startedAt)}–${time(segment.endedAt)} · ${labels[segment.stage] || '未知'}`; detail.append(item);
                    }
                    sources.append(detail);
                }
            }
            if (!data.records.length) sources.textContent = '这段时间尚无来源记录。';
        } catch { if (current === generation) sources.textContent = '来源记录读取失败，请刷新记录重试。'; }
    }
    sync.addEventListener('click', async () => {
        if (posting || sync.disabled) return;
        posting = true; updateActions(); status.textContent = '正在同步小米最近 7 天睡眠、活动与手环指标，请稍候…';
        let error = '';
        try { await request('/mi-fitness/sync', {days: 7}); }
        catch (e) { error = e.message + ' 结果请以刷新后的状态为准；历史记录保留。'; }
        finally { posting = false; await readStatus(); if (error) status.textContent += ' ' + error; document.getElementById('health-refresh').click(); }
    });
    const type = document.getElementById('mi-data-type'), more = document.getElementById('mi-data-more');
    const recordStatus = document.getElementById('mi-data-record-status'), records = document.getElementById('mi-data-records');
    const names = Object.fromEntries(Array.from(type.options, option => [option.value, option.textContent]));
    const states = {idle: '尚未同步', syncing: '同步中', success: '已同步', no_data: '暂无记录', partial: '部分未读取', error: '读取失败', auth_required: '需重新授权'};
    let selectedRange = null, recordGeneration = 0, nextOffset = null, dataGeneration = 0;
    const paragraph = (parent, text) => { const p = document.createElement('p'); p.textContent = text; parent.append(p); };
    async function readRecords(append = false) {
        if (!selectedRange) return;
        const current = ++recordGeneration, offset = append ? nextOffset : 0;
        more.hidden = true; recordStatus.textContent = '正在读取来源记录…';
        if (!append) records.replaceChildren();
        try {
            const data = await request('/mi-fitness/records?' + new URLSearchParams({...selectedRange, type: type.value, offset, limit: 30}));
            if (current !== recordGeneration) return;
            for (const row of data.records) {
                const details = document.createElement('details'), summary = document.createElement('summary'), pre = document.createElement('pre');
                summary.textContent = `${row.recordedAt.replace('T', ' ')} · ${names[row.type]} · 小米来源 ${row.sourceDeviceKey.slice(0, 8)}`;
                pre.textContent = JSON.stringify(row.values, null, 2); pre.style.whiteSpace = 'pre-wrap'; pre.style.overflowWrap = 'anywhere';
                details.append(summary, pre); records.append(details);
            }
            recordStatus.textContent = data.total ? `共 ${data.total} 条，已展示 ${records.children.length} 条；展开查看全部字段。` : '这段时间暂无此类记录。佩戴手环并同步手机 App 后再刷新。';
            nextOffset = data.nextOffset; more.hidden = nextOffset === null;
        } catch { if (current === recordGeneration) recordStatus.textContent = '记录读取失败，请刷新重试。'; }
    }
    async function readData(range) {
        if (!range?.from || !range?.to) return;
        selectedRange = {from: range.from, to: range.to}; readRecords();
        const current = ++dataGeneration, summary = document.getElementById('mi-data-summary'), daily = document.getElementById('mi-data-daily');
        try {
            const data = await request('/mi-fitness/data?' + new URLSearchParams(selectedRange));
            if (current !== dataGeneration) return;
            summary.replaceChildren(); daily.replaceChildren();
            for (const [key, count] of Object.entries(data.counts)) {
                const state = data.status.sources[key];
                paragraph(summary, `${names[key]}：${count} 条 · ${states[state.state] || state.state}${state.message ? ' · ' + state.message : ''}`);
            }
            paragraph(daily, data.methodology);
            for (const day of data.daily) paragraph(daily, `${day.date} · ${day.steps ?? '未知'} 步 · 距离 ${day.distanceMeters ?? '未知'} 米 · 步数流消耗 ${day.stepCalories ?? '未知'} · 独立消耗 ${day.activityCalories ?? '未知'}（热量保留源值，待手机核对单位）`);
            if (!data.daily.length) paragraph(daily, '这段时间暂无活动记录。');
        } catch { if (current === dataGeneration) summary.textContent = '小米数据汇总读取失败，请刷新重试。'; }
    }
    type.addEventListener('change', () => readRecords());
    more.addEventListener('click', () => readRecords(true));
    connect.addEventListener('click', startPair);
    pairRetry.addEventListener('click', startPair);
    pairClose.addEventListener('click', () => showPairPanel(false));
    pairCancel.addEventListener('click', async () => {
        if (pairBusy || !pairActive) return;
        const current = ++pairGeneration;
        clearPairTimer(); pairBusy = true; updateActions();
        try {
            const data = await request('/mi-fitness/pair/cancel', {attemptId: pairAttempt}, 20000);
            if (current !== pairGeneration) return;
            renderPair(data, true);
            if (data.state === 'success') await readStatus();
        } catch {
            if (current === pairGeneration) pairStatus.textContent = '取消请求未确认，正在刷新绑定状态。';
        } finally {
            if (current === pairGeneration) { pairBusy = false; updateActions(); await readPairStatus(current); }
        }
    });
    pairQr.addEventListener('error', () => {
        if (pairActive) pairStatus.textContent = '二维码加载失败，请取消后重新获取。';
    });
    refresh.addEventListener('click', () => { readStatus(); if (!pairBusy) readPairStatus(); if (selectedRange) readData(selectedRange); });
    app.addEventListener('health-records-refreshed', event => { readSources(event.detail); readData(event.detail); });
    window.addEventListener('pagehide', () => { ++pairGeneration; clearPairTimer(); });
    window.addEventListener('pageshow', event => { if (event.persisted) { readStatus(); readPairStatus(); } });
    readStatus(); readPairStatus();
})();
