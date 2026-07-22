package app.novelreader.platform

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class AndroidPlatform(
    private val activity: ComponentActivity,
    private val launchOpenDocument: () -> Unit,
    private val launchOpenTree: () -> Unit,
) : Platform {

    override val isDesktop = false

    override val appDataDir: File get() = activity.filesDir

    override val tts: app.novelreader.tts.TtsEngine by lazy {
        app.novelreader.tts.AndroidTtsEngine(activity.applicationContext)
    }

    private var docDeferred: CompletableDeferred<Uri?>? = null
    private var treeDeferred: CompletableDeferred<Uri?>? = null

    fun onDocumentPicked(uri: Uri?) {
        docDeferred?.complete(uri)
    }

    fun onTreePicked(uri: Uri?) {
        treeDeferred?.complete(uri)
    }

    override suspend fun pickBookFile(): BookSource? {
        val deferred = CompletableDeferred<Uri?>()
        docDeferred = deferred
        withContext(Dispatchers.Main) { launchOpenDocument() }
        val uri = deferred.await() ?: return null
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        return SafBookSource(activity, uri)
    }

    override suspend fun pickSyncFolder(): SyncFolder? {
        val deferred = CompletableDeferred<Uri?>()
        treeDeferred = deferred
        withContext(Dispatchers.Main) { launchOpenTree() }
        val uri = deferred.await() ?: return null
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        return SafSyncFolder(activity, uri)
    }

    override fun resolveSource(uriOrPath: String): BookSource? = try {
        val uri = Uri.parse(uriOrPath)
        SafBookSource(activity, uri).also { it.sizeBytes }
    } catch (_: Exception) {
        null
    }

    override fun resolveSyncFolder(uriOrPath: String): SyncFolder? = try {
        SafSyncFolder(activity, Uri.parse(uriOrPath)).also { it.list() }  // 驗證權限
    } catch (_: Exception) {
        null
    }

    override fun listFonts(): List<AppFont> = listOf(
        AppFont("sans", "系統黑體"),
        AppFont("serif", "系統明體（Serif）"),
        AppFont("mono", "等寬字體"),
    )

    override fun resolveFontFamily(id: String?): FontFamily? = when (id) {
        null, "sans" -> null // Compose 預設即系統字體
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> null
    }

    override fun keepScreenOn(on: Boolean) {
        activity.runOnUiThread {
            if (on) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

class SafBookSource(
    private val activity: ComponentActivity,
    private val uri: Uri,
) : BookSource {

    override val displayName: String by lazy {
        var name = "unknown.txt"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
        }
        name
    }

    override val sizeBytes: Long by lazy {
        var size = 0L
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) size = cursor.getLong(idx)
        }
        size
    }

    override val uriOrPath: String get() = uri.toString()

    override fun open(): InputStream =
        activity.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("無法開啟檔案（權限可能已失效）")
}

class SafSyncFolder(
    private val activity: ComponentActivity,
    private val treeUri: Uri,
) : SyncFolder {

    override val displayPath: String get() = treeUri.lastPathSegment ?: treeUri.toString()

    private fun dir(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(activity, treeUri) ?: return null
        val existing = root.findFile("_novelreader")
        return when {
            existing != null && existing.isDirectory -> existing
            existing == null -> root.createDirectory("_novelreader")
            else -> null
        }
    }

    override fun list(): List<String> =
        dir()?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name } ?: emptyList()

    override fun read(name: String): ByteArray? = try {
        dir()?.findFile(name)?.let { f ->
            activity.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        }
    } catch (_: Exception) {
        null
    }

    override fun write(name: String, bytes: ByteArray): Boolean = try {
        val d = dir() ?: return false
        val file = d.findFile(name) ?: d.createFile("application/json", name) ?: return false
        activity.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } != null
    } catch (_: Exception) {
        false
    }
}
