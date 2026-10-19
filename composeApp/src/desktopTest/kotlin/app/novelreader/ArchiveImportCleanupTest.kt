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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * 讀取時檢查 [pauseWhen]，成立就停在同步檢查點，等測試放行。
     * importEpub 先建立暫存複本再讀來源，所以停住時兩份暫存都已在 tmp 內。
     */
    private class PausingSource(
        private val inner: BookSource,
        private val pauseWhen: () -> Boolean,
    ) : BookSource by inner {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)

        private fun checkpoint() {
            if (paused.count > 0 && pauseWhen()) {
                paused.countDown()
                resume.await(30, TimeUnit.SECONDS)
            }
        }

        override fun open(): InputStream = object : FilterInputStream(inner.open()) {
            override fun read(): Int { checkpoint(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { checkpoint(); return super.read(b, off, len) }
        }
    }

    /** 在 Progress(0.05)（複本已寫好、尚未解析）時記下 tmp 內的檔案數，確認兩份暫存確實建立過 */
    private fun runImport(archive: File): Run = runBlocking {
        val root = createTempDirectory("novelreader-cleanup-").toFile()
        val platform = TestPlatform(root)
        val repo = BookRepository(platform, AppStores(platform))
        val tmpDir = File(root, "tmp")
        val source = ArchiveEntryBookSource(archive, "書.epub", "書.epub", tmpDir)
        var maxTemp = 0
        fun copied(st: ImportState) = st is ImportState.Progress && st.fraction >= 0.05f
        val states = repo.import(source)
            .onEach { if (copied(it)) maxTemp = maxOf(maxTemp, tmpDir.listFiles()?.size ?: 0) }
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
    fun `匯入中途取消後兩份暫存都刪除`() = runBlocking {
        val root = createTempDirectory("novelreader-cleanup-").toFile()
        val platform = TestPlatform(root)
        val repo = BookRepository(platform, AppStores(platform))
        val tmpDir = File(root, "tmp")
        fun tempCount() = tmpDir.listFiles()?.size ?: 0
        val source = PausingSource(
            ArchiveEntryBookSource(archiveWithEpub(validEpub), "書.epub", "書.epub", tmpDir),
        ) { tempCount() >= 2 }
        val states = Collections.synchronizedList(mutableListOf<ImportState>())

        val job = launch(Dispatchers.Default) { repo.import(source).collect { states += it } }
        assertTrue(source.paused.await(30, TimeUnit.SECONDS), "匯入沒有停在檢查點")
        assertEquals(2, tempCount())

        job.cancel()
        source.resume.countDown()
        withTimeout(30_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(states.none { it is ImportState.Done || it is ImportState.Error }, states.toString())
        assertEquals(0, tempCount())
        root.deleteRecursively()
        Unit
    }
}
