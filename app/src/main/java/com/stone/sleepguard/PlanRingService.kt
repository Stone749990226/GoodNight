package com.stone.sleepguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class PlanRingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "正在响铃", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        })
        val stop = PendingIntent.getService(this, 0, Intent(this, PlanRingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(intent.getStringExtra("title")).setContentText("正在响铃 · 点此停止")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true).setSilent(true)
            .setContentIntent(stop).addAction(0, "停止响铃", stop).build()
        startForeground(82051, notification)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 60_000)
        if (player?.isPlaying != true) {
            val fallback = Uri.parse("android.resource://$packageName/${R.raw.plan_chime}")
            val primary = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            if (primary == null || !play(primary) { play(fallback) { stopSelf() } }) {
                if (!play(fallback) { stopSelf() }) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun play(uri: Uri, onError: () -> Unit): Boolean {
        player?.release()
        val next = MediaPlayer()
        player = next
        return try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            next.setDataSource(this, uri)
            next.isLooping = true
            next.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Audio playback error $what/$extra")
                onError()
                true
            }
            next.prepare()
            next.start()
            Log.i(TAG, "Alarm playback started, playing=${next.isPlaying}")
            true
        } catch (failure: Exception) {
            Log.e(TAG, "Cannot play alarm sound", failure)
            false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GoodNightRing"
        private const val CHANNEL = "plan_ring_controls"
        private const val STOP = "com.stone.sleepguard.STOP_RING"
        fun start(context: Context, title: String): Boolean = runCatching {
            context.startForegroundService(Intent(context, PlanRingService::class.java).putExtra("title", title))
        }.onFailure { Log.e(TAG, "Foreground playback unavailable", it) }.getOrNull() != null
    }
}
