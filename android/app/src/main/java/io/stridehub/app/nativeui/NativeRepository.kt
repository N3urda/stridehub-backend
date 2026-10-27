package io.stridehub.app.nativeui

import android.content.Context
import android.net.Uri
import androidx.room.*
import io.stridehub.app.NativeSession
import io.stridehub.app.NativeRevocations
import io.stridehub.app.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    @Transaction suspend fun putActivityDraft(row:SavedDraft) {
        val current=draft(row.account,row.path)?.payload?.let(::JSONObject)
        val incoming=JSONObject(row.payload)
        current?.optJSONObject(ActivityMutationCoordinator.ACK)?.let {incoming.put(ActivityMutationCoordinator.ACK,it)}
        putDraft(row.copy(payload=incoming.toString()))
    }
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
class ApiFailure(val status: Int, message: String, val authenticationRejected:Boolean=false): Exception(message)
data class NativeResult(val data: JSONObject, val savedAt: Long, val offline: Boolean = false, val warning: String? = null)
class NativeRepository(private val context: Context, val server: ServerAddress, val username: String, private val credential: String) {
    val account = AccountScope.key(server.baseUrl(), username)
    private val generation = NativeSession.generation()
    fun active(): Boolean = generation == NativeSession.generation() && (credential.isEmpty() || NativeSession.candidateToken(context) == credential)
    private fun assertActive() {
        if(generation != NativeSession.generation()) throw kotlinx.coroutines.CancellationException("账号连接已改变")
        if(credential.isNotEmpty() && NativeSession.candidateToken(context)!=credential) throw ApiFailure(401,"登录已失效，请重新连接。")
    }
    private val sessionUpgrade=PersistentSessionUpgrade()
    private var preparedRevocation:String?=null
    @Volatile private var serverRejected=false
    private suspend fun ensurePersistentSession() {
        if(credential.isEmpty())return // Temporary password login has no existing credential.
        assertActive()
        sessionUpgrade.ensure({assertActive();NativeSession.isPersistent(context)}) {
            assertActive()
            // Even a locally expired legacy token is sent only here until the server confirms it.
            val metadata=JSONObject(execute(request("/mobile/v1/session").patch(JSONObject().put("persistent",true).toString().toRequestBody("application/json".toMediaType())).build()))
            PersistentSessionUpgrade.requireMetadata(metadata,username)
            if(!NativeSession.promotePersistent(context,credential,generation))throw kotlinx.coroutines.CancellationException("账号连接已改变")
        }
    }
    /** Must succeed before the UI clears its credential or promises automatic revocation. */
    fun prepareLogout() {
        if(serverRejected && NativeSession.candidateToken(context).isEmpty())return
        assertActive()
        preparedRevocation=NativeRevocations.prepare(context,server.baseUrl(),username,credential)
    }
    fun cancel() { client.dispatcher.cancelAll() }
    suspend fun saveDraft(path:String,payload:String) { assertActive();cache.putDraft(SavedDraft(account,path,payload));if(!active()) cache.deleteDraft(account,path) }

    suspend fun saveActivityDraft(path:String,payload:String) {assertActive();cache.putActivityDraft(SavedDraft(account,path,payload));if(!active()) cache.deleteDraft(account,path)}
    val cache = NativeDatabase.get(context).cache()
    private val client = OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).cache(null).build()
    private suspend fun execute(request: Request, requireCurrent:Boolean=true, expectJson:Boolean=true): String = suspendCancellableCoroutine { continuation ->
        if(requireCurrent) assertActive()
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(NativeApiResponse.networkFailure(e)) }
            override fun onResponse(call: Call,response: Response) { try { response.use {
                val source=it.body?.source()
                val limit=16L*1024*1024
                source?.request(limit+1)
                if((source?.buffer?.size ?: 0)>limit) { if(continuation.isActive) continuation.resumeWithException(ApiFailure(413,"服务器响应过大，请缩小日期范围。"));return }
                val body=source?.readUtf8().orEmpty()
                if(!continuation.isActive) return
                try {if(requireCurrent) assertActive()} catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) continuation.cancel() else continuation.resumeWithException(e);return}
                val login = request.method == "POST" && request.url == server.route("/mobile/v1/sessions").toHttpUrl()
                continuation.resume(NativeApiResponse.read(it.code,it.header("Content-Type"),body,login,expectJson))
            }} catch(e:Exception) {
                if(requireCurrent && credential.isNotEmpty() && e is ApiFailure && e.authenticationRejected && active()) {
                    serverRejected=true
                    NativeSession.rejectIfCurrent(context,credential,generation)
                }
                if(continuation.isActive) continuation.resumeWithException(if(e is java.io.IOException) NativeApiResponse.networkFailure(e) else e)
            } }

        })
    }
    private fun request(path: String): Request.Builder {
        val relative=path.substringBefore('?')
        val url=server.route(relative)+path.substringAfter('?',"").let { if(it.isBlank()) "" else "?$it" }
        return Request.Builder().url(url).header("Authorization","Bearer $credential").header("Accept","application/json").header("Cache-Control","no-store")
    }
    suspend fun raw(path: String): ByteArray = withContext(Dispatchers.IO) {
        // Exports use the same cancellable request path; all current export formats are UTF-8.
        ensurePersistentSession()
        execute(request(path).get().build(),expectJson=false).toByteArray()
    }
    suspend fun get(path: String, refresh: Boolean = true): NativeResult {
        assertActive()
        val previous=cache.response(account,path)
        if(!refresh && previous!=null) return NativeResult(JSONObject(previous.payload),previous.savedAt)
        return try {
            ensurePersistentSession()
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
            assertActive()
            NativeResult(JSONObject(previous.payload),previous.savedAt,true,e.message ?: "无法连接服务器")
        }
    }
    suspend fun mutate(path: String, method: String, data: JSONObject=JSONObject()): JSONObject {
        ensurePersistentSession()
        val response=execute(request(path).method(method,if(method=="DELETE") null else data.toString().toRequestBody("application/json".toMediaType())).build())
        assertActive()
        cache.clearResponses(account)
        return if(response.isBlank()) JSONObject() else JSONObject(response)
    }
    suspend fun upload(uri: Uri, filename: String, progress: (Int)->Unit): JSONObject = withContext(Dispatchers.IO) {
        ensurePersistentSession()
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
    suspend fun revokeAfterLogout()=withContext(Dispatchers.IO) {
        if(serverRejected && preparedRevocation==null)return@withContext
        val id=preparedRevocation ?: throw IllegalStateException("尚未安全保存退出请求。")
        NativeRevocations.drain(context)
        if(NativeRevocations.isPending(context,id)) {
            NativeRevocations.schedulePending(context)
            throw java.io.IOException("本机已退出，联网后将自动撤销服务器会话。")
        }
    }
    companion object {
        suspend fun login(context: Context,address: String,user: String,password: String,http: Boolean,remember: Boolean): NativeRepository {
            val server=ServerAddress.parse(address,http)
            val temporary=NativeRepository(context,server,user,"")
            val result=temporary.mutate("/mobile/v1/sessions","POST",JSONObject().put("username",user).put("password",password).put("deviceName","Android ${android.os.Build.MODEL}").put("persistent",true))
            PersistentSessionUpgrade.requireMetadata(result,user)
            val token=result.getString("token");val identity=result.getString("username")
            require(Regex("^shmobile_[a-f0-9]{64}$").matches(token)) {"服务器返回的登录凭据无效，请重试。"}
            temporary.assertActive()
            // Always encrypt and retain the explicit persistent session, regardless of old UI flags.
            NativeSession.save(context,server.baseUrl(),identity,token,true,null,true)
            context.getSharedPreferences("native-ui",Context.MODE_PRIVATE).edit().putString("username",identity).putString("server",server.baseUrl()).putBoolean("nativeLoginUsed",true).apply()
            return NativeRepository(context,server,identity,token)
        }
        fun current(context: Context): NativeRepository? {
            val token=NativeSession.candidateToken(context).orEmpty().ifBlank { return null }
            val server=NativeSession.server(context) ?: return null
            return NativeRepository(context,server,NativeSession.username(context),token)
        }
    }
}
