package app.novelreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.novelreader.ui.bookshelf.BookshelfScreen
import app.novelreader.ui.reader.ReaderScreen
import app.novelreader.ui.settings.AppSettingsScreen
import app.novelreader.ui.theme.NovelReaderTheme

@Composable
fun App(state: AppState) {
    LaunchedEffect(Unit) { state.init() }

    NovelReaderTheme(state.settings.theme) {
        Surface(Modifier.fillMaxSize()) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (val screen = state.screen) {
                    is Screen.Bookshelf -> BookshelfScreen(state)
                    is Screen.Reader -> ReaderScreen(state, screen.meta)
                    is Screen.Settings -> AppSettingsScreen(state)
                }
            }
        }
    }
}
