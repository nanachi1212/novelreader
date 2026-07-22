package app.novelreader

import app.novelreader.tts.TtsController
import app.novelreader.tts.TtsEngine
import app.novelreader.tts.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class TtsControllerTest {

    @Test
    fun `純省略號段落不送進語音引擎`() {
        val engine = FakeTtsEngine()
        val spoken = mutableListOf<Int>()
        TtsController(engine, CoroutineScope(Dispatchers.Unconfined)).start(
            paragraphs = listOf("....................", "他終於開口了。"),
            startIndex = 0,
            onParagraph = { spoken += it },
            onChapterDone = {},
        )

        assertEquals(listOf("他終於開口了。"), engine.texts)
        assertEquals(listOf(1), spoken)
    }

    private class FakeTtsEngine : TtsEngine {
        val texts = mutableListOf<String>()

        override suspend fun listVoices(): List<TtsVoice> = emptyList()

        override fun speak(text: String, voiceId: String?, rate: Float, onDone: () -> Unit): Boolean {
            texts += text
            return true
        }

        override fun stop() {}
    }
}
