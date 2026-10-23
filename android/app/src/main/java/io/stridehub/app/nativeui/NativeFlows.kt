@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable fun ActivityEditor(vm:AppModel,r:JSONObject) {
    val repo=vm.repository ?: return;val id=r.optString("id");val original=r.optJSONObject("payload")
    NativeData(vm,"/api/v1/mobile/activity-options") {options->
        val editable=original?.optJSONObject("capabilities")?.optJSONArray("editableFields")?.let {a->(0 until a.length()).map {a.optString(it)} }
        val fields=activityFields(options).filter {if(editable==null) it.key!="deviceName" else it.key in editable}
        val initial=original?.optJSONObject("fields") ?: Forms.initial(fields)
        val key="activity:"+id.ifBlank {r.optString("draftId","new")}
        var text by rememberSaveable(key){mutableStateOf(initial.toString())};var version by rememberSaveable {mutableStateOf(original?.text("version","").orEmpty())}
        var loaded by remember {mutableStateOf(false)};val busy=vm.busy;var error by remember {mutableStateOf<String?>(null)};var conflict by remember {mutableStateOf<JSONObject?>(null)};val scope=rememberCoroutineScope()
        LaunchedEffect(key,vm.busy){if(!vm.busy) repo.cache.draft(repo.account,key)?.let {text=it.payload};loaded=true}
        LaunchedEffect(text,loaded,vm.busy){if(loaded && !vm.busy){delay(300);repo.saveActivityDraft(key,text)}}

        val data=remember(text){JSONObject(text)}
        val ack=data.optJSONObject(ActivityMutationCoordinator.ACK)
        val effectiveId=ack?.optString("id") ?: id
        val effectiveVersion=ack?.optString("version") ?: version
        PageColumn {
            SectionTitle(if(effectiveId.isBlank()) "新增运动" else "编辑运动","单位：距离 ${options.optJSONObject("units")?.text("distance")} · 海拔 ${options.optJSONObject("units")?.text("elevation")}")
            if(effectiveId.isBlank() && !options.optBoolean("canCreate")) ErrorCard("当前服务器导入模式不支持手动新增运动。")
            FormFields(fields,data,{text=it.toString()},!busy)
            error?.let {ErrorCard(it)}
            conflict?.let {latest->Card {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("服务器最新版本，请核对");latest.optJSONObject("fields")?.let {SummaryFields(fields,it)};TextButton(onClick={version=latest.getString("version");data.optJSONObject(ActivityMutationCoordinator.ACK)?.put("version",version)?.let {text=JSONObject(text).put(ActivityMutationCoordinator.ACK,it).toString()};conflict=null;error=null}){Text("已核对，保留草稿并使用最新版本号")};TextButton(onClick={text=latest.getJSONObject("fields").toString();version=latest.getString("version");data.optJSONObject(ActivityMutationCoordinator.ACK)?.put("version",version)?.let {text=JSONObject(text).put(ActivityMutationCoordinator.ACK,it).toString()};conflict=null;error=null}){Text("使用服务器内容")}}}}
            Button(enabled=loaded && !busy && (effectiveId.isNotBlank() || options.optBoolean("canCreate")),onClick={error=null;val origin=vm.currentRoute;vm.action {activeRepo->
                try {
                    Forms.validate(fields,data)
                    val body=JSONObject();fields.forEach {field->if(data.has(field.key)) body.put(field.key,data.opt(field.key))}
                    val store=object:ActivityDraftStore {
                        override suspend fun load(key:String)=activeRepo.cache.draft(activeRepo.account,key)?.payload?.let(::JSONObject)
                        override suspend fun save(key:String,value:JSONObject) {activeRepo.saveDraft(key,value.toString())}
                    }
                    val saved=ActivityMutationCoordinator(store).save(key,effectiveId,effectiveVersion,body,
                        write={target,currentVersion,payload->
                            if(target.isNotBlank()) payload.put("version",currentVersion)
                            activeRepo.mutate("/api/v1/mobile/activities"+if(target.isBlank()) "" else "/"+encoded(target),if(target.isBlank()) "POST" else "PUT",payload)
                        },readBack={target->activeRepo.get("/api/v1/mobile/activities/"+encoded(target))})
                    vm.message="运动已保存";vm.refresh();if(vm.currentRoute==origin) vm.pop()
                } catch(e:Exception) {
                    if(e is kotlinx.coroutines.CancellationException) throw e
                    error=e.message ?: "保存失败"
                    if(e is ApiFailure && e.status==409 && effectiveId.isNotBlank()) conflict=runCatching {activeRepo.get("/api/v1/mobile/activities/"+encoded(effectiveId)).data}.getOrNull()
                    throw e
                }
            }},modifier=Modifier.fillMaxWidth()){Text(if(busy) "正在保存…" else "保存运动")}

        }
    }
}
private fun activityFields(options:JSONObject):List<FormField> {
    val sports=options.array("sportTypes").map {it.getString("value")};val workoutTypes=listOf("")+options.array("workoutTypes").map {it.getString("value")}
    return listOf(
        FormField("name","运动名称",required=true,default=""),FormField("description","运动描述",default=""),
        FormField("sportType","运动类型",FieldType.CHOICE,true,options=sports,default="Run",optionLabels=sports.associateWith {sportLabel(it)}),
        FormField("startDateTime","开始时间",default=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")),required=true),
        FormField("duration","运动时长",FieldType.OBJECT,true,children=listOf(FormField("hours","小时",FieldType.INTEGER,true,default=0),FormField("minutes","分钟",FieldType.INTEGER,true,default=30),FormField("seconds","秒",FieldType.INTEGER,true,default=0))),
        FormField("distance","距离",FieldType.NUMBER,true,default=0),FormField("elevation","爬升",FieldType.NUMBER,default=0),
        FormField("gearId","装备",FieldType.CHOICE,options=listOf("")+options.array("gears").map {it.getString("id")},default="",optionLabels=options.array("gears").associate {it.getString("id") to it.getString("name")}),
        FormField("calories","能量 kcal",FieldType.INTEGER),FormField("averageHeartRate","平均心率 bpm",FieldType.INTEGER),FormField("maxHeartRate","最高心率 bpm",FieldType.INTEGER),
        FormField("workoutType","训练类型",FieldType.CHOICE,options=workoutTypes,default=workoutTypes.firstOrNull()),
        FormField("deviceName","记录设备",default=""),FormField("isCommute","通勤",FieldType.BOOLEAN,required=true,default=false),FormField("isGroupActivity","团体运动",FieldType.BOOLEAN,required=true,default=false)
    )
}
@Composable fun ImportsScreen(vm:AppModel) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val repo=vm.repository ?: return
    var uploading by remember {mutableStateOf(false)};var progress by remember {mutableIntStateOf(0)};var uploadError by remember {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null && !uploading) {
        uploading=true;uploadError=null;progress=0;scope.launch {
            try {val filename=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->if(cursor.moveToFirst()) cursor.getString(0) else null} ?: "activity.gpx"
                repo.upload(uri,filename){progress=it};vm.refresh();vm.message="文件已提交，请查看最终处理结果。"
            } catch(e:Exception){uploadError=e.message ?: "上传失败，请重新选择文件重试"} finally {uploading=false}
        }
    }}
    NativeData(vm,"/api/v1/mobile/imports") {data->
        val ongoing=data.array("records").any {it.text("state") in listOf("queued","running")}
        LaunchedEffect(ongoing,vm.revision){if(ongoing){delay(4000);vm.refresh()}}
        PageColumn {
            SectionTitle("文件导入","支持服务器可解析的 FIT、TCX、GPX 等运动文件。")
            Button(onClick={picker.launch(arrayOf("*/*"))},enabled=!uploading,modifier=Modifier.fillMaxWidth()){Text(if(uploading) "上传中 ${if(progress>=0) "$progress%" else ""}" else "选择运动文件")}
            if(uploading) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
            uploadError?.let {ErrorCard(it)}
            Text("上传成功表示已排队。处理完成并出现运动记录后才算导入成功。",style=MaterialTheme.typography.bodySmall)
            if(data.array("records").isEmpty()) Text("暂无导入任务")
            data.array("records").forEach {receipt-> Card {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(receipt.text("filename"),style=MaterialTheme.typography.titleMedium)
                Text("${statusLabel(receipt.text("state"))} · 尝试 ${receipt.text("attempt")} · ${receipt.text("updatedAt")}")
                if(!receipt.isNull("error")) Text(receipt.text("error"),color=MaterialTheme.colorScheme.error)
                if(receipt.text("state") in listOf("failed","interrupted")) OutlinedButton(onClick={vm.action {it.mutate("/api/v1/mobile/imports/${encoded(receipt.getString("id"))}/retry","POST");vm.message="已重新排队"}},enabled=!vm.busy){Text("重试此文件")}
                if(receipt.text("activityId","").isNotBlank()) TextButton(onClick={vm.push(route("workout",payload=JSONObject().put("id",receipt.getString("activityId")).put("name",receipt.text("filename"))))}){Text("查看导入的运动")}
            }}}
        }
    }
}
@Composable fun SyncScreen(vm:AppModel) {
    var days by rememberSaveable {mutableStateOf("30")}
    NativeData(vm,"/api/v1/mobile/sync") {data->
        val ongoing=data.array("records").any {it.text("state") in listOf("queued","running")}
        LaunchedEffect(ongoing,vm.revision){if(ongoing){delay(4000);vm.refresh()}}
        PageColumn {
            SectionTitle("同步 Zepp","先让手表同步到 Zepp 手机 App，再提交云端同步任务。")
            OutlinedTextField(days,{days=it},label={Text("同步最近天数 (1–90)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            val sources=data.optJSONObject("sources") ?: JSONObject()
            listOf("daily" to "每日健康","sleep" to "睡眠","workouts" to "运动").forEach {(kind,label)->
                val source=sources.optJSONObject(kind) ?: JSONObject()
                Card {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(label,style=MaterialTheme.typography.titleMedium);Text("${statusLabel(source.text("state"))} · 最近成功 ${source.text("lastSuccessAt")}")
                    if(source.text("nextAction","").isNotBlank()) Text(source.text("nextAction"),style=MaterialTheme.typography.bodySmall)
                    if(source.text("lastError","").isNotBlank()) Text(source.text("lastError"),color=MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick={vm.action {repo->repo.mutate("/api/v1/mobile/sync","POST",JSONObject().put("kind",kind).put("days",days.toInt()));vm.message="同步任务已提交"}},enabled=!vm.busy && days.toIntOrNull() in 1..90){Text("同步$label")}
                }}
            }
            SectionTitle("任务记录","退出页面不取消已提交的服务器任务。")
            data.array("records").forEach {job->Card {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${mapOf("daily" to "每日健康","sleep" to "睡眠","workouts" to "运动")[job.text("kind")] ?: "同步"} · ${statusLabel(job.text("state"))}",style=MaterialTheme.typography.titleMedium)
                Text("最近 ${job.text("days")} 天 · ${job.text("updatedAt")}")
                job.optJSONObject("result")?.let {Metrics(it,listOf("imported" to "新增","updated" to "更新","unchanged" to "未变","skipped" to "跳过","recordCount" to "记录总数"))}
                if(job.text("error","").isNotBlank()) Text(job.text("error"),color=MaterialTheme.colorScheme.error)
                if(job.text("state") in listOf("failed","interrupted")) TextButton(onClick={vm.action {repo->repo.mutate("/api/v1/mobile/sync","POST",JSONObject().put("kind",job.getString("kind")).put("days",job.getInt("days")))}}){Text("重新同步此范围")}
            }}}
        }
    }
}
@Composable fun TodayScreen(vm:AppModel) {NativeData(vm,"/api/v1/training/today") {data->PageColumn {
    SectionTitle("今日安排",data.text("date")+" · "+data.text("timezone"))
    val check=data.optJSONObject("checkIn") ?: Forms.initial(Forms.get("check-ins").fields).put("date",data.text("date")).put("version",0).put("id",UUID.randomUUID().toString())
    ActionRow("填写今日身体状态"){vm.push(route("form","check-ins",payload=check))}
    if(data.array("sessions").isEmpty()) InfoCard("今天还没有已保存的训练","没有课表不等同于休息日。可在计划页新建训练。")
    data.array("sessions").forEach {RecordCard(vm,Forms.get("sessions"),it)}
    SectionTitle("待填写执行反馈")
    if(data.array("pendingFeedback").isEmpty()) Text("暂无待补充的训练反馈")
    data.array("pendingFeedback").forEach {RecordCard(vm,Forms.get("sessions"),it)}
    data.optJSONObject("reconciliation")?.array("items")?.forEach {row->row.optJSONObject("planned")?.let {planned->InfoCard(planned.text("title"),row.text("suggestion")){vm.push(route("comparison",id=planned.getString("id")))}}}
    data.optJSONArray("dataQuality")?.let {for(i in 0 until it.length()) Text(it.optString(i),style=MaterialTheme.typography.bodySmall)}
}}}
@Composable fun AssociationScreen(vm:AppModel,r:JSONObject) {
    if(r.optString("kind")=="sessions") {
        val planned=r.getJSONObject("payload");var selected by rememberSaveable {mutableStateOf<List<String>>(planned.optJSONArray("activityIds")?.let {a->(0 until a.length()).map {a.optString(it)}} ?: emptyList())}
        NativeData(vm,"/api/v1/training/activities") {data->PageColumn {
            SectionTitle("关联运动",planned.text("title"));Text("选择本次课表实际完成的运动。解除全部勾选即可解除关联。")
            data.array("items").forEach {activity->val id=activity.getString("id");Card {Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{checked->selected=if(checked) selected+id else selected-id});Column {Text(activity.text("name"));Text("${activity.text("startAt")} · ${activity.text("distanceKm")} km",style=MaterialTheme.typography.bodySmall)}}}}
            Button(onClick={vm.action {repo->repo.mutate("/api/v1/training/sessions/${encoded(planned.getString("id"))}/link","POST",JSONObject().put("version",planned.get("version")).put("activityIds",JSONArray(selected)));vm.pop();vm.pop();vm.message="运动关联已保存"}},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("保存关联 (${selected.size})")}
        }}
    } else NativeData(vm,"/api/v1/training/sessions") {data->PageColumn {
        SectionTitle("选择训练课表","把这次运动关联到已保存的训练。")
        data.array("items").forEach {session->InfoCard(session.text("title"),session.text("startAt")){vm.action {repo->val ids=session.optJSONArray("activityIds") ?: JSONArray();val target=r.getString("id");if((0 until ids.length()).none {ids.optString(it)==target}) ids.put(target);repo.mutate("/api/v1/training/sessions/${encoded(session.getString("id"))}/link","POST",JSONObject().put("version",session.get("version")).put("activityIds",ids));vm.pop();vm.message="已关联课表"}}}
        if(data.array("items").isEmpty()) Text("请先在计划页创建训练课表。")
    }}
}
@Composable fun ComparisonScreen(vm:AppModel,id:String) {NativeData(vm,"/api/v1/training/sessions/${encoded(id)}/comparison") {data->PageColumn {
    SectionTitle("计划与实际比较")
    data.optJSONObject("planned")?.let {planned->SectionTitle("训练计划",planned.text("title"));Metrics(planned,listOf("startAt" to "时间","distanceKm" to "距离 km","durationMinutes" to "时长 分钟","status" to "状态"));ActionRow("编辑课表与执行反馈"){vm.push(route("form","sessions",id=id,payload=planned))};ActionRow("选择关联运动"){vm.push(route("association","sessions",id=id,payload=planned))}}
    SectionTitle("实际运动");data.optJSONObject("actual")?.let {Metrics(it,listOf("distanceKm" to "距离 km","durationMinutes" to "时长 分钟","averageHeartRate" to "平均心率 bpm"))} ?: Text("尚无足够的关联实际记录")
    data.optJSONObject("delta")?.let {Metrics(it,listOf("distanceKm" to "距离差 km","durationMinutes" to "时长差 分钟"))}
    InfoCard("执行提示",data.text("suggestion"));data.optJSONArray("dataQuality")?.let {for(i in 0 until it.length()) Text(it.optString(i),style=MaterialTheme.typography.bodySmall)}
}}}
