package app.novelreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.novelreader.core.model.AppTheme

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    background = Color(0xFFFDFBF7),
    surface = Color(0xFFFDFBF7),
    onBackground = Color(0xFF2B2B2B),
    onSurface = Color(0xFF2B2B2B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    background = Color(0xFF141414),
    surface = Color(0xFF141414),
    surfaceContainer = Color(0xFF1E1E1E),
    onBackground = Color(0xFFC8C8C8),
    onSurface = Color(0xFFC8C8C8),
)

/** 護眼（羊皮紙）主題 */
private val SepiaScheme = lightColorScheme(
    primary = Color(0xFF8B6B3D),
    background = Color(0xFFF5ECD9),
    surface = Color(0xFFF5ECD9),
    surfaceContainer = Color(0xFFEFE3CB),
    surfaceContainerHigh = Color(0xFFEADDC2),
    onBackground = Color(0xFF463A28),
    onSurface = Color(0xFF463A28),
    secondaryContainer = Color(0xFFE4D3B0),
)

@Composable
fun isAppDark(theme: AppTheme): Boolean = when (theme) {
    AppTheme.DARK -> true
    AppTheme.LIGHT, AppTheme.SEPIA -> false
    AppTheme.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun NovelReaderTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val scheme = when {
        theme == AppTheme.SEPIA -> SepiaScheme
        isAppDark(theme) -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
