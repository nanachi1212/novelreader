package app.novelreader.data

import app.novelreader.core.model.SyncRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class SyncManager(
    private val stores: AppStores,
    private val syncFolder: () -> app.novelreader.platform.SyncFolder?,
) {
    private val pending = ConcurrentHashMap<String, SyncRecord>()
    private val flushJobs = ConcurrentHashMap<String, Job>()

    /** 更新書籍進度，啟動防抖寫入 */
    fun scheduleFlush(fingerprint: String, record: SyncRecord, scope: kotlinx.coroutines.CoroutineScope, debounceMs: Long = 30_000) {
        pending[fingerprint] = record
        flushJobs[fingerprint]?.cancel()
        flushJobs[fingerprint] = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            flush(fingerprint)
            flushJobs.remove(fingerprint)
        }
    }

    /** 立即寫入（關書、onStop 時呼叫） */
    suspend fun flush(fingerprint: String) {
        val local = pending[fingerprint] ?: return
        withContext(Dispatchers.IO) {
            // 讀本機副本 + 同步 sidecar
            val stored = stores.loadBookData(fingerprint) ?: stores.defaultBookData(fingerprint)
            val remote = try {
                val folder = syncFolder() ?: return@withContext
                val bytes = folder.read("$fingerprint.json") ?: return@withContext
                stores.json.decodeFromString<SyncRecord>(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }

            // 合併：pending (剛更新) + stored (本機) + remote (同步)
            var merged = mergeSyncRecords(stored, local)
            if (remote != null) {
                merged = mergeSyncRecords(merged, remote)
            }

            // 寫本機
            stores.saveBookData(fingerprint, merged)

            // 寫同步 sidecar（失敗靜默）
            try {
                val folder = syncFolder() ?: return@withContext
                val json = stores.json.encodeToString(SyncRecord.serializer(), merged)
                folder.write("$fingerprint.json", json.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }

            pending.remove(fingerprint)
        }
    }

    /** 全部 flush（開書時對上次書也要 flush） */
    suspend fun flushAll() {
        val fps = pending.keys.toList()
        for (fp in fps) flush(fp)
    }

    /** 打開書籍時先讀同步 sidecar 與本機副本合併 */
    suspend fun loadMerged(fingerprint: String): SyncRecord = withContext(Dispatchers.IO) {
        val local = stores.loadBookData(fingerprint) ?: stores.defaultBookData(fingerprint)

        val remote = try {
            val folder = syncFolder() ?: return@withContext local
            val bytes = folder.read("$fingerprint.json") ?: return@withContext local
            stores.json.decodeFromString<SyncRecord>(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return@withContext local
        }

        val merged = mergeSyncRecords(local, remote)
        stores.saveBookData(fingerprint, merged)
        merged
    }
}
