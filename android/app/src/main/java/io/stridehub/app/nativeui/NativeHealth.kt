package io.stridehub.app.nativeui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

@Composable fun HealthDetail(vm:AppModel,r:JSONObject) {
    if(r.getString("kind")=="sleep") {PageColumn {
        val data=r.getJSONObject("payload");SectionTitle("睡眠详情",data.text("date"))
        SleepStackChart(listOf(data))
        Metrics(data,listOf("startedAt" to "入睡时间","endedAt" to "醒来时间","totalMinutes" to "总睡眠 分钟","deepMinutes" to "深睡 分钟","lightMinutes" to "浅睡 分钟","remMinutes" to "REM 分钟","awakeMinutes" to "清醒 分钟","wakeCount" to "醒来次数","score" to "设备评分","restingHeartRate" to "静息心率 bpm","sourceLabel" to "数据来源"))
        Text("指标来自设备记录；缺失项保持未知。",style=MaterialTheme.typography.bodySmall)
    };return}
    NativeData(vm,"/api/v1/health/zepp/days/"+encoded(r.getString("id"))) {data->PageColumn {
        SectionTitle("每日健康指标",data.text("date"))
        data.optJSONObject("activity")?.let {SectionTitle("日常活动");Metrics(it,listOf("steps" to "步数","distanceMeters" to "距离 m","caloriesKcal" to "能量 kcal","activeMinutes" to "活跃 分钟","walkingMinutes" to "步行 分钟","runningMinutes" to "跑步 分钟"))}
        data.optJSONObject("heartRate")?.let {SectionTitle("心率");Metrics(it,listOf("restingBpm" to "静息 bpm","averageBpm" to "平均 bpm","minBpm" to "最低 bpm","maxBpm" to "最高 bpm","sampleCount" to "样本数"))}
        val samples=data.optJSONObject("samples") ?: JSONObject()
        NativeLineChart("分钟心率 · bpm",samples.array("heartRate"),"bpm",Color(0xFFC95B78),expectedCadenceSeconds=60.0)
        data.optJSONObject("oxygen")?.let {SectionTitle("血氧");Metrics(it,listOf("averagePercent" to "平均 %","minPercent" to "最低 %","sampleCount" to "样本数"))}
        NativeLineChart("血氧 · %",samples.array("oxygen"),"percent",MaterialTheme.colorScheme.primary)
        data.optJSONObject("stress")?.let {SectionTitle("压力");Metrics(it,listOf("average" to "平均","max" to "最高","sampleCount" to "样本数"))}
        NativeLineChart("压力",samples.array("stress"),"value",MaterialTheme.colorScheme.secondary)
        data.optJSONObject("sleep")?.let {sleep->SectionTitle("睡眠");Metrics(sleep,listOf("startedAt" to "入睡","endedAt" to "醒来","totalMinutes" to "总时长 分钟","deepMinutes" to "深睡 分钟","lightMinutes" to "浅睡 分钟","remMinutes" to "REM 分钟"))}
        Text("来源：Zepp / Amazfit · 原始数据日期 ${data.text("sourceDate")}。不支持或未获得的指标显示缺失。",style=MaterialTheme.typography.bodySmall)
    }}
}

@Composable fun UpcomingSession(vm:AppModel,today:String) {
    val repo=vm.repository ?: return
    var next by androidx.compose.runtime.remember(today) {androidx.compose.runtime.mutableStateOf<JSONObject?>(null)}
    var loading by androidx.compose.runtime.remember {androidx.compose.runtime.mutableStateOf(true)}
    var error by androidx.compose.runtime.remember {androidx.compose.runtime.mutableStateOf<String?>(null)}
    androidx.compose.runtime.LaunchedEffect(today,vm.revision) {
        try {val from=java.time.LocalDate.parse(today);next=HealthContracts.upcoming(repo.get("/api/v1/training/sessions?from=$from&to=${from.plusDays(365)}&status=planned").data.array("items"),java.time.Instant.now())}
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message} finally {loading=false}
    }
    if(next!=null) RecordCard(vm,Forms.get("sessions"),next!!) else if(loading) Text("正在读取安排…") else if(error!=null) InfoCard("无法读取下一项训练",error!!) else InfoCard("暂无安排","从计划页创建训练课表。")
}

@Composable fun NutritionScreen(vm:AppModel) {NativeData(vm,"/api/v1/health/workspace") {data->PageColumn {
    SectionTitle("饮食与营养","仅统计已记录餐次")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={vm.push(route("records","meals"))}){Text("记录饮食")}
        OutlinedButton(onClick={vm.push(route("records","meal-plans"))}){Text("饮食计划")}
    }
    val nutrition=data.optJSONObject("nutrition") ?: JSONObject()
    val days=nutrition.array("days").filter {it.optInt("mealCount")>0 || it.text("planId","").isNotBlank()}
    DailyBars("已记录能量 · kcal",DashboardData.calendar(nutrition.array("days").map {d->JSONObject().put("date",d.text("date")).put("value",d.optJSONObject("actualLogged")?.opt("energyKcal") ?: JSONObject.NULL)}),"value","kcal")
    var selected by remember(days.toString()){mutableIntStateOf((days.size-1).coerceAtLeast(0))}
    if(days.isNotEmpty()) {
        ChartSelection(days,selected,{selected=it},days[selected].text("date"))
        val day=days[selected]
        NutritionTargetChart(day)
        MacroDonut(day)
        Text("${day.text("mealCount")} 餐 · ${day.text("estimatedMeals")} 餐为估算",style=MaterialTheme.typography.labelSmall)
        var details by remember {mutableStateOf(false)}
        TextButton(onClick={details=!details}){Text(if(details) "收起餐次" else "查看当天餐次")}
        if(details) nutrition.array("meals").filter {it.text("date")==day.text("date")}.forEach {RecordCard(vm,Forms.get("meals"),it)}
    } else Text("记录餐食后查看营养趋势。")
}}}
