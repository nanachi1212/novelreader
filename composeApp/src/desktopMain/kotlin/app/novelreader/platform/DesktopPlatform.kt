package app.novelreader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FontStyle
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFileChooser

object DesktopPlatform : Platform {
    override val isDesktop = true

    /** 沒有繁中字形的 Skia 預設字體會出豆腐字，Windows 上固定退到微軟正黑體 */
    private const val DEFAULT_CJK_FONT = "Microsoft JhengHei"

    // 常見中文字體排在清單最前面
    private val preferredFonts = listOf(
        "Microsoft JhengHei", "Microsoft JhengHei UI", "微軟正黑體",
        "PMingLiU", "MingLiU", "新細明體",
        "DFKai-SB", "標楷體", "KaiTi", "楷体",
        "Noto Sans TC", "Noto Serif TC", "思源黑體", "思源宋體",
        "Microsoft YaHei", "SimSun", "SimHei",
        "Yu Gothic UI", "Meiryo",
    )

    private val familyCache = ConcurrentHashMap<String, FontFamily>()

    override val appDataDir: File by lazy {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(base, "NovelReader").apply { mkdirs() }
    }

    /** SAPI 只在 Windows 可用；其他平台回 null（UI 會隱藏朗讀入口） */
    override val tts: app.novelreader.tts.TtsEngine? by lazy {
        if (System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)) {
            app.novelreader.tts.OneCoreTtsEngine()
        } else null
    }

    override val archive: ArchiveSupport get() = DesktopArchiveSupport

    @OptIn(ExperimentalComposeUiApi::class)
    override fun secondaryClickModifier(onClick: () -> Unit): Modifier = Modifier.pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                    onClick()
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

    override suspend fun pickBookFile(): BookSource? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, "選擇書籍或壓縮檔（txt / epub / zip / rar / 7z）", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            name.endsWith(".txt", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true) ||
                DesktopArchiveSupport.isArchivePath(name)
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) return@withContext null
        val file = File(dir, name)
        if (file.isFile) FileBookSource(file) else null
    }

    override suspend fun pickSyncFolder(): SyncFolder? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "選擇同步資料夾（小說所在的 Google Drive 或 Autosync 資料夾）"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.let { DesktopSyncFolder(it) }
        } else null
    }

    override fun resolveSource(uriOrPath: String): BookSource? {
        ArchiveUri.parse(uriOrPath)?.let { (archivePath, entryPath) ->
            val af = File(archivePath)
            return if (af.isFile) {
                ArchiveEntryBookSource(af, entryPath, entryPath.substringAfterLast('/'), File(appDataDir, "tmp"))
            } else null // 壓縮檔已被移走 → 走既有「找不到原始檔案」訊息
        }
        val f = File(uriOrPath)
        return if (f.isFile) FileBookSource(f) else null
    }

    override fun resolveSyncFolder(uriOrPath: String): SyncFolder? {
        val f = File(uriOrPath)
        return if (f.isDirectory && f.exists()) DesktopSyncFolder(f) else null
    }

    override fun listFonts(): List<AppFont> {
        val all = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames(Locale.TRADITIONAL_CHINESE)
            .toList()
        val preferred = preferredFonts.filter { p -> all.any { it.equals(p, ignoreCase = true) } }
        val rest = all.filterNot { name -> preferred.any { it.equals(name, ignoreCase = true) } }.sorted()
        return (preferred + rest).map { AppFont(id = it, label = it) }
    }

    override fun resolveFontFamily(id: String?): FontFamily? {
        val name = id ?: DEFAULT_CJK_FONT
        return familyCache.getOrPut(name) {
            try {
                val skTypeface = org.jetbrains.skia.FontMgr.default
                    .matchFamilyStyle(name, FontStyle.NORMAL)
                if (skTypeface != null) FontFamily(Typeface(skTypeface)) else FontFamily.Default
            } catch (_: Exception) {
                FontFamily.Default
            }
        }
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

class FileBookSource(private val file: File) : BookSource {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override val uriOrPath: String get() = file.absolutePath
    override fun open(): InputStream = file.inputStream()
}

class DesktopSyncFolder(private val root: File) : SyncFolder {
    private val dir: File get() = File(root, "_novelreader").apply { mkdirs() }
    override val displayPath: String get() = root.absolutePath

    override fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    override fun read(name: String): ByteArray? {
        val f = File(dir, name)
        return try {
            if (f.isFile) f.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    override fun write(name: String, bytes: ByteArray): Boolean = try {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        val target = File(dir, name)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    } catch (_: Exception) {
        false
    }
}
