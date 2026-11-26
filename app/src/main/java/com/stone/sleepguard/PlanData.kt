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
    val endMinute: Int? = null,
    val alarm: Boolean = false,
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
    val icon: String = "",
    val targetCount: Int = 1,
    val reminderMode: String = "ALARM",
)

@Entity(tableName = "habit_days", primaryKeys = ["habitId", "date"], foreignKeys = [ForeignKey(
    entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("date")])
data class HabitDay(
    val habitId: Long,
    val date: Int,
    val count: Int = 0,
    val dayTarget: Int? = null,
    val completedAt: Long? = null,
    val overdueEmailSent: Boolean = false,
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
    @Query("SELECT * FROM habit_days WHERE habitId = :id ORDER BY date") fun history(id: Long): Flow<List<HabitDay>>
    @Query("SELECT * FROM plan_tasks WHERE id = :id") suspend fun task(id: Long): PlanTask?
    @Query("SELECT * FROM plan_tasks WHERE completedAt IS NULL AND reminderMinute IS NOT NULL AND dueDate >= :today")
    suspend fun pendingTasks(today: Int): List<PlanTask>
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

    @Query("SELECT * FROM habit_days WHERE date = :date") fun observeDays(date: Int): Flow<List<HabitDay>>
    @Query("SELECT * FROM habit_days WHERE date = :date") suspend fun days(date: Int): List<HabitDay>
    @Query("SELECT * FROM habit_days WHERE habitId = :habitId AND date = :date") suspend fun day(habitId: Long, date: Int): HabitDay?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDay(day: HabitDay): Long
    @Update suspend fun updateDay(day: HabitDay)

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

@Database(entities = [PlanTask::class, Habit::class, HabitReminder::class, HabitCheckIn::class, HabitDay::class, PlanSummary::class], version = 3, exportSchema = false)
abstract class PlanDatabase : RoomDatabase() { abstract fun dao(): PlanDao }

private val PLAN_MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE habit_days ADD COLUMN dayTarget INTEGER")
        db.execSQL("UPDATE habit_days SET dayTarget = CASE WHEN completedAt IS NOT NULL THEN MAX(count, 1) ELSE (SELECT targetCount FROM habits WHERE id = habitId) END")
        db.execSQL("ALTER TABLE plan_tasks ADD COLUMN endMinute INTEGER")
        db.execSQL("ALTER TABLE plan_tasks ADD COLUMN alarm INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE habits ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
    }
}

private val PLAN_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE habits ADD COLUMN targetCount INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE habits ADD COLUMN reminderMode TEXT NOT NULL DEFAULT 'ALARM'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS habit_days (
            habitId INTEGER NOT NULL, date INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 0,
            completedAt INTEGER, overdueEmailSent INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(habitId, date), FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_days_date ON habit_days(date)")
    }
}

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
    private val db = Room.databaseBuilder(context, PlanDatabase::class.java, "plan.db")
        .addMigrations(PLAN_MIGRATION_1_2, PLAN_MIGRATION_2_3).build()
    val dao = db.dao()
    private val mutex = Mutex()

    fun tasks(date: LocalDate): Flow<List<PlanTask>> = dao.observeTasks(date.toEpochDay().toInt())
    fun habits(): Flow<List<Habit>> = dao.observeHabits()
    fun reminders(): Flow<List<HabitReminder>> = dao.observeReminders()
    fun days(date: LocalDate): Flow<List<HabitDay>> = dao.observeDays(date.toEpochDay().toInt())

    suspend fun saveTask(task: PlanTask) = mutex.withLock {
        require(task.title.isNotBlank())
        require(task.dueMinute == null || task.dueMinute in 0..1439)
        require(task.endMinute == null || (task.dueMinute != null && task.endMinute in task.dueMinute + 1..1439))
        val saved = if (task.id == 0L) task.copy(id = dao.insertTask(task)) else {
            dao.updateTask(task)
            task
        }
        PlanScheduler.syncTask(context, saved)
    }

    suspend fun toggleTask(id: Long, date: LocalDate) = mutex.withLock {
        val task = dao.tasks(date.toEpochDay().toInt()).firstOrNull { it.id == id } ?: return@withLock
        val updated = task.copy(completedAt = if (task.completedAt == null) System.currentTimeMillis() else null)
        dao.updateTask(updated)
        PlanScheduler.syncTask(context, updated)
    }

    suspend fun deleteTask(id: Long) = mutex.withLock { dao.deleteTask(id); PlanScheduler.cancelTask(context, id) }

    suspend fun saveHabit(habit: Habit, reminderMinutes: List<Int>): Long = mutex.withLock {
        val clean = habit.title.trim()
        require(clean.isNotEmpty()) { "请写下习惯名称" }

        val times = reminderMinutes.distinct().sorted().take(5)
        val id = if (habit.id == 0L) dao.insertHabit(habit.copy(title = clean))
        else { dao.updateHabit(habit.copy(title = clean)); dao.deleteReminders(habit.id); habit.id }
        times.forEach { dao.insertReminder(HabitReminder(habitId = id, minute = it)) }
        id
    }

    suspend fun deleteHabit(id: Long) = mutex.withLock { dao.deleteHabit(id) }

    suspend fun changeHabit(habitId: Long, date: LocalDate, minute: Int?, undo: Boolean = false) = mutex.withLock {
        if (date > LocalDate.now()) return@withLock
        val day = date.toEpochDay().toInt()
        val updated = db.withTransaction {
            val habit = dao.habit(habitId) ?: return@withTransaction false
            if (!habit.isDue(date)) return@withTransaction false
            val current = dao.day(habitId, day) ?: HabitDay(habitId, day)
            val target = current.dayTarget ?: habit.targetCount.coerceAtLeast(1)
            val stamped = dao.checkIns(day).filter { it.habitId == habitId && it.completedAt != null }
            val times = dao.reminders(habitId).map { it.minute }
            if (!undo && minute != null && (minute !in times || stamped.any { it.minute == minute })) return@withTransaction false
            if (undo && current.count == 0 || !undo && current.count >= target) return@withTransaction false
            val count = current.count + if (undo) -1 else 1
            val now = System.currentTimeMillis()
            if (undo) {
                stamped.maxByOrNull { it.completedAt!! }?.let { dao.deleteCheckIn(habitId, day, it.minute) }
            } else {
                val slot = minute ?: times.firstOrNull { t -> stamped.none { it.minute == t } }
                if (slot != null) {
                    val existing = dao.checkIn(habitId, day, slot)
                    if (existing == null) dao.insertCheckIn(HabitCheckIn(habitId = habitId, date = day, minute = slot, completedAt = now))
                    else dao.updateCheckIn(existing.copy(completedAt = now))
                }
            }
            val next = current.copy(count = count, dayTarget = target, completedAt = if (count >= target) now else null)
            if (dao.insertDay(next) == -1L) dao.updateDay(next)
            true
        }
        if (updated && !undo && date == LocalDate.now()) maybeSendAllDone(date)
    }

    suspend fun dueHabits(date: LocalDate): List<Habit> = dao.habits().filter { it.isDue(date) }

    private suspend fun dayMap(date: LocalDate): Map<Long, HabitDay> =
        dao.days(date.toEpochDay().toInt()).associateBy { it.habitId }

    private fun Habit.isComplete(day: HabitDay?): Boolean =
        day?.completedAt != null

    private suspend fun doneCount(date: LocalDate): Int {
        val days = dayMap(date)
        return dueHabits(date).count { it.isComplete(days[it.id]) }
    }

    suspend fun maybeSendAllDone(date: LocalDate) {
        val habits = dueHabits(date)
        if (habits.isEmpty()) return
        val days = dayMap(date)
        if (!habits.all { it.isComplete(days[it.id]) }) return
        val day = date.toEpochDay().toInt()
        dao.ensureSummary(PlanSummary(day))
        if (dao.summary(day)?.allDoneEmailSent == true) return
        dao.markAllDone(day)
        EmailNotifier.habitsAllDone(context, date, habits.size)
    }

    suspend fun markOverdue(habitId: Long, date: LocalDate, minute: Int) {
        val dayNumber = date.toEpochDay().toInt()
        val habit = dao.habit(habitId) ?: return
        if (!habit.isDue(date)) return
        if (dao.reminders(habitId).none { it.minute == minute }) return
        if (dao.checkIn(habitId, dayNumber, minute)?.completedAt != null) return
        val current = dao.day(habitId, dayNumber)
        if (habit.isComplete(current) || current?.overdueEmailSent == true) return
        val next = (current ?: HabitDay(habitId = habitId, date = dayNumber)).copy(overdueEmailSent = true)
        if (current == null) dao.insertDay(next) else dao.updateDay(next)
        EmailNotifier.habitOverdue(context, date, habit.title, minute, doneCount(date), dueHabits(date).size)
    }

    /** 午夜汇总：全部完成但没来得及发时补发全部完成；否则发未完成汇总。 */
    suspend fun midnightSummary(date: LocalDate) {
        val habits = dueHabits(date)
        if (habits.isEmpty()) return
        val dayNumber = date.toEpochDay().toInt()
        dao.ensureSummary(PlanSummary(dayNumber))
        if (dao.summary(dayNumber)?.midnightEmailSent == true) return
        val days = dayMap(date)
        val done = habits.count { it.isComplete(days[it.id]) }
        if (done == habits.size) {
            if (dao.summary(dayNumber)?.allDoneEmailSent != true) {
                dao.markAllDone(dayNumber)
                EmailNotifier.habitsAllDone(context, date, habits.size)
            }
        } else {
            val missed = habits.filterNot { it.isComplete(days[it.id]) }.map { it.title }
            EmailNotifier.habitsIncomplete(context, date, habits.size, done, missed)
        }
        dao.markMidnight(dayNumber)
    }
}

