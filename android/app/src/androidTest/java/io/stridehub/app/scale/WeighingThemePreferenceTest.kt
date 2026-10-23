package io.stridehub.app.scale

import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeighingThemePreferenceTest {
    @Test fun explicitThemeOverridesSystemAndDrivesPaletteAndBars() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("native-ui",0)
        val previous=preferences.getString("theme",null)
        try {
            for((selected,dark) in listOf("dark" to true,"light" to false)) {
                preferences.edit().putString("theme",selected).commit()
                val opposite=Configuration(context.resources.configuration).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
                assertEquals("Explicit app theme overrides the opposite system",dark,WeighingActivity.resolveDarkTheme(context.createConfigurationContext(opposite)))
                ActivityScenario.launch(WeighingActivity::class.java).use {scenario->scenario.onActivity {activity->
                    val paper=WeighingActivity::class.java.getDeclaredField("PAPER").apply {isAccessible=true}.getInt(activity)
                    assertEquals(if(dark) 0xff101822.toInt() else 0xfff2f5f9.toInt(),paper)
                    val shell=WeighingActivity::class.java.getDeclaredField("shell").apply {isAccessible=true}.get(activity) as android.view.View
                    assertEquals("Real content background follows the selected palette",paper,(shell.background as android.graphics.drawable.ColorDrawable).color)
                    if(android.os.Build.VERSION.SDK_INT>=35) {
                        assertEquals("Edge-to-edge system status bar is transparent",android.graphics.Color.TRANSPARENT,activity.window.statusBarColor)
                        assertEquals("Edge-to-edge navigation bar is transparent",android.graphics.Color.TRANSPARENT,activity.window.navigationBarColor)
                    } else {
                        assertEquals(paper,activity.window.statusBarColor)
                        assertEquals(paper,activity.window.navigationBarColor)
                    }
                    val lightIcons=activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    assertEquals(if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,lightIcons)
                }}
            }
            preferences.edit().putString("theme","system").commit()
            val darkSystem=Configuration(context.resources.configuration).apply {uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
            assertTrue(WeighingActivity.resolveDarkTheme(context.createConfigurationContext(darkSystem)))
        } finally {
            preferences.edit().apply {if(previous==null) remove("theme") else putString("theme",previous)}.commit()
        }
    }
}
