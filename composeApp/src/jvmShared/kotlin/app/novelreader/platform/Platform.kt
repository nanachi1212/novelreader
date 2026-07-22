package app.novelreader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.InputStream

/** 一本書的原始檔來源（匯入與重新轉碼時使用） */
interface BookSource {
    /** 檔名（含副檔名） */
    val displayName: String
    val sizeBytes: Long
    /** desktop: 絕對路徑；android: content:// URI 字串 */
    val uriOrPath: String
    fun open(): InputStream
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

interface Platform {
    val isDesktop: Boolean
    /** app 私有資料目錄（desktop: %APPDATA%\NovelReader；android: filesDir） */
    val appDataDir: File
    /** 開啟系統檔案選擇器選一本書；取消回傳 null */
    suspend fun pickBookFile(): BookSource?
    /** 選同步資料夾；取消回傳 null */
    suspend fun pickSyncFolder(): SyncFolder?
    /** 從先前存下的 uriOrPath 還原書籍來源（檔案已不存在/權限失效回傳 null） */
    fun resolveSource(uriOrPath: String): BookSource?
    fun resolveSyncFolder(uriOrPath: String): SyncFolder?
    /** 可選字體清單（只含 id 與顯示名稱） */
    fun listFonts(): List<AppFont>
    /** 依 id 解析字體；null 表示用預設字體。實作端應快取已建立的 FontFamily */
    fun resolveFontFamily(id: String?): FontFamily?
    fun keepScreenOn(on: Boolean) {}
    /** 桌面滑鼠右鍵選單；觸控平台只顯示內容。 */
    @Composable
    fun SecondaryClickArea(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
        content()
    }
    /** 語音朗讀引擎；null 表示該平台/裝置不支援 */
    val tts: app.novelreader.tts.TtsEngine? get() = null
    /** 壓縮檔匯入支援；null 表示該平台不支援（目前僅桌面版提供） */
    val archive: ArchiveSupport? get() = null
    /** 解碼封面圖片位元組（EPUB 封面），失敗回傳 null */
    fun decodeImage(bytes: ByteArray): ImageBitmap?
}
