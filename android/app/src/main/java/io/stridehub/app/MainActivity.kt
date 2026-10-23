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
import io.stridehub.app.nativeui.StrideHubApp
import io.stridehub.app.scale.WeighingActivity

/** All application business screens use native Compose. BLE remains in the existing native activity. */
class MainActivity : ComponentActivity() {
    private var awaitingWeighing by mutableStateOf(false)
    private val weighingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingWeighing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WeighingShortcut.register(this)
        awaitingWeighing = savedInstanceState?.getBoolean(AWAITING_WEIGHING) ?: false
        if (savedInstanceState == null) dispatchWeighingShortcut(intent)
        setContent {
            if (awaitingWeighing) {
                // The offline launcher action never starts health requests behind the weighing page.
                MaterialTheme { Surface { Text("正在打开本机称重…") } }
            } else {
                StrideHubApp()
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
        super.onSaveInstanceState(outState)
    }

    private fun dispatchWeighingShortcut(incoming: Intent) {
        if (incoming.action != WeighingShortcut.ACTION && incoming.action != OPEN_WEIGHING) return
        incoming.action = Intent.ACTION_MAIN
        if (awaitingWeighing) return
        awaitingWeighing = true
        weighingLauncher.launch(Intent(this, WeighingActivity::class.java))
    }

    private companion object {
        const val AWAITING_WEIGHING = "awaitingWeighing"
        const val OPEN_WEIGHING = "io.stridehub.app.OPEN_WEIGHING"
    }
}
