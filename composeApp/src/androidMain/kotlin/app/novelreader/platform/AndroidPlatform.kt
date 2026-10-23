package app.novelreader.platform

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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
    /** 參數是選擇器一開始顯示的資料夾（只是建議，null 用系統預設） */
    private val launchOpenTree: (Uri?) -> Unit,
) : Platform {

    private var volumeKeyHandler: ((Boolean) -> Boolean)? = null

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

    private suspend fun pickTree(initial: Uri?, flags: Int): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        treeDeferred = deferred
        withContext(Dispatchers.Main) { launchOpenTree(initial) }
        val uri = deferred.await() ?: return null
        try {
            activity.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }
        return uri
    }

    override suspend fun pickSyncFolder(): SyncFolder? =
        pickTree(null, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ?.let { SafSyncFolder(activity, it) }

    override suspend fun pickScanFolder(): String? =
        pickTree(DOWNLOADER_NOVELS, Intent.FLAG_GRANT_READ_URI_PERMISSION)?.toString()

    /**
     * 一個資料夾查一次（DocumentFile 每個檔案還要各查一次，截圖資料夾一堆 PNG 時很慢）。
     * 「.」開頭的略過：下載中的檔案在磁碟上叫 .pending-*，還沒寫完。
     */
    override fun listScanFolder(uriOrPath: String): List<BookSource>? = try {
        val tree = Uri.parse(uriOrPath)
        val books = mutableListOf<BookSource>()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        fun walk(docId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = activity.contentResolver.query(children, columns, null, null, null)
                ?: throw java.io.IOException("無法讀取資料夾")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    when {
                        name.startsWith(".") -> {}
                        c.getString(2) == Document.MIME_TYPE_DIR -> walk(id)
                        name.endsWith(".txt", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true) ->
                            books += SafBookSource(activity, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        books
    } catch (_: Exception) {
        null
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

    override fun setVolumeKeyHandler(handler: ((Boolean) -> Boolean)?) {
        volumeKeyHandler = handler
    }

    fun handleVolumeKey(keyCode: Int, action: Int): Boolean {
        if (action != android.view.KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> volumeKeyHandler?.invoke(false) == true
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> volumeKeyHandler?.invoke(true) == true
            else -> false
        }
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** 萬用下載器存小說的地方（下載/萬用下載器/小說），選資料夾時先開在這裡 */
        val DOWNLOADER_NOVELS: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/萬用下載器/小說"
        )
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
