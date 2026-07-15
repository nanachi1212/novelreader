package app.novelreader.ui.bookshelf

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import app.novelreader.ui.AppState
import app.novelreader.ui.Screen
import app.novelreader.ui.formatPercent
import app.novelreader.ui.formatRelativeTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var importState by remember { mutableStateOf<ImportState?>(null) }
    var removeTarget by remember { mutableStateOf<BookMeta?>(null) }
    var progressMap by remember { mutableStateOf(emptyMap<String, ReadingProgress>()) }

    LaunchedEffect(state.library) {
        val map = HashMap<String, ReadingProgress>()
        for (book in state.library.books) {
            state.stores.loadBookData(book.fingerprint)?.let { map[book.fingerprint] = it.progress }
        }
        progressMap = map
    }

    fun startImport() {
        scope.launch {
            val source = state.platform.pickBookFile() ?: return@launch
            state.repository.import(source).collect { st ->
                importState = st
                if (st is ImportState.Done) {
                    state.refreshLibrary()
                    importState = null
                    state.openBook(st.meta)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("書架") },
                actions = {
                    IconButton(onClick = { state.screen = Screen.Settings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startImport() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("匯入書籍") },
            )
        },
    ) { padding ->
        val books = state.library.books.sortedByDescending { book ->
            progressMap[book.fingerprint]?.updatedAt?.takeIf { it > 0 } ?: book.importedAt
        }

        if (books.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("書架是空的", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "點右下角「匯入書籍」選擇 txt 檔開始閱讀",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(books, key = { it.fingerprint }) { book ->
                    val progress = progressMap[book.fingerprint]
                    BookCard(
                        book = book,
                        progress = progress,
                        onClick = { state.openBook(book) },
                        onLongClick = { removeTarget = book },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookMeta,
    progress: ReadingProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
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
