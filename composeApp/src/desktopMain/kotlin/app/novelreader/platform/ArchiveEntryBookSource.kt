package app.novelreader.platform

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * 壓縮檔內單一 entry 的 BookSource：懶解壓到暫存檔。
 * resolveSource() 可能在主執行緒被呼叫，因此建構時不做任何 IO；
 * 真正解壓延到首次存取 sizeBytes / open()（都發生在 import() 的 IO dispatcher 內）。
 * 暫存檔 deleteOnExit；異常結束的殘留由啟動時的 tmp 清理收拾。
 */
class ArchiveEntryBookSource(
    private val archiveFile: File,
    private val entryPath: String,
    override val displayName: String,
    private val tempDir: File,
) : BookSource {
    private var extracted: File? = null

    override val uriOrPath: String get() = ArchiveUri.build(archiveFile.absolutePath, entryPath)
    override val sizeBytes: Long get() = ensureExtracted().length()
    override fun open(): InputStream = ensureExtracted().inputStream()

    @Synchronized
    private fun ensureExtracted(): File {
        extracted?.takeIf { it.isFile }?.let { return it }
        tempDir.mkdirs()
        val suffix = "." + displayName.substringAfterLast('.', "tmp")
        val dest = File(tempDir, "${UUID.randomUUID()}$suffix")
        try {
            DesktopArchiveSupport.extractEntry(archiveFile, entryPath, dest)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        dest.deleteOnExit()
        extracted = dest
        return dest
    }
}
