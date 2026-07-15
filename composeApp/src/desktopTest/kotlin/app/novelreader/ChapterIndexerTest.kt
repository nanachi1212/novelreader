package app.novelreader

import app.novelreader.core.index.ChapterIndexer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterIndexerTest {

    private fun heading(line: String) = ChapterIndexer.isHeading(ChapterIndexer.normalize(line))

    @Test
    fun `常見章節標題`() {
        assertTrue(heading("第一章"))
        assertTrue(heading("第一章 風雪夜"))
        assertTrue(heading("第十章風雪夜"))
        assertTrue(heading("第100章 決戰"))
        assertTrue(heading("第１０８章　全形數字"))
        assertTrue(heading("  第三卷 江湖再見"))
        assertTrue(heading("第五節 開始"))
        assertTrue(heading("第两百章 简体也要认"))
        assertTrue(heading("第一千三百五十六章 大結局"))
    }

    @Test
    fun `特殊章節名`() {
        assertTrue(heading("序章"))
        assertTrue(heading("楔子"))
        assertTrue(heading("番外 某年某月"))
        assertTrue(heading("尾聲"))
        assertTrue(heading("後記"))
    }

    @Test
    fun `英文章節`() {
        assertTrue(heading("Chapter 1"))
        assertTrue(heading("chapter 12: The Beginning"))
    }

    @Test
    fun `正文不該被誤判`() {
        assertFalse(heading("第二天，他醒了。"))
        assertFalse(heading("第一次見到她時「你是誰」他問"))
        assertFalse(heading("這一章寫得真好，我看了三遍。"))
        assertFalse(heading("")) // 空行
        assertFalse(heading("第" + "很".repeat(60) + "章")) // 過長
        assertFalse(heading("第二天早上他去了學校然後遇到了很多人發生了很多故事情節非常精彩"))
    }

    @Test
    fun `嚴格模式只認第X章`() {
        assertTrue(ChapterIndexer.isStrictHeading("第一章 開始"))
        assertFalse(ChapterIndexer.isStrictHeading("卷一 風雲起"))
        assertFalse(ChapterIndexer.isStrictHeading("序章"))
    }
}
