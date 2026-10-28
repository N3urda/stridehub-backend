package io.stridehub.app.nativeui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One upgrade per repository, retried after transport failures; the server is idempotent. */
class PersistentSessionUpgrade {
    private val mutex=Mutex()
    suspend fun ensure(isPersistent:()->Boolean, upgrade:suspend ()->Unit) {
        if(isPersistent()) return
        mutex.withLock { if(!isPersistent()) upgrade() }
    }
    companion object {
        fun requireMetadata(data:JSONObject,username:String) {
            val capabilities=data.optJSONArray("capabilities")
            val names=capabilities?.let {a->(0 until a.length()).map {a.optString(it)}}.orEmpty()
            require(data.opt("username")==username && data.optInt("apiVersion")==1
                && "persistent-mobile-sessions" in names && "mobile-workflows" in names
                && data.opt("persistent")==true && data.has("expiresAt") && data.isNull("expiresAt")) {
                "服务器未确认持久登录，请升级 StrideHub 后端后重试。"
            }
        }
    }
}
