package app.novelreader.core.convert

import com.github.houbb.opencc4j.util.ZhConverterUtil

/** 簡繁轉換包裝（opencc4j）。字典載入較慢，app 啟動時應在 IO thread 呼叫 warmUp() 預熱。 */
object ChineseConvert {
    fun toTraditional(text: String): String = ZhConverterUtil.toTraditional(text)
    fun toSimplified(text: String): String = ZhConverterUtil.toSimple(text)

    fun warmUp() {
        try {
            ZhConverterUtil.toTraditional("预热")
        } catch (_: Exception) {
        }
    }
}
