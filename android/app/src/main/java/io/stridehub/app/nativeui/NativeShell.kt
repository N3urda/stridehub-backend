@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.stridehub.app.nativeui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Keep the existing saved tab IDs stable; weighing is displayed in the middle.
const val WEIGHING_TAB = 4
val LocalOpenWeighing = staticCompositionLocalOf<() -> Unit> { error("Missing weighing navigation") }

object NativeAppearance {
    fun colors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
        primary=Color(0xFF7CD7C8), onPrimary=Color(0xFF00382F),
        primaryContainer=Color(0xFF173E37), onPrimaryContainer=Color(0xFFD2F5EA),
        secondary=Color(0xFFA9C5E4), onSecondary=Color(0xFF15324C),
        secondaryContainer=Color(0xFF233C48), onSecondaryContainer=Color(0xFFD9EBF7),
        tertiary=Color(0xFFE4B58F), onTertiary=Color(0xFF432B18),
        background=Color(0xFF101A1D), onBackground=Color(0xFFE5F0EF),
        surface=Color(0xFF19272B), onSurface=Color(0xFFE5F0EF),
        surfaceVariant=Color(0xFF26383D), onSurfaceVariant=Color(0xFFAFBFBE),
        surfaceContainerLowest=Color(0xFF101A1D), surfaceContainerLow=Color(0xFF19272B),
        surfaceContainer=Color(0xFF1E2E32), surfaceContainerHigh=Color(0xFF23343A),
        surfaceContainerHighest=Color(0xFF293C41),
        outline=Color(0xFF788E8C), outlineVariant=Color(0xFF33474A)
    ) else lightColorScheme(
        primary=Color(0xFF006D60), onPrimary=Color.White,
        primaryContainer=Color(0xFFDDF3ED), onPrimaryContainer=Color(0xFF123D35),
        secondary=Color(0xFF466784), onSecondary=Color.White,
        secondaryContainer=Color(0xFFE4EEF5), onSecondaryContainer=Color(0xFF233D52),
        tertiary=Color(0xFF875735), onTertiary=Color.White,
        background=Color(0xFFF3F7F6), onBackground=Color(0xFF182F35),
        surface=Color.White, onSurface=Color(0xFF182F35),
        surfaceVariant=Color(0xFFE9F0EE), onSurfaceVariant=Color(0xFF52676B),
        surfaceContainerLowest=Color.White, surfaceContainerLow=Color.White,
        surfaceContainer=Color(0xFFF7FAF9), surfaceContainerHigh=Color(0xFFF0F5F3),
        surfaceContainerHighest=Color(0xFFEAF1EF),
        outline=Color(0xFF7B8F8F), outlineVariant=Color(0xFFD9E4E1)
    )

    val typography = Typography().run { copy(
        displaySmall=displaySmall.copy(fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.SemiBold),
        headlineLarge=headlineLarge.copy(fontSize=30.sp,lineHeight=39.sp,fontWeight=FontWeight.Bold),
        headlineMedium=headlineMedium.copy(fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold),
        headlineSmall=headlineSmall.copy(fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold),
        titleLarge=titleLarge.copy(fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=titleMedium.copy(fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=bodyLarge.copy(fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=bodyMedium.copy(fontSize=14.sp,lineHeight=22.sp),
        bodySmall=bodySmall.copy(fontSize=12.sp,lineHeight=19.sp),
        labelLarge=labelLarge.copy(fontSize=13.sp,lineHeight=20.sp),
        labelMedium=labelMedium.copy(fontSize=12.sp,lineHeight=18.sp),
        labelSmall=labelSmall.copy(fontSize=11.sp,lineHeight=16.sp)
    ) }
    val shapes = Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),
        medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp),extraLarge=RoundedCornerShape(28.dp))

    /** The retained BLE views use the same semantic colors as the Compose pages. */
    @JvmStatic fun weighingColors(dark: Boolean): IntArray = colors(dark).run {
        intArrayOf(background.toArgb(), onSurface.toArgb(), onSurfaceVariant.toArgb(),
            primary.toArgb(), primaryContainer.toArgb(), outlineVariant.toArgb(),
            surfaceContainerLow.toArgb(), onPrimary.toArgb())
    }
}

@Composable fun StrideHubTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val activity = remember(context) { context.hostActivity() }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = NativeAppearance.colors(dark), typography=NativeAppearance.typography, shapes=NativeAppearance.shapes, content = content)
}

@Composable fun StrideHubNavigationBar(selectedTab: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    val destinations = listOf(
        Triple(0, "总览", Icons.Default.Dashboard),
        Triple(1, "身体", Icons.Default.Favorite),
        Triple(WEIGHING_TAB, "称重", Icons.Default.MonitorWeight),
        Triple(2, "运动", Icons.Default.DirectionsRun),
        Triple(3, "计划", Icons.Default.CalendarMonth)
    )
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
        destinations.forEach { (id, label, icon) ->
            NavigationBarItem(
                selected = selectedTab == id, onClick = { onSelect(id) }, enabled = enabled,
                icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary,indicatorColor=MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.testTag("main-tab-$id")
            )
        }
    }
}

/** Shared app chrome; the activity still owns BLE, permissions, dialogs and local storage. */
object WeighingShell {
    const val TARGET_TAB = "io.stridehub.app.TARGET_TAB"

    @JvmStatic fun install(activity: ComponentActivity, content: View) {
        activity.enableEdgeToEdge()
        activity.setContent {
            val theme = activity.getSharedPreferences("native-ui", Context.MODE_PRIVATE)
                .getString("theme", "system") ?: "system"
            StrideHubTheme(theme) {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("称重") }, navigationIcon = {
                            IconButton(onClick = { activity.finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        })
                    },
                    bottomBar = {
                        StrideHubNavigationBar(WEIGHING_TAB, { tab ->
                            if (tab != WEIGHING_TAB) {
                                activity.setResult(Activity.RESULT_OK, Intent().putExtra(TARGET_TAB, tab))
                                activity.finish()
                            }
                        })
                    }
                ) { padding ->
                    AndroidView(factory = { content }, modifier = Modifier.fillMaxSize()
                        .padding(padding).consumeWindowInsets(padding))
                }
            }
        }
    }
}

private fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}
