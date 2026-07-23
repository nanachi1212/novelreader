package app.novelreader.core.model

import kotlinx.serialization.Serializable

enum class BookFormat { TXT, EPUB }

enum class AppTheme { SYSTEM, LIGHT, DARK, SEPIA }

enum class ReadingMode { CONTINUOUS, PAGED }

enum class ReadingDirection { VERTICAL, HORIZONTAL }

@Serializable
data class BookMeta(
    val fingerprint: String,
    val title: String,
    val fileName: String,
    val sourceUri: String,
    val format: BookFormat = BookFormat.TXT,
    /** 偵測或手動指定的原始編碼名稱，例如 "Big5" */
    val charset: String = "UTF-8",
    val fileSize: Long = 0,
    val importedAt: Long = 0,
    val chapterCount: Int = 0,
    /** 轉碼後 content.txt 的總位元組數（計算閱讀百分比用） */
    val totalBytes: Long = 0,
    val coverPath: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ChapterIndexEntry(
    val index: Int,
    val title: String,
    /** 在 content.txt（UTF-8）中的位元組範圍 */
    val byteStart: Long,
    val byteEnd: Long,
)

@Serializable
data class ReadingProgress(
    val fingerprint: String,
    val chapterIndex: Int = 0,
    /** 章內第一個可見段落索引 */
    val paragraphIndex: Int = 0,
    val percent: Float = 0f,
    val updatedAt: Long = 0,
    val deviceId: String = "",
)

@Serializable
data class Bookmark(
    val id: String,
    val fingerprint: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val excerpt: String,
    val createdAt: Long,
    val deviceId: String = "",
    /** 刪除標記（tombstone），同步合併用 */
    val deleted: Boolean = false,
)

@Serializable
data class ReaderSettings(
    val fontId: String? = null,
    val fontSizeSp: Float = 19f,
    val lineHeightMult: Float = 1.7f,
    val marginHorizontalDp: Int = 24,
    val marginVerticalDp: Int = 32,
    val theme: AppTheme = AppTheme.SYSTEM,
    val readingMode: ReadingMode = ReadingMode.CONTINUOUS,
    val readingDirection: ReadingDirection = ReadingDirection.VERTICAL,
    val s2tEnabled: Boolean = false,
    // 朗讀（TTS）
    val ttsRate: Float = 1f,
    val ttsVoiceId: String? = null,
    /** Android 九宮格觸控：0 關閉、1 前頁、2 後頁。由左上到右下排列。 */
    val touchPageZones: List<Int> = List(9) { 0 },
    val syncFolderUri: String? = null,
    val deviceId: String = "",
    // desktop 視窗狀態
    val windowWidth: Int = 1000,
    val windowHeight: Int = 760,
    val windowX: Int = Int.MIN_VALUE,
    val windowY: Int = Int.MIN_VALUE,
)

@Serializable
data class Library(
    val books: List<BookMeta> = emptyList(),
)

/** 每本書一份的進度+書籤紀錄；本機 book.json 與同步 sidecar 都用這個格式 */
@Serializable
data class SyncRecord(
    val schemaVersion: Int = 1,
    val progress: ReadingProgress,
    val bookmarks: List<Bookmark> = emptyList(),
)
