package app.novelreader.platform

import java.io.File

/** 壓縮檔內一個書檔（txt/epub）的資訊 */
data class ArchiveEntryInfo(
    /** 壓縮檔內路徑，統一 '/' 分隔（供 extract 與複合 URI 使用） */
    val entryPath: String,
    /** 最後一段檔名（已解碼，供 UI 顯示與書名用） */
    val displayName: String,
    /** 解壓後大小；未知為 -1 */
    val sizeBytes: Long,
)

/** 使用者可讀的壓縮檔錯誤（加密 / RAR5 / 損壞…），UI 直接顯示 message */
class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 壓縮檔匯入支援；實作僅存在於支援的平台（目前只有桌面） */
interface ArchiveSupport {
    /** 副檔名判斷：.zip / .rar / .7z（不分大小寫） */
    fun isArchivePath(path: String): Boolean

    /** 列出所有 .txt/.epub entry（跳過目錄、__MACOSX、'.' 開頭、0-byte）。失敗擲 [ArchiveException] */
    fun listBookEntries(archiveFile: File): List<ArchiveEntryInfo>

    /** 解壓單一 entry 到 destFile，回傳實際寫出的 bytes。失敗擲 [ArchiveException] */
    fun extractEntry(archiveFile: File, entryPath: String, destFile: File): Long
}

/**
 * 從壓縮檔匯入的書其 sourceUri 用複合格式：`archive://{壓縮檔絕對路徑}!/{entry路徑}`，
 * 讓「換編碼重新匯入」能經 resolveSource 重新解壓原 entry。
 * 自行解析、不經 java.net.URI（Windows 路徑含反斜線與中文，不做 percent-encoding）。
 */
object ArchiveUri {
    private const val SCHEME = "archive://"
    private val archiveExts = listOf(".zip", ".rar", ".7z")

    fun build(archivePath: String, entryPath: String): String = "$SCHEME$archivePath!/$entryPath"

    fun isArchiveUri(uriOrPath: String): Boolean = uriOrPath.startsWith(SCHEME)

    /** 回傳 (壓縮檔路徑, entry 路徑)；非 archive URI 或格式不符回 null */
    fun parse(uriOrPath: String): Pair<String, String>? {
        if (!isArchiveUri(uriOrPath)) return null
        val body = uriOrPath.removePrefix(SCHEME)
        // 路徑本身可能含 "!"：掃每個 "!/"，取第一個前綴以壓縮檔副檔名結尾者為分隔點
        var idx = body.indexOf("!/")
        while (idx >= 0) {
            val prefix = body.substring(0, idx)
            if (archiveExts.any { prefix.endsWith(it, ignoreCase = true) }) {
                val entry = body.substring(idx + 2)
                if (entry.isNotBlank()) return prefix to entry
            }
            idx = body.indexOf("!/", idx + 1)
        }
        return null
    }
}
