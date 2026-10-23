@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import android.app.Application
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stridehub.app.NativeSession
import io.stridehub.app.scale.WeighingActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.text.DateFormat
import java.util.Date
import java.net.URLEncoder

fun JSONObject.text(key:String,fallback:String="—"):String = if(!has(key) || isNull(key)) fallback else opt(key)?.toString().orEmpty()
fun JSONObject.array(key:String):List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONArray.objects():List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun encoded(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
fun route(type:String,kind:String="",id:String="",payload:JSONObject?=null,title:String="",path:String=""):String=JSONObject().put("type",type).put("kind",kind).put("id",id).put("payload",payload).put("title",title).put("path",path).apply {if(type=="activityForm" && id.isBlank()) put("draftId",java.util.UUID.randomUUID().toString())}.toString()
class AppModel(app:Application, private val state:SavedStateHandle):AndroidViewModel(app) {
    var repository by mutableStateOf(NativeRepository.current(app));private set
    var tab by mutableIntStateOf(state["tab"] ?: 0);private set
    var revision by mutableIntStateOf(0);private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var authEpoch by mutableIntStateOf(0);private set
    var currentRoute by mutableStateOf(stack().lastOrNull().orEmpty());private set
    private fun stack():ArrayList<String> = state["stack$tab"] ?: arrayListOf()
    fun select(value:Int) { tab=value;state["tab"]=value;currentRoute=stack().lastOrNull().orEmpty() }
    fun push(value:String) { val values=stack();values.add(value);state["stack$tab"]=values;currentRoute=value }
    fun pop() { val values=stack();if(values.isNotEmpty()) values.removeAt(values.lastIndex);state["stack$tab"]=values;currentRoute=values.lastOrNull().orEmpty() }
    override fun onCleared() {repository?.cancel();super.onCleared()}
    fun refresh() { revision++ }
    fun loggedIn(repo:NativeRepository) { repository?.cancel();repository=repo;authEpoch++;resetNavigation() }
    fun resetNavigation() { (0..3).forEach { state["stack$it"]=arrayListOf<String>() };select(0) }
    fun action(block:suspend (NativeRepository)->Unit) { if(busy) return;val repo=repository ?: return;busy=true;viewModelScope.launch { try { block(repo);revision++ } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;message=e.message ?: "操作失败，请重试。" } finally { busy=false } } }
    fun logout() {
        val repo=repository ?: return
        NativeSession.clear(getApplication());repo.cancel();repository=null;authEpoch++;resetNavigation()
        viewModelScope.launch {
            repo.forget()
            val revoked=runCatching {repo.revokeAfterLogout()}.isSuccess
            message=if(revoked) "已退出登录" else "本机已退出。服务器暂不可达，远端凭据将在有效期内失效。"
        }
    }

}
@Composable fun StrideHubApp(vm:AppModel=viewModel()) {
    val context=LocalContext.current
    val preferences=context.getSharedPreferences("native-ui",Context.MODE_PRIVATE)
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme","system") ?: "system") }
    val dark=theme=="dark" || (theme=="system" && isSystemInDarkTheme())
    val activity=remember(context) {context.hostActivity()}
    SideEffect {
        activity?.window?.let {window->
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    val colors=if(dark) darkColorScheme(primary=Color(0xFFA9C7FF),secondary=Color(0xFFD1BCFA)) else lightColorScheme(primary=Color(0xFF2D5FC5),secondary=Color(0xFF7960AF),background=Color(0xFFF7F8FC),surface=Color(0xFFF7F8FC))
    MaterialTheme(colorScheme=colors) {
        val snackbar=remember { SnackbarHostState() }
        LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it);vm.message=null } }
        val repo=vm.repository
        Scaffold(snackbarHost={SnackbarHost(snackbar)},floatingActionButton={ FloatingActionButton(onClick={context.startActivity(Intent(context,WeighingActivity::class.java))}) { Icon(Icons.Default.MonitorWeight,"开始称重") } },
            topBar={TopAppBar(title={Text(if(repo==null) "连接 StrideHub" else listOf("总览","身体","运动","计划")[vm.tab])},navigationIcon={ if(vm.currentRoute.isNotBlank()) IconButton(onClick=vm::pop,enabled=!vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } },actions={ if(repo!=null) { IconButton(onClick=vm::refresh) {Icon(Icons.Default.Refresh,"刷新")};IconButton(onClick={vm.push(route("settings"))}){Icon(Icons.Default.Settings,"设置与更多")}} })},
            bottomBar={if(repo!=null) NavigationBar { val icons=listOf(Icons.Default.Dashboard,Icons.Default.Favorite,Icons.Default.DirectionsRun,Icons.Default.CalendarMonth);listOf("总览","身体","运动","计划").forEachIndexed { i,label -> NavigationBarItem(selected=vm.tab==i,onClick={vm.select(i)},enabled=!vm.busy,icon={Icon(icons[i],label)},label={Text(label)}) } }}
        ) { padding ->
            BackHandler(enabled=vm.busy || vm.currentRoute.isNotBlank()) {if(!vm.busy) vm.pop()}
            Box(Modifier.padding(padding).fillMaxSize()) {
                if(repo==null) LoginScreen(vm)
                else key(repo.account,vm.authEpoch) { val holder=rememberSaveableStateHolder();holder.SaveableStateProvider("${repo.account}:${vm.tab}:${vm.currentRoute}") {
                    if(vm.currentRoute.isBlank()) when(vm.tab) {0->Overview(vm);1->BodyScreen(vm);2->WorkoutsScreen(vm);else->PlansScreen(vm)}
                    else { val r=JSONObject(vm.currentRoute);when(r.optString("type")) {
                        "settings" -> SettingsScreen(vm,theme) { theme=it;preferences.edit().putString("theme",it).apply() }
                        "records" -> RecordsScreen(vm,r.getString("kind"))
                        "form" -> RecordEditor(vm,r)
                        "workout" -> WorkoutScreen(vm,r.getJSONObject("payload"))
                        "running" -> RunningOverviewScreen(vm,r.optJSONObject("payload"))
                        "data" -> HealthDetail(vm,r)
                        "today" -> TodayScreen(vm)
                        "nutrition" -> NutritionScreen(vm)
                        "imports" -> ImportsScreen(vm)
                        "sync" -> SyncScreen(vm)
                        "activityForm" -> ActivityEditor(vm,r)
                        "association" -> AssociationScreen(vm,r)
                        "comparison" -> ComparisonScreen(vm,r.getString("id"))
                    }}
                }}
                if(vm.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            }
        }
    }
}
@Composable private fun LoginScreen(vm:AppModel) {
    val context=LocalContext.current;val prefs=context.getSharedPreferences("native-ui",Context.MODE_PRIVATE)
    var server by rememberSaveable { mutableStateOf(prefs.getString("server",NativeSession.server(context)?.baseUrl() ?: "").orEmpty()) };var username by rememberSaveable { mutableStateOf(prefs.getString("username",NativeSession.username(context)).orEmpty()) }
    // Password is intentionally never saved through process recreation.
    var password by remember { mutableStateOf("") };var allowHttp by rememberSaveable {mutableStateOf(false)};var remember by rememberSaveable {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)};var loading by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom=80.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("你的身体与运动记录",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        if(NativeSession.server(context)!=null && !prefs.getBoolean("nativeLoginUsed",false)) InfoCard("全原生版本需要重新登录","服务器地址与账号已保留。输入密码建立移动会话后，即可继续使用全部原生页面。")
        Text("连接自己的服务器后，查看趋势、管理计划和记录日常。离线称重随时可用。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(server,{server=it},label={Text("服务器地址")},placeholder={Text("https://stridehub.example.com")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(username,{username=it},label={Text("用户名")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(allowHttp,{allowHttp=it});Text("明确允许此连接使用 HTTP（局域网）")}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(remember,{remember=it});Text("保持登录，最多七天")}
        error?.let { ErrorCard(it) }
        Button(enabled=!loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),onClick={ loading=true;error=null;scope.launch {
            try { val repo=NativeRepository.login(context,server,username,password,allowHttp,remember);password="";vm.loggedIn(repo) } catch(e:Exception) { error=e.message ?: "连接失败" } finally {loading=false}
        }},modifier=Modifier.fillMaxWidth()) {if(loading) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text("连接并登录")}
        OutlinedButton(onClick={context.startActivity(Intent(context,WeighingActivity::class.java))},modifier=Modifier.fillMaxWidth()){Text("离线称重与本机历史")}
    }
}
@Composable fun PageColumn(content:@Composable ColumnScope.()->Unit) {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp).padding(bottom=92.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)}
@Composable fun SectionTitle(title:String,subtitle:String?=null) { Column {Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}} }
@Composable fun ErrorCard(message:String,onRetry:(()->Unit)?=null) {Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp)){Text(message);onRetry?.let {TextButton(onClick=it){Text("重试")}}}}}
@Composable fun InfoCard(title:String,body:String,onClick:(()->Unit)?=null) {Card(modifier=Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable fun ActionRow(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector=Icons.Default.ChevronRight,onClick:()->Unit) { Surface(onClick=onClick,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Icon(icon,label)}} }
@Composable fun NativeData(vm:AppModel,path:String,content:@Composable (JSONObject)->Unit) {
    val repo=vm.repository ?: return
    var screen by remember(path,repo.account) {mutableStateOf(NativeContentState())};var loading by remember(path){mutableStateOf(false)};var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(path,repo.account,vm.revision,retry) {
        loading=true
        repo.cache.response(repo.account,path)?.let {screen=screen.preload(it)}
        try {screen=screen.refreshed(repo.get(path))} catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;screen=screen.failed(e)} finally {loading=false}
    }
    val result=screen.result;val error=screen.error

    PullToRefreshBox(isRefreshing=loading,onRefresh={retry++},modifier=Modifier.fillMaxSize()) { Column {
        if(loading && result==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { r -> if(r.offline || screen.pending) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {Text("${if(screen.pending) "缓存预览，正在核对" else "离线缓存"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(r.savedAt))} · 下拉重试",Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.labelMedium)} }
        error?.let { Column(Modifier.padding(16.dp)) {ErrorCard(it){retry++};if(it.contains("登录")) TextButton(onClick=vm::logout){Text("重新连接")}} }
        if(result!=null) Box(Modifier.weight(1f)){content(result!!.data)} else if(!loading && error==null) Text("暂无记录",Modifier.padding(24.dp))
    }}
}
@Composable private fun Overview(vm:AppModel) {NativeData(vm,"/api/v1/health/workspace") { data -> PageColumn {
    SectionTitle("最近的真实记录",data.text("today")+" · "+data.text("timezone"))
    val sleep=data.array("sleep").firstOrNull();val weight=data.optJSONObject("weight")?.array("records")?.firstOrNull();val daily=data.array("daily").firstOrNull()
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        MetricTile("体重",weight?.text("weightKg") ?: "—","kg · ${weight?.text("date") ?: "尚无记录"}",Modifier.weight(1f)){vm.select(1)}
        MetricTile("睡眠",sleep?.text("totalMinutes") ?: "—","分钟 · ${sleep?.text("date") ?: "尚无记录"}",Modifier.weight(1f)){vm.select(1)}
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        MetricTile("步数",daily?.optJSONObject("activity")?.text("steps") ?: "—",daily?.text("date") ?: "尚无记录",Modifier.weight(1f)){vm.select(1)}
        MetricTile("静息心率",daily?.optJSONObject("heartRate")?.text("restingBpm") ?: "—","bpm",Modifier.weight(1f)){vm.select(1)}
    }
    data.optJSONObject("weight")?.optJSONArray("trend")?.let {NativeLineChart("体重趋势 · kg",it.objects(),"weightKg",Color(0xFF376AC6))}
    ActionRow("今日安排与执行反馈",Icons.Default.Today){vm.push(route("today"))}
    SectionTitle("下一项训练")
    UpcomingSession(vm,data.text("today"))
    SectionTitle("近期运动")
    val workouts=data.optJSONObject("workouts")?.array("records").orEmpty();if(workouts.isEmpty()) Text("这段时间还没有运动记录")
    workouts.take(3).forEach {WorkoutCard(vm,it)}
    InfoCard("同步状态",data.optJSONObject("sync")?.let {"${statusLabel(it.text("state"))} · 最近成功 ${it.text("lastSuccessAt")}"} ?: "尚无同步信息"){vm.push(route("sync"))}
}}}
@Composable fun MetricTile(label:String,value:String,note:String,modifier:Modifier=Modifier,onClick:()->Unit) {Card(onClick=onClick,modifier=modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.65f))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,style=MaterialTheme.typography.labelLarge);Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(note,style=MaterialTheme.typography.labelSmall)}}}
@Composable private fun BodyScreen(vm:AppModel) {
    var from by rememberSaveable {mutableStateOf(LocalDate.now().minusDays(29).toString())}
    var to by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    NativeData(vm,"/api/v1/health/workspace?from=$from&to=$to") {data->PageColumn {
    SectionTitle("身体与恢复","${data.text("from")} 至 ${data.text("to")}")
    DateRangeControls(from,to,maxDays=365) {a,b->from=a;to=b}
    val context=LocalContext.current
    ActionRow("开始称重 / 本机历史",Icons.Default.MonitorWeight){ context.startActivity(Intent(context,WeighingActivity::class.java)) }
    data.optJSONObject("weight")?.optJSONArray("trend")?.let {NativeLineChart("体重 · kg",it.objects(),"weightKg",MaterialTheme.colorScheme.primary)}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("weight","measurements","blood-pressure","check-ins").forEach {kind-> AssistChip(onClick={vm.push(route("records",kind))},label={Text(Forms.get(kind).title)})}}
    ActionRow("健康背景与检查报告"){vm.push(route("records","context"))}
    SectionTitle("睡眠历史")
    val sleep=data.array("sleep");NativeLineChart("睡眠时长 · 分钟",sleep.reversed(),"totalMinutes",MaterialTheme.colorScheme.secondary)
    if(sleep.isEmpty()) InfoCard("暂无睡眠记录","在同步页获取已上传到 Zepp 的睡眠数据。")
    sleep.forEach { r->InfoCard(r.text("date"),"睡眠 ${r.text("totalMinutes")} 分钟 · 深睡 ${r.text("deepMinutes")} 分钟 · ${r.text("source","Zepp")}"){vm.push(route("data","sleep",payload=r,title="睡眠详情"))} }
    SectionTitle("每日健康指标")
    data.array("daily").forEach {r->InfoCard(r.text("date"),"步数 ${r.optJSONObject("activity")?.text("steps") ?: "—"} · 静息心率 ${r.optJSONObject("heartRate")?.text("restingBpm") ?: "—"}"){vm.push(route("data","day",id=r.text("date"),title="每日指标"))}}
}}}
@Composable private fun PlansScreen(vm:AppModel) {PageColumn {
    SectionTitle("计划与日常","保存的计划、执行状态与真实反馈")
    ActionRow("今日安排与训练反馈",Icons.Default.Today){vm.push(route("today"))}
    ActionRow("饮食与每日营养"){vm.push(route("nutrition"))}
    listOf("sessions","plans","meal-plans","meals","weight-goals","races","check-ins","fuel-logs").forEach {kind -> ActionRow(Forms.get(kind).title){vm.push(route("records",kind))}}
    ActionRow("训练偏好"){vm.push(route("records","profile"))}
}}
@Composable private fun SettingsScreen(vm:AppModel,theme:String,onTheme:(String)->Unit) {PageColumn {
    SectionTitle("连接与账号",vm.repository?.username)
    Text(vm.repository?.server?.baseUrl().orEmpty(),style=MaterialTheme.typography.bodyMedium)
    ActionRow("Zepp 同步与任务状态",Icons.Default.Sync){vm.push(route("sync"))}
    ActionRow("文件导入与处理结果",Icons.Default.UploadFile){vm.push(route("imports"))}
    ActionRow("运动记录管理",Icons.Default.DirectionsRun){vm.push(route("records","activities"))}
    val context=LocalContext.current
    ActionRow("称重、设备、权限与上传队列",Icons.Default.MonitorWeight){context.startActivity(Intent(context,WeighingActivity::class.java))}
    SectionTitle("外观")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach {(key,label)->FilterChip(theme==key,{onTheme(key)},label={Text(label)})}}
    Text("缓存和草稿仅属于当前服务器与账号；退出会清除本账号业务缓存。本机称重历史保留。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=vm::logout,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("退出 / 切换服务器或账号")}
}}
fun statusLabel(value:String)=mapOf("deleted" to "已删除","queued" to "已排队","running" to "正在处理","syncing" to "正在同步","success" to "已完成","partial" to "部分完成","no_data" to "没有新数据","failed" to "失败","interrupted" to "已中断","skipped" to "已跳过","idle" to "待同步","not_configured" to "尚未配置","auth_required" to "需重新授权","planned" to "已计划","completed" to "已完成","cancelled" to "已取消","active" to "进行中","pending" to "待执行","draft" to "草稿","paused" to "已暂停")[value] ?: value

private fun Context.hostActivity():Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> if(baseContext===this) null else baseContext.hostActivity()
    else -> null
}
