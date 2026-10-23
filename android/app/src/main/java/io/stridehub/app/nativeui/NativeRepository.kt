package io.stridehub.app.nativeui

import android.content.Context
import android.net.Uri
import androidx.room.*
import io.stridehub.app.NativeSession
import io.stridehub.app.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Account identity is never derived from a token (tokens rotate). */
object AccountScope {
    fun key(server: String, username: String): String = MessageDigest.getInstance("SHA-256")
        .digest((server.trimEnd('/') + "\u0000" + username).toByteArray()).joinToString("") { "%02x".format(it) }
}
@Entity(tableName = "responses", primaryKeys = ["account", "path"])
data class CachedResponse(val account: String, val path: String, val payload: String, val savedAt: Long)
@Entity(tableName = "drafts", primaryKeys = ["account", "path"])
data class SavedDraft(val account: String, val path: String, val payload: String)
@Dao interface NativeCache {
    @Query("SELECT * FROM responses WHERE account=:account AND path=:path") suspend fun response(account: String, path: String): CachedResponse?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: CachedResponse)
    @Query("SELECT * FROM drafts WHERE account=:account AND path=:path") suspend fun draft(account: String, path: String): SavedDraft?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(row: SavedDraft)
    @Query("DELETE FROM drafts WHERE account=:account AND path=:path") suspend fun deleteDraft(account: String,path: String)
    @Query("DELETE FROM responses WHERE account=:account") suspend fun clearResponses(account: String)
    @Query("DELETE FROM drafts WHERE account=:account") suspend fun clearDrafts(account: String)
    @Transaction suspend fun clear(account: String) { clearResponses(account); clearDrafts(account) }
}
@Database(entities = [CachedResponse::class,SavedDraft::class], version = 1, exportSchema = false)
abstract class NativeDatabase: RoomDatabase() {
    abstract fun cache(): NativeCache
    companion object { @Volatile private var instance: NativeDatabase? = null
        fun get(context: Context): NativeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,NativeDatabase::class.java,"native-health-v1.db").build().also { instance=it }
        }
    }
}
class ApiFailure(val status: Int, message: String): Exception(message)
data class NativeResult(val data: JSONObject, val savedAt: Long, val offline: Boolean = false, val warning: String? = null)
class NativeRepository(private val context: Context, val server: ServerAddress, val username: String, private val credential: String) {
    val account = AccountScope.key(server.baseUrl(), username)
    private val generation = NativeSession.generation()
    fun active(): Boolean = generation == NativeSession.generation() && (credential.isEmpty() || NativeSession.token(context) == credential)
    private fun assertActive() {
        if(generation != NativeSession.generation()) throw kotlinx.coroutines.CancellationException("账号连接已改变")
        if(credential.isNotEmpty() && NativeSession.token(context)!=credential) throw ApiFailure(401,"登录已过期，请重新连接。")
    }
    fun cancel() { client.dispatcher.cancelAll() }
    suspend fun saveDraft(path:String,payload:String) { assertActive();cache.putDraft(SavedDraft(account,path,payload));if(!active()) cache.deleteDraft(account,path) }

    val cache = NativeDatabase.get(context).cache()
    private val client = OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).cache(null).build()
    private suspend fun execute(request: Request, requireCurrent:Boolean=true): String = suspendCancellableCoroutine { continuation ->
        if(requireCurrent) assertActive()
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) { try { response.use {
                val source=it.body?.source()
                val limit=16L*1024*1024
                source?.request(limit+1)
                if((source?.buffer?.size ?: 0)>limit) { if(continuation.isActive) continuation.resumeWithException(ApiFailure(413,"服务器响应过大，请缩小日期范围。"));return }
                val body=source?.readUtf8().orEmpty()
                if(!continuation.isActive) return
                try {if(requireCurrent) assertActive()} catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) continuation.cancel() else continuation.resumeWithException(e);return}
                if(!it.isSuccessful) {
                    val message=runCatching { val j=JSONObject(body); j.optJSONObject("error")?.optString("message") ?: j.optString("message") }.getOrNull().orEmpty()
                    continuation.resumeWithException(ApiFailure(it.code, when(it.code) {
                        401 -> "登录已过期，请重新连接。"
                        404 -> message.ifBlank { "服务器缺少此移动接口，请升级 StrideHub 后端。" }
                        409,428 -> message.ifBlank { "记录已改变。草稿已保留，请核对服务器最新内容后再保存。" }
                        429 -> "请求过于频繁，请稍后重试。"
                        else -> message.ifBlank { "服务器返回 ${it.code}，请重试。" }
                    }))
                } else continuation.resume(body)
            }} catch(e:Exception) {if(continuation.isActive) continuation.resumeWithException(e)} }
        })
    }
    private fun request(path: String): Request.Builder {
        val relative=path.substringBefore('?')
        val url=server.route(relative)+path.substringAfter('?',"").let { if(it.isBlank()) "" else "?$it" }
        return Request.Builder().url(url).header("Authorization","Bearer $credential").header("Accept","application/json").header("Cache-Control","no-store")
    }
    suspend fun raw(path: String): ByteArray = withContext(Dispatchers.IO) {
        // Exports use the same cancellable request path; all current export formats are UTF-8.
        execute(request(path).get().build()).toByteArray()
    }
    suspend fun get(path: String, refresh: Boolean = true): NativeResult {
        val previous=cache.response(account,path)
        if(!refresh && previous!=null) return NativeResult(JSONObject(previous.payload),previous.savedAt)
        return try {
            val body=execute(request(path).get().build())
            val data=JSONObject(body)
            val now=System.currentTimeMillis()
            assertActive()
            cache.put(CachedResponse(account,path,data.toString(),now))
            if(!active()) { cache.clearResponses(account);assertActive() }
            NativeResult(data,now)
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            if(e is ApiFailure && e.status<500 && e.status!=429) throw e
            if(previous==null) throw e
            NativeResult(JSONObject(previous.payload),previous.savedAt,true,e.message ?: "无法连接服务器")
        }
    }
    suspend fun mutate(path: String, method: String, data: JSONObject=JSONObject()): JSONObject {
        val response=execute(request(path).method(method,if(method=="DELETE") null else data.toString().toRequestBody("application/json".toMediaType())).build())
        assertActive()
        cache.clearResponses(account)
        return if(response.isBlank()) JSONObject() else JSONObject(response)
    }
    suspend fun upload(uri: Uri, filename: String, progress: (Int)->Unit): JSONObject = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val body=object: RequestBody() {
            override fun contentType()="application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                resolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "无法读取所选文件" }; val buffer=ByteArray(16384);var count=0L
                    val total=resolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: -1
                    while(true) { val size=stream.read(buffer); if(size<0) break; sink.write(buffer,0,size); count+=size
                        progress(if(total>0) ((count*100)/total).toInt() else -1) }
                }
            }
        }
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",filename,body).build()
        JSONObject(execute(request("/api/v1/mobile/imports").post(multipart).build()))
    }
    suspend fun forget() { cache.clear(account) }
    // This one explicitly scoped request revokes the captured old credential after local logout.
    suspend fun revokeAfterLogout() {execute(request("/mobile/v1/session").delete().build(),requireCurrent=false)}
    companion object {
        suspend fun login(context: Context,address: String,user: String,password: String,http: Boolean,remember: Boolean): NativeRepository {
            val server=ServerAddress.parse(address,http)
            val temporary=NativeRepository(context,server,user,"")
            val result=try {temporary.mutate("/mobile/v1/sessions","POST",JSONObject().put("username",user).put("password",password).put("deviceName","Android ${android.os.Build.MODEL}"))}
            catch(e:ApiFailure) {if(e.status==404) throw ApiFailure(404,"服务器尚未提供原生移动登录，请升级 StrideHub 后端。") else throw e}
            require(result.optInt("apiVersion")==1 && result.optJSONArray("capabilities")?.let { a -> (0 until a.length()).any { a.optString(it)=="mobile-workflows" } } ==true) { "服务器移动接口版本不兼容，请升级 StrideHub 后端。" }
            val token=result.getString("token");val identity=result.getString("username")
            temporary.assertActive()
            NativeSession.save(context,server.baseUrl(),identity,token,remember,result.getString("expiresAt"))
            context.getSharedPreferences("native-ui",Context.MODE_PRIVATE).edit().putString("username",identity).putString("server",server.baseUrl()).putBoolean("nativeLoginUsed",true).apply()
            return NativeRepository(context,server,identity,token)
        }
        fun current(context: Context): NativeRepository? {
            val token=NativeSession.token(context).orEmpty().ifBlank { return null }
            val server=NativeSession.server(context) ?: return null
            return NativeRepository(context,server,NativeSession.username(context),token)
        }
    }
}
