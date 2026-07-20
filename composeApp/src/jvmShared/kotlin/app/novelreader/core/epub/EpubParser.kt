package app.novelreader.core.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import org.w3c.dom.Element as XmlElement
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB 解析：container.xml → OPF（manifest/spine）→ EPUB3 nav 或 EPUB2 NCX 目錄，
 * jsoup 抽正文（保留段落換行）。輸出結構跟既有 TXT 管線相容（章節標題 + 段落清單）。
 */
object EpubParser {

    data class ParsedChapter(val title: String, val paragraphs: List<String>)
    data class ParsedBook(val chapters: List<ParsedChapter>, val coverBytes: ByteArray?)

    private val blockTags = setOf(
        "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "tr", "section", "article", "br",
    )

    fun parse(epubFile: File): ParsedBook {
        ZipFile(epubFile).use { zip ->
            val containerXml = readEntry(zip, "META-INF/container.xml")
                ?: throw IllegalArgumentException("不是有效的 EPUB 檔案（找不到 container.xml）")
            val opfPath = parseContainer(containerXml)
            val opfBytes = readEntry(zip, opfPath)
                ?: throw IllegalArgumentException("EPUB 缺少 OPF 檔案：$opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val opf = parseOpf(opfBytes)
            val navTitles = loadNavTitles(zip, opf, opfDir)

            val coverBytes = opf.coverHref?.let { readEntry(zip, resolvePath(opfDir, it)) }

            val chapters = ArrayList<ParsedChapter>()
            for (idref in opf.spine) {
                val item = opf.manifest[idref] ?: continue
                if (!item.mediaType.contains("html") && !item.mediaType.contains("xml")) continue
                val fullPath = resolvePath(opfDir, item.href)
                val bytes = readEntry(zip, fullPath) ?: continue
                val html = String(bytes, detectHtmlCharset(bytes))
                val doc = Jsoup.parse(html, fullPath)

                val paragraphs = extractParagraphs(doc.body())
                if (paragraphs.isEmpty()) continue

                val title = navTitles[normalizePath(fullPath)]
                    ?: doc.select("h1,h2,h3").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: doc.title().trim().takeIf { it.isNotBlank() }
                    ?: item.href.substringAfterLast('/').substringBeforeLast('.')

                chapters.add(ParsedChapter(title, paragraphs))
            }

            return ParsedBook(chapters, coverBytes)
        }
    }

    // ---- container.xml ----

    private fun parseContainer(bytes: ByteArray): String {
        val doc = newXmlDoc(bytes)
        val rootfiles = doc.getElementsByTagName("rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? XmlElement ?: continue
            val fullPath = el.getAttribute("full-path")
            if (fullPath.isNotBlank()) return fullPath
        }
        throw IllegalArgumentException("container.xml 缺少 rootfile")
    }

    // ---- OPF ----

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)
    private data class Opf(
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val navHref: String?,      // EPUB3 nav 文件（manifest id 對應的 href，已解析為相對 opfDir 路徑）
        val ncxHref: String?,      // EPUB2 NCX 文件
        val coverHref: String?,
    )

    private fun parseOpf(bytes: ByteArray): Opf {
        val doc = newXmlDoc(bytes)

        val manifest = LinkedHashMap<String, ManifestItem>()
        val manifestNodes = doc.getElementsByTagName("item")
        for (i in 0 until manifestNodes.length) {
            val el = manifestNodes.item(i) as? XmlElement ?: continue
            val id = el.getAttribute("id")
            if (id.isBlank()) continue
            manifest[id] = ManifestItem(
                href = el.getAttribute("href"),
                mediaType = el.getAttribute("media-type") ?: "",
                properties = el.getAttribute("properties") ?: "",
            )
        }

        val spine = ArrayList<String>()
        var ncxId: String? = null
        val spineNodes = doc.getElementsByTagName("spine")
        if (spineNodes.length > 0) {
            val spineEl = spineNodes.item(0) as XmlElement
            ncxId = spineEl.getAttribute("toc").takeIf { it.isNotBlank() }
            val itemrefs = spineEl.getElementsByTagName("itemref")
            for (i in 0 until itemrefs.length) {
                val el = itemrefs.item(i) as? XmlElement ?: continue
                if (el.getAttribute("linear") == "no") continue
                val idref = el.getAttribute("idref")
                if (idref.isNotBlank()) spine.add(idref)
            }
        }

        val navHref = manifest.values.find { it.properties.contains("nav") }?.href
        val ncxHref = ncxId?.let { manifest[it]?.href }
            ?: manifest.values.find { it.mediaType.contains("ncx") }?.href

        var coverHref = manifest.values.find { it.properties.contains("cover-image") }?.href
        if (coverHref == null) {
            val metaNodes = doc.getElementsByTagName("meta")
            for (i in 0 until metaNodes.length) {
                val el = metaNodes.item(i) as? XmlElement ?: continue
                if (el.getAttribute("name") == "cover") {
                    val coverId = el.getAttribute("content")
                    coverHref = manifest[coverId]?.href
                    break
                }
            }
        }

        return Opf(manifest, spine, navHref, ncxHref, coverHref)
    }

    // ---- 目錄（EPUB3 nav 或 EPUB2 NCX），回傳「解析後路徑 → 標題」 ----

    private fun loadNavTitles(zip: ZipFile, opf: Opf, opfDir: String): Map<String, String> {
        opf.navHref?.let { navHref ->
            val fullPath = resolvePath(opfDir, navHref)
            val bytes = readEntry(zip, fullPath)
            if (bytes != null) {
                val titles = parseNav3(bytes, fullPath)
                if (titles.isNotEmpty()) return titles
            }
        }
        opf.ncxHref?.let { ncxHref ->
            val fullPath = resolvePath(opfDir, ncxHref)
            val bytes = readEntry(zip, fullPath)
            if (bytes != null) return parseNcx2(bytes, fullPath.substringBeforeLast('/', ""))
        }
        return emptyMap()
    }

    private fun parseNav3(bytes: ByteArray, navFullPath: String): Map<String, String> {
        val navDir = navFullPath.substringBeforeLast('/', "")
        val doc = Jsoup.parse(String(bytes, detectHtmlCharset(bytes)), navFullPath)
        val nav = doc.select("nav[epub|type=toc]").firstOrNull() ?: doc.select("nav").firstOrNull()
        ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (a in nav.select("a[href]")) {
            val href = a.attr("href").substringBefore('#')
            if (href.isBlank()) continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            val path = normalizePath(resolvePath(navDir, href))
            result.putIfAbsent(path, title)
        }
        return result
    }

    private fun parseNcx2(bytes: ByteArray, ncxDir: String): Map<String, String> {
        val doc = newXmlDoc(bytes)
        val result = LinkedHashMap<String, String>()
        val navPoints = doc.getElementsByTagName("navPoint")
        for (i in 0 until navPoints.length) {
            val el = navPoints.item(i) as? XmlElement ?: continue
            val textNodes = el.getElementsByTagName("text")
            val title = if (textNodes.length > 0) textNodes.item(0).textContent?.trim().orEmpty() else ""
            val contentNodes = el.getElementsByTagName("content")
            val src = if (contentNodes.length > 0) (contentNodes.item(0) as XmlElement).getAttribute("src") else ""
            val href = src.substringBefore('#')
            if (title.isEmpty() || href.isBlank()) continue
            val path = normalizePath(resolvePath(ncxDir, href))
            result.putIfAbsent(path, title)
        }
        return result
    }

    // ---- 正文抽取：保留段落換行，去除標籤 ----

    private fun extractParagraphs(body: Element): List<String> {
        val sb = StringBuilder()
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.text())
                    is Element -> if (node.tagName().equals("br", ignoreCase = true)) sb.append('\n')
                    else -> {}
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.tagName().lowercase() in blockTags) sb.append('\n')
            }
        })
        return sb.toString().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    // ---- helpers ----

    private fun newXmlDoc(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: zip.getEntry(path.removePrefix("/")) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    /** 解析 baseDir 下的相對路徑（處理 ../），回傳 zip 內的完整路徑 */
    private fun resolvePath(baseDir: String, href: String): String {
        if (href.startsWith("/")) return href.removePrefix("/")
        val combined = if (baseDir.isBlank()) href else "$baseDir/$href"
        val parts = ArrayList<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun normalizePath(path: String): String = path.substringBefore('#')

    private fun detectHtmlCharset(bytes: ByteArray): Charset {
        val prologue = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val match = Regex("""encoding\s*=\s*["']([\w-]+)["']""", RegexOption.IGNORE_CASE).find(prologue)
            ?: Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(prologue)
        val name = match?.groupValues?.get(1)
        return name?.let {
            try {
                Charset.forName(it)
            } catch (_: Exception) {
                Charsets.UTF_8
            }
        } ?: Charsets.UTF_8
    }
}
