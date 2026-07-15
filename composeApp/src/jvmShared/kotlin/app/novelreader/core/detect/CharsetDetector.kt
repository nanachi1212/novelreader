package app.novelreader.core.detect

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * 編碼偵測：BOM → juniversalchardet → GBK/Big5 試解碼比較 → 簡繁專用字統計。
 * 回傳的 charset 保證可用於 InputStreamReader。
 */
object CharsetDetector {

    /** 手動覆寫選單提供的選項（"AUTO" 表示自動偵測） */
    val manualOptions = listOf("AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5")

    // 只在簡體中使用的常見字
    private const val SIMP_ONLY =
        "们这边发东车门书学难还进远运体见闻马鸟龙风电让认话语请谢岁钱银广处备样价当为无与开关变实际点应"
    // 只在繁體中使用的常見字
    private const val TRAD_ONLY =
        "們這邊發東車門書學難還進遠運體見聞馬鳥龍風電讓認話語請謝歲錢銀廣處備樣價當為無與開關變實際點應"

    fun detect(sample: ByteArray): Charset {
        detectBom(sample)?.let { return it }

        val detector = UniversalDetector(null)
        detector.handleData(sample, 0, sample.size)
        detector.dataEnd()
        val name = detector.detectedCharset

        return when {
            name == null -> tieBreak(sample)
            name.equals("UTF-8", true) -> Charsets.UTF_8
            name.startsWith("UTF-16", true) -> safeCharset(name)
            // 偵測結果是中文編碼時，仍用試解碼確認 GBK vs Big5（chardet 對兩者易混淆）
            name.equals("GB18030", true) || name.equals("GBK", true) ||
                name.equals("GB2312", true) || name.equals("BIG5", true) -> tieBreak(sample)
            else -> safeCharset(name)
        }
    }

    /** BOM 檢查；UTF-16 用 "UTF-16"（讀取時自動吃掉 BOM），UTF-8 的 BOM 由轉碼端去除 */
    private fun detectBom(b: ByteArray): Charset? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> charset("UTF-16")
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> charset("UTF-16")
        else -> null
    }

    private fun tieBreak(sample: ByteArray): Charset {
        val slice = if (sample.size > 64 * 1024) sample.copyOf(64 * 1024) else sample

        // UTF-8 嚴格解碼零錯誤 → UTF-8（純 ASCII 也落在這裡）
        val utf8Errors = decodeErrorCount(slice, Charsets.UTF_8)
        if (utf8Errors == 0) return Charsets.UTF_8

        // GBK vs Big5：比較解碼錯誤數（GB18030 幾乎不報錯，所以比較時用 GBK）
        val gbk = safeCharset("GBK")
        val big5 = safeCharset("Big5")
        val gbkErrors = decodeErrorCount(slice, gbk)
        val big5Errors = decodeErrorCount(slice, big5)

        val diff = kotlin.math.abs(gbkErrors - big5Errors)
        val threshold = maxOf(4, (maxOf(gbkErrors, big5Errors) / 10))
        if (diff > threshold) {
            // 錯誤數差距明顯，直接取錯誤少的；GB 系一律回傳超集 GB18030
            return if (gbkErrors < big5Errors) safeCharset("GB18030") else big5
        }

        // 差距不明顯 → 各自解碼後統計簡/繁專用字
        val asGbk = decodeLenient(slice, gbk)
        val asBig5 = decodeLenient(slice, big5)
        val gbkScore = countChars(asGbk, SIMP_ONLY) + countChars(asGbk, TRAD_ONLY)
        val big5Score = countChars(asBig5, SIMP_ONLY) + countChars(asBig5, TRAD_ONLY)
        return if (gbkScore >= big5Score) safeCharset("GB18030") else big5
    }

    /** 用 REPORT 模式解碼，計算格式錯誤的位元組數 */
    private fun decodeErrorCount(bytes: ByteArray, charset: Charset): Int {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val output = java.nio.CharBuffer.allocate(bytes.size + 16)
        var errors = 0
        while (input.hasRemaining()) {
            val result: CoderResult = decoder.decode(input, output, true)
            when {
                result.isError -> {
                    errors++
                    input.position(minOf(input.limit(), input.position() + result.length()))
                }
                else -> break
            }
            if (output.remaining() < 8) break // 不會發生，保險
        }
        return errors
    }

    private fun decodeLenient(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun countChars(text: String, set: String): Int {
        var n = 0
        for (c in text) if (set.indexOf(c) >= 0) n++
        return n
    }

    fun safeCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (_: Exception) {
        Charsets.UTF_8
    }
}
