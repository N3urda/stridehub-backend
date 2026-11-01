(() => {
    'use strict';
    const app = document.getElementById('reports-app'); if (!app) return;
    const {node, request, num, minutes, shiftDay, dateInZone, status} = window.StrideHubData;
    const $ = id => document.getElementById(id), base = app.dataset.apiBase.replace(/\/$/, '');
    const state = {workspace: null, reports: [], selected: null, loading: false, attachmentGeneration: 0, busyPlans: new Set()};
    const flags = {normal: '正常', high: '偏高', low: '偏低', abnormal: '异常', unknown: '未标记'};
    const planSources = {user: '本人安排', ai: 'AI 整理建议', clinician: '医生意见记录'};
    const planStatuses = {draft: '草稿', active: '进行中', paused: '已暂停', completed: '已完成', cancelled: '已取消'};
    const abnormal = f => ['high', 'low', 'abnormal'].includes(f.flag);
    const link = (text, href, cls = 'report-text-link') => { const a = node('a', cls, text); a.href = href; return a; };
    const getReport = () => state.reports.find(r => r.id === state.selected);
    const compareReport = () => state.reports.find(r => r.id === $('report-compare').value);
    const dateOf = value => {
        if (!value || !Number.isFinite(new Date(value).getTime())) return null;
        return dateInZone(new Date(value), state.workspace.timezone);
    };
    const numeric = value => typeof value === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value.trim()) ? Number(value) : null;
    const updateRefresh = () => { $('reports-refresh').disabled = state.loading || state.busyPlans.size > 0; };

    function renderReportList() {
        const target = $('reports-list'); target.replaceChildren();
        $('reports-count').textContent = `${state.reports.length} 份报告 · 保留全部历史，不受日常记录日期范围影响`;
        if (!state.reports.length) target.append(node('p', 'report-empty', '还没有保存的体检报告。整理报告并保存后，原始检查结果会显示在这里。'));
        for (const report of state.reports) {
            const button = node('button', 'report-choice'); button.type = 'button';
            button.setAttribute('aria-pressed', String(report.id === state.selected));
            button.append(node('strong', '', report.title), node('span', '', `${report.reportDate} · ${report.findings.length} 项检查`), node('span', '', `${report.findings.filter(abnormal).length} 项报告标记异常 · ${report.sourceLabel}`));
            button.addEventListener('click', () => selectReport(report.id)); target.append(button);
        }
    }

    async function loadAttachment(report) {
        const generation = ++state.attachmentGeneration, target = $('report-attachment');
        target.replaceChildren(node('span', 'report-muted', '正在读取原件…'));
        try {
            const attachment = await request(`${base}/reports/${encodeURIComponent(report.id)}/attachment`);
            if (generation !== state.attachmentGeneration) return;
            target.replaceChildren();
            if (!attachment.available) {
                target.append(node('span', 'report-muted', '这份报告尚未保存 PDF 原件'));
                const label = node('label', 'report-upload-label', '保存 PDF 原件（最多 20 MiB）'), input = node('input');
                input.type = 'file'; input.accept = 'application/pdf,.pdf'; input.id = 'report-pdf-input'; label.htmlFor = input.id; label.append(input);
                const upload = node('button', 'report-button', '保存原件'); upload.type = 'button'; upload.disabled = true;
                input.addEventListener('change', () => { upload.disabled = !input.files.length; });
                upload.addEventListener('click', async () => {
                    const file = input.files[0]; if (!file) return;
                    if (!file.size || file.size > 20 * 1024 * 1024) { status($('reports-status'), '请选择非空且不超过 20 MiB 的 PDF 文件。', true); return; }
                    upload.disabled = true; input.disabled = true; upload.textContent = '正在保存…';
                    try {
                        await request(`${base}/reports/${encodeURIComponent(report.id)}/attachment`, {method: 'PUT', headers: {'Content-Type': 'application/pdf', 'X-CSRF-Token': await freshToken()}, body: file});
                        if (generation !== state.attachmentGeneration) return;
                        status($('reports-status'), 'PDF 原件已保存。'); await loadAttachment(report);
                    } catch (e) {
                        if (generation !== state.attachmentGeneration) return;
                        status($('reports-status'), e.message + ' 可重新读取报告核对原件状态。', true);
                        input.disabled = false; upload.disabled = false; upload.textContent = '保存原件';
                    }
                });
                target.append(label, upload); return;
            }
            const fileUrl = `${base}/reports/${encodeURIComponent(report.id)}/attachment/file`;
            const view = link('浏览 PDF 原件 ↗', fileUrl, 'report-button'); view.target = '_blank'; view.rel = 'noopener';
            target.append(view, link('下载原件', fileUrl + '?download=1', 'report-button'));
        } catch (e) {
            if (generation !== state.attachmentGeneration) return;
            const retry = node('button', 'report-button', '重试读取原件'); retry.type = 'button'; retry.addEventListener('click', () => loadAttachment(report));
            target.replaceChildren(node('span', 'report-muted', e.message), retry);
        }
    }

    function selectReport(id, keepFilters = false) {
        state.selected = id; const report = getReport();
        renderReportList(); $('report-detail').hidden = !report;
        if (!report) { ++state.attachmentGeneration; return; }
        const url = new URL(location.href); url.searchParams.set('report', id); history.replaceState(null, '', url);
        $('report-title').textContent = report.title;
        $('report-source').textContent = `${report.reportDate} · 来源：${report.sourceLabel}`;
        $('report-review').textContent = report.needsReview ? '提取内容待核对；可对照原件检查数值、单位与页码。' : '已标记核对 · 保留报告原始写法';
        const previousCompare = keepFilters ? $('report-compare').value : '';
        $('report-compare').replaceChildren(node('option', '', '不对照')); $('report-compare').firstChild.value = '';
        for (const older of state.reports.filter(r => r.id !== id && r.reportDate < report.reportDate)) {
            const option = node('option', '', `${older.reportDate} · ${older.title}`); option.value = older.id; $('report-compare').append(option);
        }
        if ([...$('report-compare').options].some(o => o.value === previousCompare)) $('report-compare').value = previousCompare;
        if (!keepFilters) { $('report-search').value = ''; $('report-abnormal').checked = false; }
        renderFindings(); renderNarratives(report); loadAttachment(report);
    }

    function renderFindings() {
        const report = getReport(); if (!report) return;
        const prior = compareReport(), query = $('report-search').value.trim().toLocaleLowerCase('zh-CN');
        const findings = report.findings.filter(f => (!$('report-abnormal').checked || abnormal(f)) && (!query || [f.name, f.valueText, f.unit, f.referenceRangeText].some(v => String(v || '').toLocaleLowerCase('zh-CN').includes(query))));
        $('report-result-count').textContent = `显示 ${findings.length} / ${report.findings.length} 项 · 原报告标记异常 ${report.findings.filter(abnormal).length} 项`;
        $('report-comparison-note').hidden = !prior;
        const target = $('report-findings'); target.replaceChildren();
        if (!findings.length) { target.append(node('p', 'report-empty', '没有符合筛选条件的检查项目。')); return; }
        const table = node('table', 'report-table'), caption = node('caption', 'report-muted', report.title + ' · 原始检查结果');
        const header = node('tr'), thead = node('thead'), tbody = node('tbody');
        const columns = ['检查项目', '报告结果', '参考范围', '原报告标记', '页码']; if (prior) columns.push(prior.reportDate + ' 对照');
        columns.forEach(label => { const th = node('th', '', label); th.scope = 'col'; header.append(th); });
        thead.append(header); table.append(caption, thead, tbody);
        for (const f of findings) {
            const tr = node('tr', abnormal(f) ? 'report-flagged' : '');
            tr.append(node('td', '', f.name), node('td', '', [f.valueText, f.unit].filter(Boolean).join(' ')), node('td', '', f.referenceRangeText || '未提供'));
            const flag = node('td'); flag.append(node('span', 'report-flag ' + (flags[f.flag] ? f.flag : 'unknown'), flags[f.flag] || flags.unknown)); tr.append(flag, node('td', '', f.page ?? '—'));
            if (prior) {
                const cell = node('td'), matched = prior.findings.filter(old => old.name === f.name), currentMatches = report.findings.filter(current => current.name === f.name);
                if (!matched.length) cell.textContent = '未记录同名项目';
                else if (matched.length !== 1 || currentMatches.length !== 1) cell.textContent = '同名项目不唯一，请对照原报告';
                else {
                    const old = matched[0]; cell.append(document.createTextNode([old.valueText, old.unit].filter(Boolean).join(' ')));
                    cell.append(node('span', 'report-delta', '原参考范围：' + (old.referenceRangeText || '未提供')));
                    const nowValue = numeric(f.valueText), oldValue = numeric(old.valueText);
                    if (f.unit === old.unit && nowValue !== null && oldValue !== null) {
                        const delta = Math.round((nowValue - oldValue) * 1000000) / 1000000;
                        cell.append(node('span', 'report-delta', `数值变化 ${delta > 0 ? '+' : ''}${num(delta, 6)} ${f.unit || ''}`.trim()));
                    } else if (f.unit !== old.unit) cell.append(node('span', 'report-delta', '单位不同，未计算变化'));
                }
                tr.append(cell);
            }
            tbody.append(tr);
        }
        target.append(table);
    }

    function renderNarratives(report) {
        const target = $('report-narratives'); target.replaceChildren();
        const sections = [
            ['报告医生建议', report.clinicianAdvice, 'clinician'],
            ['AI 整理与解读', report.aiInterpretation ? [report.aiInterpretation] : [], 'ai'],
            ['报告备注与补充原文', report.notes ? [report.notes] : [], 'notes'],
        ];
        for (const [title, lines, type] of sections) {
            if (!lines?.length) continue;
            const section = node('section', 'report-narrative ' + type); section.append(node('h4', '', title));
            if (type === 'ai') section.append(node('p', 'report-muted', '以下为已保存的 AI 解读，与报告原始结果、医生意见分别记录。'));
            lines.forEach(line => section.append(node('p', '', line))); target.append(section);
        }
    }

    function trackingCard(title, value, unit, details, action, href) {
        const card = node('article', 'report-tracking-card'); card.append(node('h3', '', title));
        const main = node('div', 'report-tracking-value', value); if (unit) main.append(node('small', '', unit)); card.append(main);
        details.forEach(detail => card.append(node('p', 'report-muted', detail)));
        card.append(link(action, href)); $('report-tracking-cards').append(card);
    }

    function renderTracking() {
        const data = state.workspace, target = $('report-tracking-cards'); target.replaceChildren();
        const today = data.today, weekStart = shiftDay(today, -6), inWindow = date => date && date >= weekStart && date <= today;
        const sortDate = rows => [...rows].filter(row => row.date && row.date <= today).sort((a, b) => b.date.localeCompare(a.date));
        $('report-tracking-range').textContent = `${data.from} 至 ${data.to} 的实际记录 · 时区 ${data.timezone}`;
        const pressures = [...data.bloodPressure].filter(r => dateOf(r.measuredAt) && new Date(r.measuredAt) <= new Date(data.generatedAt)).sort((a, b) => new Date(b.measuredAt) - new Date(a.measuredAt));
        const latestPressure = pressures[0], weekPressure = pressures.filter(r => inWindow(dateOf(r.measuredAt)) && Number.isFinite(r.systolic) && Number.isFinite(r.diastolic));
        const pressureTime = latestPressure ? new Intl.DateTimeFormat('zh-CN', {timeZone: data.timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(latestPressure.measuredAt)) : '';
        const pressureNotes = [latestPressure ? `最近测量 ${pressureTime}` : '所选范围尚无血压记录'];
        if (weekPressure.length) pressureNotes.push(`近 7 天平均 ${num(weekPressure.reduce((sum, r) => sum + r.systolic, 0) / weekPressure.length, 1)} / ${num(weekPressure.reduce((sum, r) => sum + r.diastolic, 0) / weekPressure.length, 1)} mmHg · ${weekPressure.length} 次测量 / ${new Set(weekPressure.map(r => dateOf(r.measuredAt))).size} 天`);
        else pressureNotes.push('近 7 天尚无可计算平均值的测量');
        trackingCard('血压', latestPressure ? `${num(latestPressure.systolic)} / ${num(latestPressure.diastolic)}` : '未记录', latestPressure ? 'mmHg' : '', pressureNotes, '记血压与查看趋势', app.dataset.healthUrl + '#pressure-section');
        const weights = sortDate(data.weight.records).filter(r => Number.isFinite(r.weightKg)), weight = weights[0], weekWeights = weights.filter(r => inWindow(r.date));
        const weightNotes = [weight ? `最近测量 ${weight.date} · ${weight.sourceLabel || ({manual: '手动记录', import: '导入记录', unknown: '来源未提供'}[weight.source] || '已保存记录')}` : '所选范围尚无体重记录'];
        if (weekWeights.length) {
            weightNotes.push(`近 7 天均值 ${num(weekWeights.reduce((sum, r) => sum + r.weightKg, 0) / weekWeights.length, 2)} kg · ${weekWeights.length} 个记录日`);
            if (weekWeights.length > 1) { const delta = weight.weightKg - weekWeights.at(-1).weightKg; weightNotes.push(`较 ${weekWeights.at(-1).date} ${delta > 0 ? '+' : ''}${num(delta, 2)} kg`); }
        } else weightNotes.push('近 7 天尚无日记录');
        trackingCard('体重 · 看一周变化', weight ? num(weight.weightKg, 2) : '未记录', weight ? 'kg' : '', weightNotes, '记录体重', app.dataset.healthUrl + '#body-weight');
        const waist = sortDate(data.measurements).find(r => Number.isFinite(r.waistCm));
        trackingCard('腰围', waist ? num(waist.waistCm, 1) : '未记录', waist ? 'cm' : '', [waist ? `最近测量 ${waist.date}` : '所选范围尚无腰围记录', waist?.sourceLabel || '同一测量方式，便于回看变化'], '记录腰围与体脂', app.dataset.weightUrl + '#measurements-table');
        const sleeps = sortDate(data.sleep).filter(r => Number.isFinite(r.totalMinutes)), sleep = sleeps[0], weekSleep = sleeps.filter(r => inWindow(r.date));
        const sleepNotes = [sleep ? `最近睡眠 ${sleep.date} · 设备记录` : '所选范围尚无睡眠时长'];
        if (weekSleep.length) sleepNotes.push(`近 7 天记录均值 ${minutes(Math.round(weekSleep.reduce((sum, r) => sum + r.totalMinutes, 0) / weekSleep.length))} · ${weekSleep.length} 条 / ${new Set(weekSleep.map(r => r.date)).size} 天`);
        trackingCard('睡眠时长', sleep ? minutes(sleep.totalMinutes) : '未记录', '', sleepNotes, '查看每晚睡眠', app.dataset.healthUrl + '#sleep-section');
        const daily = sortDate(data.daily), activity = daily.find(r => Number.isFinite(r.activity?.steps));
        trackingCard('每日活动', activity ? num(activity.activity.steps) : '未记录', activity ? '步' : '', [activity ? `最近活动 ${activity.date}` : '所选范围尚无步数记录', Number.isFinite(activity?.activity?.activeMinutes) ? `当天活动 ${num(activity.activity.activeMinutes)} 分钟` : '活动时长未提供'], '查看活动与心率', app.dataset.healthUrl + '#zepp-daily');
        const restCandidates = [
            ...daily.filter(r => Number.isFinite(r.heartRate?.restingBpm)).map(r => ({date: r.date, value: r.heartRate.restingBpm, source: '设备日记录'})),
            ...sortDate(data.sleep).filter(r => Number.isFinite(r.restingHeartRate)).map(r => ({date: r.date, value: r.restingHeartRate, source: '设备睡眠记录'})),
        ].sort((a, b) => b.date.localeCompare(a.date));
        const rest = restCandidates[0];
        trackingCard('静息心率', rest ? num(rest.value) : '未记录', rest ? '次/分' : '', [rest ? `${rest.date} · ${rest.source}` : '设备尚未提供静息心率', '与运动心率、日平均心率分别查看'], '查看心率来源', app.dataset.healthUrl + '#zepp-daily');
    }

    function renderPlans() {
        const target = $('report-plan-list'); target.replaceChildren();
        if (!state.workspace.healthPlans.length) { target.append(node('p', 'report-empty', '尚无已保存的跟踪或复查安排。')); return; }
        for (const plan of state.workspace.healthPlans) {
            const section = node('article', 'report-plan'), heading = node('div', 'report-plan-heading');
            heading.append(node('h3', '', plan.title), node('span', 'report-tag', planStatuses[plan.status] || plan.status)); section.append(heading);
            section.append(node('p', 'report-muted', `${planSources[plan.source] || '已保存安排'}${plan.sourceLabel ? ' · ' + plan.sourceLabel : ''} · ${plan.startDate}${plan.endDate ? ' 至 ' + plan.endDate : ' 起'}`), node('p', 'report-plan-prose', plan.goal));
            if (plan.notes || plan.rationale) { const details = node('details'); details.append(node('summary', '', '查看依据与补充说明')); [plan.rationale, plan.notes].filter(Boolean).forEach(text => details.append(node('p', 'report-plan-prose', text))); section.append(details); }
            const list = node('ul', 'report-plan-items');
            plan.items.forEach((item, index) => {
                const complete = item.status === 'completed', row = node('li', 'report-plan-item' + (complete ? ' is-complete' : ''));
                const checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.id = `plan-${plan.id}-item-${index}`; checkbox.dataset.planId = plan.id; checkbox.checked = complete;
                checkbox.disabled = state.loading || state.busyPlans.has(plan.id) || item.status === 'skipped' || ['completed', 'cancelled', 'paused'].includes(plan.status);
                const label = node('label'); label.htmlFor = checkbox.id; label.append(node('span', 'report-item-title', item.title));
                const timing = [item.date ? `${item.date}${item.time ? ' ' + item.time : ''}` : '未指定日期', item.status === 'skipped' ? '已跳过' : complete ? '已完成' : '待完成'].join(' · ');
                label.append(node('span', 'report-muted', timing)); if (item.notes) label.append(node('span', 'report-muted', item.notes));
                checkbox.addEventListener('change', () => toggleItem(plan, item, checkbox.checked ? 'completed' : 'pending'));
                row.append(checkbox, label); list.append(row);
            });
            section.append(list); target.append(section);
        }
    }

    async function freshToken() {
        const response = await fetch(location.pathname, {credentials: 'same-origin', cache: 'no-store'});
        const doc = new DOMParser().parseFromString(await response.text(), 'text/html'), token = doc.getElementById('reports-app')?.dataset.csrf;
        if (!response.ok || response.redirected || !token) throw new Error('登录已过期，请重新登录后再试。');
        return token;
    }

    async function toggleItem(plan, item, nextStatus) {
        if (state.loading || state.busyPlans.has(plan.id)) return;
        state.busyPlans.add(plan.id);
        for (const input of $('report-plan-list').querySelectorAll('input[type=checkbox]')) if (input.dataset.planId === plan.id) input.disabled = true;
        updateRefresh(); status($('report-plan-status'), '正在保存事项状态…');
        const url = `${base}/plans/${encodeURIComponent(plan.id)}`;
        try {
            await request(url, {method: 'PUT', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': await freshToken()}, body: JSON.stringify({version: plan.version, items: plan.items.map(row => row.id === item.id ? {...row, status: nextStatus} : row)})});
            const current = await request(url); replacePlan(current);
            if (current.items.find(row => row.id === item.id)?.status !== nextStatus) throw new Error('保存后事项发生变化，请核对最新内容。');
            status($('report-plan-status'), nextStatus === 'completed' ? '已记录完成，并核对了服务器记录。' : '已恢复为待完成，并核对了服务器记录。');
        } catch (e) {
            try {
                const current = await request(url); replacePlan(current);
                if (e.status !== 409 && current.items.find(row => row.id === item.id)?.status === nextStatus) status($('report-plan-status'), '已重新读取并确认事项状态已保存。');
                else status($('report-plan-status'), e.status === 409 ? '计划已在其他位置更新。已显示最新内容，请核对后再操作。' : e.message + ' 已显示服务器当前状态。', true);
            } catch { status($('report-plan-status'), e.message + ' 暂时无法核验，请刷新记录后再操作。', true); }
        } finally { state.busyPlans.delete(plan.id); renderPlans(); updateRefresh(); }
    }

    function replacePlan(plan) {
        state.workspace.healthPlans = state.workspace.healthPlans.map(current => current.id === plan.id ? plan : current);
    }

    async function load() {
        if (state.loading || state.busyPlans.size) return;
        state.loading = true; updateRefresh(); if (state.workspace) renderPlans(); status($('reports-status'), '正在读取报告与日常记录…');
        try {
            const workspace = await request(base + '/workspace'); state.workspace = workspace;
            state.reports = [...workspace.healthContext.reports].sort((a, b) => b.reportDate.localeCompare(a.reportDate) || a.id.localeCompare(b.id));
            const requested = state.selected || new URL(location.href).searchParams.get('report');
            selectReport(state.reports.find(r => r.id === requested)?.id || state.reports[0]?.id || null, true);
            renderTracking(); renderPlans(); status($('reports-status'), '');
        } catch (e) { status($('reports-status'), e.message + (state.workspace ? ' 当前保留上次读取的内容。' : ' 可点击“刷新记录”重试。'), true); }
        finally { state.loading = false; if (state.workspace) renderPlans(); updateRefresh(); }
    }

    $('reports-refresh').addEventListener('click', load);
    $('report-search').addEventListener('input', renderFindings);
    $('report-abnormal').addEventListener('change', renderFindings);
    $('report-compare').addEventListener('change', renderFindings);
    load();
})();
