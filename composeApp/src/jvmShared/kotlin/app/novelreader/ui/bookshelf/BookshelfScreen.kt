package app.novelreader.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.ReadingProgress
import app.novelreader.data.BookRepository.ImportState
import app.novelreader.platform.ArchiveEntryInfo
import app.novelreader.platform.ArchiveUri
import app.novelreader.platform.BookSource
import app.novelreader.ui.AppState
import app.novelreader.ui.Screen
import app.novelreader.ui.formatPercent
import app.novelreader.ui.formatRelativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val QUICK_TAGS = listOf("在看", "已看完", "想看")

private enum class SortMode(val label: String) {
    RECENT("最近閱讀"), TITLE("書名"), IMPORTED("新增時間"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var importState by remember { mutableStateOf<ImportState?>(null) }
    var removeTarget by remember { mutableStateOf<BookMeta?>(null) }
    var optionsTarget by remember { mutableStateOf<BookMeta?>(null) }
    var tagEditTarget by remember { mutableStateOf<BookMeta?>(null) }
    var duplicatePrompt by remember { mutableStateOf<Pair<BookMeta, BookMeta>?>(null) }
    var archiveScanning by remember { mutableStateOf(false) }
    var archivePicker by remember { mutableStateOf<Pair<String, List<ArchiveEntryInfo>>?>(null) }
    var batchProgress by remember { mutableStateOf<BatchProgress?>(null) }
    var batchSummary by remember { mutableStateOf<BatchSummary?>(null) }
    var progressMap by remember { mutableStateOf(emptyMap<String, ReadingProgress>()) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var activeTag by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.library) {
        val map = HashMap<String, ReadingProgress>()
        for (book in state.library.books) {
            state.stores.loadBookData(book.fingerprint)?.let { map[book.fingerprint] = it.progress }
        }
        progressMap = map
    }

    fun setTags(fingerprint: String, newTags: List<String>) {
        scope.launch {
            val updatedBooks = state.library.books.map {
                if (it.fingerprint == fingerprint) it.copy(tags = newTags) else it
            }
            state.stores.saveLibrary(state.library.copy(books = updatedBooks))
            state.refreshLibrary()
        }
    }

    fun toggleTag(fingerprint: String, tag: String) {
        val book = state.library.books.find { it.fingerprint == fingerprint } ?: return
        setTags(fingerprint, if (tag in book.tags) book.tags - tag else book.tags + tag)
    }

    /** 單本匯入（既有流程）：完成後直接開書、相似書名跳詢問 */
    suspend fun importSingle(source: BookSource) {
        state.repository.import(source).collect { st ->
            importState = st
            if (st is ImportState.Done) {
                state.refreshLibrary()
                importState = null
                if (st.possibleDuplicateOf != null) {
                    duplicatePrompt = st.meta to st.possibleDuplicateOf
                } else {
                    state.openBook(st.meta)
                }
            }
        }
    }

    /** 批次匯入：逐本收 Flow、失敗續行、完成後留在書架顯示總結 */
    suspend fun importArchiveEntries(archivePath: String, entries: List<ArchiveEntryInfo>) {
        var imported = 0
        var skipped = 0
        var similar = 0
        val failures = mutableListOf<Pair<String, String>>()
        entries.forEachIndexed { i, entry ->
            batchProgress = BatchProgress(i + 1, entries.size, entry.displayName, 0f)
            val src = state.platform.resolveSource(ArchiveUri.build(archivePath, entry.entryPath))
            if (src == null) {
                failures += entry.displayName to "無法讀取壓縮檔"
                return@forEachIndexed
            }
            state.repository.import(src).collect { st ->
                when (st) {
                    is ImportState.Progress ->
                        batchProgress = BatchProgress(i + 1, entries.size, entry.displayName, st.fraction)
                    is ImportState.Done -> {
                        if (st.alreadyExisted) skipped++ else {
                            imported++
                            if (st.possibleDuplicateOf != null) similar++
                        }
                    }
                    is ImportState.Error -> failures += entry.displayName to st.message
                }
            }
        }
        batchProgress = null
        state.refreshLibrary()
        batchSummary = BatchSummary(imported, skipped, similar, failures)
    }

    fun startImport() {
        scope.launch {
            val source = state.platform.pickBookFile() ?: return@launch
            val archive = state.platform.archive
            if (archive == null || !archive.isArchivePath(source.uriOrPath)) {
                importSingle(source)
                return@launch
            }
            // 壓縮檔：列出內含書檔
            archiveScanning = true
            val entries = try {
                withContext(Dispatchers.IO) { archive.listBookEntries(File(source.uriOrPath)) }
            } catch (e: Exception) {
                importState = ImportState.Error(e.message ?: "無法讀取壓縮檔")
                return@launch
            } finally {
                archiveScanning = false
            }
            when {
                entries.isEmpty() ->
                    importState = ImportState.Error("壓縮檔內找不到任何 txt 或 epub 檔案")
                entries.size == 1 -> {
                    // 只有一本 → 維持單本語意（直接開書、相似書名照舊詢問）
                    val src = state.platform.resolveSource(ArchiveUri.build(source.uriOrPath, entries[0].entryPath))
                    if (src == null) importState = ImportState.Error("無法讀取壓縮檔")
                    else importSingle(src)
                }
                else -> archivePicker = source.uriOrPath to entries
            }
        }
    }

    val allTags = remember(state.library) {
        (QUICK_TAGS + state.library.books.flatMap { it.tags }).distinct()
    }

    val visibleBooks = remember(state.library, progressMap, searchQuery, sortMode, activeTag) {
        state.library.books
            .filter { activeTag == null || activeTag in it.tags }
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
            .let { list ->
                when (sortMode) {
                    SortMode.RECENT -> list.sortedByDescending { b ->
                        progressMap[b.fingerprint]?.updatedAt?.takeIf { it > 0 } ?: b.importedAt
                    }
                    SortMode.TITLE -> list.sortedBy { it.title }
                    SortMode.IMPORTED -> list.sortedByDescending { it.importedAt }
                }
            }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("書架") },
                    actions = {
                        Box {
                            TextButton(onClick = { sortMenuOpen = true }) {
                                Text(sortMode.label)
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                for (mode in SortMode.entries) {
                                    DropdownMenuItem(
                                        text = { Text("依${mode.label}排序") },
                                        onClick = { sortMode = mode; sortMenuOpen = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { state.screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "設定")
                        }
                    },
                )
                if (state.library.books.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("搜尋書名…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, contentDescription = "清除") } }
                        } else null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (allTags.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = activeTag == null,
                                    onClick = { activeTag = null },
                                    label = { Text("全部") },
                                )
                            }
                            items(allTags) { tag ->
                                FilterChip(
                                    selected = activeTag == tag,
                                    onClick = { activeTag = if (activeTag == tag) null else tag },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startImport() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("匯入書籍") },
            )
        },
    ) { padding ->
        if (state.library.books.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("書架是空的", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.platform.archive != null)
                        "點右下角「匯入書籍」選擇 txt / epub 或壓縮檔開始閱讀"
                    else
                        "點右下角「匯入書籍」選擇 txt 或 epub 檔開始閱讀",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (visibleBooks.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("沒有符合的書籍", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleBooks, key = { it.fingerprint }) { book ->
                    val progress = progressMap[book.fingerprint]
                    BookCard(
                        state = state,
                        book = book,
                        progress = progress,
                        onClick = { state.openBook(book) },
                        onLongClick = { optionsTarget = book },
                    )
                }
            }
        }
    }

    // 匯入進度
    when (val st = importState) {
        is ImportState.Progress -> AlertDialog(
            onDismissRequest = {},
            title = { Text("匯入中…") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { st.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在偵測編碼並建立章節目錄")
                }
            },
            confirmButton = {},
        )
        is ImportState.Error -> AlertDialog(
            onDismissRequest = { importState = null },
            title = { Text("匯入失敗") },
            text = { Text(st.message) },
            confirmButton = {
                TextButton(onClick = { importState = null }) { Text("確定") }
            },
        )
        else -> {}
    }

    // 壓縮檔匯入：掃描中 / 勾選 / 批次進度 / 總結
    if (archiveScanning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("掃描壓縮檔…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }
    archivePicker?.let { (archivePath, entries) ->
        ArchiveEntryPickerDialog(
            entries = entries,
            onConfirm = { selected ->
                archivePicker = null
                scope.launch { importArchiveEntries(archivePath, selected) }
            },
            onDismiss = { archivePicker = null },
        )
    }
    batchProgress?.let { BatchImportProgressDialog(it) }
    batchSummary?.let { BatchImportSummaryDialog(it) { batchSummary = null } }

    // 書籍選項（長按觸發）
    optionsTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { optionsTarget = null },
            title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = { tagEditTarget = book; optionsTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("編輯標籤") }
                    TextButton(
                        onClick = { removeTarget = book; optionsTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("移除書籍") }
                }
            },
            confirmButton = {
                TextButton(onClick = { optionsTarget = null }) { Text("取消") }
            },
        )
    }

    // 標籤編輯
    tagEditTarget?.let { target ->
        val live = state.library.books.find { it.fingerprint == target.fingerprint } ?: target
        var customTag by remember(live.fingerprint) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { tagEditTarget = null },
            title = { Text("編輯標籤：${live.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (tag in QUICK_TAGS) {
                            FilterChip(
                                selected = tag in live.tags,
                                onClick = { toggleTag(live.fingerprint, tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                    val customTags = live.tags.filterNot { it in QUICK_TAGS }
                    if (customTags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (tag in customTags) {
                                FilterChip(
                                    selected = true,
                                    onClick = { toggleTag(live.fingerprint, tag) },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customTag,
                            onValueChange = { customTag = it },
                            singleLine = true,
                            placeholder = { Text("自訂標籤") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val t = customTag.trim()
                            if (t.isNotEmpty() && t !in live.tags) toggleTag(live.fingerprint, t)
                            customTag = ""
                        }) { Text("新增") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tagEditTarget = null }) { Text("完成") }
            },
        )
    }

    // 移除確認
    removeTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除書籍") },
            text = { Text("確定要從書架移除《${book.title}》嗎？\n（不會刪除原始檔案）") },
            confirmButton = {
                TextButton(onClick = {
                    state.removeBook(book)
                    removeTarget = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("取消") }
            },
        )
    }

    // 匯入時發現書名相似的書
    duplicatePrompt?.let { (newMeta, oldMeta) ->
        AlertDialog(
            onDismissRequest = { duplicatePrompt = null; state.openBook(newMeta) },
            title = { Text("發現相似書名") },
            text = {
                Text(
                    "書架上已經有《${oldMeta.title}》，剛剛匯入的《${newMeta.title}》書名很像，" +
                        "但檔案不同（可能是不同編碼或來源），要怎麼處理？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.removeBook(oldMeta)
                    duplicatePrompt = null
                    state.openBook(newMeta)
                }) { Text("刪除原本那本") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        state.removeBook(newMeta)
                        duplicatePrompt = null
                    }) { Text("刪除新匯入的") }
                    TextButton(onClick = {
                        duplicatePrompt = null
                        state.openBook(newMeta)
                    }) { Text("保留兩本") }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    state: AppState,
    book: BookMeta,
    progress: ReadingProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var cover by remember(book.fingerprint) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(book.fingerprint, book.coverPath) {
        if (book.coverPath == null) return@LaunchedEffect
        cover = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = state.stores.coverFile(book.fingerprint).takeIf { it.isFile }?.readBytes()
                bytes?.let { state.platform.decodeImage(it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            cover?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .width(48.dp)
                        .height(64.dp)
                        .padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${book.chapterCount} 章 · ${book.charset}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatRelativeTime(progress?.updatedAt ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (book.tags.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (tag in book.tags) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress?.percent ?: 0f },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatPercent(progress?.percent ?: 0f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
