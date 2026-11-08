package io.stridehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import io.stridehub.app.nativeui.AppModel
import io.stridehub.app.nativeui.StrideHubApp
import io.stridehub.app.nativeui.WEIGHING_TAB
import io.stridehub.app.scale.WeighingActivity

/** One activity and one navigation shell, including the offline-capable BLE tab. */
class MainActivity : WeighingActivity() {
    private val model by lazy { ViewModelProvider(this)[AppModel::class.java] }
    override fun embeddedWeighing() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WeighingShortcut.register(this)
        dispatchWeighingShortcut(intent)
        setContent {
            StrideHubApp(vm = model, openWeighing = ::openWeighing,
                weighingContent = weighingView(), onWeighingVisible = ::setWeighingVisible)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchWeighingShortcut(intent)
    }
    private fun dispatchWeighingShortcut(incoming: Intent) {
        if (incoming.action != WeighingShortcut.ACTION && incoming.action != "io.stridehub.app.OPEN_WEIGHING") return
        incoming.action = Intent.ACTION_MAIN
        openWeighing()
    }
    fun openWeighing() { model.select(WEIGHING_TAB) }
}
