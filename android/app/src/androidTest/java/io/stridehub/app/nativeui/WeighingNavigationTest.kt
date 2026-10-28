package io.stridehub.app.nativeui

import android.app.Instrumentation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stridehub.app.MainActivity
import io.stridehub.app.NativeSession
import io.stridehub.app.ServerAddress
import io.stridehub.app.scale.WeighingActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity/result navigation using only the isolated package's offline state. */
@RunWith(AndroidJUnit4::class)
class WeighingNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun useOfflineState() {
        val context = instrumentation.targetContext
        NativeSession.clear(context)
        context.getSharedPreferences("connection", 0).edit().clear().commit()
    }

    @Test fun weighingShowsAllMainTabsAndWorkoutsReturnsToTheRequestedTab() {
        withMainAndWeighing(previousTab = 1) { scenario, weighing, _ ->
            assertMainNavigation()
            compose.onNodeWithTag("main-tab-2").performClick()
            compose.waitForIdle()
            scenario.onActivity { main ->
                assertEquals("运动 keeps its stable tab ID", 2,
                    ViewModelProvider(main)[AppModel::class.java].tab)
                assertFalse("Navigation does not require a server login", NativeSession.signedIn(main))
            }
            assertTrue("Selecting a different main tab closes weighing", weighing.isFinishing)
            compose.onNodeWithText("连接 StrideHub").assertIsDisplayed()
        }
    }

    @Test fun mainWeighingTabOpensWeighingAndReturnsToTheSelectedMainTab() {
        val monitor = instrumentation.addMonitor(WeighingActivity::class.java.name, null, false)
        var weighing: WeighingActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { main ->
                    // A local UI fixture: empty credential, no real login, no external destination.
                    ViewModelProvider(main)[AppModel::class.java].loggedIn(
                        NativeRepository(main, ServerAddress.parse("http://127.0.0.1:9", true),
                            "weighing-ui-fixture", "")
                    )
                }
                compose.waitForIdle()
                visibleMainTab(0).assertIsSelected()
                visibleMainTab(4).assertIsNotSelected().performClick()
                weighing = monitor.waitForActivityWithTimeout(5_000) as? WeighingActivity
                assertNotNull("The main 称重 tab opens the native weighing activity", weighing)
                assertMainNavigation()
                visibleMainTab(2).performClick()
                compose.waitForIdle()
                scenario.onActivity { main ->
                    assertEquals(2, ViewModelProvider(main)[AppModel::class.java].tab)
                    assertFalse("The navigation fixture stores no session", NativeSession.signedIn(main))
                }
                assertTrue(requireNotNull(weighing).isFinishing)
                visibleMainTab(2).assertIsSelected()
                visibleMainTab(4).assertIsNotSelected()
                assertEquals("One weighing activity serves the complete tab round trip", 1, monitor.hits)
            }
        } finally {
            weighing?.let { activity ->
                if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finish() }
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun systemBackReturnsToThePreviousMainTab() {
        withMainAndWeighing(previousTab = 3) { scenario, weighing, _ ->
            assertMainNavigation()
            pressBack()
            compose.waitForIdle()
            scenario.onActivity { main ->
                assertEquals("Back preserves the previously selected 计划 tab", 3,
                    ViewModelProvider(main)[AppModel::class.java].tab)
            }
            assertTrue(weighing.isFinishing)
            compose.onNodeWithText("离线称重与本机历史").assertIsDisplayed()
        }
    }

    @Test fun selectingActiveWeighingTabDoesNotLaunchAnotherActivity() {
        withMainAndWeighing(previousTab = 0) { _, weighing, monitor ->
            repeat(3) {
                compose.onNodeWithTag("main-tab-4").performClick()
                compose.waitForIdle()
            }
            instrumentation.waitForIdleSync()
            assertEquals("Repeated active-tab taps keep one weighing activity", 1, monitor.hits)
            assertFalse(weighing.isFinishing)
            assertMainNavigation()
        }
    }

    @Test fun offlineCardAndSystemBackPreserveTheConnectionDraft() {
        val server = "https://draft.example.invalid"
        val username = "draft@example.invalid"
        val monitor = instrumentation.addMonitor(WeighingActivity::class.java.name, null, false)
        var weighing: WeighingActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("服务器地址").performScrollTo().performTextReplacement(server)
                compose.onNodeWithText("用户名").performScrollTo().performTextReplacement(username)
                closeSoftKeyboard()
                compose.onNodeWithText("离线称重与本机历史")
                    .performScrollTo().assertIsDisplayed().performClick()
                weighing = monitor.waitForActivityWithTimeout(5_000) as? WeighingActivity
                assertNotNull("The prominent card opens offline weighing", weighing)
                assertMainNavigation()
                pressBack()
                compose.waitForIdle()
                scenario.onActivity { main ->
                    assertFalse("The draft was never submitted as a login", NativeSession.signedIn(main))
                }
                assertTrue(requireNotNull(weighing).isFinishing)
                compose.onNodeWithText("服务器地址").performScrollTo().assertTextContains(server)
                compose.onNodeWithText("用户名").performScrollTo().assertTextContains(username)
            }
        } finally {
            weighing?.let { activity ->
                if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finish() }
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun recreationPreservesSharedNavigationAndTheCurrentWeighingSection() {
        ActivityScenario.launch(WeighingActivity::class.java).use { scenario ->
            assertMainNavigation()
            onView(withText("记录")).perform(click())
            scenario.recreate()
            compose.waitForIdle()
            assertMainNavigation()
            onView(withText("记录")).check(matches(isSelected()))
            // The other weighing sections remain reachable under the shared app shell.
            onView(withText("趋势")).perform(click())
            onView(withText("趋势")).check(matches(isSelected()))
            assertMainNavigation()
        }
    }

    private fun assertMainNavigation() {
        compose.waitForIdle()
        val tabs = listOf(0 to "总览", 1 to "身体", 4 to "称重", 2 to "运动", 3 to "计划")
        tabs
            .forEach { (id, label) ->
                val tab = visibleMainTab(id)
                    .assertIsDisplayed().assertTextContains(label)
                if (id == 4) tab.assertIsSelected() else tab.assertIsNotSelected()
            }
        val horizontalPositions = tabs.map { (id, _) ->
            visibleMainTab(id).fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue("称重 is a full main tab between 身体 and 运动",
            horizontalPositions.zipWithNext().all { (left, right) -> left < right })
    }

    private fun visibleMainTab(id: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        // Main keeps its composition alive behind weighing; address only the displayed activity.
        val candidates = compose.onAllNodesWithTag("main-tab-$id")
        return (0 until candidates.fetchSemanticsNodes().size)
            .map { candidates[it] }.single { it.isDisplayed() }
    }

    private fun withMainAndWeighing(
        previousTab: Int,
        block: (ActivityScenario<MainActivity>, WeighingActivity, Instrumentation.ActivityMonitor) -> Unit
    ) {
        val monitor = instrumentation.addMonitor(WeighingActivity::class.java.name, null, false)
        var weighing: WeighingActivity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                scenario.onActivity { main ->
                    ViewModelProvider(main)[AppModel::class.java].select(previousTab)
                    main.openWeighing()
                }
                weighing = monitor.waitForActivityWithTimeout(5_000) as? WeighingActivity
                assertNotNull("The existing native weighing activity opens", weighing)
                compose.waitForIdle()
                block(scenario, requireNotNull(weighing), monitor)
                weighing?.let { activity ->
                    if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finish() }
                }
                instrumentation.waitForIdleSync()
            }
        } finally {
            weighing?.let { activity ->
                if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finish() }
            }
            instrumentation.removeMonitor(monitor)
        }
    }
}
