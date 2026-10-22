package app.novelreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 朗讀期間的前景服務（mediaPlayback）：關螢幕後 App 不會被系統凍結或回收，
 * 並提供媒體通知、鎖定畫面與藍牙耳機按鍵控制。真正的朗讀仍由閱讀畫面的 TtsController 驅動。
 */
class TtsService : Service() {
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "novelreader-tts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send(TtsCommand.PLAY)
                override fun onPause() = send(TtsCommand.PAUSE)
                override fun onSkipToNext() = send(TtsCommand.NEXT)
                override fun onSkipToPrevious() = send(TtsCommand.PREVIOUS)
                override fun onStop() = send(TtsCommand.STOP)
            })
            isActive = true
        }
        // 段落之間語音會短暫停止，不持有 wake lock 的話關螢幕後 CPU 可能睡著而接不上下一段
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelreader:tts")
            .apply { setReferenceCounted(false) }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        intent?.getStringExtra(EXTRA_COMMAND)?.let { send(TtsCommand.valueOf(it)) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        super.onDestroy()
    }

    private fun refresh() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "輕閱朗讀")
                .build()
        )
        if (playing) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) else if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗讀", NotificationManager.IMPORTANCE_LOW)
        )
        fun action(icon: Int, label: String, command: TtsCommand) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(
                this, command.ordinal,
                Intent(this, TtsService::class.java).putExtra(EXTRA_COMMAND, command.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (playing) "朗讀中" else "已暫停")
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一段", TtsCommand.PREVIOUS))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "暫停", TtsCommand.PAUSE)
                else action(android.R.drawable.ic_media_play, "繼續", TtsCommand.PLAY)
            )
            .addAction(action(android.R.drawable.ic_media_next, "下一段", TtsCommand.NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "停止", TtsCommand.STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "novelreader_tts"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_COMMAND = "command"
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        private var instance: TtsService? = null
        private var title = ""
        private var playing = false
        private var onCommand: ((TtsCommand) -> Unit)? = null

        private fun send(command: TtsCommand) {
            onCommand?.invoke(command)
        }

        /** 由主執行緒呼叫；[active] 為 false 時關掉服務與通知 */
        fun update(context: Context, active: Boolean, playing: Boolean, title: String, onCommand: (TtsCommand) -> Unit) {
            if (!active) {
                this.onCommand = null
                context.stopService(Intent(context, TtsService::class.java))
                return
            }
            this.playing = playing
            this.title = title.ifEmpty { "輕閱" }
            this.onCommand = onCommand
            // 服務已在前景時直接更新；只有第一次（使用者在畫面上按朗讀）才啟動，避免背景啟動前景服務被系統拒絕
            instance?.refresh() ?: ContextCompat.startForegroundService(context, Intent(context, TtsService::class.java))
        }
    }
}
