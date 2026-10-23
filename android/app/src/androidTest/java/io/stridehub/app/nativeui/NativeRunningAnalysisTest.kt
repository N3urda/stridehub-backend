package io.stridehub.app.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRunningAnalysisTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun fixture(name:String)=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("$name.json").bufferedReader().use {it.readText()})
    @Test fun populatedServerAnalysisHasNativeScatterZonesDistributionCoverageAndSplits() {
        val data=fixture("native-running-detail-populated")
        compose.setContent {MaterialTheme {PageColumn {RunningAnalysisContent(data)}}}
        compose.onNodeWithText("最快完整公里 秒/km").assertExists()
        compose.onNodeWithText("配速与心率").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("训练心率区间 · 分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("心率分布 · 分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("记录断档 秒").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("显式暂停 秒").assertExists()
        compose.onNodeWithText("公里分段与 GAP").performScrollTo().assertIsDisplayed()
    }
    @Test fun missingServerAnalysisExplainsMissingSensorsAndDoesNotInventPlots() {
        val data=fixture("native-running-detail-missing")
        compose.setContent {MaterialTheme {PageColumn {RunningAnalysisContent(data)}}}
        compose.onNodeWithText("暂无同时记录配速与心率的有效样本").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("暂无足够的实际记录").assertCountEquals(2)
        compose.onNodeWithText("暂无公里分段；缺失配速与 GAP 保持未知。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂无计圈记录。").assertExists()
    }
}
