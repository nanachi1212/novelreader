package app.novelreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.novelreader.core.model.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    deviceId: String,
    canAddCurrent: Boolean,
    onAddCurrent: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onJump: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    currentChapterIndex: Int,
    currentParagraphIndex: Int,
    currentExcerpt: String,
) {
    val sorted = bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.paragraphIndex }))
    val dateFormat = remember(Unit) { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 480.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("書籤", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    enabled = canAddCurrent,
                    onClick = {
                        onAddCurrent(
                            Bookmark(
                                id = UUID.randomUUID().toString(),
                                fingerprint = "",
                                chapterIndex = currentChapterIndex,
                                paragraphIndex = currentParagraphIndex,
                                excerpt = currentExcerpt.take(60),
                                createdAt = System.currentTimeMillis(),
                                deviceId = deviceId,
                            )
                        )
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Text("加入目前位置")
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("尚無書籤", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    items(sorted, key = { it.id }) { bm ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onJump(bm.chapterIndex, bm.paragraphIndex); onDismiss() }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    dateFormat.format(Date(bm.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    bm.excerpt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                            }
                            IconButton(onClick = { onDelete(bm) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "刪除書籤")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
