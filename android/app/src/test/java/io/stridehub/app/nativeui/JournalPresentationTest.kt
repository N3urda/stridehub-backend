package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class JournalPresentationTest {
    @Test fun calendarUsesProfileTimezoneAndKeepsCancelledAndSkippedDistinct() {
        val sunday=JSONObject("""{"startAt":"2026-11-08T17:30:00Z","status":"completed"}""")
        val rows=listOf(sunday,JSONObject("""{"startAt":"2026-11-09T09:00:00+08:00","status":"skipped"}"""),
            JSONObject("""{"startAt":"2026-11-09T10:00:00+08:00","status":"cancelled"}"""),
            JSONObject("""{"startAt":"2026-11-16T09:00:00+08:00","status":"planned"}"""))
        val week=JournalPresentation.weekSessions(rows,LocalDate.parse("2026-11-09"),"Asia/Shanghai")
        assertEquals(3,week.size)
        assertEquals("2026-11-09",JournalPresentation.sessionDate(sunday,"Asia/Shanghai"))
        assertEquals(1 to 2,JournalPresentation.completion(week))
        val day=JournalPresentation.sessionsOn(week,"2026-11-09","Asia/Shanghai")
        assertEquals(3,day.size)
        assertEquals(1 to 2,JournalPresentation.completion(day))
        assertSame(sunday,day.first())
    }
    @Test fun nutritionSelectsTheRequestedDayEvenWhenNoMealsAndDefaultsToLatestRecordedDay() {
        val days=listOf(JSONObject("""{"date":"2026-11-01","mealCount":2}"""),JSONObject("""{"date":"2026-11-02","mealCount":1}"""),JSONObject("""{"date":"2026-11-03","mealCount":0,"planId":null}"""))
        assertEquals(1,JournalPresentation.nutritionSelection(days,""))
        assertEquals(2,JournalPresentation.nutritionSelection(days,"2026-11-03"))
        assertEquals(1,JournalPresentation.nutritionSelection(days,"2020-01-01"))
    }
    @Test fun partialNutritionIsNotPromotedToCompleteIntake() {
        val row=JSONObject("""{"actualLogged":{"energyKcal":null,"proteinGrams":0,"fatGrams":null},"knownSubtotal":{"energyKcal":450,"proteinGrams":0,"fatGrams":null}}""")
        assertEquals(listOf("energyKcal"),JournalPresentation.partialNutrients(row))
        assertTrue(row.getJSONObject("actualLogged").isNull("energyKcal"))
    }
    @Test fun workoutWeeksPreserveMissingTotalsAndConvertSecondsOnce() {
        val weeks=listOf(JSONObject("""{"date":"2026-11-02","movingSeconds":5400,"distanceKm":12.5}"""),JSONObject("""{"date":"2026-11-09","movingSeconds":null,"distanceKm":null}"""))
        val rows=JournalPresentation.weekChart(weeks,"minutes")
        assertEquals(90.0,rows.first().getDouble("value"),0.0)
        assertTrue(rows.last().isNull("value"))
        assertEquals(12.5,JournalPresentation.weekChart(weeks,"distanceKm").first().getDouble("value"),0.0)
    }
    @Test fun workoutWeekAxisLeavesAnUnrecordedWeekBlankInsteadOfCompressingIt() {
        val weeks=listOf(JSONObject("""{"date":"2026-11-16","distanceKm":8.0}"""),JSONObject("""{"date":"2026-11-02","distanceKm":12.5}"""))
        val rows=JournalPresentation.weekChart(weeks,"distanceKm")
        assertEquals(listOf("2026-11-02","2026-11-09","2026-11-16"),rows.map {it.getString("date")})
        assertTrue(rows[1].isNull("value"))
        assertEquals(8.0,rows[2].getDouble("value"),0.0)
    }
    @Test fun paceAndDurationHaveHumanUnitsAndUnknownIsNotZero() {
        assertEquals("4′50″/km",JournalPresentation.pace(290.0))
        assertEquals("1时30分",JournalPresentation.duration(5400.0))
        assertEquals("0秒",JournalPresentation.duration(0.0))
        assertEquals("—",JournalPresentation.pace(0.0))
        assertEquals("—",JournalPresentation.duration(null))
    }
}
