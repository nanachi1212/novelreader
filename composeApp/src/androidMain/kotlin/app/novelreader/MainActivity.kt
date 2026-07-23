package app.novelreader

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.novelreader.platform.AndroidPlatform
import app.novelreader.ui.App
import app.novelreader.ui.AppState
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_STOP_TTS = "app.novelreader.STOP_TTS"
    }

    private lateinit var platform: AndroidPlatform
    private lateinit var appState: AppState

    private val openDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            platform.onDocumentPicked(uri)
        }

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            platform.onTreePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(
            activity = this,
            launchOpenDocument = {
                openDocLauncher.launch(arrayOf("text/plain", "application/epub+zip"))
            },
            launchOpenTree = { openTreeLauncher.launch(null) },
        )
        appState = AppState(platform)
        setContent {
            App(appState)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 10)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_STOP_TTS) platform.tts.stop()
    }

    override fun onStop() {
        super.onStop()
        // 離開前台時立即保存閱讀進度（JSON 很小，同步寫入可接受）
        runBlocking { appState.flushNow() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (platform.handleVolumeKey(event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        platform.tts.shutdown()
        super.onDestroy()
    }
}
