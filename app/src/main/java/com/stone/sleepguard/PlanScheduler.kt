package com.stone.sleepguard

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
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

    private fun req(vararg parts: Any): Int = parts.joinToString(":").hashCode()

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "习惯闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "习惯的定时闹钟提醒"
            enableVibration(true)
        })
    }

    suspend fun rebuild(context: Context) {
        val repo = (context.applicationContext as? SleepApp)?.planRepository ?: return
        val habits = repo.dao.habits()
        val reminders = repo.dao.allReminders()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        for (offset in 0L..13L) {
            val date = today.plusDays(offset)
            val slots = slotsFor(habits, reminders, date)
            for ((habit, minute) in slots) {
                val at = atMillis(date, minute)
                if (at > now) setAlarm(context, at, habitAlarm(context, habit.id, date, minute), "h:${habit.id}:${date.toEpochDay()}:$minute".hashCode())
                val overdue = at + 2 * 60 * 60_000L
                if (overdue > now) setAlarm(context, overdue, overdueAlarm(context, habit.id, date, minute), "o:${habit.id}:${date.toEpochDay()}:$minute".hashCode())
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

    private fun setAlarm(context: Context, at: Long, operation: PendingIntent, requestCode: Int) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(context, requestCode,
            Intent(context, MainActivity::class.java).putExtra("plan", true), flags())
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } else {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
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

    fun notification(context: Context, habit: Habit, date: LocalDate, minute: Int): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, req("open", habit.id, minute),
            Intent(context, MainActivity::class.java).putExtra("plan", true), flags())
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("⏰ ${habit.title}")
            .setContentText("${minutesText(minute)} · 点完成打卡")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "完成", completeIntent(context, habit.id, date, minute))
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
                val habitId = intent.getLongExtra(PlanScheduler.EXTRA_HABIT, 0)
                val date = LocalDate.ofEpochDay(intent.getLongExtra(PlanScheduler.EXTRA_DATE, 0))
                val minute = intent.getIntExtra(PlanScheduler.EXTRA_MINUTE, -1)
                if (habitId == 0L || minute !in 0..1439) return@launch
                val habit = app.planRepository.dao.habit(habitId) ?: return@launch
                if (action == PlanScheduler.ACTION_OVERDUE) {
                    app.planRepository.markOverdue(habitId, date, minute)
                } else if (action == PlanScheduler.ACTION_COMPLETE) {
                    app.planRepository.toggleSlot(habitId, date, minute)
                    context.getSystemService(NotificationManager::class.java).cancel((habitId.toString() + date + minute).hashCode())
                } else if (action == PlanScheduler.ACTION_HABIT) {
                    if (!habit.isDue(date)) return@launch
                    if (app.planRepository.dao.reminders(habitId).none { it.minute == minute }) return@launch
                    val done = app.planRepository.dao.checkIn(habitId, date.toEpochDay().toInt(), minute)?.completedAt != null
                    if (!done) {
                        PlanScheduler.ensureChannel(context)
                        context.getSystemService(NotificationManager::class.java)
                            .notify((habitId.toString() + date + minute).hashCode(), PlanScheduler.notification(context, habit, date, minute))
                    }
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }
    }
}
