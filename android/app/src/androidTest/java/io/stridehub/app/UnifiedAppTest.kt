package io.stridehub.app

import android.app.Instrumentation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stridehub.app.scale.WeighingActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Compose connection controls and the existing native BLE activity in one task. */
@RunWith(AndroidJUnit4::class)
class UnifiedAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineWeighingEntryAndReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        NativeSession.clear(context)
        context.getSharedPreferences("connection", 0).edit().clear().commit()
        val monitor: Instrumentation.ActivityMonitor = instrumentation.addMonitor(
            WeighingActivity::class.java.name, null, false
        )
        var weighing: WeighingActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                scenario.onActivity { main ->
                    assertFalse("Main app must contain only native UI", NativeUiTestSupport.containsWebSurface(main.window.decorView))
                }
                val offlineEntry = compose.onNodeWithText("离线称重与本机历史")
                    .assertIsDisplayed()
                assertTrue("Offline weighing is visible before the connection form",
                    offlineEntry.fetchSemanticsNode().boundsInRoot.bottom <
                        compose.onNodeWithText("服务器地址").fetchSemanticsNode().boundsInRoot.top)
                offlineEntry.performClick()
                weighing = monitor.waitForActivityWithTimeout(5000) as? WeighingActivity
                assertNotNull("Offline weighing must open without NAS login", weighing)
                instrumentation.runOnMainSync {
                    assertFalse("Weighing must contain only native UI", NativeUiTestSupport.containsWebSurface(weighing!!.window.decorView))
                }
                compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                assertTrue("Weighing returns to the same native connection screen", weighing!!.isFinishing)
                compose.onNodeWithText("连接 StrideHub").assertIsDisplayed()
                compose.onNodeWithText("离线称重与本机历史").assertIsDisplayed()
                scenario.onActivity { main ->
                    assertFalse(NativeUiTestSupport.containsWebSurface(main.window.decorView))
                    assertFalse(NativeSession.signedIn(main))
                }
            }
        } finally {
            weighing?.let { activity ->
                if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finish() }
            }
            instrumentation.removeMonitor(monitor)
        }
    }
}
