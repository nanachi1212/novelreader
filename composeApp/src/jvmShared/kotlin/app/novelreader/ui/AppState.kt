package app.novelreader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.novelreader.core.convert.ChineseConvert
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.Library
import app.novelreader.core.model.ReaderSettings
import app.novelreader.data.AppStores
import app.novelreader.data.BookRepository
import app.novelreader.platform.AppFont
import app.novelreader.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed class Screen {
    data object Bookshelf : Screen()
    data class Reader(val meta: BookMeta) : Screen()
    data object Settings : Screen()
}

/**
 * App 全域狀態。由平台入口（Main.kt / MainActivity）建立並持有，
 * 以便關閉視窗 / onStop 時呼叫 flushNow() 保存進度。
 */
class AppState(val platform: Platform) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stores = AppStores(platform)
    val repository = BookRepository(platform, stores)
    val syncManager by lazy { app.novelreader.data.SyncManager(stores, ::getSyncFolder) }

    var initialized by mutableStateOf(false)
        private set
    var settings by mutableStateOf(ReaderSettings())
        private set
    var library by mutableStateOf(Library())
        private set
    var screen by mutableStateOf<Screen>(Screen.Bookshelf)
    var syncFolderUri by mutableStateOf<String?>(null)

    val fonts: List<AppFont> by lazy { platform.listFonts() }

    /** 閱讀畫面註冊的立即保存 hook（關窗 / onStop 時呼叫） */
    @Volatile
    var readerFlush: (suspend () -> Unit)? = null

    suspend fun init() {
        if (initialized) return
        settings = stores.loadSettings()
        library = stores.repairLibrary()
        syncFolderUri = settings.syncFolderUri
        initialized = true
        scope.launch(Dispatchers.IO) { ChineseConvert.warmUp() }
    }

    private fun getSyncFolder(): app.novelreader.platform.SyncFolder? =
        syncFolderUri?.let { platform.resolveSyncFolder(it) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val updated = transform(settings)
        settings = updated
        syncFolderUri = updated.syncFolderUri
        scope.launch { stores.saveSettings(updated) }
    }

    suspend fun refreshLibrary() {
        library = stores.loadLibrary()
    }

    fun openBook(meta: BookMeta) {
        screen = Screen.Reader(meta)
    }

    fun backToShelf() {
        screen = Screen.Bookshelf
    }

    fun removeBook(meta: BookMeta) {
        scope.launch {
            stores.deleteBook(meta.fingerprint)
            val lib = stores.loadLibrary()
            stores.saveLibrary(lib.copy(books = lib.books.filterNot { it.fingerprint == meta.fingerprint }))
            refreshLibrary()
        }
    }

    /** 立即保存目前閱讀進度與同步（阻塞到寫完） */
    suspend fun flushNow() {
        readerFlush?.invoke()
        syncManager.flushAll()
    }
}
