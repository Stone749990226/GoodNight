package com.stone.sleepguard

import android.app.*
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

class MonitorService : Service(), SensorEventListener {
    private val app get() = application as SleepApp
    private val repo get() = app.repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private lateinit var sensors: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var detector: MotionDetector? = null
    private var sensorKey: String? = null
    private var current: NightRecord? = null
    private var detecting = false
    private var ending = false
    private var overlay: View? = null
    private var overlayId: Long? = null
    private var foregroundOk = false
    private var lastBeat = 0L
    private var sensitivity = "中"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                Intent.ACTION_USER_PRESENT -> "UNLOCK"
                Intent.ACTION_POWER_CONNECTED -> "POWER_ON"
                Intent.ACTION_POWER_DISCONNECTED -> "POWER_OFF"
                else -> return
            }
            scope.launch { repo.event(type) }
            if (type == "SCREEN_OFF") removeOverlay()
            if (type == "UNLOCK" || type == "SCREEN_ON") scope.launch { delay(350); showPending() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = WeakReference(this)
        sensors = getSystemService(SensorManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:night")
        wakeLock.setReferenceCounted(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "夜间守候", NotificationManager.IMPORTANCE_LOW).apply {
            description = "监控状态和待填写的拿起原因"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        try { startForeground(NOTIFICATION, notification("正在准备守候")); foregroundOk = true }
        catch (e: Exception) { app.message.value = "无法启动后台监控：${e.message}"; stopSelf(); return }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { app.preferences.flow.collect { sensitivity = it.sensitivity; configure() } }
        scope.launch { repo.active.collect { current = it; configure(); showPending() } }
        scope.launch {
            while (isActive) {
                delay(1000)
                val record = current
                if (record?.night?.phase == Phase.SETTLING && System.currentTimeMillis() >= (record.night.armedAt ?: Long.MAX_VALUE)) repo.arm()
                if (record != null && SystemClock.elapsedRealtime() - lastBeat > 60_000) {
                    lastBeat = SystemClock.elapsedRealtime()
                    if (!ending && detector != null && record.night.phase in listOf(Phase.SETTLING, Phase.MONITORING)) wakeLock.acquire(600_000L)
                    repo.heartbeat()
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) return START_NOT_STICKY
        scope.launch {
            commands.withLock {
                try {
                    when (intent?.action) {
                        SLEEP -> repo.startSleep(app.preferences.flow.first())
                        RESUME -> repo.resume()
                        null -> { repo.markRecovery(); repo.resume() }
                    }
                    current = repo.dao.active()
                    configure()
                    showPending()
                    if (current == null) stopSelf()
                } catch (e: Exception) {
                    app.message.value = e.message ?: "监控操作失败"
                    current = repo.dao.active()
                    if (current == null) stopSelf()
                }
            }
        }
        return START_STICKY
    }
    // The lock intentionally spans callbacks, with bounded renewal and release on every inactive phase/onDestroy.
    @SuppressLint("Wakelock")
    private fun configure() {
        val record = current
        val enabled = !ending && record?.night?.phase in listOf(Phase.SETTLING, Phase.MONITORING)
        val key = if (enabled) "${record?.night?.id}:${record?.night?.lastSleepAt}:$sensitivity" else null
        if (key != sensorKey) {
            sensors.unregisterListener(this)
            detector = null; sensorKey = null; detecting = false
            if (wakeLock.isHeld) wakeLock.release()
            if (enabled) {
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sensor != null && sensors.registerListener(this, sensor, 100_000, 0)) {
                    detector = MotionDetector(sensitivity); sensorKey = key
                    wakeLock.acquire(600_000L)
                } else {
                    app.message.value = "无法启用加速度传感器，监控已中断"
                    scope.launch { repo.markRecovery() }
                }
            }
        }
        if (record?.night?.phase != Phase.PENDING) removeOverlay()
        if (foregroundOk && record != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
            notification(Phase.label(record.night.phase)))
        if (record == null && sensorKey == null && lastBeat != 0L) stopSelf()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (detecting || ending) return
        val armed = current?.night?.phase == Phase.MONITORING
        val detail = detector?.sample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000, armed)
        if (detail != null && armed) {
            detecting = true
            scope.launch {
                try { repo.motion(detail) } catch (e: Exception) { app.message.value = "活动记录失败：${e.message}"; detecting = false }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("晚安 · $text").setContentText(if (current?.pending != null) "解锁后填写原因，或打开晚安记录醒来" else "仅在你点睡觉后守候；打开晚安可结束监控")
            .setContentIntent(open).setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    private fun showPending() {
        val record = current ?: return
        val pending = record.pending ?: return
        if (record.night.phase != Phase.PENDING) return
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (!Settings.canDrawOverlays(this)) { app.message.value = "悬浮窗未授权：活动已记录，请从通知或主页填写原因"; return }
        if (overlayId == pending.id) return
        removeOverlay()
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(21, 36, 44)); cornerRadius = dp(24).toFloat() }
            elevation = dp(12).toFloat()
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(226, 239, 233)); setPadding(0, 0, 0, dp(12)) }
        layout.addView(text("快去睡觉，为什么又拿起手机了？", 21f).apply { typeface = Typeface.DEFAULT_BOLD })
        layout.addView(text("${clock(pending.detectedAt)} · ${pending.detail}。写下原因，再去完成你的事。", 13f))
        val input = EditText(this).apply {
            hint = "例如：忘记背单词了"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            minLines = 2; maxLines = 5; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(pending.reason ?: "")
        }
        layout.addView(input, LinearLayout.LayoutParams(-1, dp(130)))
        val error = text("", 13f); layout.addView(error)
        val submit = Button(this).apply { text = "记录原因"; isAllCaps = false }
        layout.addView(submit, LinearLayout.LayoutParams(-1, dp(56)))
        layout.addView(Button(this).apply {
            text = "我醒了，结束监控"; isAllCaps = false
            setOnClickListener {
                scope.launch {
                    try {
                        // Release monitoring resources before waiting for the database.
                        ending = true
                        sensors.unregisterListener(this@MonitorService); detector = null; sensorKey = null
                        if (wakeLock.isHeld) wakeLock.release()
                        repo.wake(); removeOverlay()
                        startActivity(Intent(this@MonitorService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("feeling", true))
                        stopSelf()
                    } catch (e: Exception) { error.text = e.message }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        submit.setOnClickListener {
            if (input.text.toString().isBlank()) { error.text = "请手动填写原因"; return@setOnClickListener }
            submit.isEnabled = false
            scope.launch {
                try { repo.reason(pending.id, input.text.toString()); removeOverlay() }
                catch (e: Exception) { error.text = e.message; submit.isEnabled = true }
            }
        }
        val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(250)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER; dimAmount = 0.4f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { wm.addView(scroll, params); overlay = scroll; overlayId = pending.id }
        catch (e: Exception) { app.message.value = "悬浮提示未能显示，请打开晚安填写：${e.message}" }
    }
    private fun removeOverlay() {
        overlay?.let { view ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        overlay = null; overlayId = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        instance.clear()
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        runCatching { unregisterReceiver(receiver) }
        removeOverlay(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val SLEEP = "com.stone.sleepguard.SLEEP"
        const val RESUME = "com.stone.sleepguard.RESUME"
        private const val CHANNEL = "night_monitor"
        private const val NOTIFICATION = 101
        @Volatile var running = false
        private var instance = WeakReference<MonitorService>(null)
        fun releaseNow() {
            instance.get()?.let { service ->
                service.ending = true
                service.sensors.unregisterListener(service)
                service.detector = null; service.sensorKey = null
                if (service.wakeLock.isHeld) service.wakeLock.release()
                service.removeOverlay()
            }
        }
        fun start(context: Context, action: String) { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(action)) }
    }
}
