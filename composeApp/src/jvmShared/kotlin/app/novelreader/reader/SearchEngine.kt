package app.novelreader.reader

import app.novelreader.core.convert.ChineseConvert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class SearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val excerpt: String,
)

/**
 * 逐章掃描 content.txt（透過既有 ChapterLoader，段落切法與顯示完全一致）。
 * 簡繁開啟時同時比對簡/繁兩種形態，避免使用者輸入的字形跟顯示形態不一致而搜不到。
 */
class SearchEngine(private val loader: ChapterLoader) {

    fun search(query: String): Flow<SearchResult> = flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@flow

        val candidates = linkedSetOf(trimmed).apply {
            try {
                add(ChineseConvert.toTraditional(trimmed))
                add(ChineseConvert.toSimplified(trimmed))
            } catch (_: Exception) {
            }
        }

        for (entry in loader.chapters) {
            val chapter = loader.load(entry.index)
            for ((pIdx, para) in chapter.paragraphs.withIndex()) {
                val pos = candidates.firstNotNullOfOrNull { c ->
                    para.indexOf(c).takeIf { it >= 0 }
                } ?: continue
                val matchLen = candidates.first { para.indexOf(it) == pos }.length
                val start = (pos - 12).coerceAtLeast(0)
                val end = (pos + matchLen + 24).coerceAtMost(para.length)
                val excerpt = buildString {
                    if (start > 0) append('…')
                    append(para.substring(start, end))
                    if (end < para.length) append('…')
                }
                emit(SearchResult(chapter.index, chapter.title, pIdx, excerpt))
            }
        }
    }.flowOn(Dispatchers.IO)
}
