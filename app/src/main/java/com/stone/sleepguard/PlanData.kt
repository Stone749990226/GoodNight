package com.stone.sleepguard

import android.app.NotificationManager
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate

const val FREQ_DAILY = "DAILY"
const val FREQ_WEEKDAYS = "WEEKDAYS"
const val FREQ_WORKDAYS = "WORKDAYS"

@Entity(tableName = "plan_tasks")
data class PlanTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val dueDate: Int,
    val dueMinute: Int? = null,
    val reminderMinute: Int? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val frequency: String = FREQ_DAILY,
    val weekdayMask: Int = 0b1111111,
    val startDate: Int = LocalDate.now().toEpochDay().toInt(),
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "habit_reminders", foreignKeys = [ForeignKey(
    entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("habitId")])
data class HabitReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val minute: Int,
)

@Entity(tableName = "habit_checkins", foreignKeys = [ForeignKey(
    entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["habitId", "date", "minute"], unique = true), Index("date")])
data class HabitCheckIn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val date: Int,
    val minute: Int,
    val completedAt: Long? = null,
    val overdueEmailSent: Boolean = false,
)

@Entity(tableName = "plan_summaries")
data class PlanSummary(
    @PrimaryKey val date: Int,
    val allDoneEmailSent: Boolean = false,
    val midnightEmailSent: Boolean = false,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_tasks WHERE dueDate = :date ORDER BY (completedAt IS NOT NULL), createdAt")
    fun observeTasks(date: Int): Flow<List<PlanTask>>
    @Query("SELECT * FROM plan_tasks WHERE dueDate = :date ORDER BY (completedAt IS NOT NULL), createdAt")
    suspend fun tasks(date: Int): List<PlanTask>
    @Insert suspend fun insertTask(task: PlanTask): Long
    @Update suspend fun updateTask(task: PlanTask)
    @Query("DELETE FROM plan_tasks WHERE id = :id") suspend fun deleteTask(id: Long)

    @Query("SELECT * FROM habits WHERE active = 1 ORDER BY createdAt") fun observeHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits WHERE active = 1 ORDER BY createdAt") suspend fun habits(): List<Habit>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun habit(id: Long): Habit?
    @Insert suspend fun insertHabit(habit: Habit): Long
    @Update suspend fun updateHabit(habit: Habit)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)

    @Query("SELECT * FROM habit_reminders ORDER BY habitId, minute") fun observeReminders(): Flow<List<HabitReminder>>
    @Query("SELECT * FROM habit_reminders WHERE habitId = :habitId ORDER BY minute") suspend fun reminders(habitId: Long): List<HabitReminder>
    @Query("SELECT * FROM habit_reminders") suspend fun allReminders(): List<HabitReminder>
    @Insert suspend fun insertReminder(reminder: HabitReminder): Long
    @Query("DELETE FROM habit_reminders WHERE habitId = :habitId") suspend fun deleteReminders(habitId: Long)

    @Query("SELECT * FROM habit_checkins WHERE date = :date") fun observeCheckIns(date: Int): Flow<List<HabitCheckIn>>
    @Query("SELECT * FROM habit_checkins WHERE date = :date") suspend fun checkIns(date: Int): List<HabitCheckIn>
    @Query("SELECT * FROM habit_checkins WHERE habitId = :habitId AND date = :date AND minute = :minute")
    suspend fun checkIn(habitId: Long, date: Int, minute: Int): HabitCheckIn?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCheckIn(checkIn: HabitCheckIn): Long
    @Update suspend fun updateCheckIn(checkIn: HabitCheckIn)
    @Query("DELETE FROM habit_checkins WHERE habitId = :habitId AND date = :date AND minute = :minute")
    suspend fun deleteCheckIn(habitId: Long, date: Int, minute: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureSummary(summary: PlanSummary): Long
    @Query("SELECT * FROM plan_summaries WHERE date = :date") suspend fun summary(date: Int): PlanSummary?
    @Query("UPDATE plan_summaries SET allDoneEmailSent = 1 WHERE date = :date") suspend fun markAllDone(date: Int)
    @Query("UPDATE plan_summaries SET midnightEmailSent = 1 WHERE date = :date") suspend fun markMidnight(date: Int)
}

@Database(entities = [PlanTask::class, Habit::class, HabitReminder::class, HabitCheckIn::class, PlanSummary::class], version = 1, exportSchema = false)
abstract class PlanDatabase : RoomDatabase() { abstract fun dao(): PlanDao }

/** 2026 年法定节假日与调休上班日；其他年份先按周一至周五，后续可继续补表。 */
object WorkdayCalendar {
    private fun range(start: LocalDate, end: LocalDate) = generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }.toSet()
    private val holidays = buildSet {
        addAll(range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)))
        addAll(range(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23)))
        addAll(range(LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6)))
        addAll(range(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)))
        addAll(range(LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21)))
        addAll(range(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27)))
        addAll(range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)))
    }
    private val makeups = setOf(
        LocalDate.of(2026, 1, 4), LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 28),
        LocalDate.of(2026, 5, 9), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 10),
    )
    fun isWorkday(date: LocalDate): Boolean {
        if (date.year != 2026) return date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        if (date in makeups) return true
        if (date in holidays) return false
        return date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }
}

fun Habit.isDue(date: LocalDate): Boolean {
    if (date.toEpochDay() < startDate) return false
    return when (frequency) {
        FREQ_WEEKDAYS -> (weekdayMask and (1 shl (date.dayOfWeek.value - 1))) != 0
        FREQ_WORKDAYS -> WorkdayCalendar.isWorkday(date)
        else -> true
    }
}

data class HabitSlot(val habit: Habit, val minute: Int)
fun slotsFor(habits: List<Habit>, reminders: List<HabitReminder>, date: LocalDate): List<HabitSlot> =
    habits.filter { it.isDue(date) }.flatMap { habit ->
        reminders.filter { it.habitId == habit.id }.map { HabitSlot(habit, it.minute) }
    }

class PlanRepository(private val context: Context) {
    private val db = Room.databaseBuilder(context, PlanDatabase::class.java, "plan.db").build()
    val dao = db.dao()
    private val mutex = Mutex()

    fun tasks(date: LocalDate): Flow<List<PlanTask>> = dao.observeTasks(date.toEpochDay().toInt())
    fun habits(): Flow<List<Habit>> = dao.observeHabits()
    fun reminders(): Flow<List<HabitReminder>> = dao.observeReminders()
    fun checkIns(date: LocalDate): Flow<List<HabitCheckIn>> = dao.observeCheckIns(date.toEpochDay().toInt())

    suspend fun addTask(title: String, date: LocalDate, dueMinute: Int?, reminderMinute: Int?) = mutex.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "请写下待办内容" }
        dao.insertTask(PlanTask(title = clean, dueDate = date.toEpochDay().toInt(), dueMinute = dueMinute, reminderMinute = reminderMinute))
    }

    suspend fun toggleTask(id: Long, date: LocalDate) = mutex.withLock {
        val task = dao.tasks(date.toEpochDay().toInt()).firstOrNull { it.id == id } ?: return@withLock
        dao.updateTask(task.copy(completedAt = if (task.completedAt == null) System.currentTimeMillis() else null))
    }

    suspend fun deleteTask(id: Long) = mutex.withLock { dao.deleteTask(id) }

    suspend fun saveHabit(habit: Habit, reminderMinutes: List<Int>): Long = mutex.withLock {
        val clean = habit.title.trim()
        require(clean.isNotEmpty()) { "请写下习惯名称" }
        require(reminderMinutes.isNotEmpty()) { "至少设置一个提醒时间" }
        val times = reminderMinutes.distinct().sorted().take(5)
        val id = if (habit.id == 0L) dao.insertHabit(habit.copy(title = clean)) else {
            dao.updateHabit(habit.copy(title = clean)); dao.deleteReminders(habit.id); habit.id
        }
        times.forEach { dao.insertReminder(HabitReminder(habitId = id, minute = it)) }
        id
    }

    suspend fun deleteHabit(id: Long) = mutex.withLock { dao.deleteHabit(id) }

    /** 切换某个习惯时段的完成状态；完成后检查是否全天全部完成。 */
    suspend fun toggleSlot(habitId: Long, date: LocalDate, minute: Int) = mutex.withLock {
        val day = date.toEpochDay().toInt()
        val existing = dao.checkIn(habitId, day, minute)
        if (existing?.completedAt != null) {
            dao.updateCheckIn(existing.copy(completedAt = null))
        } else if (existing == null) {
            dao.insertCheckIn(HabitCheckIn(habitId = habitId, date = day, minute = minute, completedAt = System.currentTimeMillis()))
        } else {
            dao.updateCheckIn(existing.copy(completedAt = System.currentTimeMillis()))
        }
        if (existing?.completedAt == null) context.getSystemService(NotificationManager::class.java)
            .cancel((habitId.toString() + date + minute).hashCode())
        maybeSendAllDone(date)
    }

    suspend fun dueSlots(date: LocalDate): List<HabitSlot> = slotsFor(dao.habits(), dao.allReminders(), date)

    suspend fun completedSlotKeys(date: LocalDate): Set<Pair<Long, Int>> =
        dao.checkIns(date.toEpochDay().toInt()).filter { it.completedAt != null }.map { it.habitId to it.minute }.toSet()

    suspend fun maybeSendAllDone(date: LocalDate) {
        val slots = dueSlots(date)
        if (slots.isEmpty()) return
        val done = completedSlotKeys(date)
        if (!slots.all { (it.habit.id to it.minute) in done }) return
        val day = date.toEpochDay().toInt()
        dao.ensureSummary(PlanSummary(day))
        if (dao.summary(day)?.allDoneEmailSent == true) return
        dao.markAllDone(day)
        EmailNotifier.habitsAllDone(context, date, slots.size)
    }

    suspend fun markOverdue(habitId: Long, date: LocalDate, minute: Int) {
        val day = date.toEpochDay().toInt()
        val habit = dao.habit(habitId) ?: return
        if (!habit.isDue(date)) return
        if (dao.reminders(habitId).none { it.minute == minute }) return
        val existing = dao.checkIn(habitId, day, minute)
        if (existing?.completedAt != null || existing?.overdueEmailSent == true) return
        if (existing == null) dao.insertCheckIn(HabitCheckIn(habitId = habitId, date = day, minute = minute, overdueEmailSent = true))
        else dao.updateCheckIn(existing.copy(overdueEmailSent = true))
        val done = completedSlotKeys(date).size
        val total = dueSlots(date).size
        EmailNotifier.habitOverdue(context, date, habit.title, minute, done, total)
    }

    /** 午夜汇总：只有全部完成但没来得及发时补发全部完成；否则发未完成汇总。 */
    suspend fun midnightSummary(date: LocalDate) {
        val slots = dueSlots(date)
        if (slots.isEmpty()) return
        val day = date.toEpochDay().toInt()
        dao.ensureSummary(PlanSummary(day))
        if (dao.summary(day)?.midnightEmailSent == true) return
        val done = completedSlotKeys(date)
        val complete = slots.all { (it.habit.id to it.minute) in done }
        if (complete) {
            if (dao.summary(day)?.allDoneEmailSent != true) {
                dao.markAllDone(day)
                EmailNotifier.habitsAllDone(context, date, slots.size)
            }
        } else {
            val missed = slots.filter { (it.habit.id to it.minute) !in done }
            EmailNotifier.habitsIncomplete(context, date, slots.size, done.size, missed)
        }
        dao.markMidnight(day)
    }
}

