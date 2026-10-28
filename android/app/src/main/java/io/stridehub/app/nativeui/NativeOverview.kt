package io.stridehub.app.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun Overview(vm:AppModel) {
    val openWeighing=LocalOpenWeighing.current
    NativeData(vm,"/api/v1/health/workspace") {data->PageColumn {
        val today=healthReferenceDate(data.text("timezone", ""))
        val dateLabel=runCatching {LocalDate.parse(today).format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.CHINA))}.getOrDefault(today)
        PageHeader(dateLabel,"身体近况","把身体的变化，放回每天的生活里。")
        val weight=data.optJSONObject("weight")?.array("records")?.firstOrNull()
        val sleep=data.array("sleep").firstOrNull()
        val daily=data.array("daily").firstOrNull()
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.MonitorWeight,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text("最近体重",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    Text(healthDate(weight?.text("date",""),today),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(healthValue(weight?.opt("weightKg"),1),Modifier.testTag("overview-weight"),style=MaterialTheme.typography.displaySmall)
                    Text("kg",Modifier.padding(bottom=6.dp),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if(weight==null) "从第一次称重开始，慢慢看见变化。" else "服务器中最近保存的记录 · 本机记录可在称重页查看",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick=openWeighing,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("overview-weighing"),shape=MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("开始称重")
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OverviewMetric("睡眠",healthDuration(sleep?.opt("totalMinutes")),healthDate(sleep?.text("date",""),today),Icons.Default.Bedtime,"overview-sleep",Modifier.weight(1f).fillMaxHeight()){vm.select(1)}
            OverviewMetric("步数",healthValue(daily?.optJSONObject("activity")?.opt("steps")),healthDate(daily?.text("date",""),today),Icons.Default.DirectionsWalk,"overview-steps",Modifier.weight(1f).fillMaxHeight()){vm.select(1)}
        }
        Surface(onClick={vm.select(1)},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.FavoriteBorder,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.tertiary)
                Column(Modifier.weight(1f)) {
                    Text("静息心率",style=MaterialTheme.typography.labelLarge)
                    Text(if(daily?.optJSONObject("heartRate")?.isNull("restingBpm")!=false) "尚无测量数据" else healthDate(daily.text("date",""),today),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(healthValue(daily?.optJSONObject("heartRate")?.opt("restingBpm")),Modifier.testTag("overview-heart-rate"),style=MaterialTheme.typography.titleLarge)
                Text("bpm",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val trend=data.optJSONObject("weight")?.array("trend").orEmpty()
        if(trend.isNotEmpty()) NativeLineChart("体重趋势 · kg",trend,"weightKg",MaterialTheme.colorScheme.primary)
        SectionTitle("今天做什么","记录之外，也给今天留一点安排")
        FeatureActionCard("今日安排与执行反馈","查看计划，留下今天的完成情况",Icons.Default.Today,{vm.push(route("today"))},emphasized=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={vm.push(route("records","meals"))},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("饮食记录")}
            OutlinedButton(onClick={vm.push(route("records","check-ins"))},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("身体感受")}
        }
        SectionTitle("下一项训练")
        UpcomingSession(vm,today)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            SectionTitle("近期运动")
            TextButton(onClick={vm.select(2)}) {Text("查看全部")}
        }
        val workouts=data.optJSONObject("workouts")?.array("records").orEmpty()
        if(workouts.isEmpty()) FeatureActionCard("从一次运动开始","同步设备记录，或导入已有运动文件",Icons.Default.DirectionsRun,{vm.select(2)})
        else workouts.take(3).forEach {WorkoutCard(vm,it)}
        val sync=data.optJSONObject("sync")
        FeatureActionCard("数据与同步",sync?.let {"${statusLabel(it.text("state"))} · 查看同步记录与设备连接"} ?: "连接设备，让记录持续汇集",Icons.Default.Sync,{vm.push(route("sync"))})
    }}
}

@Composable private fun OverviewMetric(title:String,value:String,date:String,icon:ImageVector,tag:String,modifier:Modifier,onClick:()->Unit) {
    Card(onClick=onClick,modifier=modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.secondary)
                Text(title,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value,Modifier.testTag(tag),style=MaterialTheme.typography.headlineSmall)
            Text(date,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
