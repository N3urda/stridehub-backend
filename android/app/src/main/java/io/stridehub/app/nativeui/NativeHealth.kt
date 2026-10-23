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
    SectionTitle("饮食与每日营养","已记录餐次的合计；不代表全天摄入。")
    ActionRow("饮食记录"){vm.push(route("records","meals"))}
    ActionRow("饮食计划"){vm.push(route("records","meal-plans"))}
    val nutrition=data.optJSONObject("nutrition") ?: JSONObject()
    NativeLineChart("已完整记录的能量 · kcal",nutrition.array("days").map {d->JSONObject().put("date",d.text("date")).put("value",d.optJSONObject("actualLogged")?.opt("energyKcal") ?: JSONObject.NULL)},"value",MaterialTheme.colorScheme.primary)
    nutrition.array("days").filter {it.optInt("mealCount")>0 || it.text("planId","").isNotBlank()}.reversed().forEach {day->
        SectionTitle(day.text("date"),"${day.text("mealCount")} 餐 · ${day.text("estimatedMeals")} 餐使用估算值")
        Card {androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            Text("指标 · 已记录 / 目标 / 差值",style=MaterialTheme.typography.labelLarge)
            listOf("energyKcal" to "能量 kcal","proteinGrams" to "蛋白质 g","carbohydrateGrams" to "碳水 g","fatGrams" to "脂肪 g").forEach {(key,label)->
                Text("$label  ${day.optJSONObject("actualLogged")?.text(key) ?: "—"} / ${day.optJSONObject("targets")?.text(key) ?: "—"} / ${day.optJSONObject("delta")?.text(key) ?: "—"}")
                if(day.optJSONObject("actualLogged")?.isNull(key)!=false && day.optJSONObject("knownSubtotal")?.isNull(key)==false) Text("已知小计 ${day.getJSONObject("knownSubtotal").text(key)}（含缺失餐次，不能作为完整合计）",style=MaterialTheme.typography.bodySmall)
            }
        }}
        nutrition.array("meals").filter {it.text("date")==day.text("date")}.forEach {RecordCard(vm,Forms.get("meals"),it)}
        TextButton(onClick={val initial=Forms.initial(Forms.get("meals").fields).put("date",day.text("date")).put("planId",day.opt("planId")).put("version",0).put("id",java.util.UUID.randomUUID().toString());vm.push(route("form","meals",payload=initial))}) {Text("记录这一天的饮食")}
    }
    Text(nutrition.text("methodology"),style=MaterialTheme.typography.bodySmall)
}}}
