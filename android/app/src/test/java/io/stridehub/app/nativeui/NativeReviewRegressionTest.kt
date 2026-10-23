package io.stridehub.app.nativeui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class NativeReviewRegressionTest {
    private class DurableDrafts:ActivityDraftStore {
        val values=mutableMapOf<String,String>()
        override suspend fun load(key:String)=values[key]?.let(::JSONObject)
        override suspend fun save(key:String,value:JSONObject) {values[key]=value.toString()}
    }
    @Test fun acknowledgedCreateSurvivesDelayedReadbackAndScreenNavigation() = runTest {
        val store=DurableDrafts();val coordinator=ActivityMutationCoordinator(store)
        val screen=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val viewModel=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val readbackStarted=CompletableDeferred<Unit>();val releaseReadback=CompletableDeferred<Unit>()
        var creates=0
        val operation=viewModel.launch {
            coordinator.save("draft-unique-1","","",JSONObject("{\"name\":\"Lunch ride\"}"),
                write={id,_,_->assertEquals("",id);creates++;JSONObject("{\"id\":\"activity-123\",\"version\":\"v1\",\"fields\":{\"name\":\"Lunch ride\"}}")},
                readBack={readbackStarted.complete(Unit);releaseReadback.await()})
        }
        runCurrent();readbackStarted.await()
        assertEquals("activity-123",store.load("draft-unique-1")!!.getJSONObject(ActivityMutationCoordinator.ACK).getString("id"))
        screen.cancel() // Leaving/recreating a composition cannot cancel its ViewModel-owned write.
        assertTrue(operation.isActive)
        releaseReadback.complete(Unit);runCurrent();operation.join()
        assertEquals(1,creates);viewModel.cancel()
    }
    @Test fun recreationAfterAcknowledgementCannotReplayPostEvenIfReadbackCancelled() = runTest {
        val store=DurableDrafts();var posts=0;var puts=0
        val readback=CompletableDeferred<Unit>()
        val first=launch {
            ActivityMutationCoordinator(store).save("draft-unique-2","","",JSONObject("{\"name\":\"Ride\"}"),
                write={_,_,_->posts++;JSONObject("{\"id\":\"same-activity\",\"version\":\"v7\",\"fields\":{\"name\":\"Ride\"}}")},
                readBack={readback.complete(Unit);awaitCancellation()})
        }
        runCurrent();readback.await();first.cancelAndJoin()
        // Reconstructed editor starts with the old blank route ID but reads the durable receipt.
        ActivityMutationCoordinator(store).save("draft-unique-2","","",JSONObject("{\"name\":\"Ride edited\"}"),
            write={id,version,_->assertEquals("same-activity",id);assertEquals("v7",version);puts++;JSONObject("{\"id\":\"same-activity\",\"version\":\"v8\"}")},readBack={})
        assertEquals(1,posts);assertEquals(1,puts)
    }
    @Test fun authoritative401403404RemoveCachedActionsWhileNetworkFailureKeepsTimestamp() {
        val snapshot=CachedResponse("account","/detail","{\"id\":\"a\"}",123456)
        val pending=NativeContentState().preload(snapshot)
        assertTrue(pending.pending);assertTrue(pending.result!!.offline);assertEquals(123456L,pending.result!!.savedAt)
        listOf(401,403,404,422).forEach {code->val rejected=pending.failed(ApiFailure(code,"rejected"));assertNull("$code must remove actionable cached detail",rejected.result);assertFalse(rejected.pending)}
        val offline=pending.failed(java.io.IOException("network lost"))
        assertTrue(offline.result!!.offline);assertEquals(123456L,offline.result!!.savedAt)
    }
    @Test fun sparseMinuteHeartRateNeverJoinsAcrossFourHoursWithoutSamples() {
        val rows=listOf("2026-10-23T08:00:00+08:00","2026-10-23T08:01:00+08:00","2026-10-23T12:00:00+08:00").map {JSONObject().put("at",it).put("bpm",72)}
        assertFalse(ChartData.breakBefore(rows,1,60.0));assertTrue(ChartData.breakBefore(rows,2,60.0))
        assertTrue(ChartData.breakBefore(rows,1,null)) // Unknown cadence does not imply continuous coverage.
    }
    @Test fun moreThanThirtySparseSamplesRemainVisibleAsStandaloneMeasuredPoints() {
        val start=OffsetDateTime.parse("2026-10-23T08:00:00+08:00")
        val rows=(0 until 45).map {index->JSONObject().put("at",start.plusSeconds(index*120L).toString()).put("bpm",70+index%10)}
        for(cadence in listOf(null,1.0,60.0)) {
            val drawnSegments=ChartData.plotSegments(rows,"bpm",cadence)
            assertEquals("Every measured sample gets a visible standalone marker",45,drawnSegments.size)
            assertTrue(drawnSegments.all {it.size==1})
            assertEquals((0 until 45).toList(),drawnSegments.flatten())
        }
        val withMissing=rows.toMutableList().apply {set(20,JSONObject().put("at",start.plusSeconds(2400).toString()).put("bpm",JSONObject.NULL))}
        assertEquals(44,ChartData.plotSegments(withMissing,"bpm",null).flatten().size)
    }
    @Test fun actualZeppParserSummaryKeysProducePaceAndAscentWithoutInventingUnknowns() {
        // Matches ZeppWorkoutParser.summary output: distance/duration yield speed, and ascentMeters.
        val parserOutput=JSONObject(requireNotNull(javaClass.getResource("/zepp-workout-summary-native.json")).readText())
        val shown=HealthContracts.zeppSummary(parserOutput)
        assertEquals(400.0,shown.getDouble("paceSecondsPerKm"),0.0);assertEquals(123,shown.getInt("ascentMeters"))
        assertTrue(HealthContracts.zeppSummary(JSONObject("{\"averageSpeedMetersPerSecond\":null,\"ascentMeters\":null}")).isNull("paceSecondsPerKm"))
        assertTrue(HealthContracts.zeppSummary(JSONObject("{\"averageSpeedMetersPerSecond\":0}")).isNull("paceSecondsPerKm"))
    }
    @Test fun upcomingSessionUsesInstantsAcrossOffsetsAndPhoneTimezoneChanges() {
        val sessions=listOf(
            JSONObject("{\"id\":\"past\",\"startAt\":\"2026-10-23T10:30:00+09:00\"}"),
            JSONObject("{\"id\":\"later\",\"startAt\":\"2026-10-23T12:00:00+08:00\"}"),
            JSONObject("{\"id\":\"next\",\"startAt\":\"2026-10-23T03:00:00Z\"}")
        )
        val now=OffsetDateTime.parse("2026-10-23T10:00:00+08:00").toInstant()
        assertEquals("next",HealthContracts.upcoming(sessions,now)!!.getString("id"))
        val changedPhoneOffset=OffsetDateTime.parse("2026-10-22T19:00:00-07:00").toInstant()
        assertEquals(now,changedPhoneOffset);assertEquals("next",HealthContracts.upcoming(sessions,changedPhoneOffset)!!.getString("id"))
    }
}
