package app.novelreader

import app.novelreader.platform.ArchiveUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveUriTest {

    @Test
    fun `組合與解析往返`() {
        val uri = ArchiveUri.build("""E:\books\小說合集.zip""", "玄幻/誅仙.txt")
        assertEquals("""archive://E:\books\小說合集.zip!/玄幻/誅仙.txt""", uri)
        assertTrue(ArchiveUri.isArchiveUri(uri))
        val (archive, entry) = ArchiveUri.parse(uri)!!
        assertEquals("""E:\books\小說合集.zip""", archive)
        assertEquals("玄幻/誅仙.txt", entry)
    }

    @Test
    fun `路徑含空白與驚嘆號`() {
        val path = """C:\My Files\最愛! 合集.7z"""
        val (archive, entry) = ArchiveUri.parse(ArchiveUri.build(path, "書!.txt"))!!
        assertEquals(path, archive)
        assertEquals("書!.txt", entry)
    }

    @Test
    fun `entry 含子路徑`() {
        val (archive, entry) = ArchiveUri.parse(ArchiveUri.build("""D:\a.rar""", "深/層/目錄/書.epub"))!!
        assertEquals("""D:\a.rar""", archive)
        assertEquals("深/層/目錄/書.epub", entry)
    }

    @Test
    fun `非 archive URI 回 null`() {
        assertNull(ArchiveUri.parse("""E:\books\普通.txt"""))
        assertNull(ArchiveUri.parse("content://com.android.providers/document/123"))
        assertFalse(ArchiveUri.isArchiveUri("""E:\books\a.zip"""))
    }

    @Test
    fun `格式不完整回 null`() {
        assertNull(ArchiveUri.parse("archive://E:\\a.zip"))       // 沒有 entry 段
        assertNull(ArchiveUri.parse("archive://E:\\a.zip!/"))     // entry 空白
        assertNull(ArchiveUri.parse("archive://E:\\a.txt!/b.txt")) // 前綴不是壓縮檔
    }
}
