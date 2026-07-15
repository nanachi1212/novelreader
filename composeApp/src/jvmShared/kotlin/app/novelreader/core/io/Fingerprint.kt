package app.novelreader.core.io

import app.novelreader.platform.BookSource
import java.io.InputStream
import java.security.MessageDigest

/**
 * 書籍指紋：v1-{檔案大小}-{SHA-256(前64KB‖後64KB) 前16 hex}
 * 改名/搬移不影響；與編碼無關。串流無法跳到檔尾時退化為只雜湊前 64KB（scheme "v1f"）。
 */
object Fingerprint {

    private const val CHUNK = 64 * 1024

    fun compute(source: BookSource): String {
        val size = source.sizeBytes
        source.open().use { ins ->
            val head = ByteArray(CHUNK)
            val headLen = readFully(ins, head)

            var scheme = "v1"
            var tail = ByteArray(0)
            var tailLen = 0

            if (size > CHUNK.toLong() * 2) {
                val toSkip = size - CHUNK.toLong() * 2
                if (skipFully(ins, toSkip)) {
                    tail = ByteArray(CHUNK)
                    tailLen = readFully(ins, tail)
                } else {
                    scheme = "v1f"
                }
            } else if (headLen == CHUNK) {
                // 檔案介於 64KB 與 128KB：剩餘部分全部當 tail
                tail = ByteArray(CHUNK)
                tailLen = readFully(ins, tail)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(head, 0, headLen)
            if (tailLen > 0) digest.update(tail, 0, tailLen)
            val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            return "$scheme-$size-$hex"
        }
    }

    /** 讀滿 buffer 或到 EOF，回傳實際讀取數（Android minSdk 26 無 readNBytes，手動迴圈） */
    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** 盡力跳過 n bytes；skip 不動時退化為丟棄式讀取 */
    private fun skipFully(ins: InputStream, n: Long): Boolean {
        var remaining = n
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0) {
            val skipped = ins.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val r = ins.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (r < 0) return false
                remaining -= r
            }
        }
        return true
    }
}
