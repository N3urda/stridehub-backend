package io.stridehub.app.nativeui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tab switching must keep one activity, one bottom bar, and the existing weighing section. */
@RunWith(AndroidJUnit4::class)
class WeighingNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Before fun offline() {
        NativeSession.clear(instrumentation.targetContext)
        instrumentation.targetContext.getSharedPreferences("connection",0).edit().clear().commit()
    }
    @Test fun tabsShareOneActivityAndRetainWeighingSection() {
        val monitor=instrumentation.addMonitor(WeighingActivity::class.java.name,null,false)
        try { ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {main->ViewModelProvider(main)[AppModel::class.java].loggedIn(NativeRepository(main,ServerAddress.parse("http://127.0.0.1:9",true),"tab-fixture",""))}
            compose.onNodeWithTag("main-tab-4").performClick().assertIsSelected()
            onView(withText("趋势")).perform(click())
            compose.onNodeWithTag("main-tab-2").performClick().assertIsSelected()
            compose.onNodeWithTag("main-tab-4").performClick().assertIsSelected()
            onView(withText("趋势")).check(matches(isSelected()))
            repeat(3){compose.onNodeWithTag("main-tab-4").performClick()}
            compose.onAllNodesWithTag("main-tab-4").assertCountEquals(1)
            assertEquals("A real tab must not start a second activity",0,monitor.hits)
            scenario.onActivity {assertFalse(it.isFinishing)}
        }} finally {instrumentation.removeMonitor(monitor)}
    }
    @Test fun otherTabDraftSurvivesWeighingRoundTrip() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {main->
                val model=ViewModelProvider(main)[AppModel::class.java]
                model.loggedIn(NativeRepository(main,ServerAddress.parse("http://127.0.0.1:9",true),"draft-fixture",""))
                model.select(1)
                model.push(route("form","check-ins"))
            }
            compose.onNodeWithText("备注").performScrollTo().performTextReplacement("保留未提交的身体感受")
            closeSoftKeyboard()
            compose.onNodeWithTag("main-tab-4").performClick().assertIsSelected()
            compose.onNodeWithTag("main-tab-1").performClick().assertIsSelected()
            compose.onNodeWithText("备注").performScrollTo().assertTextContains("保留未提交的身体感受")
        }
    }
    @Test fun recreationPreservesTheWeighingTabAndSection() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.openWeighing()}
            compose.waitForIdle()
            onView(withText("记录")).perform(click())
            scenario.recreate()
            compose.onNodeWithTag("main-tab-4").assertIsSelected()
            onView(withText("记录")).check(matches(isSelected()))
        }
    }
    @Test fun offlineEntryPreservesConnectionDraft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("服务器地址").performScrollTo().performTextReplacement("https://draft.example.invalid")
            compose.onNodeWithText("用户名").performScrollTo().performTextReplacement("draft-user")
            closeSoftKeyboard()
            compose.onNodeWithText("离线称重与本机历史").performScrollTo().performClick()
            compose.onNodeWithTag("main-tab-4").assertIsSelected()
            pressBack()
            compose.onNodeWithText("服务器地址").performScrollTo().assertTextContains("https://draft.example.invalid")
            compose.onNodeWithText("用户名").performScrollTo().assertTextContains("draft-user")
        }
    }
}
