package app.novelreader

import app.novelreader.platform.BookSource
import java.io.ByteArrayInputStream
import java.io.InputStream

class BytesSource(
    private val bytes: ByteArray,
    override val displayName: String = "test.txt",
) : BookSource {
    override val sizeBytes: Long = bytes.size.toLong()
    override val uriOrPath: String = "mem://test"
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}
