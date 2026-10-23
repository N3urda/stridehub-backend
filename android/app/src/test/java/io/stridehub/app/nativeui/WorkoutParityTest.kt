package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkoutParityTest {
    private fun fixture(name:String)=JSONObject(requireNotNull(javaClass.getResource("/$name.json")).readText())
    @Test fun associationUsesSupportedCurrentSportIncludingCloudRuns() {
        val cloud=fixture("zepp-workout-summary-native")
        assertTrue(WorkoutPresentation.canAssociate(WorkoutPresentation.zepp(cloud,"zepp-eligible")))
        listOf(7,8).forEach {sport->assertTrue(WorkoutPresentation.canAssociate(WorkoutPresentation.zepp(JSONObject(cloud.toString()).put("sportType",sport),"zepp-run")))}
        listOf(6,9,10,49).forEach {sport->assertFalse(WorkoutPresentation.canAssociate(WorkoutPresentation.zepp(JSONObject(cloud.toString()).put("sportType",sport),"zepp-ineligible")))}
        listOf("Walk","Ride","Other").forEach {sport->assertFalse(WorkoutPresentation.canAssociate(JSONObject().put("sportType",sport)))}
    }
    @Test fun confirmedSourcesRemainNavigableWithoutChangingCanonicalTrainingIdentity() {
        val merged=JSONObject("{\"id\":\"import-1\",\"sources\":[{\"source\":\"import\",\"id\":\"import-1\"},{\"source\":\"zepp\",\"id\":\"zepp-cloud-1\"}],\"aliases\":[\"import-1\",\"zepp-cloud-1\"],\"sourceLink\":{\"id\":\"zepp-cloud-1\",\"version\":1}}")
        assertEquals(setOf("import-1","zepp-cloud-1"),WorkoutPresentation.sources(merged).map {it.getString("id")}.toSet())
        val cloudRoute=WorkoutPresentation.sourceRoute(merged,"zepp-cloud-1")
        assertEquals("import-1",cloudRoute.getString("canonicalId"));assertEquals("zepp-cloud-1",cloudRoute.getString("id"))
        val fileRoute=WorkoutPresentation.sourceRoute(cloudRoute,"import-1")
        assertEquals("import-1",fileRoute.getString("canonicalId"));assertEquals(2,WorkoutPresentation.sources(fileRoute).size)
        assertEquals(1,fileRoute.getJSONObject("sourceLink").getInt("version"))
    }
    @Test fun freshEditedActivityOwnsHeadingUnitsSportAndReceiptSummary() {
        val fresh=JSONObject("{\"id\":\"receipt-id\",\"name\":\"Edited ride\",\"sportType\":\"Ride\",\"startDateLocal\":\"2026-10-23T14:25:00\",\"distanceInMeter\":12500,\"movingTimeInSeconds\":2700,\"averageHeartRate\":132}")
        val summary=WorkoutPresentation.imported(fresh)
        assertEquals("Edited ride",summary.getString("name"));assertEquals(12.5,summary.getDouble("distanceKm"),0.0)
        assertEquals(45.0,summary.getDouble("durationMinutes"),0.0);assertEquals(132.0,summary.getDouble("averageHeartRate"),0.0)
        assertEquals("2026-10-23T14:25:00",summary.getString("startAt"));assertFalse(WorkoutPresentation.canAssociate(summary))
        // Neither the old Run route nor an ID-only receipt is needed to populate or gate current detail.
        assertEquals("receipt-id",summary.getString("id"))
        val missing=WorkoutPresentation.imported(JSONObject("{\"id\":\"missing\",\"name\":\"No metrics\",\"sportType\":\"Run\"}"))
        assertTrue(missing.isNull("distanceKm"));assertTrue(missing.isNull("durationMinutes"));assertTrue(missing.isNull("averageHeartRate"))
    }
    @Test fun realRunningOverviewRetainsTrendsCalendarBestsAndNavigableActivities() {
        val view=WorkoutPresentation.overview(fixture("native-running-overview-populated"))
        assertEquals(1,view.summary.getInt("count"));assertTrue(view.weekly.isNotEmpty());assertTrue(view.monthly.isNotEmpty())
        assertTrue(view.daily.any {it.getDouble("distanceKm")==6.0});assertEquals("run-native-fixture",view.bestEfforts.single().getString("activityId"))
        assertEquals("run-native-fixture",view.activities.array("items").single().getString("id"))
        val missing=WorkoutPresentation.overview(fixture("native-running-overview-missing"))
        assertEquals(0,missing.summary.getInt("count"));assertTrue(missing.bestEfforts.isEmpty());assertTrue(missing.activities.array("items").isEmpty())
        assertTrue(missing.weekly.all {it.isNull("paceSecondsPerKm") && it.isNull("averageHeartRate")})
    }
    @Test fun actualServerRunningAnalyticsRetainCoverageGapsPausesAndUnknowns() {
        val populated=WorkoutPresentation.analysisSections(fixture("native-running-detail-populated"))
        assertTrue(populated.scatter.isNotEmpty());assertTrue(populated.zones.isNotEmpty());assertTrue(populated.distribution.isNotEmpty())
        assertEquals(80.0,populated.coverage.getDouble("gapSeconds"),0.0);assertEquals(10.0,populated.coverage.getDouble("pausedSeconds"),0.0)
        assertEquals(290.0,populated.splitSummary.getDouble("fastestFullKmPace"),0.0)
        assertEquals(295.0,populated.splits.first().getDouble("gapPaceSecondsPerKm"),0.0)
        val missing=WorkoutPresentation.analysisSections(fixture("native-running-detail-missing"))
        assertTrue(missing.scatter.isEmpty());assertTrue(missing.zones.isEmpty());assertTrue(missing.distribution.isEmpty())
        assertTrue(missing.splitSummary.isNull("fastestFullKmPace"));assertTrue(missing.analysis.getJSONObject("metrics").isNull("weightedHeartRate"))
    }
}
