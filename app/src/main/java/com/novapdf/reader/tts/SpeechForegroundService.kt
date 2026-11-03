package com.novapdf.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.novapdf.reader.R
import com.novapdf.reader.ui.MainActivity

class SpeechForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nova_pdf_tts_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_PLAY = "com.novapdf.reader.ACTION_PLAY"
        const val ACTION_PAUSE = "com.novapdf.reader.ACTION_PAUSE"
        const val ACTION_PREV = "com.novapdf.reader.ACTION_PREV"
        const val ACTION_NEXT = "com.novapdf.reader.ACTION_NEXT"
        const val ACTION_STOP = "com.novapdf.reader.ACTION_STOP"

        var callback: ServiceActionCallback? = null

        fun startService(context: Context) {
            val intent = Intent(context, SpeechForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SpeechForegroundService::class.java)
            context.stopService(intent)
        }
    }

    interface ServiceActionCallback {
        fun onPlayClicked()
        fun onPauseClicked()
        fun onPrevClicked()
        fun onNextClicked()
        fun onStopClicked()
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SpeechForegroundService = this@SpeechForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> callback?.onPlayClicked()
            ACTION_PAUSE -> callback?.onPauseClicked()
            ACTION_PREV -> callback?.onPrevClicked()
            ACTION_NEXT -> callback?.onNextClicked()
            ACTION_STOP -> {
                callback?.onStopClicked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification(
            title = "Xouryn PDF Reader",
            content = "Speaking active document",
            isPlaying = intent?.action != ACTION_PAUSE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    fun updateNotification(docTitle: String, currentSentenceText: String, isPlaying: Boolean) {
        val notification = buildNotification(
            title = docTitle,
            content = currentSentenceText.take(120),
            isPlaying = isPlaying
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SpeechForegroundService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPausePendingIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SpeechForegroundService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPendingIntent = PendingIntent.getService(
            this, 3,
            Intent(this, SpeechForegroundService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this, 4,
            Intent(this, SpeechForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Xouryn PDF Reader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio playback controls for PDF pages read out loud"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

