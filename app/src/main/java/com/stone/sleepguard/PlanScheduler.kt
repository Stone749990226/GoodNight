package com.stone.sleepguard

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object PlanScheduler {
    const val ACTION_HABIT = "com.stone.sleepguard.HABIT_ALARM"
    const val ACTION_OVERDUE = "com.stone.sleepguard.HABIT_OVERDUE"
    const val ACTION_MIDNIGHT = "com.stone.sleepguard.PLAN_MIDNIGHT"
    const val ACTION_COMPLETE = "com.stone.sleepguard.HABIT_COMPLETE"
    const val EXTRA_HABIT = "habitId"
    const val EXTRA_DATE = "date"
    const val EXTRA_MINUTE = "minute"
    const val CHANNEL = "habit_alarm"
    const val CHANNEL_NOTIFY = "habit_notify"

    private fun req(vararg parts: Any): Int = parts.joinToString(":").hashCode()

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ALARM_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "计划与习惯闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                enableVibration(true)
            })
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "习惯闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "习惯的闹钟提醒"
                enableVibration(true)
            })
        }
        if (manager.getNotificationChannel(CHANNEL_NOTIFY) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_NOTIFY, "习惯通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "习惯的普通通知提醒"
            })
        }
    }

    const val ACTION_TASK = "com.stone.sleepguard.TASK_REMINDER"
    private const val ALARM_CHANNEL = "plan_ring_v1"

    private fun taskIntent(context: Context, id: Long) = PendingIntent.getBroadcast(context, 0,
        Intent(context, PlanAlarmReceiver::class.java).setAction(ACTION_TASK)
            .setData(android.net.Uri.parse("goodnight://plan/$id")), flags())

    fun cancelTask(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(taskIntent(context, id))
        context.getSystemService(NotificationManager::class.java).cancel("task", id.toInt())
    }

    private fun taskTrigger(task: PlanTask): Long? = task.reminderMinute?.let { minute ->
        LocalDate.ofEpochDay(task.dueDate.toLong()).atStartOfDay().plusMinutes(minute.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun syncTask(context: Context, task: PlanTask) {
        cancelTask(context, task.id)
        val trigger = taskTrigger(task) ?: return
        if (task.completedAt == null && trigger > System.currentTimeMillis())
            setAlarm(context, trigger, taskIntent(context, task.id), req("task", task.id), task.alarm)
    }

    fun postTask(context: Context, task: PlanTask) {
        ensureChannel(context)
        val channel = if (task.alarm) ALARM_CHANNEL else CHANNEL_NOTIFY
        val open = PendingIntent.getActivity(context, req("task", task.id),
            Intent(context, MainActivity::class.java).putExtra("plan", true).putExtra("planDate", task.dueDate.toLong()), flags())
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(task.title).setContentText(task.dueMinute?.let { "计划时间 ${minutesText(it)}" } ?: "计划提醒")
            .setCategory(if (task.alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify("task", task.id.toInt(), notification)
    }

    suspend fun rebuild(context: Context) {
        val repo = (context.applicationContext as? SleepApp)?.planRepository ?: return
        val habits = repo.dao.habits()
        val reminders = repo.dao.allReminders()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        repo.dao.pendingTasks(today.toEpochDay().toInt()).forEach { task ->
            if ((taskTrigger(task) ?: 0) > now) syncTask(context, task)
        }
        for (offset in 0L..13L) {
            val date = today.plusDays(offset)
            val slots = slotsFor(habits, reminders, date)
            for ((habit, minute) in slots) {
                val at = atMillis(date, minute)
                if (at > now) setAlarm(context, at, habitAlarm(context, habit.id, date, minute), "h:${habit.id}:${date.toEpochDay()}:$minute".hashCode(), habit.reminderMode == "ALARM")
                val overdue = at + 2 * 60 * 60_000L
                if (overdue > now) setAlarm(context, overdue, overdueAlarm(context, habit.id, date, minute), "o:${habit.id}:${date.toEpochDay()}:$minute".hashCode(), alarm = false)
            }
        }
        val nextMidnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val midnight = PendingIntent.getBroadcast(context, "midnight".hashCode(),
            Intent(context, PlanAlarmReceiver::class.java).setAction(ACTION_MIDNIGHT)
                .putExtra(EXTRA_DATE, today.toEpochDay().toInt()), flags())
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, midnight)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, midnight)
    }

    private fun setAlarm(context: Context, at: Long, operation: PendingIntent, requestCode: Int, alarm: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(context, requestCode,
            Intent(context, MainActivity::class.java).putExtra("plan", true), flags())
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } else if (alarm) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun habitAlarm(context: Context, habitId: Long, date: LocalDate, minute: Int) =
        PendingIntent.getBroadcast(context, req(ACTION_HABIT, habitId, date.toEpochDay(), minute),
            Intent(context, PlanAlarmReceiver::class.java).setAction(ACTION_HABIT)
                .putExtra(EXTRA_HABIT, habitId).putExtra(EXTRA_DATE, date.toEpochDay()).putExtra(EXTRA_MINUTE, minute), flags())

    private fun overdueAlarm(context: Context, habitId: Long, date: LocalDate, minute: Int) =
        PendingIntent.getBroadcast(context, req(ACTION_OVERDUE, habitId, date.toEpochDay(), minute),
            Intent(context, PlanAlarmReceiver::class.java).setAction(ACTION_OVERDUE)
                .putExtra(EXTRA_HABIT, habitId).putExtra(EXTRA_DATE, date.toEpochDay()).putExtra(EXTRA_MINUTE, minute), flags())

    fun completeIntent(context: Context, habitId: Long, date: LocalDate, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(context, req(ACTION_COMPLETE, habitId, date.toEpochDay(), minute),
            Intent(context, PlanAlarmReceiver::class.java).setAction(ACTION_COMPLETE)
                .putExtra(EXTRA_HABIT, habitId).putExtra(EXTRA_DATE, date.toEpochDay()).putExtra(EXTRA_MINUTE, minute), flags())

    fun notification(context: Context, habit: Habit, date: LocalDate, minute: Int, count: Int, partner: String): Notification {
        ensureChannel(context)
        val channel = if (habit.reminderMode == "ALARM") ALARM_CHANNEL else CHANNEL_NOTIFY
        val target = habit.targetCount.coerceAtLeast(1)
        val action = if (count + 1 >= target) "完成" else "+1"
        val open = PendingIntent.getActivity(context, req("open", habit.id, minute),
            Intent(context, MainActivity::class.java).putExtra("plan", true), flags())
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("${partner}提醒你该去${habit.title}了哦 ${habit.glyph()}")
            .setContentText("${minutesText(minute)} · 今天 $count/$target")
            .setPriority(if (habit.reminderMode == "ALARM") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (habit.reminderMode == "ALARM") NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, action, completeIntent(context, habit.id, date, minute))
            .build()
    }

    private fun atMillis(date: LocalDate, minute: Int): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(minute / 60, minute % 60)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as? SleepApp ?: run { pending.finish(); return }
        app.scope.launch(Dispatchers.IO) {
            try {
                val action = intent.action
                if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_DATE_CHANGED) {
                    PlanScheduler.rebuild(context); return@launch
                }
                if (action == PlanScheduler.ACTION_MIDNIGHT) {
                    val date = intent.getIntExtra(PlanScheduler.EXTRA_DATE, 0).let { LocalDate.ofEpochDay(it.toLong()) }
                    app.planRepository.midnightSummary(date)
                    PlanScheduler.rebuild(context)
                    return@launch
                }
                if (action == PlanScheduler.ACTION_TASK) {
                    val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return@launch
                    val task = app.planRepository.dao.task(id) ?: return@launch
                    val minute = task.reminderMinute ?: return@launch
                    val trigger = LocalDate.ofEpochDay(task.dueDate.toLong()).atStartOfDay().plusMinutes(minute.toLong())
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (task.completedAt == null && trigger <= System.currentTimeMillis()) PlanScheduler.postTask(context, task)
                    return@launch
                }
                val habitId = intent.getLongExtra(PlanScheduler.EXTRA_HABIT, 0)
                val date = LocalDate.ofEpochDay(intent.getLongExtra(PlanScheduler.EXTRA_DATE, 0))
                val minute = intent.getIntExtra(PlanScheduler.EXTRA_MINUTE, -1)
                if (habitId == 0L || minute !in 0..1439) return@launch
                val habit = app.planRepository.dao.habit(habitId) ?: return@launch
                if (action == PlanScheduler.ACTION_OVERDUE) {
                    app.planRepository.markOverdue(habitId, date, minute)
                } else if (action == PlanScheduler.ACTION_COMPLETE) {
                    app.planRepository.changeHabit(habitId, date, minute)
                    context.getSystemService(NotificationManager::class.java).cancel((habitId.toString() + date + minute).hashCode())
                } else if (action == PlanScheduler.ACTION_HABIT) {
                    if (!habit.isDue(date)) return@launch
                    if (app.planRepository.dao.reminders(habitId).none { it.minute == minute }) return@launch
                    val count = app.planRepository.dao.day(habitId, date.toEpochDay().toInt())?.count ?: 0
                    val target = habit.targetCount.coerceAtLeast(1)
                    if (count < target) {
                        val partner = partnerName(app.rolePreferences.flow.first().role)
                        PlanScheduler.ensureChannel(context)
                        context.getSystemService(NotificationManager::class.java)
                            .notify((habitId.toString() + date + minute).hashCode(),
                                PlanScheduler.notification(context, habit, date, minute, count, partner))
                    }
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }
    }
}
