package app.novelreader

import app.novelreader.core.io.Transcoder
import app.novelreader.reader.ChapterLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeFileSmokeTest {

    @Test
    fun `20MB Big5 大檔匯入與隨機章節載入`() {
        val para = "他望向遠方的山巒，心中湧起一股難以言喻的情緒。走過千山萬水，歷經風霜雨雪，只為尋一個答案。"
        val sb = StringBuilder(11_000_000)
        var chapterCount = 0
        while (sb.length < 10_000_000) {
            chapterCount++
            sb.append("第").append(chapterCount).append("章 試煉之路\n\n")
            repeat(60) { sb.append(para).append('\n') }
        }
        val bytes = sb.toString().toByteArray(Charset.forName("Big5"))
        assertTrue(bytes.size > 18_000_000, "測試檔僅 ${bytes.size} bytes")

        val out = File.createTempFile("novelreader-large", ".txt").apply { deleteOnExit() }
        val t0 = System.currentTimeMillis()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        val importMs = System.currentTimeMillis() - t0
        println("匯入 ${bytes.size / 1024 / 1024}MB Big5 耗時 ${importMs}ms，共 ${result.chapters.size} 章")

        assertEquals(chapterCount, result.chapters.size)
        assertTrue(importMs < 30_000, "匯入太慢：${importMs}ms")

        // 隨機跳章載入（模擬目錄跳轉），單章載入必須快
        val loader = ChapterLoader(out, result.chapters)
        for (idx in listOf(0, chapterCount / 2, chapterCount - 1)) {
            val t1 = System.currentTimeMillis()
            val ch = runBlocking { loader.load(idx) }
            val loadMs = System.currentTimeMillis() - t1
            assertTrue(ch.paragraphs.isNotEmpty())
            assertTrue(ch.paragraphs.first().startsWith("第"), "章節開頭錯位：${ch.paragraphs.first().take(20)}")
            assertTrue(loadMs < 2_000, "章節載入太慢：${loadMs}ms")
        }
    }
}
