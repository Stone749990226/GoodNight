package com.stone.sleepguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
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
    @Query("DELETE FROM interruptions WHERE id = :id") suspend fun deleteInterruption(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreNight(night: Night)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreInterruption(interruption: Interruption)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreEvent(event: NightEvent)
}

@Database(entities = [Night::class, Interruption::class, NightEvent::class], version = 1, exportSchema = false)
abstract class SleepDatabase : RoomDatabase() { abstract fun dao(): SleepDao }

private val Context.settingsStore by preferencesDataStore("settings")
data class SleepSettings(val targetBed: Int = 1410, val targetWake: Int = 450)
class SleepPreferences(context: Context) {
    private val store = context.settingsStore
    private val bed = intPreferencesKey("bed")
    private val wake = intPreferencesKey("wake")
    val flow = store.data.map { SleepSettings(it[bed] ?: 1410, it[wake] ?: 450) }
    suspend fun save(value: SleepSettings) { store.edit { it[bed] = value.targetBed; it[wake] = value.targetWake } }
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
    private val db = Room.databaseBuilder(context, SleepDatabase::class.java, "sleep.db").build()
    val dao = db.dao()
    private val mutex = Mutex()
    val records = dao.observeRecords()
    val active = dao.observeActive()
    suspend fun startSleep(settings: SleepSettings, now: Long = System.currentTimeMillis()) = mutex.withLock {
        check(powerStatus(context).eligible) { "请先连接充电器，并确认手机正在充电" }
        db.withTransaction {
            val current = dao.active()
            check(current?.night?.phase !in listOf(Phase.SETTLING, Phase.MONITORING)) { "已经开始监控了" }
            check(current?.pending == null) { "请先填写这次拿起手机的原因" }
            val id = if (current == null) dao.insert(Night(date = nightDate(now), firstSleepAt = now,
                lastSleepAt = now, targetBed = settings.targetBed, targetWake = settings.targetWake,
                armedAt = now + 15_000)) else {
                current.interruptions.filter { !it.morning && it.returnedAt == null }.forEach { dao.update(it.copy(returnedAt = now)) }
                dao.update(current.night.copy(lastSleepAt = now, phase = Phase.SETTLING, armedAt = now + 15_000, heartbeatAt = now))
                current.night.id
            }
            dao.insert(NightEvent(nightId = id, at = now, type = "SLEEP", detail = "本人点击我去睡了；15秒后开始检测"))
            id
        }
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
        db.withTransaction {
            val interruption = dao.interruption(id) ?: error("记录不存在")
            val record = dao.record(interruption.nightId) ?: error("记录不存在")
            dao.update(interruption.copy(reason = trimmed))
            if (record.night.phase == Phase.PENDING && record.pending?.id == id)
                dao.update(record.night.copy(phase = Phase.TEMP, heartbeatAt = System.currentTimeMillis()))
        }
    }
    suspend fun wake(now: Long = System.currentTimeMillis()): Long? = mutex.withLock {
        val record = dao.active() ?: return@withLock null
        db.withTransaction {
            record.pending?.takeIf { it.detectedAt >= now - 300_000 }?.let { dao.update(it.copy(morning = true)) }
            dao.update(record.night.copy(phase = Phase.AWAKE, wakeAt = now, heartbeatAt = now))
            dao.insert(NightEvent(nightId = record.night.id, at = now, type = "WAKE", detail = "本人点击醒了；监控结束"))
        }
        record.night.id
    }
    suspend fun feeling(id: Long, feeling: String) = mutex.withLock { dao.record(id)?.night?.let { dao.update(it.copy(feeling = feeling)) } }
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
