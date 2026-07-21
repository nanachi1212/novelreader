package app.novelreader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class TtsVoice(
    val id: String,
    val label: String,
    /** BCP-47，如 "zh-TW"、"zh-CN" */
    val language: String,
)

/**
 * 平台語音引擎抽象。實作端（SAPI / Android TTS）需保證：
 * - speak() 正常唸完才呼叫 onDone；被 stop() 中斷的段落不得再回呼
 * - onDone 可能來自背景執行緒，由呼叫端負責切回主執行緒
 */
interface TtsEngine {
    /** 可用語音清單（中文語音排前面）；無任何語音時回空清單 */
    suspend fun listVoices(): List<TtsVoice>
    /** rate 1.0 = 正常語速，範圍約 0.5–4.0（部分引擎如 SAPI 有硬體上限，實際唸速可能封頂較低）。回傳 false 表示引擎不可用（派送失敗） */
    fun speak(text: String, voiceId: String?, rate: Float, onDone: () -> Unit): Boolean
    /** 停掉當前段落；冪等 */
    fun stop()
    /** App 結束時釋放資源 */
    fun shutdown() {}
}

/**
 * 逐段朗讀控制器：驅動「唸完一段 → 下一段」的序列，處理中斷 race。
 * 所有方法須在主執行緒呼叫；引擎回呼經 [scope]（主執行緒）切回後才推進，
 * 以 session 世代編號丟棄過期回呼。
 */
class TtsController(
    private val engine: TtsEngine,
    private val scope: CoroutineScope,
) {
    var rate: Float = 1f
    var voiceId: String? = null

    private var session = 0

    /**
     * 從 [startIndex] 開始逐段唸 [paragraphs]。
     * 每段開始時回呼 [onParagraph]；整章唸完回呼 [onChapterDone]。
     * 再次呼叫會自動中止前一輪。
     */
    fun start(
        paragraphs: List<String>,
        startIndex: Int,
        onParagraph: (Int) -> Unit,
        onChapterDone: () -> Unit,
        onError: () -> Unit = {},
    ) {
        engine.stop()
        val s = ++session

        fun speakAt(index: Int) {
            if (s != session) return
            if (index >= paragraphs.size) {
                onChapterDone()
                return
            }
            onParagraph(index)
            val ok = engine.speak(paragraphs[index], voiceId, rate) {
                scope.launch { if (s == session) speakAt(index + 1) }
            }
            if (!ok) {
                session++
                onError()
            }
        }
        speakAt(startIndex.coerceIn(0, paragraphs.size))
    }

    /** 停止朗讀；之後的過期 onDone 一律丟棄 */
    fun stop() {
        session++
        engine.stop()
    }
}
