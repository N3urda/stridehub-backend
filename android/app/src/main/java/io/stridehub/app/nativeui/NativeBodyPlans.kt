@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun BodyScreen(vm:AppModel) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(29).toString())}
    var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var customDates by rememberSaveable {mutableStateOf(false)}
    var allSleep by rememberSaveable(from,to) {mutableStateOf(false)}
    var allDaily by rememberSaveable(from,to) {mutableStateOf(false)}
    val openWeighing=LocalOpenWeighing.current
    NativeData(vm,"/api/v1/health/workspace?from=$from&to=$to") {data->PageColumn {
        PageHeader("BODY","身体与恢复","体重、睡眠与日常指标，放在一起看。")
        FeatureActionCard(
            title="开始称重 / 本机历史",
            subtitle="连接体脂秤，记录身体的每一次变化",
            icon=Icons.Default.MonitorWeight,
            onClick=openWeighing,
            modifier=Modifier.testTag("body-weighing"),
            emphasized=true
        )
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("记录范围",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                TextButton(onClick={customDates=!customDates}) {Text(if(customDates) "收起日期" else "自定日期")}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(7L to "近 7 天",30L to "近 30 天",90L to "近 90 天").forEach {(days,label)->
                    val today=LocalDate.now()
                    val rangeFrom=today.minusDays(days-1).toString()
                    FilterChip(selected=from==rangeFrom && to==today.toString(),onClick={from=rangeFrom;to=today.toString();customDates=false},label={Text(label)})
                }
            }
            Text("$from — $to",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(customDates) DateRangeControls(from,to,maxDays=365) {a,b->from=a;to=b}
        }
        val weights=data.optJSONObject("weight")
        val weight=weights?.array("records")?.firstOrNull()
        val sleep=data.array("sleep")
        val latestSleep=sleep.firstOrNull()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            BodySnapshot("最新体重",healthValue(weight?.opt("weightKg"),1),"kg",weight?.text("date") ?: "此范围暂无记录",Icons.Default.MonitorWeight,Modifier.weight(1f).fillMaxHeight())
            BodySnapshot("最新睡眠",healthDuration(latestSleep?.opt("totalMinutes")),null,latestSleep?.text("date") ?: "此范围暂无记录",Icons.Default.Bedtime,Modifier.weight(1f).fillMaxHeight())
        }
        SectionTitle("体重与身体记录","从单次测量到长期变化")
        val trend=weights?.array("trend").orEmpty()
        if(trend.isNotEmpty()) NativeLineChart("体重 · kg",trend,"weightKg",MaterialTheme.colorScheme.primary)
        else InfoCard("还没有这一时段的体重趋势","开始称重或补充体重记录后，这里会呈现实际测量的变化。")
        DestinationGrid(vm,listOf(
            BodyPlanDestination("weight",Forms.get("weight").title,"查看与补充体重",Icons.Default.MonitorWeight),
            BodyPlanDestination("measurements",Forms.get("measurements").title,"记录围度的变化",Icons.Default.Straighten),
            BodyPlanDestination("blood-pressure",Forms.get("blood-pressure").title,"保留每次测量",Icons.Default.FavoriteBorder),
            BodyPlanDestination("check-ins",Forms.get("check-ins").title,"记录今天的感受",Icons.Default.SelfImprovement)
        ))
        ActionRow("健康背景与检查报告",Icons.Default.FolderOpen){vm.push(route("records","context"))}
        SectionTitle("睡眠历史","睡眠时长与记录详情")
        if(sleep.isEmpty()) {
            InfoCard("暂无睡眠记录","同步已上传到 Zepp 的睡眠数据，或调整日期范围查看之前的记录。")
            ActionRow("同步 Zepp 健康数据",Icons.Default.Sync){vm.push(route("sync"))}
        } else {
            NativeLineChart("睡眠时长 · 分钟",sleep.reversed(),"totalMinutes",MaterialTheme.colorScheme.secondary)
            (if(allSleep) sleep else sleep.take(5)).forEach {r->
                InfoCard(r.text("date"),"睡眠 ${healthDuration(r.opt("totalMinutes"))} · 深睡 ${healthDuration(r.opt("deepMinutes"))}\n${r.text("sourceLabel",r.text("source","Zepp"))}") {
                    vm.push(route("data","sleep",payload=r,title="睡眠详情"))
                }
            }
            if(sleep.size>5) TextButton(onClick={allSleep=!allSleep},modifier=Modifier.fillMaxWidth()) {Text(if(allSleep) "收起睡眠历史" else "查看全部 ${sleep.size} 条睡眠记录")}
        }
        SectionTitle("每日健康指标","步数、心率与当日记录")
        val daily=data.array("daily")
        if(daily.isEmpty()) InfoCard("暂无每日健康指标","当前范围没有设备记录。完成同步后，可在这里查看每天的实际数据。")
        (if(allDaily) daily else daily.take(5)).forEach {r->
            val heartRate=healthValue(r.optJSONObject("heartRate")?.opt("restingBpm"))
            InfoCard(r.text("date"),"步数 ${healthValue(r.optJSONObject("activity")?.opt("steps"))} · 静息心率 ${if(heartRate=="—") heartRate else "$heartRate bpm"}") {
                vm.push(route("data","day",id=r.text("date"),title="每日指标"))
            }
        }
        if(daily.size>5) TextButton(onClick={allDaily=!allDaily},modifier=Modifier.fillMaxWidth()) {Text(if(allDaily) "收起每日指标" else "查看全部 ${daily.size} 天的指标")}
    }}
}

@Composable fun PlansScreen(vm:AppModel) {PageColumn {
    PageHeader("PLAN","计划与日常","从今天要做的事开始，让每一步都有记录。")
    SectionTitle("今日执行")
    FeatureActionCard(
        title="今日安排与训练反馈",
        subtitle="查看今天的安排，记录完成情况与运动感受",
        icon=Icons.Default.Today,
        onClick={vm.push(route("today"))},
        modifier=Modifier.testTag("plans-today"),
        emphasized=true
    )
    SectionTitle("训练与目标","安排下一次训练，回看正在坚持的目标")
    DestinationGrid(vm,listOf(
        BodyPlanDestination("sessions",Forms.get("sessions").title,"安排每次训练",Icons.Default.EventNote),
        BodyPlanDestination("plans",Forms.get("plans").title,"管理日常行动",Icons.Default.Checklist),
        BodyPlanDestination("weight-goals",Forms.get("weight-goals").title,"查看阶段目标",Icons.Default.Flag),
        BodyPlanDestination("races",Forms.get("races").title,"赛事与准备安排",Icons.Default.EmojiEvents)
    ))
    ActionRow("训练偏好",Icons.Default.Tune){vm.push(route("records","profile"))}
    SectionTitle("饮食与补给","把计划、摄入与运动补给连起来")
    FeatureActionCard(
        title="饮食与每日营养",
        subtitle="查看每天的饮食记录与营养汇总",
        icon=Icons.Default.Restaurant,
        onClick={vm.push(route("nutrition"))}
    )
    DestinationGrid(vm,listOf(
        BodyPlanDestination("meal-plans",Forms.get("meal-plans").title,"提前安排吃什么",Icons.Default.MenuBook),
        BodyPlanDestination("meals",Forms.get("meals").title,"记录实际摄入",Icons.Default.Restaurant),
        BodyPlanDestination("fuel-logs",Forms.get("fuel-logs").title,"补给与饮水记录",Icons.Default.WaterDrop)
    ))
    SectionTitle("身体感受","给数据补上你自己的感受")
    FeatureActionCard(
        title=Forms.get("check-ins").title,
        subtitle="记录体感、恢复与日常状态，回看变化时多一份依据",
        icon=Icons.Default.SelfImprovement,
        onClick={vm.push(route("records","check-ins"))}
    )
}}

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
