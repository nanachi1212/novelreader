package app.novelreader

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.novelreader.platform.DesktopPlatform
import app.novelreader.ui.App
import app.novelreader.ui.AppState
import kotlinx.coroutines.runBlocking

fun main() {
    val appState = AppState(DesktopPlatform)
    runBlocking { appState.init() }
    appState.stores.cleanupTempFiles()
    val s = appState.settings

    application {
        val windowState = rememberWindowState(
            size = DpSize(s.windowWidth.coerceAtLeast(400).dp, s.windowHeight.coerceAtLeast(300).dp),
            position = if (s.windowX != Int.MIN_VALUE) {
                WindowPosition(s.windowX.dp, s.windowY.dp)
            } else {
                WindowPosition.PlatformDefault
            },
        )

        Window(
            onCloseRequest = {
                // 關窗前保存閱讀進度與視窗狀態
                runBlocking {
                    appState.flushNow()
                    try {
                        val size = windowState.size
                        val pos = windowState.position
                        appState.stores.saveSettings(
                            appState.settings.copy(
                                windowWidth = size.width.value.toInt(),
                                windowHeight = size.height.value.toInt(),
                                windowX = if (pos is WindowPosition.Absolute) pos.x.value.toInt() else Int.MIN_VALUE,
                                windowY = if (pos is WindowPosition.Absolute) pos.y.value.toInt() else Int.MIN_VALUE,
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                DesktopPlatform.tts?.shutdown()
                exitApplication()
            },
            state = windowState,
            title = "輕閱 NovelReader",
        ) {
            App(appState)
        }
    }
}
