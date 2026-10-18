package app.novelreader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import app.novelreader.data.AppStores
import app.novelreader.data.BookRepository
import app.novelreader.data.BookRepository.ImportState
import app.novelreader.platform.AppFont
import app.novelreader.platform.ArchiveEntryBookSource
import app.novelreader.platform.BookSource
import app.novelreader.platform.Platform
import app.novelreader.platform.SyncFolder
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 從壓縮檔匯入 EPUB 時有兩份暫存（解壓出的 entry + importEpub 的複本），匯入結束後都要刪掉 */
class ArchiveImportCleanupTest {
    private class TestPlatform(override val appDataDir: File) : Platform {
        override val isDesktop = true
        override suspend fun pickBookFile(): BookSource? = null
        override suspend fun pickSyncFolder(): SyncFolder? = null
        override fun resolveSource(uriOrPath: String): BookSource? = null
        override fun resolveSyncFolder(uriOrPath: String): SyncFolder? = null
        override fun listFonts(): List<AppFont> = emptyList()
        override fun resolveFontFamily(id: String?): FontFamily? = null
        override fun decodeImage(bytes: ByteArray): ImageBitmap? = null
    }

    private val validEpub = mapOf(
        "META-INF/container.xml" to
            """<?xml version="1.0"?><container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
        "content.opf" to
            """<?xml version="1.0"?><package><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
        "c.xhtml" to "<html><body><p>第一章 內容</p></body></html>",
    )

    private fun zipOf(entries: Map<String, ByteArray>, suffix: String): File {
        val file = File.createTempFile("novelreader-cleanup", suffix).apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun archiveWithEpub(epubEntries: Map<String, String>): File {
        val epub = zipOf(epubEntries.mapValues { it.value.toByteArray() }, ".epub")
        return zipOf(mapOf("書.epub" to epub.readBytes()), ".zip")
    }

    private class Run(val states: List<ImportState>, val maxTempFiles: Int, val tempFilesAfter: Int)

    /** 在 Progress(0.05)（複本已寫好、尚未解析）時記下 tmp 內的檔案數，確認兩份暫存確實建立過 */
    private fun runImport(archive: File, cancelAfterCopy: Boolean = false): Run = runBlocking {
        val root = createTempDirectory("novelreader-cleanup-").toFile()
        val platform = TestPlatform(root)
        val repo = BookRepository(platform, AppStores(platform))
        val tmpDir = File(root, "tmp")
        val source = ArchiveEntryBookSource(archive, "書.epub", "書.epub", tmpDir)
        var maxTemp = 0
        fun copied(st: ImportState) = st is ImportState.Progress && st.fraction >= 0.05f
        val states = repo.import(source)
            .onEach { if (copied(it)) maxTemp = maxOf(maxTemp, tmpDir.listFiles()?.size ?: 0) }
            .takeWhile { !(cancelAfterCopy && copied(it)) }
            .toList()
        val after = tmpDir.listFiles()?.size ?: 0
        root.deleteRecursively()
        Run(states, maxTemp, after)
    }

    @Test
    fun `匯入成功後兩份暫存都刪除`() {
        val run = runImport(archiveWithEpub(validEpub))
        assertTrue(run.states.last() is ImportState.Done, run.states.last().toString())
        assertEquals(2, run.maxTempFiles)
        assertEquals(0, run.tempFilesAfter)
    }

    @Test
    fun `匯入失敗後兩份暫存都刪除`() {
        val run = runImport(archiveWithEpub(validEpub - "content.opf"))
        assertTrue(run.states.last() is ImportState.Error, run.states.last().toString())
        assertEquals(2, run.maxTempFiles)
        assertEquals(0, run.tempFilesAfter)
    }

    @Test
    fun `匯入中途取消後兩份暫存都刪除`() {
        val run = runImport(archiveWithEpub(validEpub), cancelAfterCopy = true)
        assertTrue(run.states.none { it is ImportState.Done || it is ImportState.Error }, run.states.toString())
        assertEquals(2, run.maxTempFiles)
        assertEquals(0, run.tempFilesAfter)
    }
}
