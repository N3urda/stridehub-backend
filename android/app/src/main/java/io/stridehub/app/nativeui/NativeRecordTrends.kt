package io.stridehub.app.nativeui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

object RecordTrends {
    fun ordered(rows: List<JSONObject>, timestamp: Boolean): List<JSONObject> = rows.map { row ->
        JSONObject(row.toString()).apply { if (timestamp && row.has("measuredAt")) put("at", row.opt("measuredAt")) }
    }.sortedBy { row -> if (timestamp) runCatching { java.time.OffsetDateTime.parse(row.text("at")).toInstant().toEpochMilli() }.getOrDefault(Long.MIN_VALUE) else runCatching { java.time.LocalDate.parse(row.text("date")).toEpochDay() }.getOrDefault(Long.MIN_VALUE) }
}

@Composable fun RecordTrendPanel(kind: String, rows: List<JSONObject>) {
    val options = when (kind) {
        "weight" -> listOf("weightKg" to "体重 · kg")
        "blood-pressure" -> listOf("systolic" to "收缩压 · mmHg", "diastolic" to "舒张压 · mmHg", "pulse" to "脉搏 · 次/分")
        "measurements" -> listOf("waistCm" to "腰围 · cm", "bodyFatPercent" to "体脂率 · %")
        "check-ins" -> listOf("sleepHours" to "主观睡眠 · 小时", "fatigue" to "疲劳 · 分", "soreness" to "酸痛 · 分")
        else -> return
    }
    var selected by remember(kind) { mutableStateOf(options.first().first) }
    if (options.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) -> FilterChip(selected == key, onClick = { selected = key }, label = { Text(label.substringBefore(" ·")) }) }
    }
    val sorted = remember(rows.toString(), kind) { RecordTrends.ordered(rows, kind == "blood-pressure") }
    NativeLineChart(options.first { it.first == selected }.second, sorted, selected, MaterialTheme.colorScheme.primary)
}
