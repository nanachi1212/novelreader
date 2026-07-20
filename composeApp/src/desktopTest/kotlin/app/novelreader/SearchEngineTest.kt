package app.novelreader

import app.novelreader.core.io.Transcoder
import app.novelreader.reader.ChapterLoader
import app.novelreader.reader.SearchEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEngineTest {

    private val text = """
        書名：測試之書

        第一章 風雪夜
        大雪紛飛，天地一片蒼茫。他推開門，寒風撲面而來。
        「你終於來了。」老人說。

        第二章 山中歲月
        歲月如梭，轉眼三年過去了。他仍記得那場大雪紛飛的夜晚。
    """.trimIndent()

    private fun buildLoader(): ChapterLoader {
        val out = File.createTempFile("novelreader-search", ".txt").apply { deleteOnExit() }
        val result = Transcoder.transcodeAndIndex(BytesSource(text.toByteArray(Charsets.UTF_8)), out)
        return ChapterLoader(out, result.chapters)
    }

    @Test
    fun `找到跨章節的所有相符段落`() = runBlocking {
        val loader = buildLoader()
        val results = SearchEngine(loader).search("大雪紛飛").toList()
        assertEquals(2, results.size, "應在兩章都找到「大雪紛飛」：$results")
        assertTrue(results.any { it.chapterTitle.contains("第一章") })
        assertTrue(results.any { it.chapterTitle.contains("第二章") })
    }

    @Test
    fun `找不到時回傳空結果`() = runBlocking {
        val loader = buildLoader()
        val results = SearchEngine(loader).search("不存在的關鍵字XYZ").toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `空白查詢不搜尋`() = runBlocking {
        val loader = buildLoader()
        val results = SearchEngine(loader).search("   ").toList()
        assertTrue(results.isEmpty())
    }
}
