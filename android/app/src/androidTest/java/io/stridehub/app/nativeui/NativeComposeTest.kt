package io.stridehub.app.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.stridehub.app.MainActivity
import io.stridehub.app.NativeSession
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeComposeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun mealFormOffersRealNativeControlsAndRetainsEnteredFood() {
        var saved=JSONObject()
        compose.setContent {MaterialTheme {var data by remember {mutableStateOf(Forms.initial(Forms.get("meals").fields))};Column(Modifier.verticalScroll(rememberScrollState())) {FormFields(Forms.get("meals").fields,data,{data=it;saved=it})}}}
        compose.onNodeWithText("食物 *").performTextInput("米饭和鸡蛋")
        compose.onNodeWithText("米饭和鸡蛋").assertExists()
        compose.onNodeWithText("早餐").performClick()
        compose.runOnIdle {org.junit.Assert.assertEquals("米饭和鸡蛋",saved.getString("foods"));org.junit.Assert.assertEquals("breakfast",saved.getString("mealType"))}
    }
    @Test fun missingChartHasNativeAccessibleExplanation() {
        compose.setContent {MaterialTheme {NativeLineChart("体重趋势",listOf(JSONObject("{\"weightKg\":null}")),"weightKg",androidx.compose.ui.graphics.Color.Blue)}}
        compose.onNodeWithText("暂无实际测量值").assertIsDisplayed()
    }
    @Test fun nestedPlanItemsCanBeAddedEditedAndRemoved() {
        compose.setContent {MaterialTheme {var data by remember {mutableStateOf(JSONObject("{\"items\":[]}"))};Column(Modifier.verticalScroll(rememberScrollState())) {FormFields(Forms.get("plans").fields.filter {it.key=="items"},data,{data=it})}}}
        compose.onNodeWithText("添加计划条目").performClick()
        compose.onNodeWithText("条目名称 *").performTextInput("晚间散步")
        compose.onNodeWithText("晚间散步").assertExists()
        compose.onNodeWithText("移除此条").performScrollTo().performClick()
        compose.onNodeWithText("晚间散步").assertDoesNotExist()
    }
}
