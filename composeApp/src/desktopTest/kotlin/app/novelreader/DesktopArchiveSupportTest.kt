package app.novelreader

import app.novelreader.core.io.Fingerprint
import app.novelreader.platform.ArchiveEntryBookSource
import app.novelreader.platform.DesktopArchiveSupport
import app.novelreader.platform.FileBookSource
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopArchiveSupportTest {

    private val bookContent = "第一章 起風了\n\n風從山谷吹來，捲起漫天黃沙。\n他知道，該啟程了。\n".repeat(30)

    private fun tempFile(suffix: String): File =
        File.createTempFile("novelreader-test", suffix).apply { deleteOnExit() }

    private fun buildZip(charset: Charset = Charsets.UTF_8, entries: Map<String, ByteArray>): File {
        val file = tempFile(".zip")
        ZipOutputStream(file.outputStream(), charset).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `zip 只列出 txt 與 epub 並跳過雜項`() {
        val zip = buildZip(entries = linkedMapOf(
            "novels/書一.txt" to bookContent.toByteArray(),
            "novels/書二.epub" to byteArrayOf(1, 2, 3),
            "cover.jpg" to byteArrayOf(9, 9),
            "__MACOSX/novels/書一.txt" to byteArrayOf(0),
            "novels/.hidden.txt" to byteArrayOf(1),
            "empty.txt" to ByteArray(0),
            "dir/" to ByteArray(0),
        ))
        val entries = DesktopArchiveSupport.listBookEntries(zip)
        assertEquals(listOf("novels/書一.txt", "novels/書二.epub"), entries.map { it.entryPath })
        assertEquals(listOf("書一.txt", "書二.epub"), entries.map { it.displayName })
        assertEquals(bookContent.toByteArray().size.toLong(), entries[0].sizeBytes)
    }

    @Test
    fun `zip 解壓內容一致`() {
        val bytes = bookContent.toByteArray()
        val zip = buildZip(entries = mapOf("深/層/書.txt" to bytes))
        val dest = tempFile(".txt")
        val written = DesktopArchiveSupport.extractEntry(zip, "深/層/書.txt", dest)
        assertEquals(bytes.size.toLong(), written)
        assertTrue(dest.readBytes().contentEquals(bytes))
    }

    @Test
    fun `zip GBK 檔名正確解碼`() {
        val name = "简体中文测试小说这是一本书.txt"
        val zip = buildZip(Charset.forName("GBK"), mapOf(name to bookContent.toByteArray()))
        val entries = DesktopArchiveSupport.listBookEntries(zip)
        assertEquals(1, entries.size)
        assertEquals(name, entries[0].displayName)
    }

    @Test
    fun `zip Big5 檔名正確解碼`() {
        val name = "繁體中文測試小說這是我們的書.txt"
        val zip = buildZip(Charset.forName("Big5"), mapOf(name to bookContent.toByteArray()))
        val entries = DesktopArchiveSupport.listBookEntries(zip)
        assertEquals(1, entries.size)
        assertEquals(name, entries[0].displayName)
    }

    @Test
    fun `7z 列舉與解壓往返`() {
        val bytes = bookContent.toByteArray()
        val src = tempFile(".txt").apply { writeBytes(bytes) }
        val sevenZ = tempFile(".7z")
        SevenZOutputFile(sevenZ).use { out ->
            val entry = out.createArchiveEntry(src, "novels/書.txt")
            out.putArchiveEntry(entry)
            out.write(bytes)
            out.closeArchiveEntry()
        }
        val entries = DesktopArchiveSupport.listBookEntries(sevenZ)
        assertEquals(listOf("novels/書.txt"), entries.map { it.entryPath })

        val dest = tempFile(".txt")
        DesktopArchiveSupport.extractEntry(sevenZ, "novels/書.txt", dest)
        assertTrue(dest.readBytes().contentEquals(bytes))
    }

    @Test
    fun `壓縮檔內的書與直接匯入的同檔指紋相同`() {
        val bytes = bookContent.toByteArray()
        val direct = tempFile(".txt").apply { writeBytes(bytes) }
        val zip = buildZip(entries = mapOf("書.txt" to bytes))
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "novelreader-test-tmp")

        val directFp = Fingerprint.compute(FileBookSource(direct))
        val archiveFp = Fingerprint.compute(ArchiveEntryBookSource(zip, "書.txt", "書.txt", tmpDir))
        assertEquals(directFp, archiveFp)
    }
}
