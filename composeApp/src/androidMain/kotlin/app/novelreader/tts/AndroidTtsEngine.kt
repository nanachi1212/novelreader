package app.novelreader.tts

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import app.novelreader.MainActivity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class AndroidTtsEngine(context: Context) : TtsEngine {
    private val appContext = context.applicationContext
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
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
        showNotification()
        return true
    }

    override fun stop() {
        currentId = null
        currentOnDone = null
        engine.stop()
        notifications.cancel(NOTIFICATION_ID)
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

    private fun showNotification() {
        try {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "朗讀", NotificationManager.IMPORTANCE_LOW)
            )
            val intent = Intent(appContext, MainActivity::class.java).apply {
                action = MainActivity.ACTION_STOP_TTS
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                appContext, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            notifications.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("輕閱正在朗讀")
                    .setContentText("點選停止朗讀")
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_media_pause, "停止", pending)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Android 13 未授予通知權限時，朗讀本身仍可正常使用。
        }
    }

    private companion object {
        const val CHANNEL_ID = "novelreader_tts"
        const val NOTIFICATION_ID = 1001
    }
}
