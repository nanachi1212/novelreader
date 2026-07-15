package app.novelreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EncodingDialog(
    currentCharset: String,
    onDismiss: () -> Unit,
    onReimport: (String) -> Unit,
) {
    val options = listOf("AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5")
    var selected by remember { mutableStateOf(currentCharset) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新匯入（編碼覆寫）") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("目前編碼：$currentCharset", Modifier.padding(bottom = 16.dp))
                for (opt in options) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        RadioButton(
                            selected = selected == opt,
                            onClick = { selected = opt },
                        )
                        Text(opt, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onReimport(selected) },
                enabled = selected != currentCharset,
            ) { Text("確認覆寫") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
