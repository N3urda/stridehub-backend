@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable fun RecordsScreen(vm:AppModel,kind:String) {
    if(kind=="activities") {WorkoutsScreen(vm,true);return}
    val form=Forms.get(kind)
    if(form.singleton) {NativeData(vm,form.path){ data -> PageColumn {SectionTitle(form.title);SummaryFields(form.fields,data);Button(onClick={vm.push(route("form",kind,payload=data))}){Text("编辑${form.title}")}}};return}
    NativeData(vm,form.path) {data->PageColumn {
        SectionTitle(form.title)
        Button(onClick={val initial=Forms.initial(form.fields).put("version",if(kind=="weight") data.opt("version") else 0).put("id",UUID.randomUUID().toString());vm.push(route("form",kind,payload=initial))}){Text("新增${form.title}")}
        val rows=data.array("records").ifEmpty {data.array("items")}.ifEmpty {data.array("plans")}
        if(kind=="weight") NativeLineChart("体重 · kg",rows.reversed(),"weightKg",MaterialTheme.colorScheme.primary)
        if(rows.isEmpty()) InfoCard("还没有记录","点击新增，保存你的第一条${form.title}。")
        rows.forEach { row -> RecordCard(vm,form,if(kind=="weight") JSONObject(row.toString()).put("version",data.opt("version")) else row) }
    }}
}
@Composable fun RecordCard(vm:AppModel,form:RecordForm,row:JSONObject) {
    val title=listOf("title","name","foods","item","date","measuredAt").map {row.text(it,"")}.firstOrNull {it.isNotBlank()} ?: form.title
    val subtitle=buildList {
        listOf("date","startAt","startDate","status","weightKg","waistCm").forEach { key -> row.text(key,"").takeIf {it.isNotBlank()}?.let {add(if(key=="status") statusLabel(it) else it)} }
    }.joinToString(" · ")
    InfoCard(title,subtitle.ifBlank {form.title}) {vm.push(route("form",form.kind,id=row.text("id",""),payload=row))}
}
@Composable fun RecordEditor(vm:AppModel,r:JSONObject) {
    val form=Forms.get(r.getString("kind"));val repo=vm.repository ?: return
    val initial=r.optJSONObject("payload") ?: Forms.initial(form.fields).put("version",0).put("id",UUID.randomUUID().toString())
    val identity=initial.text("id",r.optString("id")).ifBlank { if(form.kind=="weight") initial.text("date") else "default" }
    val draftKey="${form.kind}:$identity"
    var text by rememberSaveable(draftKey) {mutableStateOf(initial.toString())}
    var draftLoaded by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)};var confirmDelete by remember {mutableStateOf(false)};var latest by remember {mutableStateOf<JSONObject?>(null)}
    var restored by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    LaunchedEffect(draftKey) {repo.cache.draft(repo.account,draftKey)?.let {text=it.payload;restored=true};draftLoaded=true}
    LaunchedEffect(text,draftLoaded) {if(draftLoaded) {delay(300);repo.saveDraft(draftKey,text)}}
    val data=remember(text){JSONObject(text)}
    fun path(value:JSONObject=data):String = if(form.singleton) form.path else form.path+"/"+encoded(if(form.kind=="weight") value.getString("date") else identity)
    fun save() {if(busy) return;busy=true;error=null;scope.launch {
        try {
            Forms.validate(form.fields,data)
            val body=Forms.payload(form,data)
            if(form.kind=="weight") body.remove("date")
            val create=form.createPost && data.optInt("version",0)==0
            if(create) body.put("id",identity)
            val result=repo.mutate(if(create) form.path else path(),if(create) "POST" else "PUT",body)
            // Read back the acknowledged result before reporting a save.
            val readPath=if(form.kind=="weight") form.path else if(form.singleton) form.path else form.path+"/"+encoded(result.text("id",identity))
            val refreshed=runCatching {repo.get(readPath)}.isSuccess
            repo.cache.deleteDraft(repo.account,draftKey);vm.message=if(refreshed) "已保存${form.title}" else "服务器已确认保存，暂时无法刷新。";vm.refresh();vm.pop()
        } catch(e:Exception) {
            error=e.message ?: "保存失败，草稿已保留。"
            if(e is ApiFailure && (e.status==409 || e.status==428)) {
                latest=runCatching { val remote=repo.get(if(form.kind=="weight") form.path else path()).data
                    if(form.kind=="weight") remote.array("records").firstOrNull {it.text("date")==data.text("date")}?.let {JSONObject(it.toString()).put("version",remote.opt("version"))} ?: JSONObject().put("version",remote.opt("version")) else remote }.getOrNull()
            }
        } finally {busy=false}
    }}
    PageColumn {
        SectionTitle(if(data.optInt("version",0)==0 && !form.singleton) "新增${form.title}" else form.title,"修改需要在线确认；未保存内容会作为本账号草稿保留。")
        if(restored) Text("已恢复本机草稿",color=MaterialTheme.colorScheme.secondary)
        val relationKeys=when(form.kind) {"meals"->setOf("planId");"fuel-logs"->setOf("sessionId");"sessions"->setOf("raceId","activityIds");else->emptySet()}
        val fields=form.fields.filterNot {it.key in relationKeys}.map {field ->
            if(form.kind=="context" && field.key=="constraints") {
                val reports=data.array("reports")
                field.copy(children=field.children.map {child->if(child.key=="sourceReportId") child.copy(type=FieldType.CHOICE,options=listOf("")+reports.map {it.text("id")},optionLabels=reports.associate {it.text("id") to (it.text("title")+" · "+it.text("reportDate"))}) else child})
            } else field
        }
        FormFields(fields,data,{text=it.toString()},enabled=!busy)
        RelatedRecordPicker(vm,form.kind,data,{text=it.toString()},!busy)
        error?.let {ErrorCard(it)}
        latest?.let { remote -> Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("服务器最新内容，请核对后再保存")
            SummaryFields(form.fields,remote)
            TextButton(onClick={text=JSONObject(text).put("version",remote.opt("version")).toString();latest=null;error=null}) {Text("已核对，使用最新版本号保留我的草稿")}
            TextButton(onClick={text=remote.toString();latest=null;error=null}) {Text("使用服务器内容替换草稿")}
        }}}
        Button(onClick=::save,enabled=!busy,modifier=Modifier.fillMaxWidth()) {if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text("保存${form.title}")}
        if(form.kind=="sessions" && data.optInt("version")>0) {
            OutlinedButton(onClick={vm.push(route("association","sessions",id=identity,payload=data))},modifier=Modifier.fillMaxWidth()){Text("选择 / 解除关联运动")}
            OutlinedButton(onClick={vm.push(route("comparison",id=identity))},modifier=Modifier.fillMaxWidth()){Text("计划与实际比较")}
        }
        if(!form.singleton && (data.optInt("version")>0 || form.kind=="weight")) TextButton(onClick={confirmDelete=true},enabled=!busy){Text("删除此记录",color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除${form.title}？")},text={Text("此操作会删除服务器上的这条记录。相关计划引用可能阻止删除，请按提示先解除关联。")},confirmButton={TextButton(onClick={confirmDelete=false;busy=true;scope.launch {try {repo.mutate(path()+"?version="+encoded(data.text("version")),"DELETE");repo.cache.deleteDraft(repo.account,draftKey);vm.refresh();vm.pop();vm.message="已删除"} catch(e:Exception){error=e.message} finally {busy=false}}}){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}
@Composable fun FormFields(fields:List<FormField>,value:JSONObject,onChange:(JSONObject)->Unit,enabled:Boolean=true,depth:Int=0) {
    val context=LocalContext.current
    fun change(key:String,v:Any?) {onChange(JSONObject(value.toString()).put(key,v ?: JSONObject.NULL))}
    fields.filter {it.key!="id"}.forEach {f->
        val present=value.has(f.key) && !value.isNull(f.key)
        val text=if(present) value.opt(f.key)?.toString().orEmpty() else ""
        when(f.type) {
            FieldType.OBJECT -> Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(f.label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Switch(present,{change(f.key,if(it) Forms.initial(f.children) else null)},enabled=enabled)}
                if(present) FormFields(f.children,value.getJSONObject(f.key),{change(f.key,it)},enabled,depth+1)
            }}
            FieldType.ROWS -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(f.label,style=MaterialTheme.typography.titleMedium)
                val array=value.optJSONArray(f.key) ?: JSONArray()
                array.objects().forEachIndexed {index,row->key(row.text("id",index.toString())) {Card {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("${f.label} ${index+1}")
                    FormFields(f.children,row,{updated->val copied=JSONArray(array.toString());copied.put(index,updated);change(f.key,copied)},enabled,depth+1)
                    TextButton(onClick={val copied=JSONArray();for(i in 0 until array.length()) if(i!=index) copied.put(array.opt(i));change(f.key,copied)},enabled=enabled){Text("移除此条")}
                }}}}
                OutlinedButton(onClick={val copied=JSONArray(array.toString());copied.put(Forms.initial(f.children));change(f.key,copied)},enabled=enabled){Text("添加${f.label}")}
            }
            FieldType.STRINGS -> {
                val array=value.optJSONArray(f.key) ?: JSONArray();val string=(0 until array.length()).joinToString("\n"){array.optString(it)}
                OutlinedTextField(string,{v->change(f.key,JSONArray(v.lines().filter {it.isNotBlank()}))},label={Text(f.label)},supportingText={Text("每行一项")},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=2)
            }
            FieldType.CHOICE -> if(f.options.size>6) {
                var expanded by remember {mutableStateOf(false)}
                ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={if(enabled) expanded=it}) {
                    OutlinedTextField(f.optionLabels[text] ?: choiceLabel(text).ifBlank {"未指定"},{},readOnly=true,label={Text(f.label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),enabled=enabled)
                    ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {f.options.forEach {option->DropdownMenuItem(text={Text(f.optionLabels[option] ?: choiceLabel(option).ifBlank {"未指定"})},onClick={change(f.key,if(option.isEmpty() && f.default==null) null else option);expanded=false})}}
                }
            } else Column {Text(f.label,style=MaterialTheme.typography.labelLarge);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){f.options.forEach {option->FilterChip(selected=text==option,onClick={change(f.key,if(option.isEmpty() && f.default==null) null else option)},label={Text(f.optionLabels[option] ?: choiceLabel(option).ifBlank {"未指定"})},enabled=enabled)}}}
            FieldType.BOOLEAN -> Column {Text(f.label,style=MaterialTheme.typography.labelLarge);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {(if(f.required) listOf(true to "是",false to "否") else listOf(null to "未记录",true to "是",false to "否")).forEach {(v,label)->FilterChip(selected=if(v==null) !present else present && value.optBoolean(f.key)==v,onClick={change(f.key,v)},label={Text(label)},enabled=enabled)}}}
            FieldType.DATE -> {
                OutlinedTextField(text,{change(f.key,it.ifBlank {null})},label={Text(f.label+if(f.required) " *" else "")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={TextButton(onClick={val date=runCatching {LocalDate.parse(text)}.getOrDefault(LocalDate.now());DatePickerDialog(context,{_,y,m,d->change(f.key,LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()},enabled=enabled){Text("日期")}})
            }
            FieldType.TIME -> OutlinedTextField(text,{change(f.key,it.ifBlank {null})},label={Text(f.label)},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={TextButton(onClick={val time=runCatching {LocalTime.parse(text)}.getOrDefault(LocalTime.of(6,30));TimePickerDialog(context,{_,h,m->change(f.key,String.format("%02d:%02d",h,m))},time.hour,time.minute,true).show()},enabled=enabled){Text("时间")}})
            FieldType.DATETIME -> OutlinedTextField(text,{change(f.key,it)},label={Text(f.label+" *")},enabled=enabled,modifier=Modifier.fillMaxWidth(),supportingText={Text("含时区，例如 2026-10-23T06:30:00+08:00")},trailingIcon={TextButton(onClick={val date=runCatching {OffsetDateTime.parse(text)}.getOrDefault(OffsetDateTime.now());DatePickerDialog(context,{_,y,m,d->TimePickerDialog(context,{_,h,min->change(f.key,LocalDateTime.of(y,m+1,d,h,min).atZone(ZoneId.systemDefault()).toOffsetDateTime().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))},date.hour,date.minute,true).show()},date.year,date.monthValue-1,date.dayOfMonth).show()},enabled=enabled){Text("选择")}})
            FieldType.NUMBER, FieldType.INTEGER -> {
                var input by rememberSaveable(f.key) {mutableStateOf(text)}
                LaunchedEffect(text) {
                    val sameNumber=if(f.type==FieldType.INTEGER) input.toLongOrNull()!=null && input.toLongOrNull()==text.toLongOrNull() else input.toDoubleOrNull()!=null && input.toDoubleOrNull()==text.toDoubleOrNull()
                    if(input!=text && !sameNumber) input=text
                }
                OutlinedTextField(input,{raw->input=raw;change(f.key,if(raw.isBlank()) null else if(f.type==FieldType.INTEGER) raw.toLongOrNull() ?: raw else raw.toDoubleOrNull() ?: raw)},label={Text(f.label+if(f.required) " *" else "")},enabled=enabled,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            }
            else -> {
                // Free text is kept in the account draft until explicitly saved.
                OutlinedTextField(text,{input->change(f.key,when(f.type){FieldType.INTEGER->input.toLongOrNull() ?: input.ifBlank {null};FieldType.NUMBER->input.toDoubleOrNull() ?: input.ifBlank {null};else->input.ifBlank {if(f.default==null) null else ""}})},label={Text(f.label+if(f.required) " *" else "")},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=if(f.key=="notes" || f.key=="rationale" || f.key=="foods") 2 else 1,keyboardOptions=KeyboardOptions(keyboardType=if(f.type==FieldType.NUMBER || f.type==FieldType.INTEGER) KeyboardType.Decimal else KeyboardType.Text))
            }
        }
    }
}
@Composable fun SummaryFields(fields:List<FormField>,data:JSONObject,depth:Int=0) {fields.filter {it.key!="id"}.forEach {f->val v=data.opt(f.key);if(v!=null && v!=JSONObject.NULL && v.toString().isNotBlank()) when {
    v is JSONObject -> {Text(f.label,style=MaterialTheme.typography.titleSmall);SummaryFields(f.children,v,depth+1)}
    v is JSONArray -> {if(v.length()>0) {Text(f.label,style=MaterialTheme.typography.titleSmall);for(i in 0 until v.length()) {val row=v.optJSONObject(i);if(row!=null) SummaryFields(f.children,row,depth+1) else Text("• ${v.optString(i)}")}}}
    else -> Row(Modifier.fillMaxWidth().padding(vertical=3.dp)){Text(f.label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(if(v is Boolean) if(v) "是" else "否" else choiceLabel(v.toString()),Modifier.weight(1f))}
}}}
fun choiceLabel(value:String):String=mapOf("unknown" to "未知","user" to "本人记录","manual" to "手动","import" to "导入","ai" to "AI 建议","clinician" to "医生","breakfast" to "早餐","lunch" to "午餐","dinner" to "晚餐","snack" to "加餐","fuel" to "运动补给","other" to "其他","label" to "包装标签","database" to "营养数据库","estimate" to "估算","general" to "综合","weight" to "减重","sleep" to "睡眠","nutrition" to "饮食","recovery" to "恢复","left" to "左臂","right" to "右臂","seated" to "坐姿","standing" to "站姿","lying" to "卧姿","easy" to "轻松跑","long" to "长距离","tempo" to "节奏跑","interval" to "间歇","race" to "比赛","rest" to "休息","cold" to "冷","neutral" to "适中","warm" to "暖","comfortable" to "舒适","hot" to "热","good" to "良好","mild" to "轻度不适","poor" to "不适","normal" to "正常","high" to "偏高","low" to "偏低","abnormal" to "异常","inactive" to "已停用")[value] ?: statusLabel(value)

@Composable fun DateRangeControls(from:String,to:String,maxDays:Long?=null,onChange:(String,String)->Unit) {
    val context=LocalContext.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(true,false).forEach {start->
            OutlinedButton(onClick={val date=LocalDate.parse(if(start) from else to);DatePickerDialog(context,{_,y,m,d->
                val picked=LocalDate.of(y,m+1,d);var a=if(start) picked else LocalDate.parse(from);var b=if(start) LocalDate.parse(to) else picked
                if(a>b) {if(start) b=a else a=b}
                if(maxDays!=null && java.time.temporal.ChronoUnit.DAYS.between(a,b)>maxDays) {if(start) b=a.plusDays(maxDays) else a=b.minusDays(maxDays)}
                onChange(a.toString(),b.toString())
            },date.year,date.monthValue-1,date.dayOfMonth).show()},modifier=Modifier.weight(1f)) {Text((if(start) "起 " else "止 ")+(if(start) from else to))}
        }
    }
}

@Composable private fun RelatedRecordPicker(vm:AppModel,kind:String,data:JSONObject,onChange:(JSONObject)->Unit,enabled:Boolean) {
    val relation=when(kind) {
        "meals"->Triple("planId","关联当天的饮食计划","/api/v1/health/meal-plans")
        "sessions"->Triple("raceId","关联赛事","/api/v1/training/races")
        "fuel-logs"->Triple("sessionId","关联训练课表","/api/v1/training/sessions")
        else->return
    }
    val repo=vm.repository ?: return
    var records by remember(relation.third) {mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember {mutableStateOf<String?>(null)};var attempt by remember {mutableIntStateOf(0)}
    LaunchedEffect(relation.third,attempt) {try {val result=repo.get(relation.third).data;records=result.array("records").ifEmpty {result.array("items")};error=null} catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e;error=e.message}}
    val eligible=records.filter {kind!="meals" || it.text("date")==data.text("date")}
    val names=eligible.associate {row->row.text("id") to (row.text("title",row.text("name",row.text("date")))+" · "+row.text("startAt",row.text("date",statusLabel(row.text("status"))))) }
    FormFields(listOf(FormField(relation.first,relation.second,FieldType.CHOICE,options=listOf("")+eligible.map {it.text("id")},optionLabels=names)),data,onChange,enabled)
    error?.let {ErrorCard(it){attempt++}}
}
