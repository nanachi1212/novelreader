package app.novelreader.platform

import app.novelreader.core.detect.CharsetDetector
import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarVersionException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.PasswordRequiredException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * zip / 7z / rar 的列舉與解壓。
 * - zip 走 commons-compress（非 java.util.zip）：可拿 entry 名 raw bytes 自行偵測 GBK/Big5 檔名，
 *   也能從 generalPurposeBit 偵測加密
 * - 7z 需要 org.tukaani:xz（LZMA2）；加密以 PasswordRequiredException 呈現
 * - rar 用 junrar，僅支援 RAR4；RAR5 轉成明確錯誤訊息
 * 解壓落點一律是呼叫端給的 UUID 暫存檔，entry 路徑不參與檔案落點（無 zip-slip 面向）。
 */
object DesktopArchiveSupport : ArchiveSupport {

    /** 單一 entry 解壓上限（zip-bomb 防護） */
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024

    private val bookExts = listOf(".txt", ".epub")
    private val archiveExts = listOf(".zip", ".rar", ".7z")

    override fun isArchivePath(path: String): Boolean =
        archiveExts.any { path.endsWith(it, ignoreCase = true) }

    override fun listBookEntries(archiveFile: File): List<ArchiveEntryInfo> = when {
        archiveFile.name.endsWith(".zip", true) -> listZip(archiveFile)
        archiveFile.name.endsWith(".7z", true) -> list7z(archiveFile)
        archiveFile.name.endsWith(".rar", true) -> listRar(archiveFile)
        else -> throw ArchiveException("不支援的壓縮格式：${archiveFile.name}")
    }

    override fun extractEntry(archiveFile: File, entryPath: String, destFile: File): Long = when {
        archiveFile.name.endsWith(".zip", true) -> extractZip(archiveFile, entryPath, destFile)
        archiveFile.name.endsWith(".7z", true) -> extract7z(archiveFile, entryPath, destFile)
        archiveFile.name.endsWith(".rar", true) -> extractRar(archiveFile, entryPath, destFile)
        else -> throw ArchiveException("不支援的壓縮格式：${archiveFile.name}")
    }

    private fun isBookName(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return bookExts.any { name.endsWith(it, ignoreCase = true) } &&
            !name.startsWith(".") &&
            !path.contains("__MACOSX/")
    }

    // ---- zip ----

    private fun openZip(f: File): ZipFile = try {
        ZipFile.builder().setFile(f).get()
    } catch (e: Exception) {
        throw ArchiveException("無法開啟壓縮檔（檔案可能損壞）：${e.message}", e)
    }

    /**
     * 解出 entry → 解碼後路徑（'/' 分隔）。
     * EFS 旗標未設的名稱把所有 raw bytes 併起來用 CharsetDetector 偵測一次
     * （比逐一偵測準：檔名太短時單獨偵測易誤判），再逐一解碼。
     */
    private fun zipEntryNames(zip: ZipFile): List<Pair<ZipArchiveEntry, String>> {
        val entries = java.util.Collections.list(zip.entries)
        val rawBlob = java.io.ByteArrayOutputStream()
        for (e in entries) {
            if (!e.generalPurposeBit.usesUTF8ForNames()) {
                e.rawName?.let { rawBlob.write(it); rawBlob.write('\n'.code) }
            }
        }
        val legacyCharset: Charset? =
            if (rawBlob.size() > 0) CharsetDetector.detect(rawBlob.toByteArray()) else null

        return entries.map { e ->
            val raw = e.rawName
            val name = when {
                e.generalPurposeBit.usesUTF8ForNames() || raw == null -> e.name
                else -> decodeUtf8Strict(raw) ?: decodeLenient(raw, legacyCharset ?: Charsets.UTF_8)
            }
            e to name.replace('\\', '/')
        }
    }

    private fun listZip(f: File): List<ArchiveEntryInfo> = openZip(f).use { zip ->
        val books = zipEntryNames(zip).filter { (e, name) ->
            !e.isDirectory && e.size != 0L && isBookName(name)
        }
        books.firstOrNull { (e, _) -> e.generalPurposeBit.usesEncryption() }?.let {
            throw ArchiveException("此壓縮檔已加密，不支援匯入")
        }
        books.map { (e, name) ->
            ArchiveEntryInfo(name, name.substringAfterLast('/'), e.size)
        }
    }

    private fun extractZip(f: File, entryPath: String, destFile: File): Long = openZip(f).use { zip ->
        val (entry, _) = zipEntryNames(zip).firstOrNull { (_, name) -> name == entryPath }
            ?: throw ArchiveException("壓縮檔內找不到：$entryPath")
        if (entry.generalPurposeBit.usesEncryption()) throw ArchiveException("此壓縮檔已加密，不支援匯入")
        checkDeclaredSize(entry.size)
        zip.getInputStream(entry).use { copyLimited(it, destFile) }
    }

    // ---- 7z ----

    private fun open7z(f: File): SevenZFile = try {
        SevenZFile.builder().setFile(f).get()
    } catch (e: PasswordRequiredException) {
        throw ArchiveException("此壓縮檔已加密，不支援匯入", e)
    } catch (e: Exception) {
        throw ArchiveException("無法開啟壓縮檔（檔案可能損壞）：${e.message}", e)
    }

    private fun list7z(f: File): List<ArchiveEntryInfo> = open7z(f).use { sz ->
        sz.entries
            .filter { !it.isDirectory }
            .map { it.name.replace('\\', '/') to it.size }
            .filter { (name, size) -> size != 0L && isBookName(name) }
            .map { (name, size) -> ArchiveEntryInfo(name, name.substringAfterLast('/'), size) }
    }

    private fun extract7z(f: File, entryPath: String, destFile: File): Long = open7z(f).use { sz ->
        val entry = sz.entries.firstOrNull { it.name.replace('\\', '/') == entryPath }
            ?: throw ArchiveException("壓縮檔內找不到：$entryPath")
        checkDeclaredSize(entry.size)
        try {
            sz.getInputStream(entry).use { copyLimited(it, destFile) }
        } catch (e: PasswordRequiredException) {
            throw ArchiveException("此壓縮檔已加密，不支援匯入", e)
        }
    }

    // ---- rar（junrar） ----

    private fun <T> withRar(f: File, block: (Archive) -> T): T = try {
        Archive(f).use { archive ->
            if (archive.mainHeader?.isEncrypted == true) {
                throw ArchiveException("此壓縮檔已加密，不支援匯入")
            }
            block(archive)
        }
    } catch (e: UnsupportedRarVersionException) {
        throw ArchiveException("此 RAR 版本不支援，請改用 zip / 7z 重新壓縮，或先解壓縮後再匯入單檔", e)
    } catch (e: ArchiveException) {
        throw e
    } catch (e: RarException) {
        throw ArchiveException("無法開啟壓縮檔（檔案可能損壞）：${e.message}", e)
    }

    private fun listRar(f: File): List<ArchiveEntryInfo> = withRar(f) { archive ->
        archive.fileHeaders
            .filter { !it.isDirectory }
            .map { it to it.fileName.replace('\\', '/') }
            .filter { (h, name) -> h.fullUnpackSize != 0L && isBookName(name) }
            .onEach { (h, _) ->
                if (h.isEncrypted) throw ArchiveException("此壓縮檔已加密，不支援匯入")
            }
            .map { (h, name) -> ArchiveEntryInfo(name, name.substringAfterLast('/'), h.fullUnpackSize) }
    }

    private fun extractRar(f: File, entryPath: String, destFile: File): Long = withRar(f) { archive ->
        val header = archive.fileHeaders.firstOrNull {
            !it.isDirectory && it.fileName.replace('\\', '/') == entryPath
        } ?: throw ArchiveException("壓縮檔內找不到：$entryPath")
        if (header.isEncrypted) throw ArchiveException("此壓縮檔已加密，不支援匯入")
        checkDeclaredSize(header.fullUnpackSize)
        try {
            destFile.outputStream().use { out ->
                archive.extractFile(header, LimitedOutputStream(out))
            }
        } catch (e: RarException) {
            destFile.delete()
            throw ArchiveException("解壓失敗（檔案可能損壞）：${e.message}", e)
        }
        destFile.length()
    }

    // ---- 共用 ----

    private fun checkDeclaredSize(size: Long) {
        if (size > MAX_ENTRY_BYTES) throw ArchiveException("檔案過大，無法匯入")
    }

    /** 串流複製到 destFile，計數超限即中止（宣告大小可能造假，雙保險） */
    private fun copyLimited(input: InputStream, destFile: File): Long {
        var total = 0L
        destFile.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ENTRY_BYTES) {
                    destFile.delete()
                    throw ArchiveException("檔案過大，無法匯入")
                }
                out.write(buf, 0, n)
            }
        }
        return total
    }

    /** junrar 只吃 OutputStream，包一層做超限中止 */
    private class LimitedOutputStream(private val inner: OutputStream) : OutputStream() {
        private var total = 0L
        private fun check(n: Int) {
            total += n
            if (total > MAX_ENTRY_BYTES) throw ArchiveException("檔案過大，無法匯入")
        }

        override fun write(b: Int) { check(1); inner.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { check(len); inner.write(b, off, len) }
        override fun flush() = inner.flush()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeLenient(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()
}
