package app.novelreader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.Library
import app.novelreader.platform.AppFont
import app.novelreader.platform.BookSource
import app.novelreader.platform.Platform
import app.novelreader.platform.SyncFolder
import app.novelreader.ui.AppState
import app.novelreader.ui.Screen
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppStateBehaviorTest {
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

    @Test
    fun `啟動後顯示書庫且可開書返回`() = runBlocking {
        val root = createTempDirectory("novelreader-ui-state-").toFile()
        try {
            val state = AppState(TestPlatform(root))
            state.init()
            assertTrue(state.initialized)
            assertIs<Screen.Bookshelf>(state.screen)

            val book = BookMeta("book", "測試書", "book.txt", "mem://book")
            state.openBook(book)
            assertEquals(book, (state.screen as Screen.Reader).meta)
            state.backToShelf()
            assertIs<Screen.Bookshelf>(state.screen)
            Unit
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `批次移除會刪掉書庫紀錄與書籍資料夾`() = runBlocking {
        val root = createTempDirectory("novelreader-ui-remove-").toFile()
        try {
            val state = AppState(TestPlatform(root))
            state.init()
            val a = BookMeta("a", "甲", "a.txt", "mem://a")
            val b = BookMeta("b", "乙", "b.txt", "mem://b")
            val c = BookMeta("c", "丙", "c.txt", "mem://c")
            state.stores.saveLibrary(Library(listOf(a, b, c)))
            state.stores.booksDir("a").mkdirs()

            state.removeBooks(listOf(a, b))
            state.scope.coroutineContext[Job]!!.children.toList().joinAll()

            assertEquals(listOf(c), state.stores.loadLibrary().books)
            assertEquals(listOf(c), state.library.books)
            assertFalse(state.stores.booksDir("a").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `關閉流程會呼叫閱讀器保存 hook`() = runBlocking {
        val root = createTempDirectory("novelreader-ui-flush-").toFile()
        try {
            val state = AppState(TestPlatform(root))
            var flushed = false
            state.readerFlush = { flushed = true }
            state.flushNow()
            assertTrue(flushed)
        } finally {
            root.deleteRecursively()
        }
    }
}
