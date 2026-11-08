package io.stridehub.app.nativeui

import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

enum class ChartAxis { AUTO, DATE, TIME, ELAPSED, DISTANCE }
enum class ChartValueKind { NUMBER, PACE, DURATION }

data class ChartValueFormat(val unit:String="", val decimals:Int=1, val kind:ChartValueKind=ChartValueKind.NUMBER) {
    fun value(number:Double):String {
        if(!number.isFinite()) return "未记录"
        if(kind==ChartValueKind.PACE || kind==ChartValueKind.DURATION) {
            if(number<0) return "未记录"
            val seconds=number.roundToLong()
            return if(kind==ChartValueKind.DURATION && seconds>=3600) "%d:%02d:%02d".format(Locale.CHINA,seconds/3600,seconds%3600/60,seconds%60)
            else "%d:%02d".format(Locale.CHINA,seconds/60,seconds%60)
        }
        return NumberFormat.getNumberInstance(Locale.CHINA).apply {
            maximumFractionDigits=decimals;minimumFractionDigits=decimals
        }.format(number)
    }
    fun label(number:Double?):String = number?.takeIf {it.isFinite()}?.let {value(it)+if(unit.isBlank()) "" else " $unit"} ?: "未记录"
    companion object {
        fun forMetric(title:String,key:String):ChartValueFormat {
            val unit=title.substringAfterLast("·", "").trim()
            return when {
                key.contains("percent",true) || unit=="%" -> ChartValueFormat("%",1)
                key.contains("pace",true) || key=="secondsPerKilometer" || title.contains("配速") -> ChartValueFormat("/km",0,ChartValueKind.PACE)
                key in listOf("movingSeconds","elapsedSeconds","durationSeconds") -> ChartValueFormat("",0,ChartValueKind.DURATION)
                unit=="mmHg" || key in listOf("systolic","diastolic") -> ChartValueFormat("mmHg",0)
                unit=="次/分" || key=="pulse" -> ChartValueFormat("次/分",0)
                unit=="cm" || key.endsWith("Cm") -> ChartValueFormat("cm",1)
                unit=="小时" || key=="sleepHours" -> ChartValueFormat("小时",1)
                unit=="分" || key in listOf("fatigue","soreness") -> ChartValueFormat("分",0)
                key.contains("weight",true) || unit=="kg" -> ChartValueFormat("kg",1)
                key.contains("bpm",true) || unit=="bpm" || key=="heartRate" -> ChartValueFormat("bpm",0)
                unit=="步/分" || key in listOf("cadenceSpm","stepsPerMinute") -> ChartValueFormat("步/分",0)
                key.contains("steps",true) || title.contains("步数") -> ChartValueFormat("步",0)
                key=="count" || unit=="次" || title.endsWith("次数") -> ChartValueFormat("次",0)
                unit=="kcal" -> ChartValueFormat("kcal",0)
                unit=="km/h" -> ChartValueFormat("km/h",1)
                key=="distanceKm" || unit=="km" -> ChartValueFormat("km",1)
                unit=="m" || key=="altitudeM" -> ChartValueFormat("m",0)
                unit=="W" || key=="powerW" -> ChartValueFormat("W",0)
                title.contains("压力") -> ChartValueFormat("",0)
                else -> ChartValueFormat(unit)
            }
        }
    }
}

object ChartPresentation {
    fun axisFor(rows:List<JSONObject>,requested:ChartAxis=ChartAxis.AUTO):ChartAxis {
        if(requested!=ChartAxis.AUTO) return requested
        if(rows.any {it.opt("seconds") is Number}) return ChartAxis.ELAPSED
        if(rows.any {runCatching {LocalDate.parse(it.optString("date",""))}.isSuccess}) return ChartAxis.DATE
        val dates=rows.mapNotNull {runCatching {OffsetDateTime.parse(it.optString("at","")).toLocalDate()}.getOrNull()}.distinct()
        return when {dates.size>1->ChartAxis.DATE;dates.size==1->ChartAxis.TIME;else->ChartAxis.AUTO}
    }

    fun axisLabel(row:JSONObject,index:Int,axis:ChartAxis=ChartAxis.AUTO,full:Boolean=false):String {
        if(axis==ChartAxis.DISTANCE) return ChartValueFormat("km",2).label((row.opt("distanceKm") as? Number)?.toDouble() ?: (row.opt("seconds") as? Number)?.toDouble())
        val seconds=(row.opt("seconds") as? Number)?.toDouble()
        if(axis==ChartAxis.ELAPSED || (axis==ChartAxis.AUTO && seconds!=null)) return seconds?.let {ChartValueFormat(kind=ChartValueKind.DURATION).value(it)} ?: "样本 ${index+1}"
        val date=row.optString("date","")
        if(date.isNotBlank()) return runCatching {LocalDate.parse(date).format(DateTimeFormatter.ofPattern(if(full) "yyyy年M月d日" else "M/d",Locale.CHINA))}.getOrDefault(date)
        val at=row.optString("at","")
        if(at.isNotBlank()) return runCatching {
            val pattern=when {full->"yyyy年M月d日 HH:mm";axis==ChartAxis.DATE->"M/d";else->"HH:mm"}
            OffsetDateTime.parse(at).format(DateTimeFormatter.ofPattern(pattern,Locale.CHINA))
        }.getOrDefault(at)
        return "样本 ${index+1}"
    }

    /** Keep some breathing room around extrema without extending a positive metric below zero. */
    fun range(values:List<Double>,minimumSpan:Double=0.0):Pair<Double,Double> {
        val low=values.minOrNull() ?: 0.0;val high=values.maxOrNull() ?: 1.0
        val naturalPadding=if(low==high) max(abs(low)*.02,1.0) else (high-low)*.12
        val padding=max(naturalPadding,(minimumSpan-(high-low))/2)
        return (if(low>=0) max(0.0,low-padding) else low-padding) to high+padding
    }
}
