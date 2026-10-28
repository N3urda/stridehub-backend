@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import android.app.Application
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stridehub.app.NativeSession
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
    init { io.stridehub.app.NativeRevocations.schedulePending(app) }
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
        try { repo.prepareLogout() } catch(e:Exception) {
            message="无法安全保存退出请求，请重试。登录状态未改变。"
            return
        }
        NativeSession.clear(getApplication());repo.cancel();repository=null;authEpoch++;resetNavigation()
        viewModelScope.launch {
            repo.forget()
            val revoked=runCatching {repo.revokeAfterLogout()}.isSuccess
            message=if(revoked) "已退出登录" else "本机已退出，联网后将自动撤销服务器会话。"
        }
    }

}
@Composable fun StrideHubApp(vm:AppModel=viewModel(), openWeighing:()->Unit) {
    val context=LocalContext.current
    val preferences=context.getSharedPreferences("native-ui",Context.MODE_PRIVATE)
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme","system") ?: "system") }
    CompositionLocalProvider(LocalOpenWeighing provides openWeighing) {
    StrideHubTheme(theme) {
        val snackbar=remember { SnackbarHostState() }
        LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it);vm.message=null } }
        val repo=vm.repository
        Scaffold(snackbarHost={SnackbarHost(snackbar)},
            topBar={TopAppBar(title={Text(if(repo==null) "连接 StrideHub" else if(vm.currentRoute.isBlank() && vm.tab==0) "StrideHub" else listOf("总览","身体","运动","计划")[vm.tab],style=MaterialTheme.typography.titleLarge)},navigationIcon={ if(vm.currentRoute.isNotBlank()) IconButton(onClick=vm::pop,enabled=!vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } },actions={ if(repo!=null) { IconButton(onClick=vm::refresh) {Icon(Icons.Default.Refresh,"刷新")};IconButton(onClick={vm.push(route("settings"))}){Icon(Icons.Default.Settings,"设置与更多")}} })},
            bottomBar={if(repo!=null) StrideHubNavigationBar(vm.tab,{tab -> if(tab==WEIGHING_TAB) openWeighing() else vm.select(tab)},enabled=!vm.busy)}
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
}
@Composable private fun LoginScreen(vm:AppModel) {
    val context=LocalContext.current;val prefs=context.getSharedPreferences("native-ui",Context.MODE_PRIVATE)
    var server by rememberSaveable { mutableStateOf(prefs.getString("server",NativeSession.server(context)?.baseUrl() ?: "").orEmpty()) };var username by rememberSaveable { mutableStateOf(prefs.getString("username",NativeSession.username(context)).orEmpty()) }
    // Password is intentionally never saved through process recreation.
    var password by remember { mutableStateOf("") };var allowHttp by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)};var loading by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    var showPassword by remember {mutableStateOf(false)}
    val openWeighing=LocalOpenWeighing.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PageHeader("STRIDEHUB","把变化，记录下来","体重、运动与日常计划，留在自己的健康空间。")
        Card(onClick=openWeighing,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.MonitorWeight,null,Modifier.size(32.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("离线称重与本机历史",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text("无需登录，随时称重",style=MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Default.ChevronRight,null)
            }
        }
        if(NativeSession.server(context)!=null && !prefs.getBoolean("nativeLoginUsed",false)) InfoCard("全原生版本需要重新登录","服务器地址与账号已保留。输入密码建立移动会话后，即可继续使用全部原生页面。")
        SectionTitle("连接你的健康空间","登录后，查看已保存的身体与运动记录。")
        OutlinedTextField(server,{server=it},label={Text("服务器地址")},placeholder={Text("https://stridehub.example.com")},supportingText={Text("可直接粘贴 StrideHub 网页地址")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),enabled=!loading,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(username,{username=it},label={Text("用户名")},supportingText={Text("使用网页版的账号和密码")},enabled=!loading,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("密码")},visualTransformation=if(showPassword) VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={showPassword=!showPassword},enabled=!loading){Icon(if(showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,if(showPassword) "隐藏密码" else "显示密码")}},enabled=!loading,singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(allowHttp,{allowHttp=it},enabled=!loading);Text("明确允许此连接使用 HTTP（局域网）")}
        Text("登录后会一直保持登录，无需定期输入密码。主动退出、修改密码或撤销会话后需要重新登录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { ErrorCard(it) }
        Button(enabled=!loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),onClick={ loading=true;error=null;scope.launch {
            try { val repo=NativeRepository.login(context,server,username,password,allowHttp,true);password="";vm.loggedIn(repo) } catch(e:Exception) { error=e.message ?: "连接失败" } finally {loading=false}
        }},modifier=Modifier.fillMaxWidth()) {if(loading) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text("连接并登录")}
    }
}
@Composable fun NativeData(vm:AppModel,path:String,content:@Composable (JSONObject)->Unit) {
    val repo=vm.repository ?: return
    var screen by remember(path,repo.account) {mutableStateOf(NativeContentState())};var loading by remember(path){mutableStateOf(false)};var retry by remember {mutableIntStateOf(0)}
    var showConnectionDetails by remember(path) {mutableStateOf(false)}
    LaunchedEffect(path,repo.account,vm.revision,retry) {
        loading=true
        repo.cache.response(repo.account,path)?.let {screen=screen.preload(it)}
        try {screen=screen.refreshed(repo.get(path))} catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;screen=screen.failed(e)} finally {loading=false}
    }
    val result=screen.result;val error=screen.error ?: result?.warning

    PullToRefreshBox(isRefreshing=loading,onRefresh={retry++},modifier=Modifier.fillMaxSize()) { Column {
        if(loading && result==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { r -> if(r.offline || screen.pending) Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(if(screen.pending) Icons.Default.Sync else Icons.Default.CloudOff,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(if(screen.pending) "正在更新记录" else "离线缓存",style=MaterialTheme.typography.labelMedium)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(r.savedAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(error!=null) IconButton(onClick={showConnectionDetails=!showConnectionDetails}) {Icon(Icons.Default.Info,if(showConnectionDetails) "收起连接详情" else "查看连接详情",Modifier.size(20.dp))}
                IconButton(onClick={retry++}) {Icon(Icons.Default.Refresh,"重试连接",Modifier.size(20.dp))}
            }
        } }
        if(error!=null && (result==null || showConnectionDetails)) Column(Modifier.padding(16.dp)) {
            ErrorCard(error){retry++};if(error.contains("登录")) TextButton(onClick=vm::logout){Text("重新连接")}
        }
        if(result!=null) Box(Modifier.weight(1f)){content(result!!.data)}
        else if(loading) Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("正在读取记录",style=MaterialTheme.typography.titleLarge)
            Text("连接你的健康空间，整理最近保存的数据。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(3) {Surface(Modifier.fillMaxWidth().height(if(it==0) 124.dp else 78.dp),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh) {}}
        } else if(error==null) Text("暂无记录",Modifier.padding(24.dp))
    }}
}
@Composable private fun SettingsScreen(vm:AppModel,theme:String,onTheme:(String)->Unit) {PageColumn {
    SectionTitle("连接与账号",vm.repository?.username)
    Text(vm.repository?.server?.baseUrl().orEmpty(),style=MaterialTheme.typography.bodyMedium)
    ActionRow("Zepp 同步与任务状态",Icons.Default.Sync){vm.push(route("sync"))}
    ActionRow("文件导入与处理结果",Icons.Default.UploadFile){vm.push(route("imports"))}
    ActionRow("运动记录管理",Icons.Default.DirectionsRun){vm.push(route("records","activities"))}
    val context=LocalContext.current
    InfoCard("保持登录",if(NativeSession.isPersistent(context)) "已开启：无需定期登录。重启 App 或手机后仍可继续使用。" else "下次联网时会自动升级为长期登录，现有记录和称重队列保留。")
    ActionRow("称重、设备、权限与上传队列",Icons.Default.MonitorWeight,onClick=LocalOpenWeighing.current)
    SectionTitle("外观")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach {(key,label)->FilterChip(theme==key,{onTheme(key)},label={Text(label)})}}
    Text("缓存和草稿仅属于当前服务器与账号；退出会清除本账号业务缓存。本机称重历史保留。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=vm::logout,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("退出 / 切换服务器或账号")}
}}
fun statusLabel(value:String)=mapOf("deleted" to "已删除","queued" to "已排队","running" to "正在处理","syncing" to "正在同步","success" to "已完成","partial" to "部分完成","no_data" to "没有新数据","failed" to "失败","interrupted" to "已中断","skipped" to "已跳过","idle" to "待同步","not_configured" to "尚未配置","auth_required" to "需重新授权","planned" to "已计划","completed" to "已完成","cancelled" to "已取消","active" to "进行中","pending" to "待执行","draft" to "草稿","paused" to "已暂停")[value] ?: value
