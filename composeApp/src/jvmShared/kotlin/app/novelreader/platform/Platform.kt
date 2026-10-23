package app.novelreader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.InputStream

/** 一本書的原始檔來源（匯入與重新轉碼時使用） */
interface BookSource : java.io.Closeable {
    /** 檔名（含副檔名） */
    val displayName: String
    val sizeBytes: Long
    /** desktop: 絕對路徑；android: content:// URI 字串 */
    val uriOrPath: String
    fun open(): InputStream
    /** 匯入結束（完成、失敗或取消）時呼叫，釋放暫存檔 */
    override fun close() {}
}

/** 同步資料夾抽象（desktop: java.io.File；android: SAF DocumentFile） */
interface SyncFolder {
    val displayPath: String
    /** 列出 _novelreader 子資料夾內的檔名 */
    fun list(): List<String>
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray): Boolean
}

data class AppFont(
    val id: String,
    val label: String,
)

/** 從資料夾掃出的一本書；folder 是第一層子資料夾名（根目錄下的書為 null），匯入時當標籤 */
data class FolderBook(val source: BookSource, val folder: String?)

interface Platform {
    val isDesktop: Boolean
    /** app 私有資料目錄（desktop: %APPDATA%\NovelReader；android: filesDir） */
    val appDataDir: File
    /** 開啟系統檔案選擇器選一本書；取消回傳 null */
    suspend fun pickBookFile(): BookSource?
    /** 選同步資料夾；取消回傳 null */
    suspend fun pickSyncFolder(): SyncFolder?
    /** 選一個資料夾並遞迴列出內含 txt/epub；取消或平台不支援回傳 null（目前僅桌面版） */
    suspend fun pickBookFolder(): List<FolderBook>? = null
    /** 選自動匯入的下載資料夾，回傳給 listScanFolder 用的 URI；取消或平台不支援回傳 null（目前僅 Android） */
    suspend fun pickScanFolder(): String? = null
    /** 遞迴列出下載資料夾內的 txt/epub；權限失效或讀不到回傳 null */
    fun listScanFolder(uriOrPath: String): List<BookSource>? = null
    /** 從先前存下的 uriOrPath 還原書籍來源（檔案已不存在/權限失效回傳 null） */
    fun resolveSource(uriOrPath: String): BookSource?
    fun resolveSyncFolder(uriOrPath: String): SyncFolder?
    /** 可選字體清單（只含 id 與顯示名稱） */
    fun listFonts(): List<AppFont>
    /** 依 id 解析字體；null 表示用預設字體。實作端應快取已建立的 FontFamily */
    fun resolveFontFamily(id: String?): FontFamily?
    fun keepScreenOn(on: Boolean) {}
    /** Android 音量鍵翻頁；其他平台忽略。 */
    fun setVolumeKeyHandler(handler: ((Boolean) -> Boolean)?) {}
    /** 桌面滑鼠右鍵選單；觸控平台維持 no-op。 */
    fun secondaryClickModifier(label: String, onClick: () -> Unit): Modifier = Modifier
    /** 語音朗讀引擎；null 表示該平台/裝置不支援 */
    val tts: app.novelreader.tts.TtsEngine? get() = null
    /** 壓縮檔匯入支援；null 表示該平台不支援（目前僅桌面版提供） */
    val archive: ArchiveSupport? get() = null
    /** 解碼封面圖片位元組（EPUB 封面），失敗回傳 null */
    fun decodeImage(bytes: ByteArray): ImageBitmap?
}
