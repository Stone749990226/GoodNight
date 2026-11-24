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
    private var morningNotified = false
    private var oversleptNotified = false
    private var lastMorningPromptAt = 0L
    private var reminderNightId: Long? = null
    private var overlayIsMorning = false
    private var partnerDisplay = "对方"
    private var morningFeelingPending = false
    private var morningSnoozeAt = 0L
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
            if (type == "UNLOCK" || type == "SCREEN_ON") scope.launch {
                delay(350)
                if (morningFeelingPending) {
                    if (morningCooldownActive()) return@launch
                    if (showWakeQuestion()) return@launch
                }
                showPending(); showMorningPrompt()
            }
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
        manager.createNotificationChannel(NotificationChannel(MORNING_CHANNEL, "起床提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "起床后提醒点我醒了"
        })
        try { startForeground(NOTIFICATION, notification("正在准备守候")); foregroundOk = true }
        catch (e: Exception) { app.message.value = "无法启动后台监控：${e.message}"; stopSelf(); return }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { app.rolePreferences.flow.collect { partnerDisplay = partnerName(it.role) } }
        scope.launch {
            repo.active.collect {
                current = it
                if (it?.night?.id != reminderNightId) {
                    reminderNightId = it?.night?.id
                    morningNotified = false
                    oversleptNotified = false
                    lastMorningPromptAt = 0L
                    morningFeelingPending = false
                    morningSnoozeAt = 0L
                    if (it == null) getSystemService(NotificationManager::class.java).cancel(MORNING_NOTIFICATION)
                }
                configure(); showPending()
            }
        }
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
                if (!ending && record?.night?.phase == Phase.MONITORING) {
                    val now = System.currentTimeMillis()
                    if (now >= morningReminderAt(record)) {
                        if (!morningNotified) {
                            morningNotified = true
                            getSystemService(NotificationManager::class.java).notify(MORNING_NOTIFICATION, morningNotification())
                            if (foregroundOk) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("早上了，记得点我醒了"))
                            EmailNotifier.missedWake(this@MonitorService, record, now)
                        }
                        showMorningPrompt()
                    }
                    if (!oversleptNotified && now >= record.night.lastSleepAt + record.night.maxSleepMinutes * 60_000L) {
                        oversleptNotified = true
                        EmailNotifier.overslept(this@MonitorService, record)
                    }
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
                        SLEEP -> {
                            val settings = app.preferences.flow.first()
                            val wake = intent?.getIntExtra(TARGET_WAKE, -1) ?: -1
                            repo.startSleep(if (wake in 0 until 24 * 60) settings.copy(targetWake = wake) else settings)
                        }
                        RESUME -> repo.resume()
                        WAKE -> finishNight()
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
        val key = if (enabled) "${record?.night?.id}:${record?.night?.lastSleepAt}" else null
        if (key != sensorKey) {
            sensors.unregisterListener(this)
            detector = null; sensorKey = null; detecting = false
            if (wakeLock.isHeld) wakeLock.release()
            if (enabled) {
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sensor != null && sensors.registerListener(this, sensor, 100_000, 0)) {
                    detector = MotionDetector(); sensorKey = key
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
            val record = current
            if (record != null && System.currentTimeMillis() >= morningWindowStart(record)) {
                if (morningCooldownActive()) { detecting = false; return }
                morningFeelingPending = true
                if (!showWakeQuestion()) {
                    detecting = false
                    if (!Settings.canDrawOverlays(this)) scope.launch {
                        runCatching { finishNight() }.onFailure { app.message.value = it.message; detecting = false }
                    }
                }
            } else scope.launch {
                try { repo.motion(detail) } catch (e: Exception) { app.message.value = "活动记录失败：${e.message}"; detecting = false }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun morningReminderAt(record: NightRecord): Long =
        wakeTimestamp(record.night.date, record.night.targetWake) + 30 * 60_000L

    private fun morningNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("wakePrompt", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val wake = PendingIntent.getService(this, 3, Intent(this, MonitorService::class.java).setAction(WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, MORNING_CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("早上好").setContentText("昨晚的守候还在，点一下我醒了。")
            .setContentIntent(open).addAction(0, "我醒了", wake).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
    }

    private suspend fun finishNight() {
        if (current?.pending != null) throw IllegalStateException("请先填写这次拿起手机的原因")
        ending = true
        sensors.unregisterListener(this)
        detector = null; sensorKey = null
        if (wakeLock.isHeld) wakeLock.release()
        val id = repo.wake()
        removeOverlay()
        getSystemService(NotificationManager::class.java).cancel(MORNING_NOTIFICATION)
        when {
            id == SleepRepository.SHORT_NIGHT -> Toast.makeText(this, "不足1小时，本次不记录", Toast.LENGTH_SHORT).show()
            id != null -> runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("feeling", true))
            }
        }
        stopSelf()
    }

    private fun showMorningPrompt() {
        val record = current
        val power = getSystemService(PowerManager::class.java)
        if (record == null) return
        if (ending || record.night.phase != Phase.MONITORING || MainActivity.visible) return
        if (System.currentTimeMillis() < morningReminderAt(record)) return
        val elapsed = SystemClock.elapsedRealtime()
        if (lastMorningPromptAt != 0L && elapsed - lastMorningPromptAt < 30 * 60_000L) return
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (overlay != null) return
        lastMorningPromptAt = elapsed
        if (Settings.canDrawOverlays(this)) {
            val density = resources.displayMetrics.density
            fun dp(n: Int) = (n * density).toInt()
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(22, 36, 46)); cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), Color.rgb(46, 71, 82))
                }
                elevation = dp(16).toFloat()
            }
            fun text(value: String, size: Float = 16f) = TextView(this).apply {
                text = value; textSize = size; setTextColor(Color.rgb(226, 239, 233))
                setPadding(0, 0, 0, dp(8))
            }
            layout.addView(text("早上好", 24f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            layout.addView(text("昨晚的守候还没结束，点一下「我醒了」吧。", 14f).apply { setTextColor(Color.rgb(159, 178, 185)) })
            layout.addView(text("约好 ${minutesText(record.night.targetWake)} 起床", 12f).apply {
                setTextColor(Color.rgb(168, 220, 203)); setPadding(0, dp(2), 0, dp(16))
            })
            fun action(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
                text = label; isAllCaps = false; textSize = 16f
                setTextColor(if (primary) Color.rgb(13, 23, 31) else Color.rgb(168, 220, 203))
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(168, 220, 203) else Color.TRANSPARENT)
                stateListAnimator = null
                setOnClickListener { onClick() }
            }
            layout.addView(action("我醒了", true) { scope.launch { runCatching { finishNight() }.onFailure { app.message.value = it.message } } },
                LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4) })
            val secondary = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            secondary.addView(action("稍后", false) { removeOverlay() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            secondary.addView(action("打开晚安", false) {
                removeOverlay()
                runCatching { startActivity(Intent(this@MonitorService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            layout.addView(secondary)
            val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
            val wm = getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(250)), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND, android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER; dimAmount = 0.35f }
            try { wm.addView(scroll, params); overlay = scroll; overlayId = null; overlayIsMorning = true }
            catch (e: Exception) { app.message.value = "悬浮提示未能显示：${e.message}" }
        }
        // 有悬浮层时它已可见；没有悬浮窗权限也尽力把晚安拉到最前，失败则靠通知兜底。
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("wakePrompt", true))
        }
    }

    private fun morningWindowStart(record: NightRecord): Long =
        wakeTimestamp(record.night.date, record.night.targetWake) - 3 * 3_600_000L

    private fun morningCooldownActive(): Boolean =
        morningSnoozeAt != 0L && SystemClock.elapsedRealtime() - morningSnoozeAt < 10 * 60_000L

    private fun showWakeQuestion(): Boolean {
        val record = current ?: return false
        if (ending || record.night.phase != Phase.MONITORING || MainActivity.visible) return false
        if (morningCooldownActive()) return false
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (!Settings.canDrawOverlays(this) || overlay != null) return false
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(22, 36, 46)); cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(46, 71, 82))
            }
            elevation = dp(16).toFloat()
        }
        layout.addView(TextView(this).apply {
            text = "bb，醒了吗？🥺"; textSize = 22f; setTextColor(Color.rgb(232, 239, 233))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, 0, 0, dp(16))
        })
        fun action(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f
            setTextColor(if (primary) Color.rgb(13, 23, 31) else Color.rgb(168, 220, 203))
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(168, 220, 203) else Color.TRANSPARENT)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
        layout.addView(action("我醒了", true) {
            removeOverlay()
            if (!showFeelingChoices()) scope.launch {
                runCatching { finishNight() }.onFailure { app.message.value = it.message }
            }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4) })
        layout.addView(action("再睡会儿", false) {
            removeOverlay()
            morningSnoozeAt = SystemClock.elapsedRealtime()
            morningFeelingPending = false
            detecting = false
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(250)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.35f }
        return try {
            wm.addView(scroll, params)
            overlay = scroll; overlayId = null; overlayIsMorning = true
            morningFeelingPending = false
            true
        } catch (e: Exception) {
            app.message.value = "悬浮提示未能显示：${e.message}"
            false
        }
    }

    private fun showFeelingChoices(): Boolean {
        val record = current ?: return false
        if (ending || record.night.phase != Phase.MONITORING || MainActivity.visible) return false
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (!Settings.canDrawOverlays(this) || overlay != null) return false
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(22, 36, 46)); cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(46, 71, 82))
            }
            elevation = dp(16).toFloat()
        }
        layout.addView(TextView(this).apply {
            text = "bb，昨晚睡得怎么样？🥺"; textSize = 21f; setTextColor(Color.rgb(232, 239, 233))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, 0, 0, dp(14))
        })
        fun action(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f
            setTextColor(if (primary) Color.rgb(13, 23, 31) else Color.rgb(168, 220, 203))
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(168, 220, 203) else Color.TRANSPARENT)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
        FEELINGS.forEach { feeling ->
            layout.addView(action(feeling.label, true) {
                scope.launch { finishMorning(feeling.value) }
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })
        }
        val scroll = ScrollView(this).apply { addView(layout); isFillViewport = true }
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(250)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.35f }
        return try {
            wm.addView(scroll, params)
            overlay = scroll; overlayId = null; overlayIsMorning = true
            morningFeelingPending = false
            true
        } catch (e: Exception) {
            app.message.value = "悬浮提示未能显示：${e.message}"
            false
        }
    }

    private suspend fun finishMorning(feeling: String) {
        val id = try { repo.wake() } catch (e: Exception) {
            app.message.value = e.message ?: "记录失败"
            detecting = false
            return
        } ?: run { removeOverlay(); stopSelf(); return }
        if (id == SleepRepository.SHORT_NIGHT) {
            removeOverlay()
            getSystemService(NotificationManager::class.java).cancel(MORNING_NOTIFICATION)
            ending = true
            sensors.unregisterListener(this)
            detector = null; sensorKey = null
            if (wakeLock.isHeld) wakeLock.release()
            Toast.makeText(this, "不足1小时，本次不记录", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        try { repo.feeling(id, feeling) } catch (e: Exception) { app.message.value = e.message ?: "感受保存失败" }
        removeOverlay()
        getSystemService(NotificationManager::class.java).cancel(MORNING_NOTIFICATION)
        ending = true
        sensors.unregisterListener(this)
        detector = null; sensorKey = null
        if (wakeLock.isHeld) wakeLock.release()
        Toast.makeText(this, "早安，已经记好啦 ☀️", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

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
        layout.addView(text("快去睡觉，为什么拿起手机了？我要报告给 ${partnerDisplay} 😠", 20f).apply { typeface = Typeface.DEFAULT_BOLD })
        val input = EditText(this).apply {
            hint = "例如：想${partnerDisplay}想得睡不着了 🥺"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            minLines = 2; maxLines = 5; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(pending.reason ?: "")
        }
        layout.addView(input, LinearLayout.LayoutParams(-1, dp(130)))
        val error = text("", 13f); layout.addView(error)
        val submit = Button(this).apply { text = "记录原因"; isAllCaps = false }
        layout.addView(submit, LinearLayout.LayoutParams(-1, dp(56)))
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
        try { wm.addView(scroll, params); overlay = scroll; overlayId = pending.id; overlayIsMorning = false }
        catch (e: Exception) { app.message.value = "悬浮提示未能显示，请打开晚安填写：${e.message}" }
    }
    private fun removeOverlay() {
        overlay?.let { view ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        overlay = null; overlayId = null; overlayIsMorning = false
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        instance.clear()
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        runCatching { unregisterReceiver(receiver) }
        removeOverlay(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java).cancel(MORNING_NOTIFICATION) }
        super.onDestroy()
    }
    companion object {
        const val SLEEP = "com.stone.sleepguard.SLEEP"
        const val RESUME = "com.stone.sleepguard.RESUME"
        const val WAKE = "com.stone.sleepguard.WAKE"
        const val TARGET_WAKE = "targetWake"
        private const val CHANNEL = "night_monitor"
        private const val MORNING_CHANNEL = "morning_reminder"
        private const val NOTIFICATION = 101
        private const val MORNING_NOTIFICATION = 102
        @Volatile var running = false
        private var instance = WeakReference<MonitorService>(null)
        fun dismissMorningPrompt() {
            instance.get()?.let { if (it.overlayIsMorning) it.removeOverlay() }
        }
        fun releaseNow() {
            instance.get()?.let { service ->
                service.ending = true
                service.sensors.unregisterListener(service)
                service.detector = null; service.sensorKey = null
                if (service.wakeLock.isHeld) service.wakeLock.release()
                service.removeOverlay()
            }
        }
        fun start(context: Context, action: String, targetWake: Int? = null) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            if (targetWake != null) intent.putExtra(TARGET_WAKE, targetWake)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
