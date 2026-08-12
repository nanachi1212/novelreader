package app.novelreader.core.io

import app.novelreader.core.detect.CharsetDetector
import app.novelreader.core.index.ChapterIndexer
import app.novelreader.core.model.ChapterIndexEntry
import app.novelreader.platform.BookSource
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 匯入核心：單趟串流把原始檔轉碼成 UTF-8 的 content.txt，
 * 同時記錄章節標題的位元組偏移。之後閱讀器只讀 content.txt。
 */
object Transcoder {

    data class Result(
        val chapters: List<ChapterIndexEntry>,
        val totalBytes: Long,
        val charsetName: String,
    )

    private const val SAMPLE_SIZE = 256 * 1024
    private const val PSEUDO_STEP = 200L * 1024      // 無章節時每 ~200KB 切一段
    private const val MAX_CHAPTER_BYTES = 1L * 1024 * 1024  // 單章上限，超過硬切
    private const val MAX_HEADINGS = 5000            // 誤判風暴門檻

    fun transcodeAndIndex(
        source: BookSource,
        outFile: File,
        forcedCharset: Charset? = null,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val charset = forcedCharset ?: source.open().use { ins ->
            CharsetDetector.detect(readSample(ins))
        }

        data class Heading(val title: String, val offset: Long, val strict: Boolean)

        val headings = ArrayList<Heading>()
        var strictOnly = false   // 標題數失控時，先退回只收嚴格模式，再失控就放棄
        var giveUpHeadings = false
        val lineMarks = ArrayList<Long>()  // 每 ~200KB 的行首偏移（偽章節/硬切用）
        var nextMark = PSEUDO_STEP
        var outOffset = 0L

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")

        source.open().use { rawIns ->
            val counting = CountingInputStream(rawIns)
            val reader = BufferedReader(InputStreamReader(counting, charset), 128 * 1024)
            BufferedOutputStream(FileOutputStream(tmpFile), 128 * 1024).use { out ->
                var first = true
                var lineCounter = 0
                while (true) {
                    var line = reader.readLine() ?: break
                    if (first) {
                        line = line.removePrefix("\uFEFF")
                        first = false
                    }

                    if (outOffset >= nextMark) {
                        lineMarks.add(outOffset)
                        nextMark = outOffset + PSEUDO_STEP
                    }

                    val normalized = ChapterIndexer.normalize(line)
                    if (!giveUpHeadings && ChapterIndexer.isHeading(normalized)) {
                        val strict = ChapterIndexer.isStrictHeading(normalized)
                        if (!strictOnly || strict) {
                            headings.add(Heading(normalized, outOffset, strict))
                        }
                        if (headings.size > MAX_HEADINGS * 4) {
                            if (!strictOnly) {
                                strictOnly = true
                                headings.retainAll { it.strict }
                            }
                            if (headings.size > MAX_HEADINGS * 4) {
                                headings.clear()
                                giveUpHeadings = true
                            }
                        }
                    }

                    val bytes = line.toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    out.write('\n'.code)
                    outOffset += bytes.size + 1

                    if (++lineCounter % 2000 == 0 && source.sizeBytes > 0) {
                        onProgress((counting.count.toFloat() / source.sizeBytes).coerceIn(0f, 0.99f))
                    }
                }
            }
        }

        replaceFile(tmpFile, outFile)

        // 誤判風暴 → 退回嚴格模式；仍太多 → 放棄標題改用偽章節
        var effective = headings.toList()
        if (effective.size > MAX_HEADINGS) {
            effective = effective.filter { it.strict }
            if (effective.size > MAX_HEADINGS) effective = emptyList()
        }

        val chapters = buildChapters(
            effective.map { it.title to it.offset },
            lineMarks, outOffset
        )
        onProgress(1f)
        return Result(chapters, outOffset, charset.name())
    }

    private fun buildChapters(
        headings: List<Pair<String, Long>>,
        lineMarks: List<Long>,
        totalBytes: Long,
    ): List<ChapterIndexEntry> {
        // 無章節：用行首標記切偽章節
        val spans = ArrayList<Pair<String, Long>>()
        if (headings.isEmpty()) {
            spans.add("開始" to 0L)
            var part = 2
            for (mark in lineMarks) {
                if (mark > 0 && mark < totalBytes) spans.add("第 $part 部分" to mark).also { part++ }
            }
        } else {
            // 第一個標題前的內容：夠長就獨立成「卷首」，很短（書名等）直接併入第一章
            val first = headings.first()
            if (first.second > 200) {
                spans.add("卷首" to 0L)
                spans.addAll(headings)
            } else {
                spans.add(first.first to 0L)
                spans.addAll(headings.drop(1))
            }
        }

        // 硬切超長章節（在 ~200KB 行首標記處切，段落邊界安全）
        val withSplits = ArrayList<Pair<String, Long>>()
        for ((i, span) in spans.withIndex()) {
            val start = span.second
            val end = if (i + 1 < spans.size) spans[i + 1].second else totalBytes
            withSplits.add(span)
            if (end - start > MAX_CHAPTER_BYTES) {
                var cont = 2
                var lastCut = start
                for (mark in lineMarks) {
                    if (mark > lastCut + MAX_CHAPTER_BYTES && mark < end) {
                        withSplits.add("${span.first}（續$cont）" to mark)
                        lastCut = mark
                        cont++
                    }
                }
            }
        }

        return withSplits.mapIndexed { i, (title, start) ->
            ChapterIndexEntry(
                index = i,
                title = title,
                byteStart = start,
                byteEnd = if (i + 1 < withSplits.size) withSplits[i + 1].second else totalBytes,
            )
        }
    }

    /**
     * EPUB 用：章節標題與段落已由 EpubParser 決定，不需編碼偵測/標題 regex。
     * 單章仍套用 MAX_CHAPTER_BYTES 硬切保護（極少數把整本書塞進一個 xhtml 的畸形 EPUB）。
     */
    fun transcodeEpub(
        chapters: List<Pair<String, List<String>>>,
        outFile: File,
        onProgress: (Float) -> Unit = {},
    ): Result {
        data class Span(val title: String, val start: Long)

        val spans = ArrayList<Span>()
        var outOffset = 0L
        val total = chapters.size.coerceAtLeast(1)

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")

        BufferedOutputStream(FileOutputStream(tmpFile), 128 * 1024).use { out ->
            for ((ci, pair) in chapters.withIndex()) {
                val (title, paragraphs) = pair
                spans.add(Span(title, outOffset))
                var lastCut = outOffset
                var cont = 2
                for (line in paragraphs) {
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    out.write('\n'.code)
                    outOffset += bytes.size + 1
                    if (outOffset - lastCut > MAX_CHAPTER_BYTES) {
                        spans.add(Span("$title（續$cont）", outOffset))
                        lastCut = outOffset
                        cont++
                    }
                }
                onProgress((ci + 1).toFloat() / total)
            }
        }

        replaceFile(tmpFile, outFile)

        val result = spans.mapIndexed { i, s ->
            ChapterIndexEntry(
                index = i,
                title = s.title,
                byteStart = s.start,
                byteEnd = if (i + 1 < spans.size) spans[i + 1].start else outOffset,
            )
        }
        onProgress(1f)
        return Result(result, outOffset, "UTF-8")
    }

    private fun readSample(ins: InputStream): ByteArray {
        val buf = ByteArray(SAMPLE_SIZE)
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return if (off == buf.size) buf else buf.copyOf(off)
    }

    private fun replaceFile(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private class CountingInputStream(base: InputStream) : FilterInputStream(base) {
        var count = 0L
            private set

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }
}
