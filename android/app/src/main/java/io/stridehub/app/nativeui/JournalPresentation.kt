package io.stridehub.app.nativeui

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/** Read-only presentation transforms: no invented zeroes or inferred completion. */
object JournalPresentation {
    fun sessionDate(session:JSONObject,zone:String):String? = runCatching {
        OffsetDateTime.parse(session.getString("startAt")).atZoneSameInstant(ZoneId.of(zone)).toLocalDate().toString()
    }.getOrNull()
    fun weekSessions(rows:List<JSONObject>,start:LocalDate,zone:String):List<JSONObject> = rows.filter {
        sessionDate(it,zone)?.let {d->d>=start.toString() && d<=start.plusDays(6).toString()} == true
    }
    fun sessionsOn(rows:List<JSONObject>,date:String,zone:String)=rows.filter {sessionDate(it,zone)==date}.sortedBy {runCatching {OffsetDateTime.parse(it.text("startAt")).toInstant()}.getOrNull()}
    fun completion(rows:List<JSONObject>):Pair<Int,Int> = rows.count {it.text("status")=="completed"} to rows.count {it.text("status") in setOf("planned","completed","skipped")}
    fun duration(seconds:Double?):String {
        val total=seconds?.takeIf {it.isFinite() && it>=0}?.roundToLong() ?: return "—"
        val hours=total/3600;val minutes=total%3600/60;val remaining=total%60
        return when {hours>0 -> "${hours}时${minutes}分";minutes>0 -> if(remaining>0) "${minutes}分${remaining}秒" else "${minutes}分钟";else -> "${remaining}秒"}
    }
    fun pace(seconds:Double?):String = seconds?.takeIf {it.isFinite() && it>0}?.roundToLong()?.let {"%d′%02d″/km".format(Locale.CHINA,it/60,it%60)} ?: "—"
    fun weekChart(rows:List<JSONObject>,metric:String):List<JSONObject> {
        val dated=rows.mapNotNull {row->runCatching {LocalDate.parse(row.text("date")) to row}.getOrNull()}.toMap()
        if(dated.isEmpty()) return emptyList()
        val last=dated.keys.max()
        return generateSequence(dated.keys.min()) {it.plusWeeks(1)}.takeWhile {it<=last}.map {date->
            val row=dated[date]
            JSONObject().put("date",date.toString()).put("value",
                DashboardData.number(row,if(metric=="minutes") "movingSeconds" else metric)?.let {if(metric=="minutes") it/60 else it} ?: JSONObject.NULL)
        }.toList()
    }
    fun nutritionSelection(days:List<JSONObject>,date:String):Int = days.indexOfFirst {it.text("date")==date}.takeIf {it>=0} ?: days.indexOfLast {it.optInt("mealCount")>0 || it.text("planId","").isNotBlank()}.coerceAtLeast(0)
    fun partialNutrients(day:JSONObject):List<String> = listOf("energyKcal","proteinGrams","carbohydrateGrams","fatGrams").filter {
        DashboardData.number(day.optJSONObject("actualLogged"),it)==null && DashboardData.number(day.optJSONObject("knownSubtotal"),it)!=null
    }
}
