package io.stridehub.app.nativeui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stridehub.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSystemBarsTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun forcedAppThemesControlReadableSystemBarIcons() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("native-ui",0)
        val original=preferences.getString("theme",null)
        try {
            for((selected,dark) in listOf("dark" to true,"light" to false)) {
                preferences.edit().putString("theme",selected).commit()
                ActivityScenario.launch(MainActivity::class.java).use {scenario->
                    compose.waitForIdle()
                    scenario.onActivity {activity->
                        val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
                        assertEquals("Status icons follow the explicit app appearance",!dark,controller.isAppearanceLightStatusBars)
                        assertEquals("Navigation icons follow the explicit app appearance",!dark,controller.isAppearanceLightNavigationBars)
                        if(android.os.Build.VERSION.SDK_INT>=35) {
                            assertEquals(android.graphics.Color.TRANSPARENT,activity.window.statusBarColor)
                            assertEquals(android.graphics.Color.TRANSPARENT,activity.window.navigationBarColor)
                        }
                    }
                }
            }
        } finally {
            preferences.edit().apply {if(original==null) remove("theme") else putString("theme",original)}.commit()
        }
    }
}
