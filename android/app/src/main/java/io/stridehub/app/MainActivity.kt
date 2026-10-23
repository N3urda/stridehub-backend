package io.stridehub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.stridehub.app.nativeui.StrideHubApp

/** All application business screens use native Compose. BLE remains in the existing native activity. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StrideHubApp() }
    }
}
