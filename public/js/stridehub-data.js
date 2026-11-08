(() => {
    'use strict';
    const node = (tag, cls, text) => { const el = document.createElement(tag); if (cls) el.className = cls; if (text !== undefined) el.textContent = String(text); return el; };
    const svg = (tag, attrs = {}, text) => { const el = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v); if (text !== undefined) el.textContent = text; return el; };
    async function request(url, options = {}) {
        let res;
        try { res = await fetch(url, {credentials: 'same-origin', cache: 'no-store', ...options, headers: {Accept: 'application/json', ...options.headers}}); }
        catch { throw new Error('连接中断，请检查网络后刷新。'); }
        if (res.status === 204 && !res.redirected) return {};
        const data = await res.json().catch(() => null);
        if (!res.ok || !data || res.redirected) {
            const message = res.status === 401 || res.redirected ? '登录已过期，请重新登录后再试。'
                : data?.message || [data?.lastError, data?.nextAction].filter(Boolean).join(' ')
                    || (res.status === 403 ? '当前操作未获授权，请刷新页面后重试。' : `服务暂时无法返回记录（${res.status}），请稍后重试。`);
            const e = new Error(message); e.status = res.status; throw e;
        }
        return data;
    }
    const dateInZone = (date, zone) => new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'}).format(date);
    const shiftDay = (date, n) => { const d = new Date(date + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
    const num = (value, decimals = 0) => Number.isFinite(value) ? value.toLocaleString('zh-CN', {maximumFractionDigits: decimals}) : '—';
    const minutes = value => Number.isFinite(value) ? `${Math.floor(value / 60)} 小时 ${Math.round(value % 60)} 分` : '未提供';
    const time = (value, zone) => value ? new Intl.DateTimeFormat('zh-CN', {timeZone: zone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(value)) : '未提供';
    const status = (target, text, error = false) => { target.textContent = text; target.classList.toggle('error', error); target.hidden = !text; };
    function metric(target, label, value, unit, detail) { const el = node('div', 'compact-metric'); const line = node('div'); line.append(node('span', 'metric-value', value), node('span', 'metric-unit', unit)); el.append(node('span', 'metric-label', label), line); if (detail) el.append(node('span', 'metric-note', detail)); target.append(el); }
    function bars(target, rows, key, options = {}) {
        return window.StrideHubCharts.bars(target, rows, {series: [{key, label: options.label || '记录', color: options.color}], unit: options.unit, label: options.label});
    }

    function stages(bar, legend, sleep) {
        bar.replaceChildren(); legend.replaceChildren();
        const types = [['deepMinutes', '深睡', 'deep'], ['lightMinutes', '浅睡', 'light'], ['remMinutes', 'REM', 'rem'], ['awakeMinutes', '清醒', 'awake']];
        const total = types.reduce((n, [k]) => n + (Number.isFinite(sleep?.[k]) ? sleep[k] : 0), 0);
        for (const [key, label, cls] of types) {
            const value = sleep?.[key];
            if (Number.isFinite(value) && value > 0) { const piece = node('span', 'stage-piece stage-' + cls); piece.style.width = (value / total * 100) + '%'; piece.title = `${label} ${value} 分`; bar.append(piece); }
            const labelNode = node('span'); labelNode.append(node('b', 'stage-' + cls), document.createTextNode(label), node('strong', '', Number.isFinite(value) ? `${value} 分` : '未知')); legend.append(labelNode);
        }
    }
    const calendar = (from, to, records, key, get) => { const lookup = new Map(records.map(r => [r.date, r])); const result = []; for (let day = from; day <= to; day = shiftDay(day, 1)) result.push({date: day, [key]: lookup.has(day) ? get(lookup.get(day)) : null}); return result; };
    window.StrideHubData = {node, svg, request, dateInZone, shiftDay, num, minutes, time, status, metric, bars, stages, calendar};
})();
