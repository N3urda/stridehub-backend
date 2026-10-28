package io.stridehub.app.nativeui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Keep unknown measurements unknown; presentation never fills in a health observation. */
fun healthValue(value:Any?,decimals:Int=0):String {
    val number=(value as? Number)?.toDouble()?.takeIf {it.isFinite()} ?: return "—"
    return NumberFormat.getNumberInstance(Locale.CHINA).apply {
        minimumFractionDigits=decimals;maximumFractionDigits=decimals
    }.format(number)
}

fun healthDuration(minutes:Any?):String {
    val value=(minutes as? Number)?.toDouble()?.takeIf {it.isFinite() && it>=0} ?: return "—"
    val total=value.roundToInt();val hours=total/60;val remainder=total%60
    return if(hours==0) "${remainder}分钟" else if(remainder==0) "${hours}小时" else "${hours}时${remainder}分"
}

fun healthReferenceDate(timezone:String?,now:Instant=Instant.now()):String =
    now.atZone(runCatching {ZoneId.of(timezone)}.getOrDefault(ZoneId.systemDefault())).toLocalDate().toString()

fun healthDate(date:String?,today:String):String {
    if(date.isNullOrBlank()) return "尚无记录"
    if(date==today) return "今天"
    return runCatching {val parsed=LocalDate.parse(date);val pattern=if(parsed.year==LocalDate.parse(today).year) "M月d日" else "yyyy年M月d日";parsed.format(DateTimeFormatter.ofPattern(pattern,Locale.CHINA))}.getOrDefault(date)
}
