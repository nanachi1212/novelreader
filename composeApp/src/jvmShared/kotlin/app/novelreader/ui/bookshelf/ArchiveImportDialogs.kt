package app.novelreader.ui.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novelreader.platform.ArchiveEntryInfo

/** 批次匯入進度（index 從 1 起） */
data class BatchProgress(val index: Int, val total: Int, val name: String, val fraction: Float)

data class BatchSummary(
    val imported: Int,
    val skippedExisting: Int,
    /** 匯入成功但書名跟現有書相似（批次模式不逐一詢問，一律保留兩本） */
    val similarTitle: Int,
    /** 檔名 to 失敗原因 */
    val failures: List<Pair<String, String>>,
)

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

/** 壓縮檔內書檔勾選對話框 */
@Composable
fun ArchiveEntryPickerDialog(
    entries: List<ArchiveEntryInfo>,
    onConfirm: (List<ArchiveEntryInfo>) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(entries.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇要匯入的書籍（${entries.size} 個檔案）") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked.size == entries.size,
                        onCheckedChange = { all ->
                            checked = if (all) entries.toSet() else emptySet()
                        },
                    )
                    Text("全選", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(entries) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checked = if (entry in checked) checked - entry else checked + entry
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entry in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + entry else checked - entry
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val size = formatSize(entry.sizeBytes)
                                if (size.isNotEmpty()) {
                                    Text(
                                        size,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.isNotEmpty(),
                // 依原始順序匯入，不依勾選順序
                onClick = { onConfirm(entries.filter { it in checked }) },
            ) { Text("匯入（${checked.size} 本）") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 批次匯入進度（不可關閉） */
@Composable
fun BatchImportProgressDialog(progress: BatchProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("匯入中…（第 ${progress.index} / ${progress.total} 本）") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(progress.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = {},
    )
}

/** 批次匯入結果總結 */
@Composable
fun BatchImportSummaryDialog(summary: BatchSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯入完成") },
        text = {
            Column {
                Text("成功匯入 ${summary.imported} 本")
                if (summary.skippedExisting > 0) Text("已在書架（略過）${summary.skippedExisting} 本")
                if (summary.similarTitle > 0) Text("書名與現有書相似（已保留兩本）${summary.similarTitle} 本")
                if (summary.failures.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("失敗 ${summary.failures.size} 本：", color = MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(summary.failures) { (name, reason) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("確定") }
        },
    )
}
