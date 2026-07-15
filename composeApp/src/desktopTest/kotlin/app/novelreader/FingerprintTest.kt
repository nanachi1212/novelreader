package app.novelreader

import app.novelreader.core.io.Fingerprint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FingerprintTest {

    @Test
    fun `相同內容不同檔名指紋相同`() {
        val bytes = Random(42).nextBytes(300 * 1024)
        val a = Fingerprint.compute(BytesSource(bytes, "a.txt"))
        val b = Fingerprint.compute(BytesSource(bytes, "改名後.txt"))
        assertEquals(a, b)
    }

    @Test
    fun `不同內容指紋不同`() {
        val a = Fingerprint.compute(BytesSource(Random(1).nextBytes(200 * 1024)))
        val b = Fingerprint.compute(BytesSource(Random(2).nextBytes(200 * 1024)))
        assertNotEquals(a, b)
    }

    @Test
    fun `小檔案也能算指紋`() {
        val fp = Fingerprint.compute(BytesSource("你好".toByteArray()))
        assertTrue(fp.startsWith("v1-6-"))
    }

    @Test
    fun `指紋格式`() {
        val bytes = Random(7).nextBytes(500 * 1024)
        val fp = Fingerprint.compute(BytesSource(bytes))
        assertTrue(Regex("""v1f?-\d+-[0-9a-f]{16}""").matches(fp), "unexpected: $fp")
    }
}
