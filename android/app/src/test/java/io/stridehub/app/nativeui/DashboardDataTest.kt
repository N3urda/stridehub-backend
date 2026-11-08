package io.stridehub.app.nativeui
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DashboardDataTest {
    @Test fun calendarRetainsMissingDaysAsUnknown() {
        val rows=DashboardData.calendar(listOf(JSONObject("{\"date\":\"2026-10-03\",\"value\":5}"),JSONObject("{\"date\":\"2026-10-01\",\"value\":0}")))
        assertEquals(listOf("2026-10-01","2026-10-02","2026-10-03"),rows.map {it.text("date")})
        assertEquals(listOf(0.0,null,5.0),ChartData.values(rows,"value"))
    }
    @Test fun stagesDoNotManufactureSleepOrIncludeAwakeTime() {
        assertEquals(listOf(60.0,240.0,60.0,0.0),DashboardData.stages(JSONObject("{\"totalMinutes\":360,\"deepMinutes\":60,\"lightMinutes\":240,\"remMinutes\":60,\"awakeMinutes\":90}")))
        assertEquals(listOf(null,null,null,360.0),DashboardData.stages(JSONObject("{\"totalMinutes\":360}")))
        assertEquals(listOf(null,null,null,null),DashboardData.stages(JSONObject()))
    }
    @Test fun impossibleStagesShowOnlyObservedTotal() {
        assertEquals(listOf(null,null,null,100.0),DashboardData.stages(JSONObject("{\"totalMinutes\":100,\"deepMinutes\":150}")))
    }
}
