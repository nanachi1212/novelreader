package app.novelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.novelreader.platform.AndroidPlatform
import app.novelreader.ui.App
import app.novelreader.ui.AppState
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

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
    }

    override fun onStop() {
        super.onStop()
        // 離開前台時立即保存閱讀進度（JSON 很小，同步寫入可接受）
        runBlocking { appState.flushNow() }
    }

    override fun onDestroy() {
        platform.tts.shutdown()
        super.onDestroy()
    }
}
