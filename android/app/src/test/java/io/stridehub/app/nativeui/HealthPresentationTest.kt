package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthPresentationTest {
    @Test fun unknownAndInvalidMeasurementsNeverBecomeZero() {
        listOf(null,JSONObject.NULL,"72.4",Double.NaN,Double.POSITIVE_INFINITY).forEach {
            assertEquals("—",healthValue(it))
        }
        assertEquals("0",healthValue(0))
        assertEquals("72.4",healthValue(72.4,1))
        assertEquals("8,260",healthValue(8260))
    }

    @Test fun durationsAreReadableAndMissingSleepStaysUnknown() {
        assertEquals("7时35分",healthDuration(455))
        assertEquals("7小时",healthDuration(420))
        assertEquals("46分钟",healthDuration(46))
        assertEquals("0分钟",healthDuration(0))
        listOf(null,JSONObject.NULL,-1,Double.NaN).forEach {assertEquals("—",healthDuration(it))}
    }

    @Test fun datesOnlySayTodayForTheWorkspaceDay() {
        assertEquals("今天",healthDate("2026-10-28","2026-10-28"))
        assertEquals("10月27日",healthDate("2026-10-27","2026-10-28"))
        assertEquals("尚无记录",healthDate(null,"2026-10-28"))
        assertEquals("2025年10月28日",healthDate("2025-10-28","2026-10-28"))
    }

    @Test fun relativeDatesUseTheCurrentDayInTheServerTimezone() {
        val now=java.time.Instant.parse("2026-10-28T18:00:00Z")
        assertEquals("2026-10-29",healthReferenceDate("Asia/Shanghai",now))
        assertEquals("2026-10-28",healthReferenceDate("America/Los_Angeles",now))
        assertEquals("10月28日",healthDate("2026-10-28",healthReferenceDate("Asia/Shanghai",now)))
    }
}
