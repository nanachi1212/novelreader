package app.novelreader.data

import app.novelreader.core.detect.CharsetDetector
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
        data class Done(val meta: BookMeta, val alreadyExisted: Boolean) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    fun import(source: BookSource, forcedCharsetName: String? = null): Flow<ImportState> = channelFlow {
        try {
            send(ImportState.Progress(0f))
            val fingerprint = Fingerprint.compute(source)

            val library = stores.loadLibrary()
            val existing = library.books.find { it.fingerprint == fingerprint }
            if (existing != null && forcedCharsetName == null) {
                send(ImportState.Done(existing, alreadyExisted = true))
                return@channelFlow
            }

            val forced = forcedCharsetName
                ?.takeUnless { it == "AUTO" }
                ?.let { CharsetDetector.safeCharset(it) }

            val result = Transcoder.transcodeAndIndex(
                source = source,
                outFile = stores.contentFile(fingerprint),
                forcedCharset = forced,
            ) { fraction ->
                trySend(ImportState.Progress(fraction))
            }
            stores.saveChapters(fingerprint, result.chapters)

            val meta = BookMeta(
                fingerprint = fingerprint,
                title = existing?.title ?: source.displayName.substringBeforeLast('.'),
                fileName = source.displayName,
                sourceUri = source.uriOrPath,
                format = BookFormat.TXT,
                charset = result.charsetName,
                fileSize = source.sizeBytes,
                importedAt = existing?.importedAt ?: System.currentTimeMillis(),
                chapterCount = result.chapters.size,
                totalBytes = result.totalBytes,
            )

            val updated = library.copy(
                books = library.books.filterNot { it.fingerprint == fingerprint } + meta
            )
            stores.saveLibrary(updated)
            send(ImportState.Done(meta, alreadyExisted = false))
        } catch (e: Exception) {
            send(ImportState.Error(e.message ?: e.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /** 換編碼重新匯入：進度與書籤都掛在指紋上，不受影響 */
    fun reimportWithCharset(meta: BookMeta, charsetName: String): Flow<ImportState> {
        val source = platform.resolveSource(meta.sourceUri)
            ?: return flow { emit(ImportState.Error("找不到原始檔案，請重新匯入")) }
        return import(source, forcedCharsetName = charsetName)
    }
}
