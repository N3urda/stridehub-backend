package io.stridehub.app.nativeui

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.stridehub.app.MainActivity
import io.stridehub.app.NativeSession
import io.stridehub.app.ServerAddress
import io.stridehub.app.scale.WeighingActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product-level acceptance of the actual app shell using synthetic, offline Room responses.
 * Run with applicationId io.stridehub.app.producttests (the debug suffix is allowed).
 * There is no server login or credential, and the only request destination is loopback port 9.
 * Screenshots are labelled fixture evidence, never real account/provider/hardware acceptance.
 * Optional runner arguments: theme=light|dark, screenshotPrefix=<safe filename prefix>, adaptive=true.
 * Adaptive mode checks that primary actions can be reached by scrolling on small/high-font screens.
 */
@RunWith(AndroidJUnit4::class)
class NativeProductPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val theme get() = arguments.getString("theme") ?: "light"
    private val screenshotPrefix get() = arguments.getString("screenshotPrefix").orEmpty()
    private val adaptive get() = arguments.getString("adaptive", "false").equals("true", ignoreCase = true)
    private val today = LocalDate.now()

    @Before fun isolateFixtureSession() {
        assertTrue("Product fixtures require their own installed app package",
            context.packageName.startsWith("io.stridehub.app.producttests"))
        assertTrue("theme must be light or dark", theme in setOf("light", "dark"))
        assertTrue("screenshotPrefix may contain at most 64 letters, digits, underscores, or hyphens",
            screenshotPrefix.matches(Regex("[A-Za-z0-9_-]{0,64}")))
        NativeSession.clear(context)
        context.getSharedPreferences("connection", 0).edit().clear().commit()
        context.getSharedPreferences("native-ui", 0).edit().clear().putString("theme", theme).commit()
    }

    @Test fun populatedTabsShowReadableMeasurementsAndPreserveMainDestinations() {
        withFixture(populated = true) { scenario ->
            awaitTag("overview-weighing")
            awaitText("离线缓存")
            primaryAction(compose.onNodeWithTag("overview-weighing"))
            assertMainNavigationVisible()
            metric("overview-weight").assertTextEquals("72.4 kg")
            metric("overview-sleep").assertTextEquals("7时35分")
            compose.onNodeWithText("每日步数").performScrollTo().assertIsDisplayed()
            val scroller=compose.onNode(hasScrollAction())
            val scrollOffset=scroller.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            scroller.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {it(0f,-scrollOffset)}
            screenshot("01-overview-fixture")
            scrollTitleToTop("睡眠结构")
            screenshot("09-sleep-chart-fixture")
            compose.onNodeWithText("饮食与营养").performScrollTo().performClick()
            awaitText("三大营养素供能占比")
            scrollTitleToTop("三大营养素供能占比")
            screenshot("10-nutrition-chart-fixture")
            pressBack()


            selectTab(1)
            awaitTag("body-weighing")
            awaitText("离线缓存")
            primaryAction(compose.onNodeWithTag("body-weighing"))
            compose.onNodeWithText("体重 · kg").assertIsDisplayed()
            screenshot("02-body-fixture")
            compose.onNodeWithTag("body-metric-pressure").performScrollTo().performClick()
            awaitText("收缩压 · mmHg")
            screenshot("11-pressure-chart-fixture")
            compose.onNodeWithTag("body-metric-composition").performScrollTo().performClick()
            awaitText("体脂率 · %")
            screenshot("12-composition-chart-fixture")

            selectTab(2)
            awaitText("运动记录")
            awaitText("离线缓存")
            primaryAction(compose.onNodeWithText("新增运动"))
            primaryAction(compose.onNodeWithText("导入文件"))
            compose.onNodeWithTag("workout-summary-duration").assertTextContains("1时26分", substring = true)
            compose.onNodeWithTag("workout-summary-heart-rate").assertTextContains("—", substring = true)
            screenshot("03-workouts-fixture")
            compose.onNodeWithText("清晨轻松跑").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("46分钟").performScrollTo().assertIsDisplayed()
            screenshot("08-workout-records-fixture")

            selectTab(3)
            awaitTag("plans-today")
            compose.onNodeWithText("一周训练").assertIsDisplayed()
            screenshot("04-plans-fixture")
            compose.onNodeWithTag("plans-today").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("plans-today").performClick()
            scenario.onActivity { main ->
                val vm = ViewModelProvider(main)[AppModel::class.java]
                assertEquals("The prominent plans action opens the existing daily workflow", "today",
                    JSONObject(vm.currentRoute).getString("type"))
                assertFalse("Cached presentation must never establish a real session", NativeSession.signedIn(main))
            }
        }
    }

    @Test fun missingOverviewMeasurementsRemainUnknown() {
        withFixture(populated = false) {
            awaitTag("overview-weighing")
            awaitText("离线缓存")
            listOf("overview-sleep").forEach { tag ->
                metric(tag).assertTextEquals("—")
            }
            primaryAction(compose.onNodeWithTag("overview-weighing"))
            assertMainNavigationVisible()
            screenshot("05-overview-empty-fixture")
        }
    }

    @Test fun overviewWeighingActionOpensSharedNavigationAndReturnsToBody() {
        withFixture(populated = true) { scenario ->
            awaitTag("overview-weighing")
            withWeighing({ primaryAction(compose.onNodeWithTag("overview-weighing")).performClick() }) { weighing ->
                assertMainNavigationVisible()
                visibleTab(4).assertIsSelected()
                screenshot("06-weighing-offline", WeighingActivity::class.java)
                visibleTab(1).performClick()
                compose.waitForIdle()
                scenario.onActivity { main ->
                    assertEquals(1, ViewModelProvider(main)[AppModel::class.java].tab)
                }
                assertFalse("The main activity stays alive across tabs", weighing.isFinishing)
                awaitTag("body-weighing")
                primaryAction(compose.onNodeWithTag("body-weighing"))
                assertMainNavigationVisible()
            }
        }
    }

    @Test fun loginKeepsOfflineWeighingAvailableWithoutAConnection() {
        ActivityScenario.launch(MainActivity::class.java).use {
            primaryAction(compose.onNodeWithText("离线称重与本机历史"))
            screenshot("07-login-offline")
            withWeighing({ primaryAction(compose.onNodeWithText("离线称重与本机历史")).performClick() }) { weighing ->
                assertMainNavigationVisible()
                visibleTab(4).assertIsSelected()
                pressBack()
                compose.waitForIdle()
                assertFalse(weighing.isFinishing)
                primaryAction(compose.onNodeWithText("离线称重与本机历史"))
                assertFalse(NativeSession.signedIn(context))
            }
        }
    }

    private fun selectTab(id: Int) {
        visibleTab(id).performClick()
        compose.waitForIdle()
        visibleTab(id).assertIsSelected()
        assertMainNavigationVisible()
    }

    private fun primaryAction(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        if (adaptive) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun assertMainNavigationVisible() {
        listOf(0, 1, 4, 2, 3).forEach { id -> visibleTab(id).assertIsDisplayed() }
    }

    // Sleep, steps, and heart-rate values sit inside clickable cards that merge semantics.
    private fun metric(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun visibleTab(id: Int): SemanticsNodeInteraction {
        val candidates = compose.onAllNodesWithTag("main-tab-$id")
        return (0 until candidates.fetchSemanticsNodes().size)
            .map { candidates[it] }.single { it.isDisplayed() }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun scrollTitleToTop(text:String) {
        val target=compose.onNodeWithText(text)
        target.performScrollTo()
        val container=compose.onNode(hasScrollAction())
        val delta=target.fetchSemanticsNode().boundsInRoot.top-container.fetchSemanticsNode().boundsInRoot.top
        container.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {it(0f,delta)}
        compose.waitForIdle()
    }

    private fun screenshot(name: String, expectedActivity: Class<out Activity> = MainActivity::class.java) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Compose idle does not wait for Android window transitions or a scroll frame to be
        // presented by the compositor. Let the short Activity fade finish before checking focus.
        SystemClock.sleep(500)
        val window = awaitFocusedWindow(expectedActivity)
        val frames = CountDownLatch(1)
        instrumentation.runOnMainSync {
            window.invalidate()
            window.postOnAnimation { window.postOnAnimation { frames.countDown() } }
        }
        assertTrue("The focused activity rendered its settled frame", frames.await(2, TimeUnit.SECONDS))
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        assertSame("The screenshot must still belong to the requested activity window", window,
            awaitFocusedWindow(expectedActivity))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "product-presentation").apply { mkdirs() }
        val filename = if (screenshotPrefix.isEmpty()) name else "$screenshotPrefix-$name"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$filename.png").outputStream().use { output ->
                assertTrue("The native activity screenshot was saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }

    private fun awaitFocusedWindow(expectedActivity: Class<out Activity>): View {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var focused: View? = null
            instrumentation.runOnMainSync {
                focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { expectedActivity.isInstance(it) && it.hasWindowFocus() }
                    ?.window?.decorView?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            }
            focused?.let { return it }
            SystemClock.sleep(25)
        }
        error("${expectedActivity.simpleName} did not acquire a visible, focused window")
    }

    private fun withWeighing(open: () -> Unit, block: (WeighingActivity) -> Unit) {
        open()
        compose.waitForIdle()
        awaitFocusedWindow(MainActivity::class.java)
        var main:MainActivity?=null
        instrumentation.runOnMainSync {main=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()}
        block(requireNotNull(main))
    }

    private fun withFixture(populated: Boolean, block: (ActivityScenario<MainActivity>) -> Unit) {
        val repo = NativeRepository(context, ServerAddress.parse("http://127.0.0.1:9", true),
            "product-presentation-fixture", "")
        runBlocking { seed(repo, populated) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { main -> ViewModelProvider(main)[AppModel::class.java].loggedIn(repo) }
                block(scenario)
            }
        } finally {
            repo.cancel()
            runBlocking { repo.cache.clear(repo.account) }
        }
    }

    /** Field names mirror HealthWorkspace, WorkoutCatalog, and the native consumers. */
    private suspend fun seed(repo: NativeRepository, populated: Boolean) {
        repo.cache.clear(repo.account)
        val workouts = workouts(populated)
        val weightRows = if (populated) JSONArray().put(JSONObject().put("date", today.toString()).put("weightKg", 72.4)) else JSONArray()
        val trend = JSONArray()
        if (populated) listOf(73.0, 72.8, 72.9, 72.7, 72.6, 72.5, 72.4).forEachIndexed { index, kg ->
            trend.put(JSONObject().put("date", today.minusDays((6 - index).toLong()).toString()).put("weightKg", kg))
        }
        val sleep = JSONArray()
        val daily = JSONArray()
        if (populated) {
            (1..13).forEach {i->
                sleep.put(JSONObject().put("date",today.minusDays(i.toLong()).toString()).put("totalMinutes",390+i*4).put("deepMinutes",60+i).put("lightMinutes",250+i*2).put("remMinutes",80+i))
                daily.put(JSONObject().put("date",today.minusDays(i.toLong()).toString()).put("activity",JSONObject().put("steps",4500+i*370)).put("heartRate",JSONObject().put("restingBpm",58+i%5)))
            }
            sleep.put(JSONObject().put("date", today.toString()).put("totalMinutes", 455).put("deepMinutes", 94)
                .put("lightMinutes", 268).put("remMinutes", 93).put("source", "Zepp").put("sourceLabel", "Zepp / Amazfit"))
            daily.put(JSONObject().put("date", today.toString()).put("activity", JSONObject().put("steps", 8260))
                .put("heartRate", JSONObject().put("restingBpm", JSONObject.NULL)))
        }
        val workspace = JSONObject().put("today", today.toString()).put("from", today.minusDays(29).toString())
            .put("to", today.toString()).put("timezone", "Asia/Shanghai")
            .put("weight", JSONObject().put("records", weightRows).put("trend", trend).put("goals", JSONArray()))
            .put("sleep", JSONArray(sleep.objects().sortedByDescending {it.text("date")})).put("daily", JSONArray(daily.objects().sortedByDescending {it.text("date")})).put("workouts", workouts)
            .put("sessions", JSONArray()).put("healthPlans", JSONArray()).put("checkIns", JSONArray())
            .put("sync", JSONObject().put("state", if (populated) "success" else "not_configured")
                .put("lastSuccessAt", if (populated) "${today}T08:30:00+08:00" else JSONObject.NULL))
        if(populated) {
            val days=JSONArray()
            (0..6).forEach {i->days.put(JSONObject().put("date",today.minusDays((6-i).toLong()).toString()).put("mealCount",3).put("estimatedMeals",1)
                .put("actualLogged",JSONObject().put("energyKcal",1800+i*30).put("proteinGrams",95+i).put("carbohydrateGrams",220).put("fatGrams",55))
                .put("targets",JSONObject().put("energyKcal",2100).put("proteinGrams",120).put("carbohydrateGrams",240).put("fatGrams",65)))}
            workspace.put("nutrition",JSONObject().put("days",days).put("meals",JSONArray()))
        }
        if (populated) {
            workspace.put("bloodPressure", JSONArray().put(JSONObject().put("measuredAt", "${today}T08:00:00+08:00").put("systolic", 118).put("diastolic", 76)).put(JSONObject().put("measuredAt", "${today.minusDays(2)}T08:00:00+08:00").put("systolic", 122).put("diastolic", 78)))
            workspace.put("measurements", JSONArray().put(JSONObject().put("date", today.toString()).put("waistCm", 84).put("bodyFatPercent", 21.5)))
        }
        val compositionRows = JSONArray()
        if (populated) (0..5).forEach { i -> compositionRows.put(JSONObject().put("measurementId", "fixture-capture-$i")
            .put("capture", JSONObject().put("measuredAt", "${today.minusDays(i.toLong())}T07:30:00+08:00"))
            .put("composition", JSONObject().put("metrics", JSONArray().put(JSONObject().put("key", "BODY_FAT").put("label", "体脂率").put("unit", "%").put("value", 21.5+i*.1))))) }
        cache(repo, "/api/v1/health/body-composition?from=${today.minusDays(29)}&to=$today&limit=100", JSONObject().put("records",compositionRows).put("snapshot","fixture-snapshot").put("nextOffset",JSONObject.NULL).put("total",compositionRows.length()))
        cache(repo, "/api/v1/health/workspace", workspace)
        cache(repo, "/api/v1/health/workspace?from=${today.minusDays(29)}&to=$today", workspace)
        cache(repo, "/api/v1/mobile/workouts?page=1&limit=30&from=${today.minusDays(89)}&to=$today&sportType=all", workouts)
        val sessions = JSONArray()
        if (populated) sessions.put(JSONObject().put("id", "fixture-session").put("version", 1)
            .put("title", "轻松跑 · 保持舒适节奏").put("startAt", "${today.plusDays(1)}T07:00:00+08:00")
            .put("durationMinutes", 45).put("distanceKm", 6.0).put("type", "easy").put("status", "planned")
            .put("activityIds", JSONArray()).put("steps", JSONArray()))
        val week = today.minusDays((today.dayOfWeek.value-1).toLong())
        val weekSessions = JSONArray()
        if (populated) weekSessions.put(JSONObject().put("id", "completed-session").put("title", "轻松跑").put("startAt", "${week}T07:00:00+08:00").put("status", "completed").put("durationMinutes", 35).put("distanceKm", 5.0))
        cache(repo, "/api/v1/health/workspace?from=$week&to=${week.plusDays(6)}", JSONObject(workspace.toString()).put("sessions",weekSessions))
        cache(repo, "/api/v1/training/sessions?from=$today&to=${today.plusDays(365)}&status=planned", JSONObject().put("items", sessions))
        cache(repo, "/api/v1/training/today", JSONObject().put("date", today.toString()).put("timezone", "Asia/Shanghai")
            .put("sessions", JSONArray()).put("pendingFeedback", JSONArray()).put("dataQuality", JSONArray()))
    }

    private fun workouts(populated: Boolean): JSONObject {
        val rows = JSONArray()
        if (populated) {
            rows.put(workout("fixture-run", "清晨轻松跑", "Run", 8.2, 2760, null))
            rows.put(workout("fixture-walk", "傍晚公园散步", "Walk", 4.2, 2400, null))
        }
        return JSONObject().put("records", rows).put("pendingDuplicates", 0)
            .put("summary", JSONObject().put("count", rows.length())
                .put("distanceKm", if (populated) 12.4 else JSONObject.NULL)
                .put("movingSeconds", if (populated) 5160 else JSONObject.NULL)
                .put("averageHeartRate", JSONObject.NULL))
            .put("pagination", JSONObject().put("page", 1).put("pages", 1).put("hasMore", false))
    }

    private fun workout(id: String, name: String, sport: String, km: Double, seconds: Int, heartRate: Int?): JSONObject =
        JSONObject().put("id", id).put("name", name).put("sportType", sport)
            .put("startAt", "${today.minusDays(1)}T07:10:00+08:00").put("distanceKm", km)
            .put("movingSeconds", seconds).put("durationMinutes", seconds / 60.0)
            .put("averageHeartRate", heartRate ?: JSONObject.NULL).put("duplicateCandidates", JSONArray())
            .put("sources", JSONArray().put(JSONObject().put("source", "import").put("id", id)))

    private suspend fun cache(repo: NativeRepository, path: String, data: JSONObject) =
        repo.cache.put(CachedResponse(repo.account, path, data.toString(), System.currentTimeMillis()))
}
