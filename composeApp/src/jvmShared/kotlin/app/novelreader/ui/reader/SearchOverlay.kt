package app.novelreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.novelreader.reader.ChapterLoader
import app.novelreader.reader.SearchEngine
import app.novelreader.reader.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchOverlay(
    loader: ChapterLoader,
    onJump: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val engine = remember(loader) { SearchEngine(loader) }
    val scope = rememberSearchScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun runSearch(q: String) {
        searchJob?.cancel()
        results = emptyList()
        if (q.isBlank()) {
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            val acc = ArrayList<SearchResult>()
            engine.search(q).collect { r ->
                acc.add(r)
                if (acc.size % 5 == 0) results = acc.toList()
            }
            results = acc.toList()
            searching = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                runSearch(it)
                            },
                            singleLine = true,
                            placeholder = { Text("搜尋書中內容…") },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "關閉")
                        }
                    },
                )
                HorizontalDivider()

                when {
                    searching && results.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    query.isNotBlank() && results.isEmpty() && !searching -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("找不到「$query」", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (searching) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        "搜尋中…已找到 ${results.size} 筆",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(results, key = { "${it.chapterIndex}-${it.paragraphIndex}" }) { r ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onJump(r.chapterIndex, r.paragraphIndex)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    r.chapterTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    r.excerpt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSearchScope() = androidx.compose.runtime.rememberCoroutineScope()
