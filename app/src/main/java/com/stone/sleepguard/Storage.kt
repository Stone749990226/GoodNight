package com.stone.sleepguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Dao
interface SleepDao {
    @Transaction @Query("SELECT * FROM nights ORDER BY firstSleepAt DESC")
    fun observeRecords(): Flow<List<NightRecord>>
    @Transaction @Query("SELECT * FROM nights WHERE phase != 'AWAKE' ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<NightRecord?>
    @Transaction @Query("SELECT * FROM nights WHERE phase != 'AWAKE' ORDER BY id DESC LIMIT 1")
    suspend fun active(): NightRecord?
    @Transaction @Query("SELECT * FROM nights WHERE id = :id")
    suspend fun record(id: Long): NightRecord?
    @Query("SELECT * FROM interruptions WHERE id = :id")
    suspend fun interruption(id: Long): Interruption?
    @Insert suspend fun insert(night: Night): Long
    @Update suspend fun update(night: Night)
    @Insert suspend fun insert(interruption: Interruption): Long
    @Update suspend fun update(interruption: Interruption)
    @Insert suspend fun insert(event: NightEvent)
    @Query("DELETE FROM interruptions WHERE nightId = :nightId") suspend fun deleteInterruptions(nightId: Long)
    @Query("DELETE FROM events WHERE nightId = :nightId") suspend fun deleteEvents(nightId: Long)
    @Query("DELETE FROM nights WHERE id = :nightId") suspend fun deleteNight(nightId: Long)
    @Query("DELETE FROM nights WHERE wakeAt IS NOT NULL AND wakeAt - firstSleepAt < :threshold")
    suspend fun deleteShortNights(threshold: Long): Int
    @Query("DELETE FROM interruptions WHERE id = :id") suspend fun deleteInterruption(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreNight(night: Night)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreInterruption(interruption: Interruption)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreEvent(event: NightEvent)
    @Insert suspend fun insertEmail(job: EmailJob): Long
    @Query("SELECT * FROM email_outbox WHERE status = 0 ORDER BY id LIMIT 20") suspend fun pendingEmails(): List<EmailJob>
    @Query("UPDATE email_outbox SET status = 1 WHERE id = :id AND status = 0") suspend fun claimEmail(id: Long): Int
    @Query("UPDATE email_outbox SET status = 2, attempts = attempts + 1, error = null WHERE id = :id") suspend fun markEmailSent(id: Long)
    @Query("UPDATE email_outbox SET status = :status, attempts = attempts + 1, error = :error WHERE id = :id") suspend fun markEmailFailed(id: Long, status: Int, error: String?)
    @Query("UPDATE email_outbox SET status = 0 WHERE status = 1") suspend fun resetSendingEmails()
    @Query("SELECT COUNT(*) FROM email_outbox WHERE status = 0") suspend fun pendingEmailCount(): Int
    @Query("SELECT COUNT(*) FROM email_outbox WHERE status = 2") suspend fun sentEmailCount(): Int
}

@Database(entities = [Night::class, Interruption::class, NightEvent::class, EmailJob::class], version = 3, exportSchema = false)
abstract class SleepDatabase : RoomDatabase() { abstract fun dao(): SleepDao }

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE nights ADD COLUMN maxSleepMinutes INTEGER NOT NULL DEFAULT 600")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `email_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `status` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `error` TEXT)")
    }
}

private val Context.settingsStore by preferencesDataStore("settings")
data class SleepSettings(val targetBed: Int = 15, val targetWake: Int = 510, val maxSleepHours: Int = 10)
class SleepPreferences(context: Context) {
    private val store = context.settingsStore
    private val bed = intPreferencesKey("bed")
    private val wake = intPreferencesKey("wake")
    private val maxSleep = intPreferencesKey("maxSleepHours")
    private val defaultsVersion = intPreferencesKey("defaultsVersion")
    val flow = store.data.map { SleepSettings(it[bed] ?: 15, it[wake] ?: 510, it[maxSleep] ?: 10) }
    suspend fun save(value: SleepSettings) {
        store.edit { it[bed] = value.targetBed; it[wake] = value.targetWake; it[maxSleep] = value.maxSleepHours }
    }
    /** 旧的默认目标是 23:30/07:30；只把仍停留在旧默认值的用户迁移到 00:15/08:30。 */
    suspend fun migrateDefaults() {
        store.edit { p ->
            if ((p[defaultsVersion] ?: 0) < 1) {
                if (p[bed] == null || p[bed] == 1410) p[bed] = 15
                if (p[wake] == null || p[wake] == 450) p[wake] = 510
                p[defaultsVersion] = 1
            }
        }
    }
}

data class PowerStatus(val plugged: Boolean, val charging: Boolean, val percent: Int) {
    val eligible get() = plugged && charging
    val label get() = if (eligible) "电源已连接 · $percent%" else if (plugged) "已接电，尚未充电 · $percent%" else "未连接电源 · $percent%"
}
fun powerStatus(context: Context): PowerStatus {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    return PowerStatus((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        if (scale > 0) level * 100 / scale else 0)
}

class SleepRepository(private val context: Context) {
    companion object { const val SHORT_NIGHT = -1L }
    private val db = Room.databaseBuilder(context, SleepDatabase::class.java, "sleep.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    val dao = db.dao()
    private val mutex = Mutex()
    val records = dao.observeRecords()
    val active = dao.observeActive()
    suspend fun startSleep(settings: SleepSettings, now: Long = System.currentTimeMillis()) = mutex.withLock {
        check(powerStatus(context).eligible) { "请先连接充电器，并确认手机正在充电" }
        var created = false
        val id = db.withTransaction {
            val current = dao.active()
            check(current?.night?.phase !in listOf(Phase.SETTLING, Phase.MONITORING)) { "已经开始监控了" }
            check(current?.pending == null) { "请先填写这次拿起手机的原因" }
            val id = if (current == null) {
                created = true
                dao.insert(Night(date = nightDate(now), firstSleepAt = now,
                    lastSleepAt = now, targetBed = settings.targetBed, targetWake = settings.targetWake,
                    armedAt = now + 15_000, maxSleepMinutes = settings.maxSleepHours * 60))
            } else {
                current.interruptions.filter { !it.morning && it.returnedAt == null }.forEach { dao.update(it.copy(returnedAt = now)) }
                dao.update(current.night.copy(lastSleepAt = now, phase = Phase.SETTLING, armedAt = now + 15_000, heartbeatAt = now,
                    maxSleepMinutes = settings.maxSleepHours * 60))
                current.night.id
            }
            dao.insert(NightEvent(nightId = id, at = now, type = "SLEEP", detail = "点击我去睡了；15秒后开始检测"))
            id
        }
        dao.record(id)?.let { EmailNotifier.sleep(context, it, again = !created) }
        id
    }
    suspend fun arm() = mutex.withLock {
        val record = dao.active() ?: return@withLock
        if (record.night.phase == Phase.SETTLING) db.withTransaction {
            val now = System.currentTimeMillis()
            dao.update(record.night.copy(phase = Phase.MONITORING, heartbeatAt = now))
            dao.insert(NightEvent(nightId = record.night.id, at = now, type = "ARMED", detail = "运动检测已开始"))
        }
    }
    suspend fun motion(detail: String) = mutex.withLock {
        val record = dao.active() ?: return@withLock
        if (record.night.phase != Phase.MONITORING) return@withLock
        db.withTransaction {
            val now = System.currentTimeMillis()
            dao.insert(Interruption(nightId = record.night.id, detectedAt = now, detail = detail))
            dao.update(record.night.copy(phase = Phase.PENDING, heartbeatAt = now))
        }
    }
    suspend fun reason(id: Long, text: String) = mutex.withLock {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "请手动写下拿起手机的原因" }
        var firstReason: Pair<Long, Long>? = null
        db.withTransaction {
            val interruption = dao.interruption(id) ?: error("记录不存在")
            val record = dao.record(interruption.nightId) ?: error("记录不存在")
            if (interruption.reason.isNullOrBlank()) firstReason = interruption.id to interruption.nightId
            dao.update(interruption.copy(reason = trimmed))
            if (record.night.phase == Phase.PENDING && record.pending?.id == id)
                dao.update(record.night.copy(phase = Phase.TEMP, heartbeatAt = System.currentTimeMillis()))
        }
        firstReason?.let { (interruptionId, nightId) ->
            dao.record(nightId)?.let { record ->
                dao.interruption(interruptionId)?.let { EmailNotifier.interruption(context, record, it) }
            }
        }
    }
    suspend fun wake(now: Long = System.currentTimeMillis()): Long? = mutex.withLock {
        val record = dao.active() ?: return@withLock null
        check(record.pending == null) { "请先填写这次拿起手机的原因" }
        if (now - record.night.firstSleepAt < 60 * 60_000L) {
            db.withTransaction {
                dao.deleteInterruptions(record.night.id)
                dao.deleteEvents(record.night.id)
                dao.deleteNight(record.night.id)
            }
            return@withLock SHORT_NIGHT
        }
        db.withTransaction {
            dao.update(record.night.copy(phase = Phase.AWAKE, wakeAt = now, heartbeatAt = now))
            dao.insert(NightEvent(nightId = record.night.id, at = now, type = "WAKE", detail = "点击我醒了；监控结束"))
        }
        record.night.id
    }
    suspend fun removeShortRecords(threshold: Long = 60 * 60_000L) = mutex.withLock {
        dao.deleteShortNights(threshold)
    }
    suspend fun feeling(id: Long, feeling: String) = mutex.withLock {
        val record = dao.record(id) ?: return@withLock
        val firstFeeling = record.night.feeling.isNullOrBlank()
        dao.update(record.night.copy(feeling = feeling))
        // 醒来邮件等感受选好后再发，保证邮件里包含「清醒/一般/很困」。
        if (firstFeeling && record.night.phase == Phase.AWAKE) {
            dao.record(id)?.takeIf { (it.night.wakeAt ?: 0) > System.currentTimeMillis() - 24 * 3_600_000L }
                ?.let { EmailNotifier.wake(context, it) }
        }
    }
    suspend fun photo(id: Long, path: String) = mutex.withLock { dao.record(id)?.night?.let { dao.update(it.copy(photo = path)) } }
    suspend fun event(type: String, detail: String = "") = mutex.withLock {
        val record = dao.active() ?: return@withLock
        dao.insert(NightEvent(nightId = record.night.id, at = System.currentTimeMillis(), type = type, detail = detail))
    }
    suspend fun heartbeat() = mutex.withLock {
        dao.active()?.night?.let { dao.update(it.copy(heartbeatAt = System.currentTimeMillis())) }
    }
    suspend fun markRecovery() = mutex.withLock {
        val record = dao.active() ?: return@withLock
        if (record.night.phase == Phase.RECOVERY) return@withLock
        db.withTransaction {
            dao.insert(NightEvent(nightId = record.night.id, at = System.currentTimeMillis(), type = "GAP",
                detail = "上次存活记录 ${clock(record.night.heartbeatAt)}；服务中断期间无法确认活动"))
            dao.update(record.night.copy(phase = Phase.RECOVERY))
        }
        dao.record(record.night.id)?.let { EmailNotifier.gap(context, it) }
    }
    suspend fun resume() = mutex.withLock {
        val record = dao.active() ?: return@withLock
        val now = System.currentTimeMillis()
        val phase = if (record.pending != null) Phase.PENDING else if (record.disruptions.lastOrNull()?.let { it.returnedAt == null } == true) Phase.TEMP else Phase.SETTLING
        db.withTransaction {
            dao.update(record.night.copy(phase = phase, armedAt = if (phase == Phase.SETTLING) now + 15_000 else record.night.armedAt, heartbeatAt = now))
            dao.insert(NightEvent(nightId = record.night.id, at = now, type = "RESUME", detail = "恢复监控；保留原就寝声明和监控缺口"))
        }
    }

    /** 填完原因 10 分钟仍未重新点晚安：自动继续监控，不发送再次晚安邮件。 */
    suspend fun autoResume(now: Long = System.currentTimeMillis()) = mutex.withLock {
        val record = dao.active() ?: return@withLock
        if (record.night.phase != Phase.TEMP) return@withLock
        db.withTransaction {
            record.interruptions.filter { !it.morning && it.returnedAt == null }.forEach { dao.update(it.copy(returnedAt = now)) }
            dao.update(record.night.copy(lastSleepAt = now, phase = Phase.SETTLING, armedAt = now + 15_000, heartbeatAt = now))
            dao.insert(NightEvent(nightId = record.night.id, at = now, type = "RESUME", detail = "10分钟未重新晚安，自动继续监控"))
        }
    }

    /** 删除已结束的一晚，返回删除前的快照供撤销。照片文件保留，撤销后仍可用。 */
    suspend fun deleteNight(id: Long): NightRecord? = mutex.withLock {
        val record = dao.record(id) ?: return@withLock null
        check(record.night.phase == Phase.AWAKE) { "今晚还在守候，先点「我醒了」结束监控" }
        db.withTransaction {
            dao.deleteInterruptions(id)
            dao.deleteEvents(id)
            dao.deleteNight(id)
        }
        record
    }

    /** 用删除时的快照原样恢复，保留原 id、中断和事件。 */
    suspend fun restore(record: NightRecord) = mutex.withLock {
        db.withTransaction {
            dao.restoreNight(record.night)
            record.interruptions.forEach { dao.restoreInterruption(it) }
            record.events.forEach { dao.restoreEvent(it) }
        }
    }

    /** 修改已结束一晚的就寝/醒来时间与中断原因，并可移除误报中断。 */
    suspend fun edit(id: Long, edit: NightEdit) = mutex.withLock {
        db.withTransaction {
            val record = dao.record(id) ?: error("记录不存在")
            check(record.night.phase == Phase.AWAKE) { "今晚还在守候，先点「我醒了」结束监控" }
            require(edit.wakeAt > edit.bedAt) { "醒来时间需要晚于就寝时间" }
            edit.removedIds.filter { candidate -> record.interruptions.any { it.id == candidate } }.forEach { dao.deleteInterruption(it) }
            edit.reasons.forEach { (interruptionId, text) ->
                record.interruptions.firstOrNull { it.id == interruptionId }?.let {
                    dao.update(it.copy(reason = text.trim().takeIf { value -> value.isNotEmpty() }))
                }
            }
            dao.update(record.night.copy(lastSleepAt = edit.bedAt,
                firstSleepAt = minOf(record.night.firstSleepAt, edit.bedAt), wakeAt = edit.wakeAt))
        }
    }
}
