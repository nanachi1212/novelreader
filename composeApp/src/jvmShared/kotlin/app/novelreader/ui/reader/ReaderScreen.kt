package app.novelreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.SyncRecord
import app.novelreader.reader.ChapterLoader
import app.novelreader.ui.AppState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(state: AppState, meta: BookMeta) {
    val scope = rememberCoroutineScope()
    val settings = state.settings

    var loader by remember { mutableStateOf<ChapterLoader?>(null) }
    var record by remember { mutableStateOf<SyncRecord?>(null) }
    var chapter by remember { mutableStateOf<ChapterLoader.Chapter?>(null) }
    var pendingScrollTo by remember { mutableStateOf<Int?>(null) }
    var chapterLoadTick by remember { mutableStateOf(0) }
    var chromeVisible by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showEncodingDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val tocListState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val focusRequester = remember { FocusRequester() }

    val fontFamily = remember(settings.fontId, state) {
        state.platform.resolveFontFamily(settings.fontId)
    }

    fun updateProgress(chapterIndex: Int, paragraphIndex: Int) {
        val ld = loader ?: return
        val rec = record ?: return
        val entry = ld.chapters.getOrNull(chapterIndex) ?: return
        val paraCount = chapter?.paragraphs?.size ?: 1
        val fraction = if (paraCount <= 0) 0f else paragraphIndex.toFloat() / paraCount
        val totalBytes = meta.totalBytes.coerceAtLeast(1)
        val percent =
            ((entry.byteStart + fraction * (entry.byteEnd - entry.byteStart)) / totalBytes)
                .toFloat().coerceIn(0f, 1f)
        val updated = rec.copy(
            progress = rec.progress.copy(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                percent = percent,
                updatedAt = System.currentTimeMillis(),
                deviceId = settings.deviceId,
            )
        )
        record = updated
        state.scope.launch { state.stores.saveBookData(meta.fingerprint, updated) }
    }

    fun openChapter(index: Int, paragraph: Int = 0) {
        val ld = loader ?: return
        if (ld.chapters.isEmpty()) return
        val target = index.coerceIn(0, ld.chapters.lastIndex)
        scope.launch {
            chapter = ld.load(target)
            pendingScrollTo = paragraph
            chapterLoadTick++
            updateProgress(target, paragraph)
            // 預載相鄰章節
            if (target + 1 <= ld.chapters.lastIndex) scope.launch { ld.load(target + 1) }
            if (target - 1 >= 0) scope.launch { ld.load(target - 1) }
        }
    }

    // 開書：載章節索引 + 進度，跳到上次位置
    LaunchedEffect(meta.fingerprint) {
        val chapters = state.stores.loadChapters(meta.fingerprint) ?: emptyList()
        val ld = ChapterLoader(state.stores.contentFile(meta.fingerprint), chapters)
        loader = ld
        val rec = state.stores.loadBookData(meta.fingerprint)
            ?: state.stores.defaultBookData(meta.fingerprint)
        record = rec
        if (chapters.isNotEmpty()) {
            val idx = rec.progress.chapterIndex.coerceIn(0, chapters.lastIndex)
            chapter = ld.load(idx)
            pendingScrollTo = rec.progress.paragraphIndex
            chapterLoadTick++
        }
    }

    // 章節載入後還原捲動位置
    LaunchedEffect(chapterLoadTick) {
        val target = pendingScrollTo ?: return@LaunchedEffect
        val ch = chapter ?: return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, ch.paragraphs.size))
        pendingScrollTo = null
        focusRequester.requestFocus()
    }

    // 捲動位置 → 進度（1 秒防抖）
    LaunchedEffect(loader) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(1000)
            .collect { para ->
                val ch = chapter ?: return@collect
                if (pendingScrollTo == null) {
                    updateProgress(ch.index, para.coerceIn(0, ch.paragraphs.size))
                }
            }
    }

    // 進出畫面：保持螢幕常亮、註冊立即保存 hook
    DisposableEffect(meta.fingerprint) {
        state.platform.keepScreenOn(true)
        state.readerFlush = {
            record?.let { state.stores.saveBookData(meta.fingerprint, it) }
        }
        onDispose {
            state.platform.keepScreenOn(false)
            state.readerFlush = null
            val rec = record
            if (rec != null) {
                state.scope.launch { state.stores.saveBookData(meta.fingerprint, rec) }
            }
        }
    }

    // TOC 打開時捲到當前章節
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            chapter?.let { tocListState.scrollToItem(it.index.coerceAtLeast(0)) }
        }
    }

    val ld = loader
    val ch = chapter

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(state = tocListState, modifier = Modifier.fillMaxSize()) {
                    items(ld?.chapters?.size ?: 0) { i ->
                        val entry = ld!!.chapters[i]
                        NavigationDrawerItem(
                            label = {
                                Text(entry.title, maxLines = 1)
                            },
                            selected = i == (ch?.index ?: -1),
                            onClick = {
                                scope.launch { drawerState.close() }
                                openChapter(i)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(focusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                    when (event.key) {
                        Key.DirectionRight -> {
                            openChapter((ch?.index ?: 0) + 1); true
                        }
                        Key.DirectionLeft -> {
                            openChapter((ch?.index ?: 0) - 1); true
                        }
                        Key.PageDown, Key.Spacebar -> {
                            scope.launch { listState.animateScrollBy(viewport * 0.9f) }; true
                        }
                        Key.PageUp -> {
                            scope.launch { listState.animateScrollBy(-viewport * 0.9f) }; true
                        }
                        Key.Escape -> {
                            state.backToShelf(); true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { chromeVisible = !chromeVisible }
                },
        ) {
            if (ld == null || ch == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val paragraphStyle = TextStyle(
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineHeightMult).sp,
                    fontFamily = fontFamily,
                    // 首行縮排兩個字；Compose Desktop（Skiko）不支援 em，必須用 sp
                    textIndent = TextIndent(firstLine = (settings.fontSizeSp * 2).sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = settings.marginHorizontalDp.dp,
                        vertical = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy((settings.fontSizeSp * 0.55f).dp),
                ) {
                    items(ch.paragraphs.size) { i ->
                        Text(
                            text = ch.paragraphs[i],
                            style = paragraphStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (ch.index < ld.chapters.lastIndex) {
                                TextButton(onClick = { openChapter(ch.index + 1) }) {
                                    Text("下一章：${ld.chapters[ch.index + 1].title}", maxLines = 1)
                                }
                            } else {
                                Text(
                                    "— 全書完 —",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 頂部 chrome
            if (chromeVisible && ch != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { state.backToShelf() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回書架")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                meta.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                ch.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            "${ch.index + 1} / ${ld?.chapters?.size ?: 0}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }

            // 底部 chrome
            if (chromeVisible && ch != null && ld != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目錄")
                        }
                        IconButton(
                            onClick = { openChapter(ch.index - 1) },
                            enabled = ch.index > 0,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章")
                        }
                        IconButton(
                            onClick = { openChapter(ch.index + 1) },
                            enabled = ch.index < ld.chapters.lastIndex,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章")
                        }
                        TextButton(onClick = { showSettingsSheet = true }) {
                            Text("Aa", style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = { showEncodingDialog = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(state = state, onDismiss = { showSettingsSheet = false })
    }

    if (showEncodingDialog) {
        EncodingDialog(
            currentCharset = meta.charset,
            onDismiss = { showEncodingDialog = false },
            onReimport = { charset ->
                scope.launch {
                    state.repository.reimportWithCharset(meta, charset).collect { st ->
                        if (st is app.novelreader.data.BookRepository.ImportState.Done) {
                            state.refreshLibrary()
                            showEncodingDialog = false
                        }
                    }
                }
            },
        )
    }
}
