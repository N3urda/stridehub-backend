@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable fun WorkoutsScreen(vm:AppModel,management:Boolean=false) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(89).toString())}
    var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var sport by rememberSaveable {mutableStateOf("all")}
    var page by rememberSaveable {mutableIntStateOf(1)}
    var showDates by rememberSaveable {mutableStateOf(false)}
    val stackActions=LocalDensity.current.fontScale>=1.5f
    val path="/api/v1/mobile/workouts?page=$page&limit=30&from=$from&to=$to&sportType=$sport"
    NativeData(vm,path) {data->PageColumn {
        SectionTitle(if(management) "运动记录管理" else "每一次运动","留下运动，也看见积累。")
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("这段时间的积累",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        Text("$from — $to",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.75f))
                    }
                    IconButton(onClick={showDates=!showDates}) {Icon(if(showDates) Icons.Default.ExpandLess else Icons.Default.DateRange,if(showDates) "收起日期筛选" else "调整日期范围")}
                }
                val summary=data.optJSONObject("summary") ?: JSONObject()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    WorkoutFigure("运动次数",workoutNumber(summary,"count",0),"次",Modifier.weight(1f))
                    WorkoutFigure("累计距离",workoutNumber(summary,"distanceKm"),"km",Modifier.weight(1f))
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.14f))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    WorkoutFact("运动时长",workoutDuration(workoutNumeric(summary,"movingSeconds")),Modifier.weight(1f),valueTag="workout-summary-duration")
                    WorkoutFact("平均心率",workoutNumber(summary,"averageHeartRate",1).let {if(it=="—") it else "$it bpm"},Modifier.weight(1f),valueTag="workout-summary-heart-rate")
                }
            }
        }
        if(showDates) {
            DateRangeControls(from,to) {a,b->from=a;to=b;page=1}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(7L to "近 7 天",30L to "近 30 天",90L to "近 90 天").forEach {(days,label)->
                    TextButton(onClick={to=LocalDate.now().toString();from=LocalDate.now().minusDays(days-1).toString();page=1;showDates=false}) {Text(label)}
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部","Run" to "跑步","Walk" to "步行","Ride" to "骑行","Other" to "其他").forEach {(k,label)->
                FilterChip(sport==k,{sport=k;page=1},label={Text(label)})
            }
        }
        if(stackActions) {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={vm.push(route("activityForm"))},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("新增运动")}
                OutlinedButton(onClick={vm.push(route("imports"))},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Default.FileUpload,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("导入文件")}
            }
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                WorkoutShortcut("跑步分析","趋势与分段",Icons.Default.Insights,Modifier.fillMaxWidth()){vm.push(route("running"))}
                WorkoutShortcut("同步 Zepp","更新设备记录",Icons.Default.Sync,Modifier.fillMaxWidth()){vm.push(route("sync"))}
            }
        } else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={vm.push(route("activityForm"))},modifier=Modifier.weight(1f)) {Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("新增运动")}
                OutlinedButton(onClick={vm.push(route("imports"))},modifier=Modifier.weight(1f)) {Icon(Icons.Default.FileUpload,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("导入文件")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                WorkoutShortcut("跑步分析","趋势与分段",Icons.Default.Insights,Modifier.weight(1f)){vm.push(route("running"))}
                WorkoutShortcut("同步 Zepp","更新设备记录",Icons.Default.Sync,Modifier.weight(1f)){vm.push(route("sync"))}
            }
        }
        val pending=data.optInt("pendingDuplicates")
        if(pending>0) InfoCard("$pending 条运动待核对","进入带有「待核对」标记的运动确认来源；核对完成前，累计总量保持未知。")
        SectionTitle("运动记录","按时间排列 · 点开查看曲线与轨迹")
        val rows=data.array("records")
        if(rows.isEmpty()) {
            InfoCard("还没有这一时段的记录","可以调整日期与运动类型，或从设备和文件中添加运动。")
            TextButton(onClick={showDates=true}) {Text("调整日期范围")}
        }
        rows.forEach {WorkoutCard(vm,it)}
        val pages=data.optJSONObject("pagination")
        if(page>1 || pages?.optBoolean("hasMore")==true) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={page--},enabled=page>1){Text("上一页")}
            Text("第 $page / ${pages?.text("pages","1")} 页",style=MaterialTheme.typography.labelMedium)
            TextButton(onClick={page++},enabled=pages?.optBoolean("hasMore")==true){Text("下一页")}
        }
        Text("缺失的记录显示为 —；来源关联由你确认后合并统计。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}

@Composable fun WorkoutCard(vm:AppModel,row:JSONObject) {
    val sport=row.text("sportType")
    val pending=row.optJSONArray("duplicateCandidates")?.length()?.let {it>0}==true
    Card(onClick={vm.push(route("workout",payload=row))},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(workoutIcon(sport),null,Modifier.padding(11.dp).size(24.dp),tint=MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(row.text("name",sportLabel(sport)),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(workoutDate(row.text("startAt")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight,"查看运动详情",tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(20.dp))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                WorkoutFact("距离 · km",workoutNumber(row,"distanceKm"),Modifier.weight(1f),true)
                WorkoutFact("运动时长",workoutDuration(workoutNumeric(row,"movingSeconds") ?: workoutNumeric(row,"durationMinutes")?.times(60)),Modifier.weight(1.3f),true)
                WorkoutFact("心率 · bpm",workoutNumber(row,"averageHeartRate",0),Modifier.weight(1f),true)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("${sportLabel(sport)} · ${workoutSource(row)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                if(pending) Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("待核对",Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

private fun workoutNumeric(row:JSONObject,key:String):Double?=(row.opt(key) as? Number)?.toDouble()?.takeIf {it.isFinite()}
private fun workoutNumber(row:JSONObject,key:String,digits:Int=2):String=workoutNumeric(row,key)?.let {String.format(Locale.CHINA,"%.${digits}f",it)} ?: "—"
private fun workoutDuration(seconds:Double?):String {
    val total=seconds?.takeIf {it>=0}?.roundToLong() ?: return "—"
    val hours=total/3600;val minutes=total%3600/60;val remaining=total%60
    return when {
        hours>0 -> if(remaining>0) "${hours}时${minutes}分${remaining}秒" else "${hours}时${minutes}分"
        minutes>0 -> if(remaining>0) "${minutes}分${remaining}秒" else "${minutes}分钟"
        else -> "${remaining}秒"
    }
}
private fun workoutDate(value:String):String=runCatching {OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy年M月d日 · HH:mm"))}.getOrDefault(value)
private fun workoutSource(row:JSONObject):String {
    val sources=WorkoutPresentation.sources(row)
    return when {
        sources.size>1 -> "${sources.size} 个来源已关联"
        sources.firstOrNull()?.text("source")=="zepp" -> "Zepp"
        else -> "手动 / 文件导入"
    }
}
private fun workoutIcon(sport:String):ImageVector=when(sport) {
    "Run","TrailRun","VirtualRun" -> Icons.AutoMirrored.Filled.DirectionsRun
    "Walk","Hike" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "Ride","VirtualRide","MountainBikeRide","GravelRide","EBikeRide","EMountainBikeRide" -> Icons.Default.DirectionsBike
    "WeightTraining" -> Icons.Default.FitnessCenter
    else -> Icons.Default.Sports
}
@Composable private fun WorkoutFigure(label:String,value:String,unit:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.75f))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(value,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text(unit,Modifier.padding(bottom=5.dp),style=MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable private fun WorkoutFact(label:String,value:String,modifier:Modifier=Modifier,prominent:Boolean=false,valueTag:String?=null) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,modifier=if(valueTag!=null) Modifier.testTag(valueTag) else Modifier,style=if(prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.SemiBold)
    }
}
@Composable private fun WorkoutShortcut(title:String,subtitle:String,icon:ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.primary)
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title,style=MaterialTheme.typography.labelLarge)
                Text(subtitle,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
fun sportLabel(value:String)=mapOf("Run" to "跑步","Walk" to "步行","Hike" to "徒步","Ride" to "骑行","TrailRun" to "越野跑","VirtualRun" to "室内跑","VirtualRide" to "室内骑行","WeightTraining" to "力量训练","Other" to "其他")[value] ?: value
@Composable fun WorkoutScreen(vm:AppModel,row:JSONObject) {
    val id=row.getString("id");val cloud=id.startsWith("zepp-");val canonicalId=row.text("canonicalId",id)
    val path=if(cloud) "/api/v1/health/zepp/workouts/"+encoded(id) else "/api/v1/mobile/activities/"+encoded(id)
    NativeData(vm,path) {detail->
        if(!cloud) NativeData(vm,"/api/v1/activities/"+encoded(id)) {source->
            val current=WorkoutPresentation.imported(source)
            WorkoutContent(vm,row,detail,current,current,canonicalId,false)
        } else {
            val current=WorkoutPresentation.zepp(detail,id)
            if(canonicalId!=id && !canonicalId.startsWith("zepp-")) NativeData(vm,"/api/v1/activities/"+encoded(canonicalId)) {canonical->WorkoutContent(vm,row,detail,current,WorkoutPresentation.imported(canonical),canonicalId,true)}
            else WorkoutContent(vm,row,detail,current,current,canonicalId,true)
        }
    }
}
@Composable private fun WorkoutContent(vm:AppModel,preview:JSONObject,detail:JSONObject,current:JSONObject,canonical:JSONObject,canonicalId:String,cloud:Boolean) {PageColumn {
    val sourceId=current.getString("id")
    SectionTitle(current.text("name","运动详情"),workoutDate(current.text("startAt")))
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(workoutIcon(current.text("sportType")),null,tint=MaterialTheme.colorScheme.primary)
                Text(sportLabel(current.text("sportType")),style=MaterialTheme.typography.labelLarge)
            }
            WorkoutFigure("运动距离",workoutNumber(current,"distanceKm"),"km")
            HorizontalDivider(color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.14f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                WorkoutFact("运动时长",workoutDuration(workoutNumeric(current,"durationMinutes")?.times(60)),Modifier.weight(1f),true)
                WorkoutFact("平均心率 · bpm",workoutNumber(current,"averageHeartRate",1),Modifier.weight(1f),true)
            }
        }
    }
    val sources=WorkoutPresentation.sources(preview)
    Text("当前来源："+(if(cloud) "Zepp / Amazfit" else "手动 / 文件导入"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(sources.size>1) {
        SectionTitle("查看原始来源","来源合并保留每份记录。统计与训练继续使用同一条规范运动。")
        sources.forEach {source->OutlinedButton(onClick={vm.push(route("workout",payload=WorkoutPresentation.sourceRoute(preview,source.getString("id"))))},enabled=source.getString("id")!=sourceId){Text(if(source.text("source")=="zepp") "Zepp 传感器与设备详情" else "导入记录、曲线与分段")}}
    }
    if(cloud) {
        SectionTitle("运动表现","来自这次运动记录的实际数据")
        Metrics(HealthContracts.zeppSummary(detail),listOf("caloriesKcal" to "能量 kcal","paceSecondsPerKm" to "平均配速 秒/km","maxHeartRateBpm" to "最高心率 bpm","ascentMeters" to "累计爬升 m"))
        SectionTitle("过程与轨迹","点按曲线查看采样值")
        val streams=detail.optJSONObject("streams") ?: JSONObject()
        listOf(Triple("heartRate","bpm","心率 · bpm"),Triple("pace","secondsPerKilometer","配速 · 秒/km"),Triple("cadence","stepsPerMinute","步频 · 步/分"),Triple("altitude","meters","海拔 · m"),Triple("power","watts","功率 · W")).forEach {(stream,key,title)->NativeLineChart(title,streams.array(stream),key,if(stream=="heartRate") Color(0xFFCA5B78) else MaterialTheme.colorScheme.primary,expectedCadenceSeconds=1.0)}
        NativeRoute(streams.array("location").mapNotNull {p->if(p.has("latitude") && p.has("longitude")) RoutePoint(p.optDouble("latitude"),p.optDouble("longitude")) else null})
    } else {
        detail.optJSONObject("fields")?.let {fields->if(fields.text("description","").isNotBlank()) Text(fields.text("description"));Metrics(fields,listOf("calories" to "能量 kcal","maxHeartRate" to "最高心率 bpm"))}
        ImportedStreams(vm,sourceId)
        if(WorkoutPresentation.canAssociate(current)) RunningAnalysis(vm,sourceId)
        if(detail.optJSONObject("capabilities")?.optBoolean("edit")==true) OutlinedButton(onClick={vm.push(route("activityForm",id=sourceId,payload=detail))},modifier=Modifier.fillMaxWidth()){Text("编辑运动")}
        ExportButton(vm,sourceId);ActivityActions(vm,sourceId,detail)
    }
    val duplicates=preview.optJSONArray("duplicateCandidates") ?: JSONArray()
    if(duplicates.length()>0 && cloud) {
        SectionTitle("核对重复来源","仅在确认是同一次运动后合并。")
        for(i in 0 until duplicates.length()) {val candidate=duplicates.optString(i);OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/health/workout-links/"+encoded(sourceId),"PUT",JSONObject().put("version",0).put("activityId",candidate));vm.pop();vm.message="来源已关联"}},enabled=!vm.busy){Text("确认关联导入记录 ${candidate.takeLast(12)}")}}
    }
    preview.optJSONObject("sourceLink")?.let {link->OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/health/workout-links/"+encoded(link.getString("id"))+"?version="+link.text("version"),"DELETE");vm.pop();vm.message="已拆分来源"}}){Text("解除来源合并")}}
    if(WorkoutPresentation.canAssociate(canonical)) ActionRow("关联到训练课表"){vm.push(route("association","workout",id=canonicalId,payload=canonical))}
    else InfoCard("此运动不能关联跑步课表","训练课表支持跑步、越野跑与室内跑。当前规范记录类型：${sportLabel(canonical.text("sportType"))}。")
}}
@Composable private fun ImportedStreams(vm:AppModel,id:String) {val repo=vm.repository ?: return;var result by remember(id){mutableStateOf<JSONObject?>(null)};var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(id,vm.revision){try {result=repo.get("/api/v1/mobile/activities/${encoded(id)}/streams").data} catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message}}
    result?.let {data->val streams=data.optJSONObject("streams") ?: JSONObject();val time=streams.optJSONArray("time") ?: JSONArray()
        fun timeline(key:String,transform:(Double)->Double={it}):List<JSONObject> {val values=streams.optJSONArray(key) ?: JSONArray();return (0 until values.length()).map {i->JSONObject().put("seconds",time.opt(i) ?: i).put("value",if(values.isNull(i)) JSONObject.NULL else (values.opt(i) as? Number)?.toDouble()?.let(transform) ?: JSONObject.NULL)}}
        NativeLineChart("心率 · bpm",timeline("heartrate"),"value",Color(0xFFCA5B78));NativeLineChart("速度 · km/h",timeline("velocity_smooth"){it*3.6},"value",MaterialTheme.colorScheme.primary)
        NativeLineChart("海拔 · m",timeline("altitude"),"value",MaterialTheme.colorScheme.secondary)
        val coordinates=streams.optJSONArray("latlng") ?: JSONArray();NativeRoute((0 until coordinates.length()).mapNotNull {i->coordinates.optJSONArray(i)?.let {p->if(p.length()==2) RoutePoint(p.optDouble(0),p.optDouble(1)) else null}})
        fun normalized(rows:List<JSONObject>):List<JSONObject> = rows.mapIndexed {i,row -> JSONObject(row.toString()).apply {
            put("number",row.opt("lapNumber") ?: row.opt("splitNumber") ?: i+1)
            put("distanceKm",if(row.isNull("distanceInMeter")) JSONObject.NULL else row.optDouble("distanceInMeter")/1000)
            put("movingSeconds",row.opt("movingTimeInSeconds") ?: JSONObject.NULL)
            val distance=row.optDouble("distanceInMeter",0.0);val seconds=row.optDouble("movingTimeInSeconds",0.0)
            put("paceSecondsPerKm",if(distance>0) seconds/distance*1000 else JSONObject.NULL)
        }}
        SegmentTable("分段",normalized(data.array("splits")));SegmentTable("计圈",normalized(data.array("laps")))
    }
    error?.let {ErrorCard(it)}
}
@Composable private fun RunningAnalysis(vm:AppModel,id:String) {
    val repo=vm.repository ?: return;var data by remember(id){mutableStateOf<JSONObject?>(null)};var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(id,vm.revision) {try {data=repo.get("/api/v1/running/activities/"+encoded(id)).data} catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e;data=null;error=e.message}}
    data?.let {RunningAnalysisContent(it)};error?.let {ErrorCard(it)}
}
@Composable fun SegmentTable(title:String,rows:List<JSONObject>) {if(rows.isNotEmpty()) {SectionTitle(title);rows.forEachIndexed {index,row->InfoCard("${row.text("number",(index+1).toString())} · ${row.text("name","")}","${row.text("distanceKm")} km · ${row.text("movingSeconds")} 秒 · 配速 ${row.text("paceSecondsPerKm")} 秒/km · 心率 ${row.text("averageHeartRate")}\nGAP ${row.text("gapPaceSecondsPerKm")} 秒/km · 爬升 ${row.text("elevationM")} m")}}}
@Composable fun Metrics(data:JSONObject,fields:List<Pair<String,String>>) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal=18.dp,vertical=8.dp)) {
            fields.forEachIndexed {index,(key,label)->
                Row(Modifier.fillMaxWidth().padding(vertical=11.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(data.text(key),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                }
                if(index<fields.lastIndex) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            }
        }
    }
}
@Composable fun ExportButton(vm:AppModel,id:String) {
    val context=LocalContext.current
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")){uri->if(uri!=null) vm.action {repo->
        val bytes=repo.raw("/api/v1/activities/${encoded(id)}/gpx")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.use {it.write(bytes)} ?: error("无法写入所选位置")}
        vm.message="GPX 已导出"
    }}
    OutlinedButton(onClick={create.launch("stridehub-${id.takeLast(12)}.gpx")},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("导出 GPX 到文件")}
}
@Composable private fun ActivityActions(vm:AppModel,id:String,detail:JSONObject) {
    val caps=detail.optJSONObject("capabilities") ?: JSONObject();var delete by remember {mutableStateOf(false)}
    if(caps.optBoolean("enrich")) OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/mobile/activities/${encoded(id)}/enrich","POST",JSONObject().put("version",detail.get("version")));vm.message="运动补全已提交"}},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("重新补全运动数据")}
    if(caps.optBoolean("delete")) TextButton(onClick={delete=true},enabled=!vm.busy){Text("删除运动",color=MaterialTheme.colorScheme.error)}
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("删除运动？")},text={Column{Text("此操作会影响以下内容：");val a=caps.optJSONArray("deleteEffects") ?: JSONArray();for(i in 0 until a.length()) Text("• "+a.optString(i))}},confirmButton={TextButton(onClick={delete=false;vm.action {repo->repo.mutate("/api/v1/mobile/activities/${encoded(id)}?version="+encoded(detail.getString("version")),"DELETE");vm.pop();vm.message="运动已删除"}}){Text("确认删除")}},dismissButton={TextButton(onClick={delete=false}){Text("取消")}})
}
