package app.novelreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.novelreader.ui.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(state: AppState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = { state.backToShelf() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("同步資料夾") },
                supportingContent = {
                    Text(state.settings.syncFolderUri ?: "尚未設定（下一版提供進度同步）")
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("關於") },
                supportingContent = { Text("輕閱 NovelReader v0.1.0") },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "小提示：在閱讀畫面點一下畫面中央可叫出工具列；" +
                    "桌面版可用 ←/→ 切換章節、PgUp/PgDn 翻頁。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}
