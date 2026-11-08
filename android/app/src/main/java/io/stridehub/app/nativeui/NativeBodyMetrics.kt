@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.OffsetDateTime

/** Adapters keep provider series independent, retain missing values and never average repeated captures. */
object BodyMetricData {
    fun dated(rows:List<JSONObject>):List<JSONObject> = rows.filter { runCatching {java.time.LocalDate.parse(it.text("date"))}.isSuccess }.sortedBy {it.text("date")}
    fun captured(rows:List<JSONObject>,key:String):List<JSONObject> = rows.mapNotNull {row->
        val capture=row.optJSONObject("capture") ?: return@mapNotNull null
        val at=capture.text("measuredAt","")
        if(runCatching {OffsetDateTime.parse(at)}.isFailure) return@mapNotNull null
        val metric=row.optJSONObject("composition")?.array("metrics")?.firstOrNull {it.text("key")==key}
        JSONObject().put("at",at).put("value",DashboardData.number(metric,"value") ?: JSONObject.NULL)
    }.sortedBy {OffsetDateTime.parse(it.getString("at")).toInstant()}
    fun pressure(rows:List<JSONObject>):List<JSONObject> = rows.mapNotNull {row->
        val at=row.text("measuredAt","")
        if(runCatching {OffsetDateTime.parse(at)}.isFailure) return@mapNotNull null
        JSONObject(row.toString()).put("at",at).also {it.remove("date")}
    }.sortedBy {OffsetDateTime.parse(it.getString("at")).toInstant()}
    fun xiaomi(data:JSONObject,key:String):List<JSONObject> = DashboardData.calendar(dated(data.optJSONObject("miFitnessData")?.array("daily").orEmpty()).map {
        JSONObject().put("date",it.text("date")).put("value",DashboardData.number(it,key) ?: JSONObject.NULL)
    })
    fun metrics(rows:List<JSONObject>):List<JSONObject> = rows.flatMap {it.optJSONObject("composition")?.array("metrics").orEmpty()}
        .distinctBy {it.text("key")}.filter {it.text("key") !in setOf("STANDARD_WEIGHT_MIN","STANDARD_WEIGHT_MAX")}
}

@Composable fun BodyMetricTabs(selected:String,onSelect:(String)->Unit,choices:List<Pair<String,String>>,prefix:String="body-metric") {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        choices.forEach {(key,label)->FilterChip(selected=selected==key,onClick={onSelect(key)},label={Text(label)},modifier=Modifier.testTag("$prefix-$key"))}
    }
}

@Composable fun BodyMetricContent(vm:AppModel,data:JSONObject,metric:String,from:String,to:String) {
    val daily=data.array("daily")
    when(metric) {
        "weight" -> {
            val weights=data.optJSONObject("weight")
            val rows=BodyMetricData.dated(weights?.array("trend").orEmpty())
            NativeLineChart("体重 · kg",rows,"weightKg",ChartPalette.weight)
            var mean by rememberSaveable {mutableStateOf(false)}
            TextButton(onClick={mean=!mean}){Text(if(mean) "收起 7 日均值" else "查看 7 日均值")}
            if(mean) {
                NativeLineChart("体重 7 日均值 · kg",rows,"average7DaysKg",MaterialTheme.colorScheme.primary)
                Text("只统计窗口内实际称重日；没有称重的日期不补值。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionRow("查看与补充体重记录"){vm.push(route("records","weight"))}
        }
        "sleep" -> {
            val sleep=BodyMetricData.dated(data.array("sleep"))
            SleepStackChart(sleep){vm.push(route("data","sleep",payload=it,title="睡眠详情"))}
            Text("小米与 Zepp 按完整度互补；同一段睡眠只统计一次。点选日期查看来源。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            var records by rememberSaveable {mutableStateOf(false)}
            TextButton(onClick={records=!records}){Text(if(records) "收起睡眠历史" else "查看全部 ${sleep.size} 条睡眠记录")}
            if(records) sleep.asReversed().forEach {row->InfoCard(row.text("date"),"${healthDuration(row.opt("totalMinutes"))} · ${row.text("sourceLabel",row.text("source"))}"){vm.push(route("data","sleep",payload=row,title="睡眠详情"))}}
        }
        "activity" -> {
            var source by rememberSaveable {mutableStateOf("zepp")}
            var value by rememberSaveable {mutableStateOf("steps")}
            BodyMetricTabs(source,{source=it},listOf("zepp" to "Zepp","xiaomi" to "小米"),"body-source")
            BodyMetricTabs(value,{value=it},listOf("steps" to "步数","energy" to "活动能量","distance" to "距离"),"body-activity")
            val key=if(source=="zepp") when(value){"energy"->"caloriesKcal";"distance"->"distanceMeters";else->"steps"} else when(value){"energy"->"activityCalories";"distance"->"distanceMeters";else->"steps"}
            val rows=if(source=="zepp") DashboardData.daily(daily,"activity",key) else BodyMetricData.xiaomi(data,key)
            val label=when(value){"energy"->"活动能量";"distance"->"距离";else->"每日步数"}
            val unit=when(value){"energy"->"kcal";"distance"->"m";else->"步"}
            DailyBars("${if(source=="zepp") "Zepp" else "小米"} · $label",rows,"value",unit,onOpen=if(source=="zepp") ({vm.push(route("data","day",id=it.text("date")))}) else null)
            Text(if(source=="xiaomi") "小米设备记录独立展示，不与 Zepp 相加；热量取独立活动热量流。" else "仅包含 Zepp 实际记录，缺失日期保持空白。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(source=="xiaomi") {
                val state=data.optJSONObject("miFitnessSync")
                InfoCard("小米连接状态",state?.let {statusLabel(it.text("state"))} ?: "暂无连接信息")
            }
        }
        "recovery" -> {
            var selected by rememberSaveable {mutableStateOf("heart")}
            BodyMetricTabs(selected,{selected=it},listOf("heart" to "静息心率","oxygen" to "血氧","stress" to "压力"),"body-recovery")
            val group=when(selected){"oxygen"->"oxygen";"stress"->"stress";else->"heartRate"}
            val key=when(selected){"oxygen"->"averagePercent";"stress"->"average";else->"restingBpm"}
            val label=when(selected){"oxygen"->"日均血氧 · %";"stress"->"日均压力";else->"静息心率 · bpm"}
            NativeLineChart(label,DashboardData.daily(daily,group,key),"value",if(selected=="heart") Color(0xFFBA4E6B) else MaterialTheme.colorScheme.primary)
            Text("来源：Zepp · 只展示设备实际返回的指标。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            var showDays by rememberSaveable {mutableStateOf(false)}
            TextButton(onClick={showDays=!showDays}){Text(if(showDays) "收起每日指标" else "查看每日指标与采样曲线")}
            if(showDays) BodyMetricData.dated(daily).asReversed().forEach {row->InfoCard(row.text("date"),"${healthValue(row.optJSONObject(group)?.opt(key),1)} · 查看当日详情"){vm.push(route("data","day",id=row.text("date")))}}
        }
        "composition" -> BodyCompositionTrends(vm,from,to,data.optJSONObject("bodyComposition"))
        "measurements" -> {
            var key by rememberSaveable {mutableStateOf("waistCm")}
            BodyMetricTabs(key,{key=it},listOf("waistCm" to "腰围","bodyFatPercent" to "记录体脂"),"body-measurement")
            NativeLineChart(if(key=="waistCm") "腰围 · cm" else "记录体脂率 · %",BodyMetricData.dated(data.array("measurements")),key,ChartPalette.weight)
            Text("来源：已保存的身体测量；与秤的逐次体成分估算分别展示。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ActionRow("查看与补充身体测量"){vm.push(route("records","measurements"))}
        }
        "pressure" -> {
            val rows=BodyMetricData.pressure(data.array("bloodPressure"))
            NativeLineChart("收缩压 · mmHg",rows,"systolic",Color(0xFFBA4E6B))
            NativeLineChart("舒张压 · mmHg",rows,"diastolic",MaterialTheme.colorScheme.primary)
            Text("保留每次测量时间；不把同一天多次测量压成一个值。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ActionRow("查看与补充血压记录"){vm.push(route("records","blood-pressure"))}
        }
    }
}

@Composable private fun BodyCompositionTrends(vm:AppModel,from:String,to:String,summary:JSONObject?) {
    val repository=vm.repository ?: return
    var records by remember(from,to,vm.revision,repository.account) {mutableStateOf<List<JSONObject>>(emptyList())}
    var offset by remember(from,to,vm.revision,repository.account) {mutableIntStateOf(0)}
    var next by remember(from,to,vm.revision,repository.account) {mutableStateOf<Int?>(null)}
    var snapshot by remember(from,to,vm.revision,repository.account) {mutableStateOf<String?>(null)}
    var total by remember(from,to,vm.revision,repository.account) {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var error by remember(from,to,vm.revision,repository.account) {mutableStateOf<String?>(null)}
    var offline by remember {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf("BODY_FAT")}
    var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(from,to,vm.revision,repository.account,offset,retry) {
        busy=true;error=null
        try {
            val path="/api/v1/health/body-composition?from=$from&to=$to&limit=100"+if(offset==0) "" else "&offset=$offset&snapshot=${encoded(snapshot.orEmpty())}"
            fun accept(page:JSONObject) {
                check(offset==0 || snapshot==page.text("snapshot")){"采集记录已变化，请刷新后重新读取。"}
                records=if(offset==0) page.array("records") else (records+page.array("records")).distinctBy {it.text("measurementId",it.text("id"))}
                snapshot=page.text("snapshot");next=if(page.isNull("nextOffset")) null else page.getInt("nextOffset");total=page.optInt("total")
            }
            repository.cache.response(repository.account,path)?.let {cached->accept(JSONObject(cached.payload));offline=true}
            val result=repository.get(path);accept(result.data);offline=result.offline
        } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message ?: "体成分暂时无法读取"} finally {busy=false}
    }
    val options=BodyMetricData.metrics(records)
    val current=options.firstOrNull {it.text("key")==selected} ?: options.firstOrNull {it.text("key")=="WEIGHT"} ?: options.firstOrNull()
    if(options.isNotEmpty()) BodyMetricTabs(current!!.text("key"),{selected=it},options.map {it.text("key") to it.text("label")},"body-composition")
    if(current!=null) NativeLineChart("${current.text("label")} · ${current.text("unit","")}".trimEnd(' ','·'),BodyMetricData.captured(records,current.text("key")),"value",ChartPalette.weight,format=ChartValueFormat(current.text("unit",""),current.optInt("digits",1).coerceIn(0,6)))
    else if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if(error==null) InfoCard("暂无逐次体成分记录","完成称重并同步后，可查看体脂、肌肉、水分等已采集指标的趋势。")
    error?.let {ErrorCard(it){retry++}}
    if(offline) Text("当前显示已缓存的体成分记录",style=MaterialTheme.typography.labelSmall)
    if(total>0) Text("已显示 ${records.size} / $total 次采集 · 同日多次测量分别保留",style=MaterialTheme.typography.labelSmall)
    next?.let {nextOffset->TextButton(onClick={offset=nextOffset},enabled=!busy){Text(if(busy) "正在读取…" else "加载更早的采集")}}
    val latest=summary?.optJSONObject("latest")
    latest?.optJSONObject("reportBmi")?.let {bmi->InfoCard("最新采集 · 报告身高 BMI",healthValue(bmi.opt("value"),1)+" · 身高 ${healthValue(bmi.opt("heightCm"),1)} cm\n${latest.text("localDate")} · 与采集时 BMI 分别保留，不重算体脂。")}
    Text((summary?.opt("methodology") as? String).orEmpty().ifBlank {"体成分为设备 / 公式估算，保留采集时算法与来源。"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
