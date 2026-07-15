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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.novelreader.ui.AppState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(state: AppState) {
    val scope = rememberCoroutineScope()

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
                    Text(state.syncFolderUri?.let { uri ->
                        state.platform.resolveSyncFolder(uri)?.displayPath ?: "（路徑無效）"
                    } ?: "未設定")
                },
                trailingContent = {
                    TextButton(onClick = {
                        scope.launch {
                            val folder = state.platform.pickSyncFolder()
                            if (folder != null) {
                                state.updateSettings { it.copy(syncFolderUri = folder.displayPath) }
                            }
                        }
                    }) {
                        Text("選擇")
                    }
                },
            )
            if (state.syncFolderUri != null) {
                ListItem(
                    headlineContent = { Text("") },
                    trailingContent = {
                        TextButton(onClick = { state.updateSettings { it.copy(syncFolderUri = null) } }) {
                            Text("清除")
                        }
                    },
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("關於") },
                supportingContent = { Text("輕閱 NovelReader M2") },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "進度自動同步到選定的資料夾（Google Drive / Autosync 都可以）。" +
                    "在閱讀畫面點中央叫工具列；桌面版快捷鍵：←/→ 換章、PgUp/PgDn 翻頁。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}
