@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import java.time.LocalDate
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

@Composable fun HealthDetail(vm:AppModel,r:JSONObject) {
    if(r.getString("kind")=="sleep") {PageColumn {
        val data=r.getJSONObject("payload");SectionTitle("睡眠详情",data.text("date"))
        SleepStackChart(listOf(data))
        Metrics(data,listOf("startedAt" to "入睡时间","endedAt" to "醒来时间","totalMinutes" to "总睡眠 分钟","deepMinutes" to "深睡 分钟","lightMinutes" to "浅睡 分钟","remMinutes" to "REM 分钟","awakeMinutes" to "清醒 分钟","wakeCount" to "醒来次数","score" to "设备评分","restingHeartRate" to "静息心率 bpm","sourceLabel" to "数据来源"))
        val sources=data.optJSONArray("contributingSources")
        if(sources!=null && sources.length()>1) Text("互补来源："+(0 until sources.length()).joinToString("、") {index->if(sources.optString(index)=="mi_fitness") "小米运动健康" else "Zepp"},style=MaterialTheme.typography.bodySmall)
        val fields=data.optJSONObject("fieldSources")
        val labels=mapOf("totalMinutes" to "总睡眠","deepMinutes" to "深睡","lightMinutes" to "浅睡","remMinutes" to "REM","restingHeartRate" to "静息心率","averageSpO2" to "平均血氧")
        if(fields!=null) labels.forEach {(key,label)->fields.optJSONObject(key)?.let {origin->
            if(origin.text("source")!=data.text("source")) Text("$label · ${if(origin.text("source")=="mi_fitness") "小米运动健康" else "Zepp"} 补充",style=MaterialTheme.typography.labelSmall)
        }}
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
    var next by remember(repo.account,today) {mutableStateOf<JSONObject?>(null)}
    var loading by remember(repo.account,today) {mutableStateOf(true)}
    var error by remember(repo.account,today) {mutableStateOf<String?>(null)}
    var offline by remember(repo.account,today) {mutableStateOf(false)}
    LaunchedEffect(repo.account,today,vm.revision) {
        loading=true;error=null
        try {
            val from=java.time.LocalDate.parse(today)
            val result=repo.get("/api/v1/training/sessions?from=$from&to=${from.plusDays(365)}&status=planned")
            next=HealthContracts.upcoming(result.data.array("items"),java.time.Instant.now());offline=result.offline
        } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message}
        finally {loading=false}
    }
    if(next!=null) RecordCard(vm,Forms.get("sessions"),next!!)
    else if(loading) Text("正在读取安排…")
    else if(error==null) InfoCard("暂无安排","从计划页创建训练课表。")
    if(offline) Text("训练安排来自离线缓存",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let {ErrorCard("无法更新下一项训练：$it",vm::refresh)}
}

@Composable fun NutritionScreen(vm:AppModel) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(29).toString())}
    var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var customDates by rememberSaveable {mutableStateOf(false)}
    var presetDays by rememberSaveable {mutableStateOf<Long?>(30L)}
    var selectedDate by rememberSaveable {mutableStateOf("")}
    NativeData(vm,"/api/v1/health/workspace?from=$from&to=$to") {data->PageColumn {
        PageHeader("NUTRITION","饮食与营养","看见已记录的摄入，逐日对照自己的计划。")
        val today=runCatching {LocalDate.parse(data.text("today"))}.getOrDefault(LocalDate.now())
        LaunchedEffect(today,presetDays) {presetDays?.let {days->from=today.minusDays(days-1).toString();to=today.toString()}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(7L to "近 7 天",30L to "近 30 天",90L to "近 90 天").forEach {(days,label)->
                FilterChip(selected=presetDays==days,onClick={presetDays=days;selectedDate=""},label={Text(label)})
            }
            TextButton(onClick={customDates=!customDates}) {Text("自定日期")}
        }
        if(customDates) DateRangeControls(from,to,maxDays=365) {a,b->presetDays=null;from=a;to=b;selectedDate="";customDates=false}
        Text("$from — $to",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={vm.push(route("records","meals"))}){Text("记录饮食")}
            OutlinedButton(onClick={vm.push(route("records","meal-plans"))}){Text("饮食计划")}
        }
        val nutrition=data.optJSONObject("nutrition") ?: JSONObject()
        val days=nutrition.array("days").sortedBy {it.text("date")}
        val selected=JournalPresentation.nutritionSelection(days,selectedDate)
        val energy=days.map {d->JSONObject().put("date",d.text("date")).put("value",d.optJSONObject("actualLogged")?.opt("energyKcal") ?: JSONObject.NULL)}
        DailyBars("已记录能量 · kcal",energy,"value","kcal",selectedIndex=selected,onSelectionChange={selectedDate=days[it].text("date")})
        if(days.isNotEmpty()) {
            ChartSelection(days,selected,{selectedDate=days[it].text("date")},"${days[selected].text("date")} · 每日营养")
            val day=days[selected]
            if(day.optInt("mealCount")==0) InfoCard("这一天还没有餐食记录",if(day.text("planId","").isNotBlank()) "已设置饮食目标，记录餐食后可查看对比。" else "未记录不代表没有进食；不会按零摄入计入趋势。")
            NutritionTargetChart(day)
            MacroDonut(day)
            val partial=JournalPresentation.partialNutrients(day)
            if(partial.isNotEmpty()) ChartPanel("还有营养信息待补全") {
                val labels=mapOf("energyKcal" to "能量","proteinGrams" to "蛋白质","carbohydrateGrams" to "碳水","fatGrams" to "脂肪")
                partial.forEach {key->
                    val value=DashboardData.number(day.optJSONObject("knownSubtotal"),key)
                    val coverage=day.optJSONObject("coverage")?.optInt(key) ?: 0
                    Text("${labels[key]}：已知小计 ${healthValue(value,0)} ${if(key=="energyKcal") "kcal" else "g"} · $coverage / ${day.optInt("mealCount")} 餐有数据",style=MaterialTheme.typography.bodySmall)
                }
                Text("这些小计不是完整合计，不计入上方目标达成和营养占比。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionTitle("当天餐次","${day.text("mealCount")} 餐 · ${day.text("estimatedMeals")} 餐为估算")
            nutrition.array("meals").filter {it.text("date")==day.text("date")}.forEach {RecordCard(vm,Forms.get("meals"),it)}
        } else InfoCard("暂无这段时间的饮食记录","记录餐食或调整日期范围后查看营养趋势。")
        Text("仅汇总已记录餐次，不代表全天摄入；缺失的营养数据保持未知。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}
