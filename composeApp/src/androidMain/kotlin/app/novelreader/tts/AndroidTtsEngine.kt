package app.novelreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class AndroidTtsEngine(context: Context) : TtsEngine {
    private val ready = CompletableDeferred<Boolean>()
    private val ids = AtomicLong()
    @Volatile private var initialized = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        initialized = status == TextToSpeech.SUCCESS
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    @Volatile private var currentId: String? = null
    @Volatile private var currentOnDone: (() -> Unit)? = null

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
    }

    override suspend fun listVoices(): List<TtsVoice> = withContext(Dispatchers.Main) {
        if (!ready.await()) return@withContext emptyList()
        engine.voices.orEmpty()
            .filterNot { it.isNetworkConnectionRequired }
            .map { TtsVoice(it.name, it.name, it.locale.toLanguageTag()) }
            .sortedBy { if (it.language.startsWith("zh", ignoreCase = true)) 0 else 1 }
    }

    override fun speak(text: String, voiceId: String?, rate: Float, onDone: () -> Unit): Boolean {
        if (!initialized) return false
        engine.voice = engine.voices?.firstOrNull { it.name == voiceId } ?: engine.defaultVoice
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        val id = ids.incrementAndGet().toString()
        currentId = id
        currentOnDone = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result == TextToSpeech.ERROR) {
            currentId = null
            currentOnDone = null
            return false
        }
        return true
    }

    override fun stop() {
        currentId = null
        currentOnDone = null
        engine.stop()
    }

    override fun shutdown() {
        stop()
        engine.shutdown()
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId != currentId) return
        val callback = currentOnDone
        currentId = null
        currentOnDone = null
        callback?.invoke()
    }
}
