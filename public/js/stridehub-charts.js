/* Shared, local-only charts. Missing observations stay missing; no health scores are inferred. */
(() => {
    'use strict';
    const colors = ['#178b91', '#7763bc', '#db8639', '#ca5c72', '#5178b8'];
    const finite = Number.isFinite;
    const fmt = v => finite(v) ? v.toLocaleString('zh-CN', {maximumFractionDigits: 2}) : '未记录';
    const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };
    const svg = (tag, attrs = {}, text) => { const n = document.createElementNS('http://www.w3.org/2000/svg', tag); Object.entries(attrs).forEach(([k,v]) => n.setAttribute(k,v)); if (text !== undefined) n.textContent = text; return n; };
    function empty(target, label) { if (!target) return; target.replaceChildren(el('p', 'viz-empty', label || '这段时间没有可用记录。')); }
    function legend(target, series) { const out = el('div', 'viz-legend'); series.forEach((s,i) => { const item = el('span'), dot = el('i'); dot.style.background = s.color || colors[i % colors.length]; item.append(dot, document.createTextNode(s.label)); out.append(item); }); target.append(out); }
    function frame(target, label, height = 230) {
        target.replaceChildren(); target.classList.add('viz');
        const width = Math.max(300, Math.min(900, target.clientWidth || 600));
        const drawing = svg('svg', {viewBox: `0 0 ${width} ${height}`, 'aria-label': label || '记录图表', role: 'group', class: 'viz-plot chart-interaction', tabindex: 0});
        drawing.append(svg('title', {}, (label || '记录图表') + '；左右方向键选择记录，回车打开明细'));
        target.append(drawing); return {drawing, width, height, left: 46, right: width - 16, top: 18, bottom: height - 34};
    }
    function interaction(target, drawing, rows, series, options, pick) {
        const readout = el('p', 'viz-readout chart-readout'); readout.setAttribute('aria-live', 'polite'); target.append(readout);
        let selected = options.selectedDate ? rows.findIndex(r => r.date === options.selectedDate) : -1; if (selected < 0) selected = Math.max(0, rows.findLastIndex(r => series.some(s => finite(r[s.key]))));
        const show = (i, activate = false) => { selected = Math.max(0, Math.min(rows.length - 1, i)); const row = rows[selected]; if (!row) return;
            readout.textContent = `${row.date || row.label || ''}　` + series.map(s => `${s.label} ${fmt(row[s.key])}${finite(row[s.key]) ? ' ' + (s.unit || options.unit || '') : ''}`).join(' · ');
            drawing.querySelectorAll('[data-index]').forEach(n => n.classList.toggle('viz-selected', Number(n.dataset.index) === selected));
            if (activate && options.onSelect) options.onSelect(row);
        };
        drawing.addEventListener('pointermove', e => { if (e.pointerType === 'mouse') show(pick(e)); });
        drawing.addEventListener('click', e => show(pick(e), true));
        drawing.addEventListener('keydown', e => { if (!['ArrowLeft','ArrowRight','Home','End','Enter',' '].includes(e.key)) return; e.preventDefault(); show(e.key === 'Home' ? 0 : e.key === 'End' ? rows.length - 1 : selected + (e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0), ['Enter',' '].includes(e.key)); });
        show(selected);
    }
    function plot(target, input, options = {}, type) {
        if (!target) return;
        const rows = [...input].sort((a,b) => finite(Date.parse(a.date)) && finite(Date.parse(b.date)) ? Date.parse(a.date)-Date.parse(b.date) : String(a.date || '').localeCompare(String(b.date || ''))), series = options.series || [{key: options.key || 'value', label: options.label || '记录', color: colors[0]}];
        const values = rows.flatMap(r => series.map(s => r[s.key])).filter(finite);
        if (!values.length) return empty(target);
        const f = frame(target, options.label), {drawing, left, right, top, bottom, width} = f;
        const totals = options.stacked ? rows.map(r => series.reduce((n,s) => n + (finite(r[s.key]) ? r[s.key] : 0), 0)) : values;
        let min = type === 'bars' ? Math.min(0, ...values) : Math.min(...values), max = Math.max(...totals);
        if (type === 'trend') { const pad = Math.max((max - min) * .15, .5); min -= pad; max += pad; } else max = Math.max(1, max * 1.1);
        const y = value => bottom - (value - min) / Math.max(.01, max - min) * (bottom - top);
        const gap = (right - left) / Math.max(1, rows.length), stamps = rows.map(r => Date.parse(r.date));
        const temporal = type === 'trend' && stamps.every(finite) && stamps.at(-1) > stamps[0];
        const x = i => temporal ? left + 8 + (stamps[i] - stamps[0]) / (stamps.at(-1) - stamps[0]) * (right-left-16) : left + gap * (i + .5);
        for (let i = 0; i <= 3; i++) { const value = min + (max - min) * i / 3, yy = y(value); drawing.append(svg('line', {x1:left,x2:right,y1:yy,y2:yy,class:'viz-grid'}), svg('text',{x:left-7,y:yy+4,'text-anchor':'end'}, fmt(Math.round(value*10)/10))); }
        drawing.append(svg('text',{x:left,y:11},options.unit || ''));
        const indices = [...new Set([0, Math.floor((rows.length-1)/2), rows.length-1])];
        indices.forEach(i => drawing.append(svg('text',{x:i === 0 ? left : i === rows.length-1 ? right : x(i), y:bottom+22,'text-anchor':i === 0 ? 'start' : i === rows.length-1 ? 'end' : 'middle'}, String(rows[i].date || rows[i].label || '').slice(5,10))));
        series.forEach((s,si) => {
            const color = s.color || colors[si % colors.length]; let path = [], previous = null;
            const flush = () => { if (path.length > 1) drawing.append(svg('polyline',{points:path.join(' '),fill:'none',stroke:color,'stroke-width':2.2,'stroke-dasharray':s.dashed ? '5 4' : ''})); path = []; };
            rows.forEach((r,i) => {
                const value = r[s.key]; if (!finite(value)) { flush(); previous = null; return; }
                if (type === 'bars') {
                    const offset = options.stacked ? series.slice(0,si).reduce((n,prev) => n+(finite(r[prev.key]) ? r[prev.key] : 0),0) : 0;
                    const w = Math.min(40, gap * .74) / (options.stacked ? 1 : series.length), xx = x(i) - Math.min(40, gap*.74)/2 + (options.stacked ? 0 : si*w);
                    const rect = svg('rect',{x:xx,y:y(value+offset),width:Math.max(.7,w-1),height:Math.max(1,y(offset)-y(value+offset)),rx:2,fill:color,'data-index':i}); rect.append(svg('title',{},`${r.date} ${s.label} ${fmt(value)} ${options.unit || ''}`)); drawing.append(rect);
                } else {
                    const stamp = Date.parse(r.date); if (previous !== null && stamp - previous > 86400000 * 1.5 && !s.connectGaps) flush();
                    path.push(`${x(i)},${y(value)}`); previous = stamp;
                    drawing.append(svg('circle',{cx:x(i),cy:y(value),r:3,fill:color,'data-index':i}));
                }
            }); flush();
        });
        legend(target,series);
        interaction(target,drawing,rows,series,options,e => {const b=drawing.getBoundingClientRect(); const xx=(e.clientX-b.left)/b.width*width; return temporal ? rows.reduce((best,r,i)=>Math.abs(x(i)-xx)<Math.abs(x(best)-xx)?i:best,0) : Math.floor((xx-left)/gap);});
    }
    function heatmap(target, input, options = {}) {
        if (!target) return; const rows = [...input].sort((a,b) => a.date.localeCompare(b.date)), key = options.key || 'value';
        if (!rows.some(r => finite(r[key]))) return empty(target);
        const offset = (new Date(rows[0].date+'T12:00:00Z').getUTCDay()+6)%7, weeks = Math.ceil((rows.length+offset)/7), h=184;
        const {drawing,width,left,right} = frame(target,options.label,h), size = (right-left)/weeks, start=left;
        const max=Math.max(1,...rows.map(r => finite(r[key]) ? r[key] : 0));
        ['一','','三','','五','','日'].forEach((d,i) => drawing.append(svg('text',{x:24,y:28+i*20},d)));
        rows.forEach((r,i) => { const k=i+offset, col=Math.floor(k/7), row=k%7, value=r[key], level=!finite(value)?0:value===0?1:Math.min(4,1+Math.ceil(value/max*3));
            const rect=svg('rect',{x:start+col*size,y:16+row*20,width:Math.max(2,size-3),height:16,rx:3,fill:['#edf0f3','#d9eceb','#91cbc6','#43a49c','#187e7d'][level],'data-index':i});
            rect.append(svg('title',{},`${r.date}：${fmt(value)} ${options.unit || ''}`)); drawing.append(rect);
        });
        drawing.append(svg('text',{x:left,y:173},rows[0].date),svg('text',{x:right,y:173,'text-anchor':'end'},rows.at(-1).date));
        interaction(target,drawing,rows,[{key,label:options.label || '记录'}],options,e => { const b=drawing.getBoundingClientRect(); const xx=(e.clientX-b.left)/b.width*width, yy=(e.clientY-b.top)/b.height*h; return Math.floor((xx-start)/size)*7+Math.max(0,Math.min(6,Math.floor((yy-16)/20)))-offset; });
        target.append(el('p','viz-caption','灰色：未记录　浅绿：0　颜色越深记录量越多；点击查看'));
    }
    function ring(target, items, options = {}) {
        if (!target) return; items=items.map((r,i)=>({...r,color:r.color||colors[i%colors.length]})); const valid=items.filter(r => finite(r.value) && r.value >= 0), total=valid.reduce((n,r)=>n+r.value,0);
        if (!total) return empty(target,'尚无可展示的构成数据。');
        target.replaceChildren(); target.classList.add('viz'); const wrap=el('div','viz-ring-wrap'), drawing=svg('svg',{viewBox:'0 0 180 180',role:'img','aria-label':options.label || '构成比例'}); let offset=0;
        valid.forEach((r,i) => {const n=svg('circle',{cx:90,cy:90,r:65,fill:'none',stroke:r.color||colors[i%colors.length],'stroke-width':20,'stroke-dasharray':`${r.value/total*408.407} 408.407`,'stroke-dashoffset':-offset,transform:'rotate(-90 90 90)'}); offset+=r.value/total*408.407; n.append(svg('title',{},`${r.label} ${fmt(r.value)} ${options.unit||''}（${fmt(r.value/total*100)}%）`)); drawing.append(n);});
        drawing.append(svg('text',{x:90,y:88,'text-anchor':'middle',class:'viz-ring-total'},fmt(total)),svg('text',{x:90,y:110,'text-anchor':'middle'},options.unit||'合计'));
        const labels=el('div','viz-ring-labels'); items.forEach((r,i)=>{const row=el('p'),dot=el('i');dot.style.background=r.color||colors[i%colors.length];row.append(dot,el('span','',r.label),el('strong','',`${fmt(r.value)}${finite(r.value)?' '+(options.unit||''):''}`));labels.append(row);}); wrap.append(drawing,labels);target.append(wrap);
        if (valid.length !== items.length) target.append(el('p','viz-caption','比例仅包括已记录值；缺失项目未纳入合计。'));
    }
    function comparison(target, items, options = {}) {
        if (!target) return; target.replaceChildren(); target.classList.add('viz','viz-comparisons'); target.setAttribute('aria-label',options.label||'实际与目标');
        items.forEach((r,i)=>{ const row=el('div','viz-comparison'), head=el('div','viz-comparison-head'), unit=r.unit||options.unit||''; head.append(el('span','',r.label),el('strong','',`${fmt(r.value)} ${unit}`));
            const track=el('div','viz-track'), max=Math.max(finite(r.value)?r.value:0,finite(r.target)?r.target:0,1);
            if(finite(r.value)){const fill=el('span','viz-fill');fill.style.width=Math.max(0,r.value/max*100)+'%';fill.style.background=r.color||colors[i%colors.length];track.append(fill);}
            if(finite(r.target)){const marker=el('i','viz-target');marker.style.left=Math.max(0,Math.min(100,r.target/max*100))+'%';track.append(marker);}
            row.append(head,track,el('p','viz-caption',finite(r.target)?`目标 ${fmt(r.target)} ${unit}${finite(r.value)?' · '+(r.value<r.target?'距目标 ':r.value>r.target?'超出目标 ':'达到目标 ')+fmt(Math.abs(r.value-r.target))+' '+unit:''}`:'未设置目标'));target.append(row);
        });
    }
    function sleepRows(records, from, to) {
        const lookup=new Map(records.map(r=>[r.date,r])), rows=[];
        for(let day=from;day<=to;){const r=lookup.get(day), out={date:day}; for(const k of ['deepMinutes','lightMinutes','remMinutes'])out[k]=finite(r?.[k])?r[k]/60:null;
            const known=['deepMinutes','lightMinutes','remMinutes'].reduce((n,k)=>n+(finite(r?.[k])?r[k]:0),0);
            out.unknownMinutes=finite(r?.totalMinutes) && r.totalMinutes>=known ? (r.totalMinutes-known)/60 : null;
            if (!finite(r?.totalMinutes) || known > r.totalMinutes) { for (const k of ['deepMinutes','lightMinutes','remMinutes']) out[k]=null; out.unknownMinutes=finite(r?.totalMinutes)?r.totalMinutes/60:null; } rows.push(out); const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+1);day=d.toISOString().slice(0,10);
        } return rows;
    }
    const sleepSeries=[{key:'deepMinutes',label:'深睡',color:'#6552a3'},{key:'lightMinutes',label:'浅睡',color:'#a797db'},{key:'remMinutes',label:'REM',color:'#548cc5'},{key:'unknownMinutes',label:'未分期',color:'#c4cbd5'}];
    window.StrideHubCharts={bars:(t,r,o)=>plot(t,r,o,'bars'),trend:(t,r,o)=>plot(t,r,o,'trend'),heatmap,ring,comparison,sleepRows,sleepSeries};
})();
