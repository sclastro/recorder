package com.sclastro.recorder

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.ui.RecorderAppRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RecorderAppRoot() }

        // Keep the screen awake while capturing, if the user asked for that.
        lifecycleScope.launch {
            combine(
                container.engine.state,
                container.settings.settings,
            ) { engine, settings ->
                settings.keepScreenOn && engine.status != RecorderEngine.Status.IDLE
            }.collect { keepOn ->
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}
