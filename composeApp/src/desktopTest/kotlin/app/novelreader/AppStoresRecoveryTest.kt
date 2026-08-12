package app.novelreader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.ChapterIndexEntry
import app.novelreader.core.model.Library
import app.novelreader.data.AppStores
import app.novelreader.platform.AppFont
import app.novelreader.platform.BookSource
import app.novelreader.platform.Platform
import app.novelreader.platform.SyncFolder
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStoresRecoveryTest {
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

    private fun book(id: String, title: String) = BookMeta(
        fingerprint = id,
        title = title,
        fileName = "$title.txt",
        sourceUri = "mem://$id",
    )

    @Test
    fun `主書庫損壞時從備份恢復`() = runBlocking {
        val root = createTempDir(prefix = "novelreader-store-")
        try {
            val stores = AppStores(TestPlatform(root))
            stores.saveLibrary(Library(listOf(book("one", "第一版"))))
            stores.saveLibrary(Library(listOf(book("two", "第二版"))))
            File(root, "library.json").writeText("{broken", Charsets.UTF_8)

            assertEquals("第一版", stores.loadLibrary().books.single().title)
            assertTrue(File(root, "library.json").readText().contains("第一版"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `修復書庫移除缺少必要檔案的項目`() = runBlocking {
        val root = createTempDir(prefix = "novelreader-repair-")
        try {
            val stores = AppStores(TestPlatform(root))
            val valid = book("valid", "完整書")
            val broken = book("broken", "殘缺書")
            stores.contentFile(valid.fingerprint).apply { parentFile.mkdirs(); writeText("正文") }
            stores.saveChapters(valid.fingerprint, listOf(ChapterIndexEntry(0, "全文", 0, 6)))
            stores.saveLibrary(Library(listOf(valid, broken)))

            assertEquals(listOf(valid), stores.repairLibrary().books)
            assertTrue(!stores.booksDir(broken.fingerprint).exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
