package app.novelreader.data

import app.novelreader.core.model.Bookmark
import app.novelreader.core.model.ChapterIndexEntry
import app.novelreader.core.model.Library
import app.novelreader.core.model.ReaderSettings
import app.novelreader.core.model.ReadingProgress
import app.novelreader.core.model.SyncRecord
import app.novelreader.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 本機持久化：全部是小 JSON 檔 + 原子寫入（tmp → rename）。
 * 佈局：
 *   <appData>/library.json、settings.json
 *   <appData>/books/<指紋>/content.txt + chapters.json + book.json
 */
class AppStores(private val platform: Platform) {

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val root: File get() = platform.appDataDir

    fun booksDir(fingerprint: String): File = File(root, "books${File.separator}$fingerprint")
    fun contentFile(fingerprint: String): File = File(booksDir(fingerprint), "content.txt")
    fun coverFile(fingerprint: String): File = File(booksDir(fingerprint), "cover.jpg")
    private fun chaptersFile(fingerprint: String): File = File(booksDir(fingerprint), "chapters.json")
    private fun bookDataFile(fingerprint: String): File = File(booksDir(fingerprint), "book.json")
    private val libraryFile: File get() = File(root, "library.json")
    private val settingsFile: File get() = File(root, "settings.json")

    // ---- library ----

    suspend fun loadLibrary(): Library = withContext(Dispatchers.IO) {
        mutex.withLock { readJson(libraryFile) ?: Library() }
    }

    suspend fun saveLibrary(library: Library) = withContext(Dispatchers.IO) {
        mutex.withLock { writeJson(libraryFile, library) }
    }

    /**
     * 修復書庫與磁碟內容不一致的狀態。常見原因是匯入途中斷電或程序被終止。
     * 只移除缺少必要內容的紀錄與孤立資料夾，不碰仍可開啟的書。
     */
    suspend fun repairLibrary(): Library = withContext(Dispatchers.IO) {
        mutex.withLock {
            val library = readJson<Library>(libraryFile) ?: Library()
            val valid = library.books.filter { meta ->
                contentFile(meta.fingerprint).isFile && chaptersFile(meta.fingerprint).isFile
            }
            val validIds = valid.mapTo(HashSet()) { it.fingerprint }
            File(root, "books").listFiles()?.filter { it.isDirectory && it.name !in validIds }?.forEach {
                it.deleteRecursively()
            }
            if (valid.size != library.books.size) writeJson(libraryFile, Library(valid))
            Library(valid)
        }
    }

    // ---- settings ----

    suspend fun loadSettings(): ReaderSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = readJson<ReaderSettings>(settingsFile)
            if (loaded == null || loaded.deviceId.isBlank()) {
                val fresh = (loaded ?: ReaderSettings()).copy(deviceId = UUID.randomUUID().toString())
                writeJson(settingsFile, fresh)
                fresh
            } else loaded
        }
    }

    suspend fun saveSettings(settings: ReaderSettings) = withContext(Dispatchers.IO) {
        mutex.withLock { writeJson(settingsFile, settings) }
    }

    // ---- 章節索引 ----

    suspend fun loadChapters(fingerprint: String): List<ChapterIndexEntry>? =
        withContext(Dispatchers.IO) { readJson(chaptersFile(fingerprint)) }

    suspend fun saveChapters(fingerprint: String, chapters: List<ChapterIndexEntry>) =
        withContext(Dispatchers.IO) { writeJson(chaptersFile(fingerprint), chapters) }

    // ---- 每書進度 + 書籤 ----

    suspend fun loadBookData(fingerprint: String): SyncRecord? =
        withContext(Dispatchers.IO) { readJson(bookDataFile(fingerprint)) }

    suspend fun saveBookData(fingerprint: String, record: SyncRecord) =
        withContext(Dispatchers.IO) { writeJson(bookDataFile(fingerprint), record) }

    fun defaultBookData(fingerprint: String): SyncRecord =
        SyncRecord(progress = ReadingProgress(fingerprint = fingerprint))

    suspend fun deleteBook(fingerprint: String) = withContext(Dispatchers.IO) {
        mutex.withLock { booksDir(fingerprint).deleteRecursively() }
    }

    fun saveCover(fingerprint: String, bytes: ByteArray) {
        val f = coverFile(fingerprint)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    /** 匯入用暫存檔（如 EPUB 需要 ZipFile 隨機存取，SAF/串流來源需先落地）；呼叫端用完需自行刪除 */
    fun newTempFile(suffix: String): File {
        val dir = File(root, "tmp").apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}$suffix")
    }

    /** 清掉 tmp 下超過 24 小時的殘留（deleteOnExit 蓋不到的異常結束情況），啟動時呼叫 */
    fun cleanupTempFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        File(root, "tmp").listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }

    // ---- helpers ----

    private inline fun <reified T> readJson(file: File): T? {
        decodeJson<T>(file)?.let { return it }
        val backup = File(file.parentFile, file.name + ".bak")
        return decodeJson<T>(backup)?.also { recovered ->
            val tmp = File(file.parentFile, file.name + ".recovered.tmp")
            tmp.writeText(json.encodeToString(recovered), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private inline fun <reified T> decodeJson(file: File): T? =
        if (!file.isFile) null else try {
            json.decodeFromString<T>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }

    private inline fun <reified T> writeJson(file: File, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
        val backup = File(file.parentFile, file.name + ".bak")
        if (file.isFile) java.nio.file.Files.copy(
            file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        try {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

/** 合併規則：進度取較新；書籤按 id 聯集、同 id 取較新（tombstone 優先於較舊的存活版） */
fun mergeSyncRecords(a: SyncRecord, b: SyncRecord): SyncRecord {
    val progress = if (a.progress.updatedAt >= b.progress.updatedAt) a.progress else b.progress
    val byId = LinkedHashMap<String, Bookmark>()
    for (bm in a.bookmarks + b.bookmarks) {
        val existing = byId[bm.id]
        if (existing == null || bm.createdAt > existing.createdAt ||
            (bm.createdAt == existing.createdAt && bm.deleted && !existing.deleted)
        ) {
            byId[bm.id] = bm
        }
    }
    // 90 天前的 tombstone 清掉
    val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
    val bookmarks = byId.values.filterNot { it.deleted && it.createdAt < cutoff }
    return SyncRecord(progress = progress, bookmarks = bookmarks)
}
