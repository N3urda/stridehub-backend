@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun BodyScreen(vm:AppModel) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(29).toString())}
    var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var customDates by rememberSaveable {mutableStateOf(false)}
    var presetDays by rememberSaveable {mutableStateOf<Long?>(30L)}
    var metric by rememberSaveable {mutableStateOf("weight")}
    val openWeighing=LocalOpenWeighing.current
    NativeData(vm,"/api/v1/health/workspace?from=$from&to=$to") {data->
        val today=runCatching {LocalDate.parse(data.text("today"))}.getOrDefault(LocalDate.now())
        LaunchedEffect(today,presetDays) {presetDays?.let {days->from=today.minusDays(days-1).toString();to=today.toString()}}
        PageColumn {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("身体与恢复",style=MaterialTheme.typography.headlineSmall)
                TextButton(onClick=openWeighing,modifier=Modifier.testTag("body-weighing")){Icon(Icons.Default.MonitorWeight,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("称重")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                listOf(7L to "7 天",30L to "30 天",90L to "90 天").forEach {(days,label)->FilterChip(selected=presetDays==days,onClick={presetDays=days;customDates=false},label={Text(label)})}
                TextButton(onClick={customDates=!customDates}){Text("自定")}
            }
            if(customDates) DateRangeControls(from,to,maxDays=365) {a,b->presetDays=null;from=a;to=b}
            BodyMetricTabs(metric,{metric=it},listOf("weight" to "体重","sleep" to "睡眠","activity" to "活动","recovery" to "恢复","composition" to "体成分","measurements" to "围度","pressure" to "血压"))
            BodyMetricContent(vm,data,metric,from,to)
            Text("$from — $to · ${data.text("timezone")}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("记录与连接")
            ActionRow("每日身体状态",Icons.Default.SelfImprovement){vm.push(route("records","check-ins"))}
            ActionRow("健康背景与检查报告",Icons.Default.FolderOpen){vm.push(route("records","context"))}
            ActionRow("同步 Zepp 健康数据",Icons.Default.Sync){vm.push(route("sync"))}
        }
    }
}

@Composable fun PlansScreen(vm:AppModel) {
    val initialWeek=remember {LocalDate.now().let {it.minusDays((it.dayOfWeek.value-1).toLong())}}
    var week by rememberSaveable {mutableStateOf(initialWeek.toString())}
    var selectedDate by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var manage by rememberSaveable {mutableStateOf(false)}
    var followCurrentWeek by rememberSaveable {mutableStateOf(true)}
    val weekStart=LocalDate.parse(week)
    NativeData(vm,"/api/v1/health/workspace?from=$week&to=${weekStart.plusDays(6)}") {data->PageColumn {
        PageHeader("PLAN","计划与日常","从一周安排，走到今天的行动。")
        val serverToday=runCatching {LocalDate.parse(data.text("today"))}.getOrDefault(LocalDate.now())
        LaunchedEffect(serverToday,followCurrentWeek) {if(followCurrentWeek) {week=serverToday.minusDays((serverToday.dayOfWeek.value-1).toLong()).toString();selectedDate=serverToday.toString()}}
        val timezone=data.text("timezone",java.time.ZoneId.systemDefault().id)
        val sessions=JournalPresentation.weekSessions(data.array("sessions"),weekStart,timezone)
        PlanWeekChart(sessions,weekStart,selectedDate,timezone,{selectedDate=it}) {offset->
            followCurrentWeek=false;week=weekStart.plusWeeks(offset).toString();selectedDate=week
        }
        if(!followCurrentWeek) TextButton(onClick={followCurrentWeek=true}) {Text("回到本周")}
        val selected=JournalPresentation.sessionsOn(sessions,selectedDate,timezone)
        SectionTitle(if(selectedDate==data.text("today")) "今天的训练" else "$selectedDate · 训练")
        if(selected.isEmpty()) InfoCard("当天没有训练安排","可以添加课表；没有安排不等于休息日。")
        selected.forEach {session->
            val start=runCatching {java.time.OffsetDateTime.parse(session.text("startAt")).atZoneSameInstant(java.time.ZoneId.of(timezone)).toLocalTime().toString().take(5)}.getOrDefault("")
            InfoCard(session.text("title","训练"),"$start · ${statusLabel(session.text("status"))} · ${healthValue(session.opt("distanceKm"),1)} km · ${healthDuration(session.opt("durationMinutes"))}") {
                vm.push(route("comparison",id=session.getString("id")))
            }
        }
        FeatureActionCard(title="今日安排与训练反馈",subtitle="查看今天的安排，补充执行反馈",icon=Icons.Default.Today,
            onClick={vm.push(route("today"))},modifier=Modifier.testTag("plans-today"),emphasized=true)
        val goals=data.optJSONObject("weight")?.array("goals").orEmpty().filter {it.text("status")=="active"}
        if(goals.isNotEmpty()) {
            SectionTitle("正在坚持的目标")
            goals.forEach {goal->val progress=goal.optJSONObject("progress")
                ChartPanel("体重目标 · ${healthValue(goal.opt("targetWeightKg"),1)} kg") {
                    val percent=DashboardData.number(progress,"percent")
                    Text("起点 ${healthValue(goal.opt("startWeightKg"),1)} kg  →  当前 ${healthValue(progress?.opt("currentWeightKg"),1)} kg",style=MaterialTheme.typography.bodyMedium)
                    if(percent!=null) {
                        LinearProgressIndicator(progress={(percent/100).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(8.dp))
                        Text("相对目标进展 ${healthValue(percent,0)}% · ${progress?.text("measuredAt")}",style=MaterialTheme.typography.labelSmall)
                    } else Text("目标开始后还没有体重记录",style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={vm.push(route("records","weight-goals"))}) {Text("查看目标")}
                }
            }
        }
        FeatureActionCard(title="饮食与每日营养",subtitle="逐日查看摄入、目标与营养结构",icon=Icons.Default.Restaurant,onClick={vm.push(route("nutrition"))})
        OutlinedButton(onClick={manage=!manage},modifier=Modifier.fillMaxWidth()) {Text(if(manage) "收起计划管理" else "管理计划与目标")}
        if(manage) {
            SectionTitle("训练与目标")
            DestinationGrid(vm,listOf(
                BodyPlanDestination("sessions",Forms.get("sessions").title,"安排每次训练",Icons.Default.EventNote),
                BodyPlanDestination("plans",Forms.get("plans").title,"管理日常行动",Icons.Default.Checklist),
                BodyPlanDestination("weight-goals",Forms.get("weight-goals").title,"查看阶段目标",Icons.Default.Flag),
                BodyPlanDestination("races",Forms.get("races").title,"赛事与准备安排",Icons.Default.EmojiEvents)))
            ActionRow("训练偏好",Icons.Default.Tune){vm.push(route("records","profile"))}
            SectionTitle("饮食与补给")
            DestinationGrid(vm,listOf(
                BodyPlanDestination("meal-plans",Forms.get("meal-plans").title,"提前安排吃什么",Icons.Default.MenuBook),
                BodyPlanDestination("meals",Forms.get("meals").title,"记录实际摄入",Icons.Default.Restaurant),
                BodyPlanDestination("fuel-logs",Forms.get("fuel-logs").title,"补给与饮水记录",Icons.Default.WaterDrop),
                BodyPlanDestination("check-ins",Forms.get("check-ins").title,"记录恢复与感受",Icons.Default.SelfImprovement)))
        }
    }}
}

@Composable private fun BodySnapshot(title:String,value:String,unit:String?,date:String,icon:ImageVector,modifier:Modifier=Modifier) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                Text(title,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(value,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                if(unit!=null) Text(unit,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(date,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class BodyPlanDestination(val kind:String,val title:String,val subtitle:String,val icon:ImageVector)
@Composable private fun DestinationGrid(vm:AppModel,destinations:List<BodyPlanDestination>) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        destinations.chunked(2).forEach {pair->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                pair.forEach {destination->
                    Surface(onClick={vm.push(route("records",destination.kind))},modifier=Modifier.weight(1f).fillMaxHeight(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(destination.icon,null,Modifier.size(23.dp),tint=MaterialTheme.colorScheme.primary)
                            Text(destination.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                            Text(destination.subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(pair.size==1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun PlanWeekChart(sessions:List<org.json.JSONObject>,week:LocalDate,selected:String,zone:String,onSelect:(String)->Unit,onWeek:(Long)->Unit) {
    val (completed,total)=JournalPresentation.completion(sessions)
    ChartPanel("一周训练") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={onWeek(-1)}) {Text("上一周")}
            Text("${week.monthValue}/${week.dayOfMonth} — ${week.plusDays(6).monthValue}/${week.plusDays(6).dayOfMonth}",style=MaterialTheme.typography.labelLarge)
            TextButton(onClick={onWeek(1)}) {Text("下一周")}
        }
        Text(if(total==0) "本周暂无待执行或已完成的训练" else "完成 $completed / $total 次",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
        if(total>0) LinearProgressIndicator(progress={completed.toFloat()/total},modifier=Modifier.fillMaxWidth().height(8.dp))
        val columns=if(LocalDensity.current.fontScale>=1.5f) 4 else 7
        (0..6).toList().chunked(columns).forEach {weekRow->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                weekRow.forEach {offset->
                    val day=week.plusDays(offset.toLong())
                    val rows=JournalPresentation.sessionsOn(sessions,day.toString(),zone)
                    val (done,planned)=JournalPresentation.completion(rows)
                    Surface(onClick={onSelect(day.toString())},modifier=Modifier.weight(1f).heightIn(min=88.dp),shape=MaterialTheme.shapes.small,
                        color=if(selected==day.toString()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(listOf("一","二","三","四","五","六","日")[offset],style=MaterialTheme.typography.labelSmall,maxLines=1)
                            Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1)
                            Text(if(planned==0) "—" else "$done/$planned",style=MaterialTheme.typography.labelSmall,maxLines=1)
                        }
                    }
                }
                repeat(columns-weekRow.size) {Spacer(Modifier.weight(1f))}
            }
        }
        Text("日期下方为已完成 / 已安排；取消项不计入进度。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
