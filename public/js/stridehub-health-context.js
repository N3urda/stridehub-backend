(() => {
    'use strict';
    const app = document.getElementById('health-app'); if (!app) return;
    const {node, request, status} = window.StrideHubData, $ = id => document.getElementById(id);
    const base = app.dataset.apiBase.replace(/\/$/, '');
    let loading = false, loaded = false;
    function render(data) {
        const constraints = $('health-constraints'), reports = $('health-reports');
        constraints.replaceChildren(); reports.replaceChildren();
        $('health-context-count').textContent = `${data.reports.length} 份报告 · ${data.constraints.filter(c => c.status === 'active').length} 项启用限制`;
        if (data.constraints.length) {
            constraints.append(node('h3', '', '已记录的健康限制'));
            const list = node('ul', 'context-constraints');
            for (const c of [...data.constraints].sort((a, b) => a.status.localeCompare(b.status))) {
                const item = node('li');
                item.append(node('strong', '', c.description), node('p', 'muted', `${c.status === 'active' ? '已启用' : '已停用'} · ${c.sourceType === 'clinician' ? '医生意见记录' : '本人记录'} · 生效日期 ${c.validFrom || '未指定'} · 复查日期 ${c.reviewOn || '未指定'}`));
                const source = data.reports.find(r => r.id === c.sourceReportId);
                if (source) { const link = node('a', '', '关联报告：' + source.title); link.href = '#health-report-' + source.id; link.addEventListener('click', () => { $('health-report-' + source.id).open = true; }); item.append(link); }
                list.append(item);
            }
            constraints.append(list, node('p', 'muted small', '限制的状态来自保存记录；复查日期经过后不会自动停用。'));
        }
        if (!data.reports.length) reports.append(node('p', 'empty-note', '尚无已保存的报告。通过 AI 聊天工具整理并保存后，可在这里核对报告数值、医生意见和来源。'));
        const flagNames = {normal: '原报告标记正常', high: '原报告标记偏高', low: '原报告标记偏低', abnormal: '原报告标记异常', unknown: '未提供标记'};
        for (const report of [...data.reports].sort((a, b) => b.reportDate.localeCompare(a.reportDate))) {
            const details = node('details', 'context-report'); details.id = 'health-report-' + report.id;
            const summary = node('summary'); summary.append(node('strong', '', report.title), node('span', 'muted', `${report.reportDate} · ${report.needsReview ? '提取内容待核对' : '已标记核对'}`)); details.append(summary);
            details.append(node('p', 'muted', '来源：' + report.sourceLabel));
            if (report.findings.length) {
                const scroll = node('div', 'context-table-scroll'); scroll.tabIndex = 0; scroll.setAttribute('role', 'region'); scroll.setAttribute('aria-label', report.title + '的原始指标');
                const table = node('table'), thead = node('thead'), header = node('tr'), tbody = node('tbody');
                ['指标', '报告结果', '参考范围', '报告标记', '页码'].forEach(label => { const th = node('th', '', label); th.scope = 'col'; header.append(th); });
                thead.append(header); table.append(thead, tbody);
                report.findings.forEach(f => { const tr = node('tr'); [f.name, [f.valueText, f.unit].filter(Boolean).join(' '), f.referenceRangeText || '未提供', flagNames[f.flag], f.page ?? '—'].forEach(value => tr.append(node('td', '', value))); tbody.append(tr); });
                scroll.append(table); details.append(scroll);
            } else details.append(node('p', 'muted', '未保存结构化指标。'));
            for (const [label, lines] of [['医生意见（已记录）', report.clinicianAdvice], ['AI 解读（已保存）', report.aiInterpretation ? [report.aiInterpretation] : []], ['备注', report.notes ? [report.notes] : []]]) {
                if (!lines.length) continue;
                const section = node('section', 'context-prose'); section.append(node('h3', '', label)); lines.forEach(line => section.append(node('p', '', line))); details.append(section);
            }
            reports.append(details);
        }
        const preferences = $('health-preferences'); preferences.hidden = !data.lifestylePreferences.length && !data.notes;
        preferences.querySelector('div').replaceChildren(...[...data.lifestylePreferences, data.notes].filter(Boolean).map(line => node('p', 'context-prose', line)));
    }
    async function load() {
        if (loading) return; loading = true; status($('health-context-status'), '正在读取…');
        try { const data = await request(base + '/workspace'); render(data.healthContext); loaded = true; status($('health-context-status'), ''); }
        catch (e) { status($('health-context-status'), e.message + (loaded ? ' 当前保留上次读取的内容。' : ' 可通过“刷新记录”重试。'), true); }
        finally { loading = false; }
    }
    $('health-refresh').addEventListener('click', load);
    load();
})();
