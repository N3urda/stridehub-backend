package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BodyMetricDataTest {
    @Test fun pressureKeepsRepeatedMeasurementsAndOrdersByInstant() {
        val rows=listOf(
            JSONObject("""{"measuredAt":"2026-11-08T18:00:00+08:00","systolic":118,"diastolic":76} """),
            JSONObject("""{"measuredAt":"2026-11-08T09:00:00Z","systolic":121,"diastolic":79} """),
            JSONObject("""{"measuredAt":"invalid","systolic":200} """)
        )
        val values=BodyMetricData.pressure(rows)
        assertEquals(2,values.size)
        assertEquals(121,values[0].getInt("systolic"))
        assertEquals(118,values[1].getInt("systolic"))
        assertFalse(values[0].has("date"))
    }

    @Test fun compositionMissingMetricRemainsGapAndSameDayCapturesRemainDistinct() {
        fun row(at:String,value:String)=JSONObject("""{"capture":{"measuredAt":"$at"},"composition":{"metrics":$value}}""")
        val rows=listOf(
            row("2026-11-08T10:00:00+08:00","""[{"key":"BODY_FAT","value":22.3}]"""),
            row("2026-11-08T08:00:00+08:00","""[{"key":"BODY_FAT","value":0}]"""),
            row("2026-11-08T09:00:00+08:00","""[{"key":"WEIGHT","value":72.1}]""")
        )
        val points=BodyMetricData.captured(rows,"BODY_FAT")
        assertEquals(3,points.size)
        assertEquals(listOf(0.0,null,22.3),ChartData.values(points,"value"))
        assertEquals("2026-11-08T09:00:00+08:00",points[1].getString("at"))
    }

    @Test fun xiaomiUsesSeparateSourceAndNullsRatherThanZeppFallback() {
        val data=JSONObject("""{"daily":[{"date":"2026-11-08","activity":{"steps":9000}}],"miFitnessData":{"daily":[{"date":"2026-11-08","steps":null,"activityCalories":85},{"date":"2026-11-07","steps":7000,"stepCalories":100}]}}""")
        assertEquals(listOf(7000.0,null),ChartData.values(BodyMetricData.xiaomi(data,"steps"),"value"))
        assertEquals(listOf(null,85.0),ChartData.values(BodyMetricData.xiaomi(data,"activityCalories"),"value"))
    }

    @Test fun xiaomiSparseDaysLeaveAnUnknownCalendarSlot() {
        val data=JSONObject("""{"miFitnessData":{"daily":[{"date":"2026-11-01","steps":4000},{"date":"2026-11-03","steps":7000}]}}""")
        val rows=BodyMetricData.xiaomi(data,"steps")
        assertEquals(listOf("2026-11-01","2026-11-02","2026-11-03"),rows.map {it.getString("date")})
        assertEquals(listOf(4000.0,null,7000.0),ChartData.values(rows,"value"))
    }

    @Test fun availableCompositionKeysKeepServerUnitsAndUnknownMetrics() {
        val rows=listOf(JSONObject("""{"composition":{"metrics":[{"key":"MUSCLE","label":"肌肉率","unit":"%","value":36},{"key":"MUSCLE_MASS","label":"肌肉重量","unit":"kg","value":25},{"key":"CUSTOM","label":"CUSTOM","unit":"","value":4}]}}"""))
        assertEquals(listOf("%","kg",""),BodyMetricData.metrics(rows).map {it.getString("unit")})
    }
}
