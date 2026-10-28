package io.stridehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import io.stridehub.app.nativeui.AppModel
import io.stridehub.app.nativeui.StrideHubApp
import io.stridehub.app.nativeui.WeighingShell
import io.stridehub.app.scale.WeighingActivity

/** All application business screens use native Compose. BLE remains in the existing native activity. */
class MainActivity : ComponentActivity() {
    private var awaitingWeighing by mutableStateOf(false)
    private var weighingOpen = false
    private val model by lazy { ViewModelProvider(this)[AppModel::class.java] }
    private val weighingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.getIntExtra(WeighingShell.TARGET_TAB, -1) ?: -1
        if (result.resultCode == RESULT_OK && target in 0..3) model.select(target)
        model.refresh()
        weighingOpen = false
        awaitingWeighing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WeighingShortcut.register(this)
        awaitingWeighing = savedInstanceState?.getBoolean(AWAITING_WEIGHING) ?: false
        weighingOpen = savedInstanceState?.getBoolean(WEIGHING_OPEN) ?: false
        if (savedInstanceState == null) dispatchWeighingShortcut(intent, coldStart = true)
        setContent {
            if (awaitingWeighing) {
                // The offline launcher action never starts health requests behind the weighing page.
                MaterialTheme { Surface { Text("正在打开本机称重…") } }
            } else {
                StrideHubApp(vm = model, openWeighing = ::openWeighing)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchWeighingShortcut(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AWAITING_WEIGHING, awaitingWeighing)
        outState.putBoolean(WEIGHING_OPEN, weighingOpen)
        super.onSaveInstanceState(outState)
    }

    private fun dispatchWeighingShortcut(incoming: Intent, coldStart: Boolean = false) {
        if (incoming.action != WeighingShortcut.ACTION && incoming.action != OPEN_WEIGHING) return
        incoming.action = Intent.ACTION_MAIN
        if (weighingOpen) return
        // Only a cold shortcut suppresses the health UI. Normal tab switches keep its drafts alive.
        if (coldStart) awaitingWeighing = true
        openWeighing()
    }

    fun openWeighing() {
        if (weighingOpen) return
        weighingOpen = true
        weighingLauncher.launch(Intent(this, WeighingActivity::class.java))
    }

    private companion object {
        const val AWAITING_WEIGHING = "awaitingWeighing"
        const val WEIGHING_OPEN = "weighingOpen"
        const val OPEN_WEIGHING = "io.stridehub.app.OPEN_WEIGHING"
    }
}
