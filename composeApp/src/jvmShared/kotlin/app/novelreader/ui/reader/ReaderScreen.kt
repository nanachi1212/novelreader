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
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novelreader.core.convert.ChineseConvert
import app.novelreader.core.model.BookMeta
import app.novelreader.core.model.SyncRecord
import app.novelreader.reader.ChapterLoader
import app.novelreader.ui.AppState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var highlightParagraph by remember { mutableStateOf<Int?>(null) }

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
        state.syncManager.scheduleFlush(meta.fingerprint, updated, state.scope)
    }

    /** 書籤增刪：立即（不防抖）寫入本機並排入同步 */
    fun mutateBookmarks(transform: (List<app.novelreader.core.model.Bookmark>) -> List<app.novelreader.core.model.Bookmark>) {
        val rec = record ?: return
        val updated = rec.copy(bookmarks = transform(rec.bookmarks))
        record = updated
        state.scope.launch {
            state.stores.saveBookData(meta.fingerprint, updated)
            state.syncManager.scheduleFlush(meta.fingerprint, updated, state.scope, debounceMs = 0)
        }
    }

    fun openChapter(index: Int, paragraph: Int = 0, highlight: Boolean = false, toEnd: Boolean = false) {
        val ld = loader ?: return
        if (ld.chapters.isEmpty()) return
        val target = index.coerceIn(0, ld.chapters.lastIndex)
        scope.launch {
            val loaded = ld.load(target)
            chapter = loaded
            val scrollTarget = if (toEnd) loaded.paragraphs.size else paragraph
            pendingScrollTo = scrollTarget
            chapterLoadTick++
            updateProgress(target, scrollTarget)
            if (highlight) {
                highlightParagraph = paragraph
                scope.launch { delay(1600); highlightParagraph = null }
            }
            // 預載相鄰章節
            if (target + 1 <= ld.chapters.lastIndex) scope.launch { ld.load(target + 1) }
            if (target - 1 >= 0) scope.launch { ld.load(target - 1) }
        }
    }

    /** 翻到頁尾／頁首時自動接下一章／上一章（上一章從章末開始，銜接翻頁方向） */
    fun pageForward(viewport: Float) {
        val ldNow = loader
        val chNow = chapter
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val atBottom = lastVisible != null &&
            lastVisible.index == info.totalItemsCount - 1 &&
            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
        if (atBottom && ldNow != null && chNow != null && chNow.index < ldNow.chapters.lastIndex) {
            openChapter(chNow.index + 1)
        } else {
            scope.launch { listState.animateScrollBy(viewport * 0.9f) }
        }
    }

    fun pageBackward(viewport: Float) {
        val chNow = chapter
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atTop && chNow != null && chNow.index > 0) {
            openChapter(chNow.index - 1, toEnd = true)
        } else {
            scope.launch { listState.animateScrollBy(-viewport * 0.9f) }
        }
    }

    // 開書：載章節索引 + 進度（先讀本機/同步合併），跳到上次位置
    LaunchedEffect(meta.fingerprint) {
        val chapters = state.stores.loadChapters(meta.fingerprint) ?: emptyList()
        val ld = ChapterLoader(state.stores.contentFile(meta.fingerprint), chapters).apply {
            transform = if (settings.s2tEnabled) { s -> ChineseConvert.toTraditional(s) } else null
        }
        loader = ld
        val rec = state.syncManager.loadMerged(meta.fingerprint)
        record = rec
        if (chapters.isNotEmpty()) {
            val idx = rec.progress.chapterIndex.coerceIn(0, chapters.lastIndex)
            chapter = ld.load(idx)
            pendingScrollTo = rec.progress.paragraphIndex
            chapterLoadTick++
        }
    }

    // 簡繁切換：清快取、重載目前章節（段落數不變，捲動位置不受影響）
    LaunchedEffect(settings.s2tEnabled, loader) {
        val ld = loader ?: return@LaunchedEffect
        ld.transform = if (settings.s2tEnabled) { s -> ChineseConvert.toTraditional(s) } else null
        ld.clearCache()
        val ch = chapter ?: return@LaunchedEffect
        chapter = ld.load(ch.index)
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

    // 進出畫面：保持螢幕常亮、註冊立即保存 hook（關窗/退出時同步 flush，不等 30 秒防抖）
    DisposableEffect(meta.fingerprint) {
        state.platform.keepScreenOn(true)
        state.readerFlush = {
            record?.let { state.stores.saveBookData(meta.fingerprint, it) }
            state.syncManager.flush(meta.fingerprint)
        }
        onDispose {
            state.platform.keepScreenOn(false)
            state.readerFlush = null
            val rec = record
            if (rec != null) {
                state.scope.launch {
                    state.stores.saveBookData(meta.fingerprint, rec)
                    state.syncManager.flush(meta.fingerprint)
                }
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
                        Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> {
                            pageForward(viewport); true
                        }
                        Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
                            pageBackward(viewport); true
                        }
                        Key.Escape -> {
                            state.backToShelf(); true
                        }
                        Key.F -> {
                            if (event.isCtrlPressed) { showSearchOverlay = true; true } else false
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

                SelectionContainer {
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
                            val highlighted = highlightParagraph == i
                            Text(
                                text = ch.paragraphs[i],
                                style = paragraphStyle,
                                modifier = Modifier.fillMaxWidth().let {
                                    if (highlighted) {
                                        it.background(MaterialTheme.colorScheme.primaryContainer)
                                    } else it
                                },
                            )
                        }
                        item {
                            DisableSelection {
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
                        TextButton(onClick = { showBookmarksSheet = true }) {
                            Text("書籤")
                        }
                        IconButton(onClick = { showSearchOverlay = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜尋")
                        }
                        TextButton(onClick = { showSettingsSheet = true }) {
                            Text("Aa", style = MaterialTheme.typography.titleMedium)
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (settings.s2tEnabled) "顯示原文（簡體）" else "轉換為繁體") },
                                    onClick = {
                                        showMoreMenu = false
                                        state.updateSettings { it.copy(s2tEnabled = !it.s2tEnabled) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("編碼設定") },
                                    onClick = {
                                        showMoreMenu = false
                                        showEncodingDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(state = state, onDismiss = { showSettingsSheet = false })
    }

    if (showBookmarksSheet) {
        val rec = record
        val curChapter = ch?.index ?: 0
        val curParagraph = listState.firstVisibleItemIndex
        val curExcerpt = ch?.paragraphs?.getOrNull(curParagraph) ?: ch?.title.orEmpty()
        BookmarksSheet(
            bookmarks = rec?.bookmarks?.filterNot { it.deleted } ?: emptyList(),
            deviceId = settings.deviceId,
            canAddCurrent = ch != null,
            currentChapterIndex = curChapter,
            currentParagraphIndex = curParagraph,
            currentExcerpt = curExcerpt,
            onAddCurrent = { bm ->
                mutateBookmarks { it + bm.copy(fingerprint = meta.fingerprint) }
            },
            onDelete = { bm ->
                mutateBookmarks { list ->
                    list.map {
                        if (it.id == bm.id) it.copy(deleted = true, createdAt = System.currentTimeMillis()) else it
                    }
                }
            },
            onJump = { chapterIndex, paragraphIndex -> openChapter(chapterIndex, paragraphIndex, highlight = true) },
            onDismiss = { showBookmarksSheet = false },
        )
    }

    if (showSearchOverlay && ld != null) {
        SearchOverlay(
            loader = ld,
            onJump = { chapterIndex, paragraphIndex -> openChapter(chapterIndex, paragraphIndex, highlight = true) },
            onDismiss = { showSearchOverlay = false },
        )
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
