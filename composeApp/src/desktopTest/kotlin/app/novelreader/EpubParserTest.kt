package app.novelreader

import app.novelreader.core.epub.EpubParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubParserTest {

    @Test
    fun `拒絕含外部實體的 EPUB XML`() {
        val file = File.createTempFile("novelreader-xxe", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?>
                <!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <container><rootfiles><rootfile full-path="&xxe;"/></rootfiles></container>
            """.trimIndent().toByteArray())
            zip.closeEntry()
        }

        assertFails { EpubParser.parse(file) }
    }

    private val containerXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val contentOpf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>測試小說</dc:title>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="cover-img" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
            <item id="chap2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="chap1"/>
            <itemref idref="chap2"/>
          </spine>
        </package>
    """.trimIndent()

    private val navXhtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc">
              <ol>
                <li><a href="chap1.xhtml">第一章 風雪夜</a></li>
                <li><a href="chap2.xhtml">第二章 山中歲月</a></li>
              </ol>
            </nav>
          </body>
        </html>
    """.trimIndent()

    private val chap1Xhtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <p>大雪紛飛，天地一片蒼茫。</p>
          <p>他推開門，寒風撲面而來。</p>
        </body></html>
    """.trimIndent()

    private val chap2Xhtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <p>歲月如梭，轉眼三年過去了。</p>
        </body></html>
    """.trimIndent()

    private fun buildEpub(): File {
        val file = File.createTempFile("novelreader-test", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun write(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            write("META-INF/container.xml", containerXml.toByteArray(Charsets.UTF_8))
            write("OEBPS/content.opf", contentOpf.toByteArray(Charsets.UTF_8))
            write("OEBPS/nav.xhtml", navXhtml.toByteArray(Charsets.UTF_8))
            write("OEBPS/chap1.xhtml", chap1Xhtml.toByteArray(Charsets.UTF_8))
            write("OEBPS/chap2.xhtml", chap2Xhtml.toByteArray(Charsets.UTF_8))
            write("OEBPS/cover.jpg", byteArrayOf(1, 2, 3, 4))
        }
        return file
    }

    @Test
    fun `解析章節標題與段落`() {
        val parsed = EpubParser.parse(buildEpub())
        assertEquals(2, parsed.chapters.size)
        assertEquals("第一章 風雪夜", parsed.chapters[0].title)
        assertEquals("第二章 山中歲月", parsed.chapters[1].title)
        assertEquals(listOf("大雪紛飛，天地一片蒼茫。", "他推開門，寒風撲面而來。"), parsed.chapters[0].paragraphs)
        assertEquals(listOf("歲月如梭，轉眼三年過去了。"), parsed.chapters[1].paragraphs)
    }

    @Test
    fun `抽取封面圖片位元組`() {
        val parsed = EpubParser.parse(buildEpub())
        assertNotNull(parsed.coverBytes)
        assertTrue(parsed.coverBytes!!.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `拒絕解壓後過大的 EPUB 項目`() {
        val file = File.createTempFile("novelreader-large-entry", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            val block = ByteArray(1024 * 1024)
            repeat(33) { zip.write(block) }
            zip.closeEntry()
        }

        assertFails { EpubParser.parse(file) }
    }
}
