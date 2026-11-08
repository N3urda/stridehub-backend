package io.stridehub.app.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChartInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun lineChartCanReadMissingAndKnownPointsWithoutTouchingCanvas() {
        val rows=listOf(
            JSONObject("{\"date\":\"2026-11-06\",\"weightKg\":70.4}"),
            JSONObject("{\"date\":\"2026-11-07\",\"weightKg\":null}"),
            JSONObject("{\"date\":\"2026-11-08\",\"weightKg\":70.8}")
        )
        compose.setContent {MaterialTheme {NativeLineChart("体重趋势 · kg",rows,"weightKg",Color.Blue)}}
        compose.onNodeWithText("2026年11月8日 · 70.8 kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一条记录").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一条记录").performClick()
        compose.onNodeWithText("2026年11月7日 · 未记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一条记录").performClick()
        compose.onNodeWithText("2026年11月6日 · 70.4 kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一条记录").assertIsNotEnabled()
    }

    @Test fun dailyChartSelectionReportsToItsOwningScreen() {
        val rows=listOf(JSONObject("{\"date\":\"2026-11-07\",\"value\":0}"),JSONObject("{\"date\":\"2026-11-08\",\"value\":1000}"))
        var chosen=-1
        compose.setContent {MaterialTheme {
            var selected by remember {mutableIntStateOf(1)}
            DailyBars("已记录能量",rows,"value","kcal",selectedIndex=selected,onSelectionChange={selected=it;chosen=it})
        }}
        compose.onNodeWithContentDescription("上一条记录").performClick()
        compose.runOnIdle {assertEquals(0,chosen)}
        compose.onNodeWithText("2026年11月7日 · 0 kcal").assertIsDisplayed()
    }
}
