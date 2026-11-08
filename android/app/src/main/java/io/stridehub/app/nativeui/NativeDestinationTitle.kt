package io.stridehub.app.nativeui

import org.json.JSONObject

/** Detail pages name their own task instead of repeating the originating bottom tab. */
fun nativeRouteTitle(route: JSONObject): String = when (route.optString("type")) {
    "settings" -> "设置与更多"
    "nutrition" -> "饮食与营养"
    "running" -> "跑步分析"
    "workout" -> "运动详情"
    "today" -> "今日安排"
    "imports" -> "运动文件导入"
    "sync" -> "数据同步"
    "association" -> "关联训练"
    "comparison" -> "训练对比"
    "activityForm" -> if (route.optString("id").isBlank()) "新增运动" else "编辑运动"
    "data" -> if (route.optString("kind") == "sleep") "睡眠详情" else "每日健康指标"
    "records", "form" -> runCatching { Forms.get(route.getString("kind")).title }.getOrDefault("记录详情")
    else -> "记录详情"
}
