package app.novelreader

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import app.novelreader.platform.AndroidPlatform
import app.novelreader.platform.SafBookSource
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
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 10)
        }
        // 系統重建畫面時 intent 還是原本那個，不要再匯入一次
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 別的 App（例如萬用下載器）用「開啟」或「分享」傳來 TXT／EPUB：走既有匯入並直接開書 */
    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        appState.importExternal(SafBookSource(this, uri))
    }

    override fun onStop() {
        super.onStop()
        // 離開前台時立即保存閱讀進度（JSON 很小，同步寫入可接受）
        runBlocking { appState.flushNow() }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        platform.handleVolumeKey(keyCode, event.action) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        platform.handleVolumeKey(keyCode, event.action) || super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        platform.tts.shutdown()
        super.onDestroy()
    }
}
