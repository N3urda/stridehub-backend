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
        PageHeader(dateLabel,"健康趋势","")
        val weight=data.optJSONObject("weight")?.array("records")?.firstOrNull()
        val sleep=data.array("sleep").firstOrNull()
        val daily=data.array("daily").firstOrNull()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OverviewMetric("最近体重",healthValue(weight?.opt("weightKg"),1)+" kg",healthDate(weight?.text("date",""),today),Icons.Default.MonitorWeight,"overview-weight",Modifier.weight(1f).fillMaxHeight(),openWeighing)
            OverviewMetric("睡眠",healthDuration(sleep?.opt("totalMinutes")),healthDate(sleep?.text("date",""),today),Icons.Default.Bedtime,"overview-sleep",Modifier.weight(1f).fillMaxHeight()){vm.select(1)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=openWeighing,modifier=Modifier.weight(1f).testTag("overview-weighing")){Icon(Icons.Default.MonitorWeight,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("开始称重")}
            OutlinedButton(onClick={vm.push(route("today"))},modifier=Modifier.weight(1f)){Text("今日安排")}
        }
        val trend=data.optJSONObject("weight")?.array("trend").orEmpty()
        NativeLineChart("体重趋势 · kg",trend,"weightKg",ChartPalette.weight)
        DailyBars("每日步数",DashboardData.daily(data.array("daily"),"activity","steps"),"value","步",onOpen={vm.push(route("data","day",id=it.text("date")))})
        SleepStackChart(data.array("sleep")){vm.push(route("data","sleep",payload=it))}
        NativeLineChart("静息心率 · bpm",DashboardData.daily(data.array("daily"),"heartRate","restingBpm"),"value",androidx.compose.ui.graphics.Color(0xFFBA4E6B))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={vm.push(route("nutrition"))},modifier=Modifier.weight(1f)){Text("饮食与营养")}
            OutlinedButton(onClick={vm.select(1)},modifier=Modifier.weight(1f)){Text("全部身体指标")}
        }
        SectionTitle("下一项训练")
        UpcomingSession(vm,today)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            SectionTitle("近期运动")
            TextButton(onClick={vm.select(2)}) {Text("查看全部")}
        }
        val workouts=data.optJSONObject("workouts")?.array("records").orEmpty()
        if(workouts.isEmpty()) Text("暂无运动记录",style=MaterialTheme.typography.bodySmall)
        else workouts.take(2).forEach {WorkoutCard(vm,it)}
        ActionRow("数据与同步",Icons.Default.Sync){vm.push(route("sync"))}
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
