(() => {
    'use strict';
    const {node, request, num, time} = window.StrideHubData;
    const fieldNames = {measurementId: '采集编号', measuredAt: '测量时间', localDate: '采集日期', weightKg: '体重（kg）', fatPercent: '上传体脂估算（%）', bmi: '上传 BMI', impedanceOhms: '阻抗（Ω）', profile: '冻结计算资料', derived: '计算信息', values: '全部原始计算值', grades: '秤算法评级', references: '采集时参考范围', scores: '算法评分', algorithmVersion: '算法版本', calculatedAt: '计算时间', calculationBasis: '计算依据', bodyShape: '原算法体型', source: '来源', heightCm: '身高（cm）', birthDate: '出生日期', birthday: '出生日期', age: '年龄（岁）', ageYears: '年龄（岁）', male: '性别', gender: '性别', sex: '性别', userId: '采集资料编号'};
    function sourceFields(value, zone, metrics) {
        const list = node('dl', 'composition-source-fields');
        for (const [key, v] of Object.entries(value || {})) {
            const label = fieldNames[key] || metrics.find(m => m.key === key)?.label || key;
            if (v && typeof v === 'object') {
                const group = node('details'); group.append(node('summary', '', label), sourceFields(v, zone, metrics)); const pair = node('div'); pair.append(group); list.append(pair);
            } else {
                let text = v == null ? '未提供' : String(v);
                if (key === 'male' && typeof v === 'boolean') text = v ? '男' : '女';
                if (key === 'calculatedAt' && typeof v === 'number' && v > 0 && Number.isFinite(new Date(v).valueOf())) text = time(new Date(v).toISOString(), zone);
                if (key === 'measuredAt' && typeof v === 'string') text = time(v, zone);
                const pair = node('div'); pair.append(node('dt', '', label), node('dd', '', text)); list.append(pair);
            }
        }
        return list;
    }
    function metricCard(m) {
        const card = node('article', 'composition-metric' + (m.highlight ? ' reference-outside' : ''));
        card.dataset.metric = m.key;
        card.append(node('h4', '', m.label));
        const value = node('p', 'composition-value', num(m.value, m.digits)); value.append(node('span', '', m.unit)); card.append(value);
        card.append(node('p', 'small subtle', m.kind === 'weight_record' ? '称重记录' : m.kind === 'calculated' ? '采集时计算值' : '估算值'));
        if (m.grade) card.append(node('span', 'composition-grade' + (m.highlight ? ' reference-outside' : ''), `秤算法参考：${m.grade}`));
        if (m.reference) card.append(node('p', 'small', `采集时参考：${m.reference}`));
        if (m.score != null) card.append(node('p', 'small subtle', `算法评分 ${num(m.score, 1)} 分`));
        if (m.note) card.append(node('p', 'small subtle', m.note));
        return card;
    }
    function render(row, zone, height) {
        const content = node('div'), c = row.capture, body = row.composition;
        content.append(node('p', 'composition-meta subtle', `${time(c.measuredAt, zone)}（${zone}）· 原始采集日期 ${c.localDate} · ${body.sourceLabel} · ${body.algorithmVersion || '版本未提供'}`));
        if (row.projectionStatus === 'manual_conflict') content.append(node('p', 'composition-note', '这次采集已保存。当天另有手动或独立体重记录，日趋势继续使用该日记录；此处显示选中的这一次采集。'));
        const summary = node('div', 'composition-summary');
        function tile(label, value, unit, note, extra = '') { const el = node('div', 'composition-summary-item ' + extra); el.append(node('span', '', label), node('strong', '', `${num(value, label === '体脂率' ? 1 : 2)} ${unit}`), node('p', 'small subtle', note)); summary.append(el); }
        tile('体重', body.weightKg, 'kg', '本次称重记录');
        tile('体脂率', body.bodyFatPercent, '%', '设备 / 公式估算');
        if (body.reportBmi) {
            const b = body.reportBmi;
            tile('BMI · 报告身高', b.value, '', `${b.heightCm} cm · ${b.source.reportDate} 报告${b.classification ? ' · 成人分层：' + b.classification : ' · 年龄资料不足，未分层'}`, 'composition-report-bmi');
        }
        tile('BMI · 采集原值', body.capturedBmi, '', body.capturedHeightCm == null ? '采集资料未提供身高' : `采集时身高 ${body.capturedHeightCm} cm`);
        content.append(summary);
        if (height?.status === 'stale') content.append(node('p', 'composition-note warning', '已选报告的身高或来源信息发生变化 / 被删除，报告身高 BMI 暂停计算。请重新选择身高依据；原始采集仍保留。'));
        if (body.reportBmi) {
            const b = body.reportBmi, explanation = node('p', 'small subtle', `${b.source.reportTitle} · ${b.source.sourceLabel} · ${b.source.reportDate} · 身高 ${b.heightCm} cm。${b.note} ${b.reference} `);
            const link = node('a', '', '查看成人 BMI 依据'); link.href = b.referenceUrl; link.target = '_blank'; link.rel = 'noopener noreferrer'; explanation.append(link); content.append(explanation);
        }
        const outside = body.metrics.filter(m => m.highlight && m.value != null);
        if (outside.length) content.append(node('p', 'composition-note warning', '秤算法参考高低：' + outside.map(m => `${m.label} ${m.grade}`).join('；') + '。这些是原算法比较结果，不是临床异常诊断。'));
        if (body.bodyShape) content.append(node('p', 'small subtle', `原算法体型：${body.bodyShape}（算法标签）`));
        const grid = node('div', 'composition-grid'); grid.append(...body.metrics.map(metricCard)); content.append(grid);
        const raw = node('details', 'composition-raw'); raw.append(node('summary', '', '查看原始采集资料与计算依据'));
        if (body.calculationBasis) raw.append(node('p', 'small', String(body.calculationBasis)));
        raw.append(node('p', 'small subtle', '以下为上传时保存的资料。页面选择报告身高不会改写这些信息。'), sourceFields(c, zone, body.metrics)); content.append(raw);
        return content;
    }
    window.StrideHubComposition = {render, metricCard};
    const app = document.getElementById('body-composition'); if (!app) return;
    const $ = id => document.getElementById(id), base = app.dataset.base;
    const state = {generation: 0, busy: false, loading: false, failedWrite: false, rows: [], selected: null, selectedRow: null, followLatest: true, height: null, range: null, loadedRange: null, zone: 'UTC', next: null, snapshot: null, total: 0};
    const message = (id, text, error = false) => { $(id).textContent = text; $(id).classList.toggle('composition-error', error); };
    function lock() {
        const locked = state.busy || state.loading;
        $('composition-refresh').disabled = state.busy;
        $('composition-capture').disabled = locked || !state.rows.length;
        $('composition-more').disabled = locked;
        $('composition-height-choice').disabled = locked || !state.height;
        $('composition-height-form').querySelector('button').disabled = locked || !state.height || state.failedWrite;
    }
    function renderHeight() {
        const h = state.height, select = $('composition-height-choice'); select.replaceChildren();
        const original = node('option', '', '沿用每次采集冻结的身高'); original.value = ''; select.append(original);
        for (const c of h.candidates) { const option = node('option', '', `${c.heightCm} cm · ${c.reportDate} · ${c.reportTitle}${c.needsReview ? '（报告待核对）' : ''}`); option.value = c.findingFingerprint; select.append(option); }
        if (h.status === 'stale') { const stale = node('option', '', '原选择已失效，请重新选择'); stale.value = 'stale'; stale.disabled = true; select.append(stale); select.value = 'stale'; }
        else select.value = h.source?.findingFingerprint || '';
        $('composition-height-source').textContent = h.source ? `已保存依据：${h.source.heightCm} cm · ${h.source.reportDate} · ${h.source.reportTitle} · ${h.source.sourceLabel} · 选择版本 ${h.version}${h.status === 'stale' ? '（已失效）' : ''}` : '当前使用每次采集保存的身高。报告候选只收录单位明确为 cm / 厘米的数值身高。';
    }
    function renderSelection() {
        const chosen = !state.followLatest && (state.rows.find(r => r.id === state.selected) || (state.selectedRow?.id === state.selected && state.selectedRow));
        if (!chosen) state.followLatest = true;
        const row = chosen || state.rows[0]; state.selected = row?.id || null;
        $('composition-capture').value = state.selected || '';
        $('composition-content').replaceChildren(row ? render(row, state.zone, state.height) : node('p', 'subtle', '所选日期范围没有有效称重采集。请调整日期，或在手机完成采集和上传后刷新。'));
    }
    function renderHistory() {
        const select = $('composition-capture'); select.replaceChildren();
        const rows = state.selectedRow && !state.rows.some(r => r.id === state.selectedRow.id) ? [...state.rows, state.selectedRow] : state.rows;
        for (const row of rows) { const option = node('option', '', `${time(row.capture.measuredAt, state.zone)} · ${num(row.capture.weightKg, 2)} kg · 体脂 ${num(row.composition.bodyFatPercent, 1)}%`); option.value = row.id; select.append(option); }
        renderSelection();
    }
    function renderStatus() {
        if (!state.loadedRange) return;
        message('composition-status', `${state.loadedRange.from} 至 ${state.loadedRange.to} · 已载入 ${state.rows.length} / ${state.total} 次有效采集；${state.followLatest ? '正在查看最近一次' : '正在查看手动选择的历史测量'}。已删除采集保留在“逐次采集”原始记录中。`);
    }
    async function load(more = false) {
        if (!state.range || state.busy || (more && (state.loading || state.next == null))) return;
        const generation = ++state.generation, range = {...state.range}; state.loading = true; lock();
        message('composition-status', more ? '正在读取更早测量…' : '正在读取体成分…');
        const query = new URLSearchParams({...range, limit: '20'});
        if (!state.followLatest && state.selected) query.set('selected', state.selected);
        if (more) { query.set('offset', state.next); query.set('snapshot', state.snapshot); }
        try {
            const data = await request(`${base}/body-composition?${query}`); if (generation !== state.generation) return;
            state.rows = more ? [...state.rows, ...data.records] : data.records; state.height = data.heightSelection;
            state.selectedRow = data.selectedRecord; state.total = data.total;
            state.loadedRange = {from: data.from, to: data.to};
            state.next = data.nextOffset; state.snapshot = data.snapshot; state.failedWrite = false;
            renderHeight(); renderHistory(); $('composition-more').hidden = state.next == null;
            renderStatus();
        } catch (error) { if (generation === state.generation) message('composition-status', `${error.message} 当前内容未刷新，请重新读取。${state.loadedRange ? `仍显示 ${state.loadedRange.from} 至 ${state.loadedRange.to} 的旧数据。` : '尚未读取到记录。'}`, true); }
        finally { if (generation === state.generation) { state.loading = false; lock(); } }
    }
    $('composition-capture').addEventListener('change', () => { state.selected = $('composition-capture').value; state.followLatest = state.selected === state.rows[0]?.id; renderSelection(); renderStatus(); });
    $('composition-refresh').addEventListener('click', () => load());
    $('composition-more').addEventListener('click', () => load(true));
    $('composition-height-form').addEventListener('submit', async event => {
        event.preventDefault(); if (state.busy || state.loading || !state.height || state.failedWrite) return;
        const value = $('composition-height-choice').value, candidate = state.height.candidates.find(c => c.findingFingerprint === value);
        if (value && !candidate) { message('composition-height-status', '请选择仍有效的报告身高，或沿用采集资料。', true); return; }
        const payload = candidate ? {version: state.height.version, reportId: candidate.reportId, findingIndex: candidate.findingIndex, findingFingerprint: candidate.findingFingerprint} : {version: state.height.version, reportId: null};
        state.busy = true; ++state.generation; lock(); message('composition-height-status', '正在保存并核验身高依据…');
        let saved = false;
        try {
            const result = await request(`${base}/body-composition/height-selection`, {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': app.dataset.csrf}, body: JSON.stringify(payload)});
            const confirmed = await request(`${base}/body-composition/height-selection`);
            if (result.version !== confirmed.version || confirmed.status === 'stale' || (confirmed.source?.findingFingerprint || '') !== value) throw new Error('保存后的身高依据又发生变化，请刷新核对。');
            saved = true; message('composition-height-status', '已保存并核验。原始采集资料保持不变。');
        } catch (error) { state.failedWrite = true; message('composition-height-status', error.message + ' 请先刷新体成分核对，不会自动重试。', true); }
        finally { state.busy = false; lock(); }
        if (saved) await load();
    });
    function setRange(from, to, zone) { if (!from || !to || from > to) return; if (from !== state.range?.from || to !== state.range?.to) { state.followLatest = true; state.selected = null; state.selectedRow = null; } state.range = {from, to}; if (zone) state.zone = zone; load(); }
    document.addEventListener('body-composition-range', event => setRange(event.detail.from, event.detail.to, event.detail.timezone));
    const health = $('health-app');
    if (health) {
        const range = $('health-range'), refresh = () => setRange(range.elements.from.value, range.elements.to.value);
        health.addEventListener('health-range-ready', refresh); health.addEventListener('weight-captures-changed', refresh);
        range.addEventListener('submit', refresh); $('health-refresh').addEventListener('click', refresh);
        request(health.dataset.profileUrl).then(p => { state.zone = p.timezone; if (state.rows.length) renderHistory(); }).catch(() => {});
        refresh();
    }
})();
