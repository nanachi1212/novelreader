package app.novelreader

import app.novelreader.core.io.Transcoder
import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscoderTest {

    private fun tempOut(): File =
        File.createTempFile("novelreader-test", ".txt").apply { deleteOnExit() }

    private val tradText = """
        書名：測試之書

        第一章 風雪夜
        大雪紛飛，天地一片蒼茫。他推開門，寒風撲面而來。
        「你終於來了。」老人說。

        第二章 山中歲月
        歲月如梭，轉眼三年過去了。

        第三章 決戰之日
        這一天終於到來。
    """.trimIndent()

    private val simpText = """
        书名：测试之书

        第一章 风雪夜
        大雪纷飞，天地一片苍茫。他推开门，寒风扑面而来。
        “你终于来了。”老人说。

        第二章 山中岁月
        岁月如梭，转眼三年过去了。
    """.trimIndent()

    private fun assertChapters(result: Transcoder.Result, expectTitles: List<String>) {
        val titles = result.chapters.map { it.title }
        for (t in expectTitles) {
            assertTrue(titles.any { it.contains(t) }, "缺少章節 $t，實際：$titles")
        }
    }

    @Test
    fun `Big5 自動偵測與章節索引`() {
        val bytes = tradText.toByteArray(Charset.forName("Big5"))
        val out = tempOut()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        assertTrue(result.charsetName.contains("Big5", ignoreCase = true), "偵測到 ${result.charsetName}")
        assertChapters(result, listOf("第一章", "第二章", "第三章"))
        val content = out.readText(Charsets.UTF_8)
        assertTrue(content.contains("大雪紛飛"), "轉碼後內容不對")
    }

    @Test
    fun `GBK 自動偵測`() {
        val bytes = simpText.toByteArray(Charset.forName("GBK"))
        val out = tempOut()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        assertTrue(
            result.charsetName.contains("GB", ignoreCase = true),
            "偵測到 ${result.charsetName}"
        )
        assertTrue(out.readText(Charsets.UTF_8).contains("大雪纷飞"))
        assertChapters(result, listOf("第一章", "第二章"))
    }

    @Test
    fun `UTF-16LE 含 BOM`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            tradText.toByteArray(Charsets.UTF_16LE)
        val out = tempOut()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        assertTrue(out.readText(Charsets.UTF_8).contains("大雪紛飛"))
        assertChapters(result, listOf("第一章", "第二章", "第三章"))
    }

    @Test
    fun `UTF-8 含 BOM 且去除 BOM 字元`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            tradText.toByteArray(Charsets.UTF_8)
        val out = tempOut()
        Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        val content = out.readText(Charsets.UTF_8)
        assertTrue(!content.contains('﻿'), "BOM 未去除")
        assertTrue(content.startsWith("書名"))
    }

    @Test
    fun `CRLF 正規化為 LF`() {
        val bytes = tradText.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        val out = tempOut()
        Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        assertTrue(!out.readText(Charsets.UTF_8).contains('\r'))
    }

    @Test
    fun `無章節時建立偽章節`() {
        val noChapters = (1..2000).joinToString("\n") { "這是普通的第 $it 行文字，沒有任何標題格式。" }
        val bytes = noChapters.toByteArray(Charsets.UTF_8)
        val out = tempOut()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        assertTrue(result.chapters.isNotEmpty())
        assertEquals("開始", result.chapters.first().title)
    }

    @Test
    fun `章節位元組範圍完整覆蓋且可定位`() {
        val bytes = tradText.toByteArray(Charset.forName("Big5"))
        val out = tempOut()
        val result = Transcoder.transcodeAndIndex(BytesSource(bytes), out)
        // 範圍連續無縫
        assertEquals(0, result.chapters.first().byteStart)
        for (i in 0 until result.chapters.size - 1) {
            assertEquals(result.chapters[i].byteEnd, result.chapters[i + 1].byteStart)
        }
        assertEquals(result.totalBytes, result.chapters.last().byteEnd)
        // 用 byteStart 讀第二章開頭，應該正好是標題行
        val entry = result.chapters.first { it.title.contains("第二章") }
        java.io.RandomAccessFile(out, "r").use { raf ->
            raf.seek(entry.byteStart)
            val buf = ByteArray((entry.byteEnd - entry.byteStart).toInt())
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            assertTrue(text.startsWith("第二章"), "章節起點錯位：${text.take(30)}")
        }
    }
}
