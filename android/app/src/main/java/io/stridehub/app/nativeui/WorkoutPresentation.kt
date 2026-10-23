package io.stridehub.app.nativeui

import org.json.JSONArray
import org.json.JSONObject

/** API-specific adapters keep route previews out of current business decisions. */
object WorkoutPresentation {
    private fun number(value:JSONObject,key:String):Double? = (value.opt(key) as? Number)?.toDouble()?.takeIf {it.isFinite()}
    fun imported(source:JSONObject):JSONObject = JSONObject().apply {
        put("id",source.getString("id"));put("name",source.opt("name"));put("startAt",source.opt("startDateLocal"));put("sportType",source.opt("sportType"))
        put("distanceKm",number(source,"distanceInMeter")?.div(1000) ?: JSONObject.NULL)
        put("durationMinutes",number(source,"movingTimeInSeconds")?.div(60) ?: JSONObject.NULL)
        put("averageHeartRate",number(source,"averageHeartRate")?.takeIf {it>0} ?: JSONObject.NULL)
    }
    fun zepp(source:JSONObject,id:String):JSONObject = JSONObject().apply {
        put("id",id);put("name",source.opt("sportName"));put("startAt",source.opt("startedAt"))
        put("sportType",when(source.optInt("sportType",-1)){1->"Run";7->"TrailRun";8->"VirtualRun";6->"Walk";9->"Ride";10->"VirtualRide";else->"Other"})
        put("distanceKm",number(source,"distanceMeters")?.div(1000) ?: JSONObject.NULL)
        put("durationMinutes",number(source,"durationSeconds")?.div(60) ?: JSONObject.NULL)
        put("averageHeartRate",number(source,"averageHeartRateBpm") ?: JSONObject.NULL)
    }
    fun associationPath(canonicalId:String):String = if(canonicalId.startsWith("zepp-")) "/api/v1/health/zepp/workouts/"+encoded(canonicalId) else "/api/v1/activities/"+encoded(canonicalId)
    fun associationSummary(canonicalId:String,source:JSONObject):JSONObject = if(canonicalId.startsWith("zepp-")) zepp(source,canonicalId) else imported(source)
    fun canAssociate(current:JSONObject)=current.optString("sportType") in listOf("Run","TrailRun","VirtualRun")
    fun sources(preview:JSONObject):List<JSONObject> {
        val rows=preview.array("sources").toMutableList()
        val aliases=preview.optJSONArray("aliases") ?: JSONArray()
        for(i in 0 until aliases.length()) {val id=aliases.optString(i);if(rows.none {it.optString("id")==id}) rows.add(JSONObject().put("id",id).put("source",if(id.startsWith("zepp-")) "zepp" else "import"))}
        if(rows.isEmpty()) {val id=preview.getString("id");rows.add(JSONObject().put("id",id).put("source",if(id.startsWith("zepp-")) "zepp" else "import"))}
        return rows.distinctBy {it.getString("id")}
    }
    fun sourceRoute(preview:JSONObject,sourceId:String):JSONObject=JSONObject(preview.toString()).put("canonicalId",preview.text("canonicalId",preview.getString("id"))).put("id",sourceId)
    fun overview(data:JSONObject)=RunningOverviewView(data.optJSONObject("summary") ?: JSONObject(),data.array("weekly"),data.array("monthly"),data.array("daily"),data.array("bestEfforts"),data.optJSONObject("activities") ?: JSONObject())
    fun analysisSections(detail:JSONObject):RunningAnalysisView = RunningAnalysisView(
        detail.optJSONObject("analysis") ?: JSONObject(),detail.optJSONObject("splitSummary") ?: JSONObject(),detail.array("splits"),detail.array("laps"))
}
data class RunningAnalysisView(val analysis:JSONObject,val splitSummary:JSONObject,val splits:List<JSONObject>,val laps:List<JSONObject>) {
    val scatter=analysis.array("scatter")
    val zones=analysis.array("zones")
    val distribution=analysis.array("heartRateDistribution")
    val coverage=analysis.optJSONObject("coverage") ?: JSONObject()
}

data class RunningOverviewView(val summary:JSONObject,val weekly:List<JSONObject>,val monthly:List<JSONObject>,val daily:List<JSONObject>,val bestEfforts:List<JSONObject>,val activities:JSONObject)
