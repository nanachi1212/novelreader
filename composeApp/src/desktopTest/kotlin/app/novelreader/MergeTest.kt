package app.novelreader

import app.novelreader.core.model.Bookmark
import app.novelreader.core.model.ReadingProgress
import app.novelreader.core.model.SyncRecord
import app.novelreader.data.mergeSyncRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeTest {

    private fun progress(chapter: Int, updatedAt: Long, device: String) =
        ReadingProgress("fp", chapterIndex = chapter, updatedAt = updatedAt, deviceId = device)

    private fun bookmark(id: String, createdAt: Long, deleted: Boolean = false) =
        Bookmark(id, "fp", 0, 0, "摘錄", createdAt, "dev", deleted)

    @Test
    fun `進度取較新者`() {
        val a = SyncRecord(progress = progress(10, updatedAt = 2000, device = "pc"))
        val b = SyncRecord(progress = progress(5, updatedAt = 1000, device = "phone"))
        assertEquals(10, mergeSyncRecords(a, b).progress.chapterIndex)
        assertEquals(10, mergeSyncRecords(b, a).progress.chapterIndex)
    }

    @Test
    fun `書籤聯集`() {
        val a = SyncRecord(progress = progress(1, 1, "pc"), bookmarks = listOf(bookmark("A", 1)))
        val b = SyncRecord(progress = progress(1, 2, "ph"), bookmarks = listOf(bookmark("B", 2)))
        val merged = mergeSyncRecords(a, b)
        assertEquals(setOf("A", "B"), merged.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun `刪除標記勝過較舊的存活版本`() {
        val now = System.currentTimeMillis()
        val alive = SyncRecord(progress = progress(1, 1, "pc"), bookmarks = listOf(bookmark("A", now - 1000)))
        val deleted = SyncRecord(progress = progress(1, 2, "ph"), bookmarks = listOf(bookmark("A", now, deleted = true)))
        val merged = mergeSyncRecords(alive, deleted)
        assertTrue(merged.bookmarks.single { it.id == "A" }.deleted)
    }

    @Test
    fun `過期 tombstone 被清除`() {
        val old = System.currentTimeMillis() - 100L * 24 * 3600 * 1000
        val a = SyncRecord(progress = progress(1, 1, "pc"), bookmarks = listOf(bookmark("A", old, deleted = true)))
        val merged = mergeSyncRecords(a, a)
        assertTrue(merged.bookmarks.isEmpty())
    }
}
