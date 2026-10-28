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
        view.summary.let {summary->Metrics(summary,listOf("count" to "跑步次数","activeDays" to "有记录的天数","distanceKm" to "累计距离 km","movingSeconds" to "运动时间 秒","paceSecondsPerKm" to "平均配速 秒/km","longestRunKm" to "最长单次 km","elevationM" to "累计爬升 m","averageHeartRate" to "加权平均心率 bpm"))}
        val weekly=view.weekly;val monthly=view.monthly
        NativeBars("周跑量 · km",weekly,"distanceKm")
        NativeBars("每周跑步次数",weekly,"count")
        NativeBars("月跑量 · km",monthly,"distanceKm")
        NativeBars("每周最长单次 · km",weekly.map {if(it.optInt("count")==0) JSONObject(it.toString()).put("longestRunKm",JSONObject.NULL) else it},"longestRunKm")
        NativeBars("每周运动时间 · 秒",weekly,"movingSeconds")
        NativeBars("每周平均配速 · 秒/km",weekly,"paceSecondsPerKm")
        NativeBars("每周加权心率 · bpm",weekly,"averageHeartRate")
        SectionTitle("跑步活动日历","${data.text("dailyFrom")} 至 ${data.optJSONObject("filters")?.text("to")}；点击日期查看当天记录。无记录不代表休息。")
        RunningCalendar(view.daily) {date->vm.push(route("running",payload=JSONObject().put("from",date).put("to",date)))}
        SectionTitle("已计算的最佳片段","来自导入运动内的最快片段，不等同于认证比赛成绩。")
        if(view.bestEfforts.isEmpty()) InfoCard("暂无最佳片段","导入并处理包含时间和距离流的运动后可查看。")
        view.bestEfforts.forEach {effort->InfoCard("${effort.text("distanceM")} m · ${effort.text("seconds")} 秒","${effort.text("date")} · ${effort.text("name")}"){vm.push(route("workout",payload=JSONObject().put("id",effort.getString("activityId"))))}}
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
    analysis.optJSONObject("metrics")?.let {Metrics(it,listOf("weightedHeartRate" to "有效加权心率 bpm","paceSecondsPerKm" to "有效平均配速 秒/km","speedVariationPercent" to "速度变异 %","efficiencyChangePercent" to "前后半程效率变化 %"))}
    Metrics(view.splitSummary,listOf("fastestFullKmPace" to "最快完整公里 秒/km","paceVariationPercent" to "完整公里配速变异 %","secondHalfPaceChangePercent" to "后半程配速变化 %"))
    var axis by rememberSaveable {mutableStateOf("seconds")}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(axis=="seconds",{axis="seconds"},label={Text("横轴：运动秒数")})
        FilterChip(axis=="distanceKm",{axis="distanceKm"},label={Text("横轴：距离 km")},enabled=analysis.array("timeline").any {!it.isNull("distanceKm")})
    }
    val timeline=analysis.array("timeline").let {rows->if(axis=="seconds") rows else rows.filter {!it.isNull("distanceKm")}.map {JSONObject(it.toString()).put("seconds",it.opt("distanceKm"))}}
    listOf("paceSecondsPerKm" to "配速 · 秒/km","heartRate" to "心率 · bpm","cadenceSpm" to "步频 · 步/分","altitudeM" to "海拔 · m","powerW" to "功率 · W").forEach {(key,label)->NativeLineChart(label,timeline,key,MaterialTheme.colorScheme.primary)}
    NativeScatter("配速与心率",view.scatter,"paceSecondsPerKm","heartRate")
    NativeBars("训练心率区间 · 分钟",(if(view.coverage.optDouble("heartRateSeconds",0.0)>0) view.zones else emptyList()).map {row->JSONObject().put("label","${row.text("name")} ${row.text("from")}–${row.text("to")} bpm").put("minutes",(row.opt("seconds") as? Number)?.toDouble()?.div(60) ?: JSONObject.NULL)},"minutes","label")
    NativeBars("心率分布 · 分钟",(if(view.coverage.optDouble("heartRateSeconds",0.0)>0) view.distribution else emptyList()).map {row->JSONObject().put("label","${row.text("from")}–${row.text("to")} bpm").put("minutes",(row.opt("seconds") as? Number)?.toDouble()?.div(60) ?: JSONObject.NULL)},"minutes","label")
    SectionTitle("数据覆盖与记录缺口")
    Metrics(view.coverage,listOf("samples" to "原始采样点数","analyzedSeconds" to "有效区间 秒","heartRateSeconds" to "有心率的有效秒数","paceSeconds" to "有配速的有效秒数","gapSeconds" to "记录断档 秒","pausedSeconds" to "显式暂停 秒","invalidIntervals" to "无效区间"))
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
    var selected by remember(rows.toString(),valueKey){mutableIntStateOf(-1)}
    val color=MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        if(known.isEmpty()) Text("暂无足够的实际记录") else {
            val upper=known.max().coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {contentDescription="$title，${known.size} 条有效记录，缺失值保持空白。"}.pointerInput(rows,valueKey){detectTapGestures {p->selected=(p.x/size.width*rows.size).toInt().coerceIn(0,rows.lastIndex)}}) {
                val width=size.width/rows.size
                values.forEachIndexed {index,value->if(value!=null){val height=(value/upper*size.height).toFloat();drawRect(color,Offset(index*width+width*.1f,size.height-height),Size(width*.8f,height))}}
            }
            if(selected in rows.indices) Text("${rows[selected].text(labelKey)} · ${values[selected]?.let {"%.2f".format(it)} ?: "缺失"}",style=MaterialTheme.typography.bodySmall)
            Text("点按查看记录 · 最大值 ${"%.2f".format(known.max())}",style=MaterialTheme.typography.labelSmall)
        }
    }}
}

@Composable private fun NativeScatter(title:String,rows:List<JSONObject>,xKey:String,yKey:String) {
    val points=rows.mapNotNull {row->val x=(row.opt(xKey) as? Number)?.toDouble();val y=(row.opt(yKey) as? Number)?.toDouble();if(x!=null && y!=null && x.isFinite() && y.isFinite()) x to y else null}
    val color=MaterialTheme.colorScheme.secondary
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        if(points.isEmpty()) Text("暂无同时记录配速与心率的有效样本") else {
            val minX=points.minOf {it.first};val maxX=points.maxOf {it.first};val minY=points.minOf {it.second};val maxY=points.maxOf {it.second}
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {contentDescription="$title，${points.size} 个有效点，横轴秒每公里，纵轴每分钟心率。"}) {
                points.forEach {(x,y)->drawCircle(color.copy(alpha=.5f),2.5.dp.toPx(),Offset(((x-minX)/(maxX-minX).coerceAtLeast(1.0)*size.width).toFloat(),(size.height-(y-minY)/(maxY-minY).coerceAtLeast(1.0)*size.height).toFloat()))}
            }
            Text("配速 ${"%.1f".format(minX)}–${"%.1f".format(maxX)} 秒/km · 心率 ${"%.0f".format(minY)}–${"%.0f".format(maxY)} bpm · ${points.size} 点",style=MaterialTheme.typography.bodySmall)
        }
    }}
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
