package io.stridehub.app.nativeui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersistentSessionUpgradeTest {
    private fun metadata()=JSONObject("""{"username":"alice","apiVersion":1,"capabilities":["mobile-workflows","persistent-mobile-sessions"],"persistent":true,"expiresAt":null}""")

    @Test fun persistentMetadataMustBeExplicitAndComplete() {
        PersistentSessionUpgrade.requireMetadata(metadata(),"alice")
        val invalid=listOf(metadata().apply {remove("persistent")},metadata().put("persistent",false),metadata().put("persistent","true"),metadata().apply {remove("expiresAt")},metadata().put("expiresAt","2099-01-01T00:00:00Z"),metadata().put("username","bob"),metadata().put("capabilities",org.json.JSONArray("[\"mobile-workflows\"]")))
        invalid.forEach {value->assertThrows(IllegalArgumentException::class.java) {PersistentSessionUpgrade.requireMetadata(value,"alice")}}
    }
    @Test fun concurrentFirstRequestsUpgradeOnceAndAlreadyPersistentRequestsSkipNetwork()=runTest {
        val gate=PersistentSessionUpgrade()
        var persistent=false;var calls=0
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val requests=(1..8).map {async {gate.ensure({persistent}) {calls++;started.complete(Unit);finish.await();persistent=true}}}
        started.await();assertEquals(1,calls);finish.complete(Unit);requests.awaitAll()
        gate.ensure({persistent}) {calls++}
        assertEquals(1,calls)
    }
    @Test fun networkFailureDoesNotLatchSuccessAndLaterRequestCanRecover()=runTest {
        val gate=PersistentSessionUpgrade();var persistent=false;var calls=0
        try {gate.ensure({persistent}) {calls++;throw IOException("offline")};fail("expected offline")}
        catch(_:IOException) {assertFalse(persistent)}
        gate.ensure({persistent}) {calls++;persistent=true}
        assertEquals(2,calls);assertTrue(persistent)
    }
    @Test fun onlyStructuredServer401IsAnAuthenticationRejection() {
        val rejected=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(401,"application/json","{\"error\":\"invalid_token\"}")}
        val proxy=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(401,"text/html","<html>proxy login</html>")}
        assertTrue(rejected.authenticationRejected)
        assertFalse(proxy.authenticationRejected)
        val gateway=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(401,"application/json","{\"message\":\"Gateway login required\"}")}
        assertFalse(gateway.authenticationRejected)
    }
}
