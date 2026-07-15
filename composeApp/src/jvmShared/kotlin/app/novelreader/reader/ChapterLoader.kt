package app.novelreader.reader

import app.novelreader.core.model.ChapterIndexEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 章節載入：seek 到 byteStart 只讀該章，LRU 快取 3 章。
 * 全書永不進記憶體 —— 這是大檔穩定的關鍵。
 */
class ChapterLoader(
    private val contentFile: File,
    val chapters: List<ChapterIndexEntry>,
) {
    data class Chapter(
        val index: Int,
        val title: String,
        val paragraphs: List<String>,
    )

    private companion object {
        const val CACHE_SIZE = 3
        const val MAX_PARAGRAPH_CHARS = 2000
    }

    /** 顯示層轉換（簡→繁）；切換時呼叫 clearCache() */
    @Volatile
    var transform: ((String) -> String)? = null

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Int, Chapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chapter>) =
            size > CACHE_SIZE
    }

    suspend fun load(index: Int): Chapter {
        val entry = chapters.getOrNull(index)
            ?: return Chapter(index, "", emptyList())
        mutex.withLock { cache[index] }?.let { return it }

        val chapter = withContext(Dispatchers.IO) { readChapter(entry) }
        mutex.withLock { cache[index] = chapter }
        return chapter
    }

    suspend fun clearCache() {
        mutex.withLock { cache.clear() }
    }

    private fun readChapter(entry: ChapterIndexEntry): Chapter {
        val length = (entry.byteEnd - entry.byteStart).toInt().coerceAtLeast(0)
        if (length == 0) return Chapter(entry.index, entry.title, emptyList())

        val buf = ByteArray(length)
        RandomAccessFile(contentFile, "r").use { raf ->
            raf.seek(entry.byteStart)
            raf.readFully(buf)
        }
        val text = String(buf, Charsets.UTF_8)
        val t = transform

        val paragraphs = ArrayList<String>(256)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val converted = t?.invoke(line) ?: line
            if (converted.length <= MAX_PARAGRAPH_CHARS) {
                paragraphs.add(converted)
            } else {
                var i = 0
                while (i < converted.length) {
                    val end = minOf(i + MAX_PARAGRAPH_CHARS, converted.length)
                    paragraphs.add(converted.substring(i, end))
                    i = end
                }
            }
        }
        return Chapter(entry.index, entry.title, paragraphs)
    }
}
