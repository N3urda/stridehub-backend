package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecordTrendsTest {
    @Test fun pressurePreservesSeparateReadingsAndMissingPulse() {
        val late = JSONObject("""{"measuredAt":"2026-11-08T20:00:00+08:00","systolic":122,"pulse":null}""")
        val early = JSONObject("""{"measuredAt":"2026-11-08T08:00:00+08:00","systolic":118}""")
        val rows = RecordTrends.ordered(listOf(late, early), true)
        assertEquals(2, rows.size)
        assertEquals(118, rows[0].getInt("systolic"))
        assertEquals(122, rows[1].getInt("systolic"))
        assertTrue(rows[1].isNull("pulse"))
        assertFalse(late.has("at"))
    }
    @Test fun detailTitleMatchesTaskNotBottomTab() {
        assertEquals("饮食与营养", nativeRouteTitle(JSONObject().put("type", "nutrition")))
        assertEquals("睡眠详情", nativeRouteTitle(JSONObject().put("type", "data").put("kind", "sleep")))
        assertEquals("编辑运动", nativeRouteTitle(JSONObject().put("type", "activityForm").put("id", "saved")))
    }
}
