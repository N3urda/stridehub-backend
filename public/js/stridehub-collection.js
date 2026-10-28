(() => {
    'use strict';
    const app = document.getElementById('health-app'); if (!app || !document.getElementById('collection-history')) return;
    const {node, request, num, time} = window.StrideHubData, $ = id => document.getElementById(id), base = app.dataset.apiBase;
    const states = {weight: {generation: 0}, zepp: {generation: 0}};
    const pageSize = 6;
    let zone = 'Asia/Shanghai', devicesGeneration = 0;
    const labels = {success: '最近读取成功', no_data: '本次未取得数据', not_configured: '尚未配置', auth_required: '需要重新授权', unavailable: '来源不可用', error: '读取失败', syncing: '正在同步', forbidden: '无权限', partial: '部分来源未取得'};
    const statusText = value => labels[value] || '状态待确认';
    const feedback = (element, text, error = false) => { element.textContent = text; element.classList.toggle('health-error', error); };
    function field(list, name, value) { const pair = node('div'); pair.append(node('dt', '', name), node('dd', '', value == null ? '未提供' : String(value))); list.append(pair); }
    async function token() { return (await request(base + '/collection/csrf')).csrfToken; }
    function capture(row) {
        const c = row.capture, d = c.derived || {}, details = node('details', 'collection-entry');
        const summary = node('summary', 'collection-summary');
        const title = node('span', 'collection-summary-value', `${num(c.weightKg, 2)} kg`), meta = node('span', 'collection-summary-meta', `${time(c.measuredAt, zone)} · 测量日期 ${c.localDate}`);
        summary.append(title, meta, node('span', 'collection-receipt' + (row.deleted ? ' deleted' : ''), row.deleted ? '已删除' : '服务器已收到'));
        details.append(summary);
        if (!row.deleted && row.projectionStatus === 'manual_conflict') details.append(node('p', 'collection-projection-note', '已保存这次测量；当天已有手动或独立日记录，趋势继续使用该日记录。'));
        const list = node('dl', 'collection-facts');
        field(list, '测量时刻', time(c.measuredAt, zone)); field(list, '原始采集日期', c.localDate);
        field(list, '服务器最近写入', time(row.updatedAt, zone)); field(list, '来源', d.source || '客户端未提供');
        field(list, '计算依据', d.calculationBasis || '未提供'); field(list, '体脂估算', c.fatPercent == null ? null : `${num(c.fatPercent, 1)} %`);
        field(list, 'BMI', c.bmi); field(list, '阻抗', c.impedanceOhms == null ? null : `${c.impedanceOhms} Ω`); field(list, '算法版本', d.algorithmVersion);
        details.append(list, node('p', 'small', row.deleted ? '这次采集已删除，不再参与日趋势。手机再次上传同一记录也不会恢复。' : row.projectionStatus === 'manual_conflict' ? '手动或独立日记录受到保护。可前往“趋势与目标”核对当天使用的体重。' : '每日趋势按当天最后一次有效采集生成，可能与较早一次测量不同。'));
        const extra = node('details'); extra.append(node('summary', '', '查看采集时资料与身体组成快照'));
        const values = node('dl', 'collection-facts');
        const metricNames = {WEIGHT: '体重', BMI: 'BMI', BODY_FAT: '体脂', MUSCLE: '肌肉', WATER: '水分', BONE: '骨量', VISCERAL_FAT: '内脏脂肪', BMR: '基础代谢', PROTEIN: '蛋白质', BODY_AGE: '身体年龄'};
        for (const [key, value] of Object.entries(d.values || {})) if (typeof value === 'number') field(values, metricNames[key] || key, value);
        const profileNames = {heightCm: '身高（cm）', age: '年龄', ageYears: '年龄', gender: '性别', sex: '性别', birthDate: '出生日期', birthday: '出生日期'};
        for (const [key, value] of Object.entries(c.profile || {})) if (['number', 'string'].includes(typeof value)) field(values, profileNames[key] || `采集资料 ${key}`, value);
        extra.append(node('p', 'small', '以下是客户端保存的估算源值，单位以对应客户端算法为准；不作医学解释。'), values); details.append(extra);
        if (!row.deleted) {
            const remove = node('button', 'hub-button', '删除这次采集'); remove.type = 'button';
            remove.addEventListener('click', async () => {
                if (!confirm(`删除 ${c.localDate} 的 ${num(c.weightKg, 2)} kg 采集？保留删除标记，手机重传不会恢复。`)) return;
                remove.disabled = true;
                try {
                    await request(`${base}/collection/weight/${encodeURIComponent(row.measurementId)}?deviceId=${encodeURIComponent(row.deviceId)}`, {method: 'DELETE', headers: {'X-CSRF-Token': await token()}});
                    app.dispatchEvent(new Event('weight-captures-changed'));
                    const verified = await load('weight');
                    if (verified) feedback($('collection-status'), '已删除这次采集并核验。删除标记保留，手机重传不会恢复。');
                    else feedback($('collection-status'), $('collection-status').textContent + ' 删除已提交，请刷新核对删除结果。', true);
                }
                catch (e) { feedback($('collection-status'), e.message + ' 请刷新称重记录核验，勿重复删除。', true); }
                // A missing delete/read receipt must not invite another mutation of an unverified row.
                remove.textContent = '请刷新核对删除结果';
            }); details.append(remove);
        }
        return details;
    }
    function source(row) {
        const details = node('details', 'collection-entry'); details.append(node('summary', '', `${row.label}  ${row.date || '无日期快照'}${row.dateBasis === 'utc_event' ? ' (UTC)' : ''}${row.metrics.length ? ' · ' + num(row.metrics[0].latest, 2) + ' ' + row.metrics[0].unit : ' · 已采集，查看说明'}`));
        if (row.metrics.length) details.append(node('p', 'collection-scalar', row.metrics.map(m => `${m.label} ${num(m.latest, 2)} ${m.unit}`).join(' / ')));
        const list = node('dl', 'collection-facts');
        for (const m of row.metrics) { field(list, m.label, `${num(m.latest, 2)} ${m.unit}`); field(list, '样本范围', `${num(m.min, 2)}–${num(m.max, 2)} ${m.unit}，${m.count} 个有效值`); }
        field(list, '最近采集', time(row.fetchedAt, zone)); field(list, '来源样本数', row.sampleCount);
        details.append(list, node('p', 'small', row.note)); return details;
    }
    async function load(topic, more = false) {
        const state = states[topic], generation = ++state.generation, prefix = topic === 'weight' ? 'collection' : 'source';
        const status = $(prefix + '-status'), button = $(prefix + '-more'), target = $(prefix + '-records');
        const range = $('health-range'), from = more ? state.from : range.elements.from.value, to = more ? state.to : range.elements.to.value;
        if (!from || !to || from > to) return false;
        const query = new URLSearchParams({from, to, limit: String(pageSize)});
        const kind = more ? state.kind : $('collection-source').value;
        if (topic === 'zepp' && kind) query.set('kind', kind);
        if (more && state.next != null) { query.set('offset', state.next); query.set('snapshot', state.snapshot); }
        const reload = $(prefix + '-reload'); button.disabled = true; reload.disabled = true; target.setAttribute('aria-busy', 'true'); feedback(status, more ? '正在读取更多记录…' : '正在读取…');
        try {
            const data = await request(`${base}/collected/${topic}?${query}`); if (generation !== state.generation) return false;
            if (!more) target.replaceChildren();
            target.append(...data.records.map(topic === 'weight' ? capture : source));
            state.next = data.nextOffset; state.snapshot = data.snapshot; state.from = from; state.to = to; state.kind = kind; button.hidden = state.next == null;
            button.textContent = `再看 ${Math.min(pageSize, data.total - target.children.length)} 条`;
            status.textContent = data.total ? `${from} 至 ${to} · 已显示 ${target.children.length} / ${data.total} 条${topic === 'weight' ? '称重记录，含已删除记录' : '来源摘要'}。` : `${from} 至 ${to} 暂无记录。可更换日期，或在手机完成采集和同步后刷新。`;
            if (topic === 'zepp' && $('collection-source').options.length === 1) for (const [key, label] of Object.entries(data.kinds)) { const option = node('option', '', label); option.value = key; $('collection-source').append(option); }
            if (topic === 'zepp') {
                try {
                    const sync = await request(base + '/zepp/status'); if (generation !== state.generation) return;
                    const item = sync.sources?.[kind === 'band_detail' ? 'band' : kind];
                    status.textContent += ` ${statusText(item?.state || sync.state)}。${item?.nextAction || item?.message || sync.nextAction || ''}`;
                } catch { if (generation === state.generation) status.textContent += ' 来源档案已读取，暂时无法确认最新同步状态。'; }
            }
            return true;
        } catch (e) { if (generation === state.generation) { feedback(status, e.message + (target.children.length ? ` 当前仍显示 ${state.from} 至 ${state.to} 的上次结果；请点击刷新重新读取。` : ' 请点击刷新重新读取。'), true); button.hidden = true; } return false; }
        finally { if (generation === state.generation) { button.disabled = false; reload.disabled = false; target.setAttribute('aria-busy', 'false'); } }
    }
    async function devices() {
        const generation = ++devicesGeneration; feedback($('devices-status'), '正在读取设备…'); $('devices-reload').disabled = true;
        try {
            const data = await request(base + '/collection/devices'); if (generation !== devicesGeneration) return;
            $('device-records').replaceChildren(); $('devices-status').textContent = data.devices.length ? `${data.devices.filter(d => !d.revoked).length} 台设备可上传 · ${data.devices.filter(d => d.revoked).length} 台已撤销。` : '尚无配对设备。在 Android 客户端中完成登录与配对后刷新。';
            for (const d of data.devices) {
                const row = node('div', 'collection-device'); row.append(node('span', '', `${d.name}：${d.revoked ? '已撤销' : '已授权'}`));
                if (!d.revoked) { const revoke = node('button', 'hub-button', '撤销授权'); revoke.type = 'button'; revoke.addEventListener('click', async () => {
                    if (!confirm(`撤销“${d.name}”的上传权限？已保存记录保留。`)) return; revoke.disabled = true;
                    try { await request(base + '/collection/devices/' + encodeURIComponent(d.deviceId), {method: 'DELETE', headers: {'X-CSRF-Token': await token()}}); await devices(); }
                    catch (e) { $('devices-status').textContent = e.message + ' 请刷新核验授权状态。'; }
                    finally { revoke.disabled = false; }
                }); row.append(revoke); } $('device-records').append(row);
            }
        } catch (e) { if (generation === devicesGeneration) feedback($('devices-status'), e.message + ' 点击刷新设备重试。', true); }
        finally { if (generation === devicesGeneration) $('devices-reload').disabled = false; }
    }
    $('collection-reload').onclick = () => load('weight'); $('collection-more').onclick = () => load('weight', true);
    $('source-reload').onclick = () => load('zepp'); $('source-more').onclick = () => load('zepp', true); $('collection-source').onchange = () => load('zepp');
    $('devices-reload').onclick = devices;
    const refresh = () => { load('weight'); load('zepp'); };
    $('health-refresh').addEventListener('click', refresh); $('health-range').addEventListener('submit', refresh);
    app.addEventListener('health-range-ready', refresh);
    (async () => { try { const profile = await request(app.dataset.profileUrl); zone = profile.timezone; } catch { /* The main health page reports this failure. */ } refresh(); devices(); })();
})();
