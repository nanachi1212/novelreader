package app.novelreader.data

import app.novelreader.core.detect.CharsetDetector
import app.novelreader.core.epub.EpubParser
import app.novelreader.core.io.Fingerprint
import app.novelreader.core.io.Transcoder
import app.novelreader.core.model.BookFormat
import app.novelreader.core.model.BookMeta
import app.novelreader.platform.BookSource
import app.novelreader.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 匯入協調：指紋 → 去重 → 轉碼+索引 → 寫入書庫 */
class BookRepository(
    private val platform: Platform,
    private val stores: AppStores,
) {
    sealed class ImportState {
        data class Progress(val fraction: Float) : ImportState()
        data class Done(
            val meta: BookMeta,
            val alreadyExisted: Boolean,
            /** 書名跟現有書架上某本很像，但指紋不同（不同來源/編碼），讓 UI 詢問使用者怎麼處理 */
            val possibleDuplicateOf: BookMeta? = null,
        ) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    private fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(Regex("""[\s　]+"""), "")

    fun import(source: BookSource, forcedCharsetName: String? = null): Flow<ImportState> = channelFlow {
        var newFingerprint: String? = null
        try {
            send(ImportState.Progress(0f))
            val fingerprint = Fingerprint.compute(source)

            val library = stores.loadLibrary()
            val existing = library.books.find { it.fingerprint == fingerprint }
            if (existing == null) newFingerprint = fingerprint
            if (existing != null && forcedCharsetName == null) {
                send(ImportState.Done(existing, alreadyExisted = true))
                return@channelFlow
            }

            val isEpub = source.displayName.substringAfterLast('.', "").equals("epub", ignoreCase = true)

            val (result, coverSaved) = if (isEpub) {
                importEpub(source, fingerprint) { fraction -> trySend(ImportState.Progress(fraction)) }
            } else {
                val forced = forcedCharsetName
                    ?.takeUnless { it == "AUTO" }
                    ?.let { CharsetDetector.safeCharset(it) }
                val r = Transcoder.transcodeAndIndex(
                    source = source,
                    outFile = stores.contentFile(fingerprint),
                    forcedCharset = forced,
                ) { fraction -> trySend(ImportState.Progress(fraction)) }
                r to false
            }
            stores.saveChapters(fingerprint, result.chapters)

            val meta = BookMeta(
                fingerprint = fingerprint,
                title = existing?.title ?: source.displayName.substringBeforeLast('.'),
                fileName = source.displayName,
                sourceUri = source.uriOrPath,
                format = if (isEpub) BookFormat.EPUB else BookFormat.TXT,
                charset = result.charsetName,
                fileSize = source.sizeBytes,
                importedAt = existing?.importedAt ?: System.currentTimeMillis(),
                chapterCount = result.chapters.size,
                totalBytes = result.totalBytes,
                coverPath = if (coverSaved) "cover.jpg" else null,
            )

            val updated = library.copy(
                books = library.books.filterNot { it.fingerprint == fingerprint } + meta
            )
            stores.saveLibrary(updated)

            val duplicate = library.books.find {
                it.fingerprint != fingerprint && normalizeTitle(it.title) == normalizeTitle(meta.title)
            }
            send(ImportState.Done(meta, alreadyExisted = false, possibleDuplicateOf = duplicate))
        } catch (e: Exception) {
            newFingerprint?.let { stores.deleteBook(it) }
            send(ImportState.Error(e.message ?: e.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /** 換編碼重新匯入：進度與書籤都掛在指紋上，不受影響 */
    fun reimportWithCharset(meta: BookMeta, charsetName: String): Flow<ImportState> {
        val source = platform.resolveSource(meta.sourceUri)
            ?: return flow { emit(ImportState.Error("找不到原始檔案，請重新匯入")) }
        return import(source, forcedCharsetName = charsetName)
    }

    /** EPUB 用 ZipFile 隨機存取解析目錄，SAF/串流來源需先落地成暫存檔 */
    private fun importEpub(
        source: BookSource,
        fingerprint: String,
        onProgress: (Float) -> Unit,
    ): Pair<Transcoder.Result, Boolean> {
        val tmp = stores.newTempFile(".epub")
        try {
            source.open().use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            onProgress(0.05f)
            val parsed = EpubParser.parse(tmp)
            val result = Transcoder.transcodeEpub(
                chapters = parsed.chapters.map { it.title to it.paragraphs },
                outFile = stores.contentFile(fingerprint),
            ) { fraction -> onProgress(0.05f + fraction * 0.95f) }
            val coverSaved = parsed.coverBytes?.let { stores.saveCover(fingerprint, it); true } ?: false
            return result to coverSaved
        } finally {
            tmp.delete()
        }
    }
}
