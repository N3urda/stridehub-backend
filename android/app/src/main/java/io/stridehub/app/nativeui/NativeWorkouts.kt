@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Composable fun WorkoutsScreen(vm:AppModel,management:Boolean=false) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(89).toString())};var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())};var sport by rememberSaveable {mutableStateOf("all")};var page by rememberSaveable {mutableIntStateOf(1)}
    val path="/api/v1/mobile/workouts?page=$page&limit=30&from=$from&to=$to"
    NativeData(vm,path) {data->PageColumn {
        SectionTitle(if(management) "运动记录管理" else "每一次运动","来源关联由你确认，避免重复统计。")
        DateRangeControls(from,to) {a,b->from=a;to=b;page=1}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("all" to "全部","Run" to "跑步","Walk" to "步行","Ride" to "骑行","Other" to "其他").forEach {(k,label)->FilterChip(sport==k,{sport=k},label={Text(label)})}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick={vm.push(route("imports"))},label={Text("导入文件")})
            AssistChip(onClick={vm.push(route("activityForm"))},label={Text("新增运动")})
            AssistChip(onClick={vm.push(route("sync"))},label={Text("同步 Zepp")})
        }
        data.optJSONObject("summary")?.let {summary->Metrics(summary,listOf("count" to "次数","distanceKm" to "总距离 km","movingSeconds" to "运动秒数","averageHeartRate" to "平均心率"))}
        if(data.optInt("pendingDuplicates")>0) InfoCard("${data.optInt("pendingDuplicates")} 条运动待核对","打开运动详情确认来源关联。存在疑似重复时，累计总量可能显示缺失。")
        val rows=data.array("records").filter {sport=="all" || if(sport=="Other") it.text("sportType") !in listOf("Run","TrailRun","VirtualRun","Walk","Hike","Ride","VirtualRide") else it.text("sportType").contains(sport,true)}
        if(rows.isEmpty()) InfoCard("暂无符合条件的运动","调整日期或类型，或者导入文件。")
        rows.forEach {WorkoutCard(vm,it)}
        val pages=data.optJSONObject("pagination")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={page--},enabled=page>1){Text("上一页")}
            Text("第 $page / ${pages?.text("pages","1")} 页")
            TextButton(onClick={page++},enabled=pages?.optBoolean("hasMore")==true){Text("下一页")}
        }
    }}
}
@Composable fun WorkoutCard(vm:AppModel,row:JSONObject) {InfoCard(row.text("name","运动"),"${row.text("startAt")}\n${sportLabel(row.text("sportType"))} · ${row.text("distanceKm")} km · ${row.text("durationMinutes")} 分钟"+(if(row.optJSONArray("duplicateCandidates")?.length()?.let {it>0}==true) "\n有待确认的重复来源" else "")){vm.push(route("workout",payload=row))}}
fun sportLabel(value:String)=mapOf("Run" to "跑步","Walk" to "步行","Hike" to "徒步","Ride" to "骑行","TrailRun" to "越野跑","VirtualRun" to "室内跑","VirtualRide" to "室内骑行","WeightTraining" to "力量训练","Other" to "其他")[value] ?: value
@Composable fun WorkoutScreen(vm:AppModel,row:JSONObject) {
    val id=row.getString("id");val cloud=id.startsWith("zepp-")
    val path=if(cloud) "/api/v1/health/zepp/workouts/"+encoded(id) else "/api/v1/mobile/activities/"+encoded(id)
    NativeData(vm,path) {detail->PageColumn {
        SectionTitle(row.text("name","运动详情"),row.text("startAt"))
        Metrics(row,listOf("distanceKm" to "距离 km","durationMinutes" to "运动分钟","averageHeartRate" to "平均心率 bpm"))
        Text("来源："+row.array("sources").joinToString("、"){if(it.text("source")=="zepp") "Zepp / Amazfit" else "手动 / 文件导入"})
        if(cloud) {
            Metrics(detail,listOf("caloriesKcal" to "能量 kcal","averagePaceSecondsPerKm" to "平均配速 秒/km","maxHeartRateBpm" to "最高心率 bpm","elevationGainMeters" to "累计爬升 m"))
            val streams=detail.optJSONObject("streams") ?: JSONObject()
            listOf(Triple("heartRate","bpm","心率 · bpm"),Triple("pace","secondsPerKilometer","配速 · 秒/km"),Triple("cadence","stepsPerMinute","步频 · 步/分"),Triple("altitude","meters","海拔 · m"),Triple("power","watts","功率 · W")).forEach {(stream,key,title)->NativeLineChart(title,streams.array(stream),key,if(stream=="heartRate") Color(0xFFCA5B78) else MaterialTheme.colorScheme.primary)}
            NativeRoute(streams.array("location").mapNotNull {p->if(p.has("latitude") && p.has("longitude")) RoutePoint(p.optDouble("latitude"),p.optDouble("longitude")) else null})
            detail.optJSONArray("notes")?.let {a->for(i in 0 until a.length()) Text(a.optString(i),style=MaterialTheme.typography.bodySmall)}
        } else {
            detail.optJSONObject("fields")?.let { fields->Metrics(fields,listOf("sportType" to "运动类型","startDateTime" to "开始时间","distance" to "距离（服务器单位）","elevation" to "爬升（服务器单位）","calories" to "能量 kcal","averageHeartRate" to "平均心率","maxHeartRate" to "最高心率"));if(fields.text("description","").isNotBlank()) Text(fields.text("description"))}
            ImportedStreams(vm,id)
            if(row.text("sportType") in listOf("Run","TrailRun","VirtualRun")) RunningAnalysis(vm,id)
            val capabilities=detail.optJSONObject("capabilities") ?: JSONObject()
            if(capabilities.optBoolean("edit")) OutlinedButton(onClick={vm.push(route("activityForm",id=id,payload=detail))},modifier=Modifier.fillMaxWidth()){Text("编辑运动")}
            ExportButton(vm,id)
            ActivityActions(vm,id,detail)
        }
        val duplicates=row.optJSONArray("duplicateCandidates") ?: JSONArray()
        if(duplicates.length()>0) {
            SectionTitle("核对重复来源","仅在确认是同一次运动后合并。")
            for(i in 0 until duplicates.length()) {val candidate=duplicates.optString(i);OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/health/workout-links/"+encoded(id),"PUT",JSONObject().put("version",0).put("activityId",candidate));vm.pop();vm.message="来源已关联"}},enabled=!vm.busy){Text("确认关联导入记录 ${candidate.takeLast(12)}")}}
        }
        row.optJSONObject("sourceLink")?.let {link->OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/health/workout-links/"+encoded(link.getString("id"))+"?version="+link.text("version"),"DELETE");vm.pop();vm.message="已拆分来源"}}){Text("解除来源合并")}}
        ActionRow("关联到训练课表"){vm.push(route("association","workout",id=id,payload=row))}
    }}
}
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
@Composable private fun RunningAnalysis(vm:AppModel,id:String) {val repo=vm.repository ?: return;var data by remember(id){mutableStateOf<JSONObject?>(null)};var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(id,vm.revision) {try {data=repo.get("/api/v1/running/activities/"+encoded(id)).data} catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message}}
    data?.let {d->val analysis=d.optJSONObject("analysis") ?: JSONObject();SectionTitle("跑步分析")
        analysis.optJSONObject("metrics")?.let {Metrics(it,listOf("weightedHeartRate" to "加权心率","paceSecondsPerKm" to "配速 秒/km","speedVariationPercent" to "速度变异 %","efficiencyChangePercent" to "效率变化 %"))}
        NativeLineChart("配速 · 秒/km",analysis.array("timeline"),"paceSecondsPerKm",MaterialTheme.colorScheme.primary)
        NativeLineChart("心率 · bpm",analysis.array("timeline"),"heartRate",Color(0xFFCA5B78))
        NativeLineChart("步频 · 步/分",analysis.array("timeline"),"cadenceSpm",MaterialTheme.colorScheme.secondary)
        SegmentTable("公里分段",d.array("splits"));SegmentTable("计圈",d.array("laps"))
        analysis.optJSONArray("notes")?.let {for(i in 0 until it.length()) Text(it.optString(i),style=MaterialTheme.typography.bodySmall)}
    };error?.let {ErrorCard(it)}
}
@Composable fun SegmentTable(title:String,rows:List<JSONObject>) {if(rows.isNotEmpty()) {SectionTitle(title);rows.forEachIndexed {index,row->InfoCard("${row.text("number",(index+1).toString())} · ${row.text("name","")}","${row.text("distanceKm")} km · ${row.text("movingSeconds")} 秒 · 配速 ${row.text("paceSecondsPerKm")} 秒/km · 心率 ${row.text("averageHeartRate")}")}}}
@Composable fun Metrics(data:JSONObject,fields:List<Pair<String,String>>) {Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){fields.forEach {(key,label)->Row(Modifier.fillMaxWidth()){Text(label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(data.text(key),Modifier.weight(1f))}}}}}
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
