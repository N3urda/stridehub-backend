package io.stridehub.app.nativeui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** Screen state distinguishes an unverified cached preview from current server authority. */
data class NativeContentState(val result: NativeResult? = null, val error: String? = null, val pending: Boolean = false) {
    fun preload(snapshot: CachedResponse) = copy(result=NativeResult(JSONObject(snapshot.payload),snapshot.savedAt,offline=true),error=null,pending=true)
    fun refreshed(value:NativeResult) = NativeContentState(result=value)
    fun failed(failure:Exception):NativeContentState {
        val authoritative=failure is ApiFailure && failure.status<500 && failure.status!=429
        return copy(result=if(authoritative) null else result?.copy(offline=true),error=failure.message ?: "暂时无法加载",pending=false)
    }
}
interface ActivityDraftStore {
    suspend fun load(key:String):JSONObject?
    suspend fun save(key:String,value:JSONObject)
}
/** A receipt belongs to a unique editor draft, so recreation can only update the acknowledged ID. */
class ActivityMutationCoordinator(private val store:ActivityDraftStore) {
    suspend fun save(key:String,requestedId:String,version:String,fields:JSONObject,
        write:suspend (String,String,JSONObject)->JSONObject,
        readBack:suspend (String)->Unit):JSONObject {
        val receipt=store.load(key)?.optJSONObject(ACK)
        val id=receipt?.getString("id") ?: requestedId
        val currentVersion=if(id==requestedId && version.isNotBlank()) version else receipt?.getString("version") ?: version
        // The write and its durable receipt complete in the ViewModel-owned operation, even if
        // its originating screen disappears. Session guards still reject a changed account.
        val acknowledged=withContext(NonCancellable) {
            val saved=write(id,currentVersion,fields)
            val savedFields=saved.optJSONObject("fields") ?: fields
            store.save(key,JSONObject(savedFields.toString()).put(ACK,JSONObject().put("id",saved.getString("id")).put("version",saved.getString("version"))))
            saved
        }
        try {readBack(acknowledged.getString("id"))} catch(e:CancellationException) {throw e} catch(_:Exception) { /* Receipt remains authoritative when refresh is unavailable. */ }
        return acknowledged
    }
    companion object {const val ACK="_activityAcknowledged"}
}
object HealthContracts {
    fun zeppSummary(source:JSONObject):JSONObject = JSONObject(source.toString()).apply {
        val speed=(source.opt("averageSpeedMetersPerSecond") as? Number)?.toDouble()
        put("paceSecondsPerKm",if(speed!=null && speed.isFinite() && speed>0) kotlin.math.round(10000/speed)/10 else JSONObject.NULL)
    }
    fun upcoming(sessions:List<JSONObject>,now:Instant):JSONObject? = sessions.mapNotNull {session->
        runCatching {OffsetDateTime.parse(session.getString("startAt")).toInstant()}.getOrNull()?.let {it to session}
    }.filter {(instant,_)-> !instant.isBefore(now)}.minByOrNull {it.first}?.second
}
