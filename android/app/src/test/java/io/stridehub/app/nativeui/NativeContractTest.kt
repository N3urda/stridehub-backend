package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeContractTest {
    @Test fun accountCacheScopeSeparatesServerInstallationAndIdentity() {
        val first=AccountScope.key("https://health.example.com/stridehub/","alice")
        assertEquals(first,AccountScope.key("https://health.example.com/stridehub","alice"))
        assertNotEquals(first,AccountScope.key("https://health.example.com/other","alice"))
        assertNotEquals(first,AccountScope.key("https://health.example.com/stridehub","bob"))
        assertNotEquals(first,AccountScope.key("http://health.example.com/stridehub","alice"))
        assertFalse(first.contains("alice"))
    }
    @Test fun missingHealthValuesStayMissingIncludingInvalidNumbers() {
        val rows=listOf(JSONObject("{\"weightKg\":72.4}"),JSONObject("{\"weightKg\":null}"),JSONObject(),JSONObject("{\"weightKg\":0}"),JSONObject("{\"weightKg\":\"bad\"}"))
        assertEquals(listOf(72.4,null,null,0.0,null),ChartData.values(rows,"weightKg"))
    }
    @Test fun chartDatesPreserveActualDayGaps() {
        val first=ChartData.x(JSONObject("{\"date\":\"2026-10-01\"}"),0)
        val later=ChartData.x(JSONObject("{\"date\":\"2026-10-05\"}"),1)
        assertEquals(4.0,later-first,0.0)
    }
    @Test fun strictApiPayloadPreservesVersionAndDropsDisplayMetadata() {
        val form=Forms.get("meals")
        val source=JSONObject("{\"id\":\"meal-a\",\"version\":7,\"updatedAt\":\"yesterday\",\"date\":\"2026-10-23\",\"foods\":\"rice\",\"nutritionSource\":\"estimate\",\"nutrients\":{\"energyKcal\":220},\"progress\":{\"percent\":10}}")
        val payload=Forms.payload(form,source)
        assertEquals(7,payload.getInt("version"));assertEquals(220,payload.getJSONObject("nutrients").getInt("energyKcal"))
        assertFalse(payload.has("id"));assertFalse(payload.has("updatedAt"));assertFalse(payload.has("progress"))
        assertEquals(7,source.getInt("version"))
    }
    @Test fun badDateAndMissingRequiredMealRejectBeforeNetwork() {
        val form=Forms.get("meals");val data=Forms.initial(form.fields).put("date","2026-02-30").put("foods","rice")
        assertThrows(IllegalArgumentException::class.java){Forms.validate(form.fields,data)}
        data.put("date","2026-10-23").put("foods","")
        assertThrows(IllegalArgumentException::class.java){Forms.validate(form.fields,data)}
    }
    @Test fun malformedNestedPlanItemIsRejectedAndOptionalNutrientsRemainNull() {
        val plan=Forms.get("plans");val data=Forms.initial(plan.fields).put("title","Sleep").put("goal","Earlier bedtime")
        data.getJSONArray("items").put(JSONObject("{\"id\":\"a\",\"title\":\"\",\"date\":null,\"time\":null,\"status\":\"pending\",\"notes\":\"\"}"))
        assertThrows(IllegalArgumentException::class.java){Forms.validate(plan.fields,data)}
        val meal=Forms.initial(Forms.get("meals").fields)
        assertTrue(meal.getJSONObject("nutrients").isNull("energyKcal"))
    }
    @Test fun trainingCreateCarriesValidDateTimeAndDoesNotInventFeedback() {
        val form=Forms.get("sessions");val data=Forms.initial(form.fields).put("title","Easy run")
        Forms.validate(form.fields,data)
        assertTrue(data.isNull("feedback"));assertEquals(0,data.getJSONArray("activityIds").length())
        assertTrue(data.getString("startAt").matches(Regex(".*T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}|.*T\\d{2}:\\d{2}:\\d{2}Z")))
    }
}
