@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.foundation.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max

@Composable fun RunningOverviewScreen(vm:AppModel,preset:JSONObject?=null) {
    var from by rememberSaveable {mutableStateOf(preset?.text("from") ?: LocalDate.now().minusDays(89).toString())}
    var to by rememberSaveable {mutableStateOf(preset?.text("to") ?: LocalDate.now().toString())}
    var all by rememberSaveable {mutableStateOf(false)}
    var sport by rememberSaveable {mutableStateOf("all")}
    var page by rememberSaveable {mutableIntStateOf(1)}
    NativeData(vm,"/api/v1/running/overview?from=${if(all) "all" else from}&to=$to&sportType=$sport&page=$page") {data->PageColumn {
        val view=WorkoutPresentation.overview(data)
        SectionTitle("跑步复盘",data.optJSONObject("filters")?.let {"${it.text("from")} 至 ${it.text("to")} · ${it.text("timezone")}"})
        DateRangeControls(from,to) {a,b->from=a;to=b;all=false;page=1}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(all,{all=!all;page=1},label={Text("全部历史")})
            listOf("all" to "全部跑步","Run" to "跑步","TrailRun" to "越野跑","VirtualRun" to "室内跑").forEach {(value,label)->FilterChip(sport==value,{sport=value;page=1},label={Text(label)})}
        }
        ChartPanel("这一段的跑步") {
            val summary=view.summary
            val facts=listOf("累计距离" to (healthValue(summary.opt("distanceKm"),1)+" km"),"运动时间" to JournalPresentation.duration(DashboardData.number(summary,"movingSeconds")),
                "平均配速" to JournalPresentation.pace(DashboardData.number(summary,"paceSecondsPerKm")),"跑步次数" to (healthValue(summary.opt("count"),0)+" 次"))
            facts.chunked(2).forEach {pair->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    pair.forEach {(label,value)->
                        Column(Modifier.weight(1f)) {
                            Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value,style=MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            var details by remember {mutableStateOf(false)}
            TextButton(onClick={details=!details}) {Text(if(details) "收起汇总" else "更多汇总")}
            if(details) Metrics(summary,listOf("activeDays" to "有记录的天数","longestRunKm" to "最长单次 km","elevationM" to "累计爬升 m","averageHeartRate" to "加权平均心率 bpm"))
        }
        val weekly=view.weekly;val monthly=view.monthly
        var metric by rememberSaveable {mutableStateOf("distanceKm")}
        val metricNames=mapOf("distanceKm" to "距离 · km","count" to "次数","movingSeconds" to "时长","paceSecondsPerKm" to "配速 · 分/km","averageHeartRate" to "心率 · bpm")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {metricNames.forEach {(key,label)->FilterChip(metric==key,{metric=key},label={Text(label)})}}
        NativeBars("每周${metricNames[metric]}",weekly,metric)
        NativeBars("每月${metricNames[metric]}",monthly,metric)
        SectionTitle("跑步活动日历","${data.text("dailyFrom")} 至 ${data.optJSONObject("filters")?.text("to")}；点击日期查看当天记录。无记录不代表休息。")
        RunningCalendar(view.daily) {date->vm.push(route("running",payload=JSONObject().put("from",date).put("to",date)))}
        SectionTitle("已计算的最佳片段","来自导入运动内的最快片段，不等同于认证比赛成绩。")
        if(view.bestEfforts.isEmpty()) InfoCard("暂无最佳片段","导入并处理包含时间和距离流的运动后可查看。")
        view.bestEfforts.forEach {effort->InfoCard("${effort.text("distanceM")} m · ${JournalPresentation.duration(DashboardData.number(effort,"seconds"))}","${effort.text("date")} · ${effort.text("name")}"){vm.push(route("workout",payload=JSONObject().put("id",effort.getString("activityId"))))}}
        SectionTitle("跑步记录")
        val activities=view.activities
        if(activities.array("items").isEmpty()) Text("此范围内没有导入的跑步记录。")
        activities.array("items").forEach {WorkoutCard(vm,it)}
        RunningCsvExport(vm,activities.array("items"),page)
        val pages=max(1,kotlin.math.ceil(activities.optDouble("total",0.0)/activities.optDouble("pageSize",50.0)).toInt())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={page--},enabled=page>1){Text("上一页")};Text("第 $page / $pages 页")
            TextButton(onClick={page++},enabled=page<pages){Text("下一页")}
        }
        Notes(data,"notes")
    }}
}

@Composable private fun RunningCalendar(days:List<JSONObject>,onDay:(String)->Unit) {
    if(days.isEmpty()) {Text("暂无日历数据");return}
    val offset=runCatching {LocalDate.parse(days.first().getString("date")).dayOfWeek.value-1}.getOrDefault(0)
    val cells=List<JSONObject?>(offset){null}+days
    val maximum=days.maxOfOrNull {it.optDouble("distanceKm",0.0)}?.coerceAtLeast(1.0) ?: 1.0
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row {listOf("一","二","三","四","五","六","日").forEach {Text(it,Modifier.weight(1f))}}
        cells.chunked(7).forEach {week->Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            for(index in 0..6) {
                val day=week.getOrNull(index)
                if(day==null) Spacer(Modifier.weight(1f)) else {
                    val distance=day.optDouble("distanceKm",0.0)
                    Surface(onClick={onDay(day.getString("date"))},modifier=Modifier.weight(1f).heightIn(min=58.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primary.copy(alpha=(.07+.28*distance/maximum).toFloat())) {
                        Column(Modifier.padding(3.dp)) {Text(day.text("date").takeLast(5),style=MaterialTheme.typography.labelSmall);Text("${day.text("distanceKm")} km",style=MaterialTheme.typography.labelSmall);Text("${day.text("count")} 次",style=MaterialTheme.typography.labelSmall)}
                    }
                }
            }
        }}
    }
}

@Composable fun RunningAnalysisContent(detail:JSONObject) {
    val view=WorkoutPresentation.analysisSections(detail)
    val analysis=view.analysis
    SectionTitle("跑步分析","统计均使用服务器的原始区间分析结果。")
    analysis.optJSONObject("metrics")?.let {Metrics(it,listOf("weightedHeartRate" to "有效加权心率 bpm","paceSecondsPerKm" to "有效平均配速","speedVariationPercent" to "速度变异 %","efficiencyChangePercent" to "前后半程效率变化 %"))}
    Metrics(view.splitSummary,listOf("fastestFullKmPace" to "最快完整公里配速","paceVariationPercent" to "完整公里配速变异 %","secondHalfPaceChangePercent" to "后半程配速变化 %"))
    var axis by rememberSaveable {mutableStateOf("seconds")}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(axis=="seconds",{axis="seconds"},label={Text("运动时间")})
        FilterChip(axis=="distanceKm",{axis="distanceKm"},label={Text("运动距离")},enabled=analysis.array("timeline").any {!it.isNull("distanceKm")})
    }
    val timeline=analysis.array("timeline").let {rows->if(axis=="seconds") rows else rows.filter {!it.isNull("distanceKm")}.map {JSONObject(it.toString()).put("seconds",it.opt("distanceKm"))}}
    listOf("paceSecondsPerKm" to "配速 · 分/km","heartRate" to "心率 · bpm","cadenceSpm" to "步频 · 步/分","altitudeM" to "海拔 · m","powerW" to "功率 · W").forEach {(key,label)->NativeLineChart(label,timeline,key,MaterialTheme.colorScheme.primary,axis=if(axis=="distanceKm") ChartAxis.DISTANCE else ChartAxis.ELAPSED)}
    NativeScatter("配速与心率",view.scatter,"paceSecondsPerKm","heartRate")
    NativeBars("训练心率区间 · 分钟",(if(view.coverage.optDouble("heartRateSeconds",0.0)>0) view.zones else emptyList()).map {row->JSONObject().put("label","${row.text("name")} ${row.text("from")}–${row.text("to")} bpm").put("minutes",(row.opt("seconds") as? Number)?.toDouble()?.div(60) ?: JSONObject.NULL)},"minutes","label")
    NativeBars("心率分布 · 分钟",(if(view.coverage.optDouble("heartRateSeconds",0.0)>0) view.distribution else emptyList()).map {row->JSONObject().put("label","${row.text("from")}–${row.text("to")} bpm").put("minutes",(row.opt("seconds") as? Number)?.toDouble()?.div(60) ?: JSONObject.NULL)},"minutes","label")
    SectionTitle("数据覆盖与记录缺口")
    Metrics(view.coverage,listOf("samples" to "原始采样点数","analyzedSeconds" to "有效区间","heartRateSeconds" to "有心率的有效时长","paceSeconds" to "有配速的有效时长","gapSeconds" to "记录断档时长","pausedSeconds" to "显式暂停时长","invalidIntervals" to "无效区间"))
    Text("可用传感器流："+(detail.optJSONArray("availableStreams")?.let {a->(0 until a.length()).joinToString("、"){a.optString(it)}} ?: "未记录"),style=MaterialTheme.typography.bodySmall)
    SegmentTable("公里分段与 GAP",view.splits);SegmentTable("计圈",view.laps)
    if(view.splits.isEmpty()) Text("暂无公里分段；缺失配速与 GAP 保持未知。")
    if(view.laps.isEmpty()) Text("暂无计圈记录。")
    Notes(analysis,"notes")
}

@Composable private fun Notes(data:JSONObject,key:String) {data.optJSONArray(key)?.let {notes->for(i in 0 until notes.length()) Text(notes.optString(i),style=MaterialTheme.typography.bodySmall)}}

@Composable fun NativeBars(title:String,rows:List<JSONObject>,valueKey:String,labelKey:String="date") {
    val values=ChartData.values(rows,valueKey)
    val known=values.filterNotNull()
    var selected by remember(rows.toString(),valueKey){mutableIntStateOf(rows.indexOfLast {DashboardData.number(it,valueKey)!=null}.coerceAtLeast(0))}
    val color=MaterialTheme.colorScheme.primary
    val format=when {title.contains("分钟")->ChartValueFormat("分钟",1);title.endsWith("· 次")->ChartValueFormat("次",0);else->ChartValueFormat.forMetric(title,valueKey)}
    ChartPanel(title) {
        if(known.isEmpty()) Text("暂无足够的实际记录") else {
            val upper=known.max().coerceAtLeast(1.0)
            val grid=MaterialTheme.colorScheme.outlineVariant
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ChartYAxis(upper,0.0,format,144.dp)
                Canvas(Modifier.weight(1f).height(144.dp).semantics {contentDescription="$title，${known.size} 条有效记录，缺失值保持空白。下方可逐条选择。"}.pointerInput(rows,valueKey){detectTapGestures {p->selected=(p.x/size.width*rows.size).toInt().coerceIn(0,rows.lastIndex)}}) {
                    (0..3).forEach {i->val y=size.height*i/3;drawLine(grid,Offset(0f,y),Offset(size.width,y))}
                    val width=size.width/rows.size
                    values.forEachIndexed {index,value->if(value!=null){val height=(value.coerceAtLeast(0.0)/upper*size.height).toFloat();drawRect(color.copy(alpha=if(index==selected) 1f else .55f),Offset(index*width+width*.16f,size.height-height),Size(width*.68f,max(height,1.dp.toPx())))}}
                }
            }
            val first=if(labelKey=="date") ChartPresentation.axisLabel(rows.first(),0) else rows.first().text(labelKey)
            val last=if(labelKey=="date") ChartPresentation.axisLabel(rows.last(),rows.lastIndex) else rows.last().text(labelKey)
            ChartRangeLabels(first,last)
            val label=if(labelKey=="date") ChartPresentation.axisLabel(rows[selected],selected,full=true) else rows[selected].text(labelKey)
            ChartSelection(rows,selected,{selected=it},"$label · ${format.label(values[selected])}")
            if(valueKey.contains("pace",true)) Text("配速越小越快；每根柱对应一个真实区间。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun NativeScatter(title:String,rows:List<JSONObject>,xKey:String,yKey:String) {
    val points=rows.mapNotNull {row->val x=(row.opt(xKey) as? Number)?.toDouble();val y=(row.opt(yKey) as? Number)?.toDouble();if(x!=null && y!=null && x.isFinite() && y.isFinite()) x to y else null}
    val color=MaterialTheme.colorScheme.secondary
    val grid=MaterialTheme.colorScheme.outlineVariant
    val paceFormat=ChartValueFormat("/km",0,ChartValueKind.PACE)
    val heartFormat=ChartValueFormat("bpm",0)
    ChartPanel(title) {
        if(points.isEmpty()) Text("暂无同时记录配速与心率的有效样本") else {
            val minX=points.minOf {it.first};val maxX=points.maxOf {it.first};val minY=points.minOf {it.second};val maxY=points.maxOf {it.second}
            val (lowX,highX)=ChartPresentation.range(points.map {it.first})
            val (lowY,highY)=ChartPresentation.range(points.map {it.second})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ChartYAxis(highY,lowY,heartFormat,180.dp)
                Canvas(Modifier.weight(1f).height(180.dp).semantics {contentDescription="$title，${points.size} 个有效点。横轴配速 ${paceFormat.label(minX)} 至 ${paceFormat.label(maxX)}，纵轴心率 ${heartFormat.label(minY)} 至 ${heartFormat.label(maxY)}。"}) {
                    (0..3).forEach {i->
                        val x=size.width*i/3;val y=size.height*i/3
                        drawLine(grid,Offset(x,0f),Offset(x,size.height));drawLine(grid,Offset(0f,y),Offset(size.width,y))
                    }
                    points.forEach {(x,y)->drawCircle(color.copy(alpha=.55f),3.dp.toPx(),Offset(((x-lowX)/(highX-lowX)*size.width).toFloat(),(size.height-(y-lowY)/(highY-lowY)*size.height).toFloat()))}
                }
            }
            Row(Modifier.fillMaxWidth().padding(start=52.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(paceFormat.label(lowX),style=MaterialTheme.typography.labelSmall)
                Text(paceFormat.label((lowX+highX)/2),style=MaterialTheme.typography.labelSmall)
                Text(paceFormat.label(highX),style=MaterialTheme.typography.labelSmall)
            }
            Text("横轴配速 · 越向左越快",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${points.size} 个有效样本 · 心率 ${heartFormat.value(minY)}–${heartFormat.value(maxY)} bpm",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun RunningCsvExport(vm:AppModel,rows:List<JSONObject>,page:Int) {
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri->if(uri!=null) vm.action {
        val keys=listOf("name","startAt","distanceKm","movingSeconds","paceSecondsPerKm","averageHeartRate")
        fun escape(value:String):String {val safe=if(value.firstOrNull() in listOf('=','+','@','-')) "'"+value else value;return "\""+safe.replace("\"","\"\"")+"\""}
        val header=listOf("名称","时间","距离 km","运动秒数","配速 秒/km","平均心率 bpm")
        val lines=listOf(header)+rows.map {row->keys.map {row.text(it,"")}}
        val csv="\uFEFF"+lines.joinToString("\r\n") {line->line.joinToString(","){escape(it)}}
        withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.use {it.write(csv.toByteArray(Charsets.UTF_8))} ?: error("无法写入所选位置")}
        vm.message="本页跑步记录 CSV 已导出"
    }}
    OutlinedButton(onClick={picker.launch("stridehub-running-page-$page.csv")},enabled=rows.isNotEmpty() && !vm.busy,modifier=Modifier.fillMaxWidth()){Text("导出本页跑步记录 CSV")}
}
