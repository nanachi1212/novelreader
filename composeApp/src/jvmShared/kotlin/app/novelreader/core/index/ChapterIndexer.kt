package app.novelreader.core.index

/**
 * 章節標題判定。
 * 對「trim 後的單行」做判斷；含句號或引號的行視為正文而非標題。
 */
object ChapterIndexer {

    private const val NUM = """[0-9０-９零〇一二兩两三四五六七八九十百千萬万]{1,12}"""

    private val patterns = listOf(
        // 第X章/節/卷/回/集/部/篇/話 [分隔符] 標題
        Regex("""^第\s*$NUM\s*[章节節卷回集部篇話话][\s:：.、－—-]?.{0,40}$"""),
        // 特殊章節名
        Regex("""^(?:序章|序言|自序|前言|楔子|引子|尾聲|尾声|終章|终章|後記|后记|番外篇?|外傳|外传)(?:$|[\s:：.、].{0,30}$)"""),
        // 英文 Chapter N
        Regex("""^(?i:chapter)\s*\d{1,5}(?:$|[\s:：.].{0,40}$)"""),
        // 卷X 標題
        Regex("""^卷\s*$NUM\s*.{0,30}$"""),
    )

    /** 嚴格模式：誤判風暴時退回只認「第X章」型且行長 ≤ 25 */
    private val strictPattern =
        Regex("""^第\s*$NUM\s*[章节節回][\s:：.、－—-]?.{0,20}$""")

    private val bodyMarkers = charArrayOf('。', '「', '」', '？', '！', '，')

    /** 正規化：去掉前後空白（含全形空白） */
    fun normalize(line: String): String =
        line.trim { it == ' ' || it == '\t' || it == '　' || it == '\uFEFF' || it == '\r' }

    fun isHeading(normalized: String): Boolean {
        if (normalized.isEmpty() || normalized.length > 50) return false
        if (normalized.indexOfAny(bodyMarkers) >= 0) return false
        return patterns.any { it.matches(normalized) }
    }

    fun isStrictHeading(normalized: String): Boolean {
        if (normalized.isEmpty() || normalized.length > 25) return false
        if (normalized.indexOfAny(bodyMarkers) >= 0) return false
        return strictPattern.matches(normalized)
    }
}
