package com.stone.sleepguard

import androidx.room.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

object Phase {
    const val SETTLING = "SETTLING"
    const val MONITORING = "MONITORING"
    const val PENDING = "PENDING"
    const val TEMP = "TEMP"
    const val RECOVERY = "RECOVERY"
    const val AWAKE = "AWAKE"
    fun label(value: String?) = when (value) {
        SETTLING -> "请放下手机"
        MONITORING -> "正在安静守候"
        PENDING -> "有一条原因待填写"
        TEMP -> "临时使用中"
        RECOVERY -> "监控曾中断，等待恢复"
        AWAKE -> "今天，醒来了"
        else -> "把今晚留给好好休息"
    }
}

@Entity(tableName = "nights")
data class Night(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val firstSleepAt: Long,
    val lastSleepAt: Long,
    val targetBed: Int,
    val targetWake: Int,
    val phase: String = Phase.SETTLING,
    val armedAt: Long? = null,
    val wakeAt: Long? = null,
    val feeling: String? = null,
    val photo: String? = null,
    val heartbeatAt: Long = firstSleepAt,
)

@Entity(tableName = "interruptions", foreignKeys = [ForeignKey(entity = Night::class,
    parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)], indices = [Index("nightId")])
data class Interruption(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nightId: Long,
    val detectedAt: Long,
    val reason: String? = null,
    val returnedAt: Long? = null,
    val morning: Boolean = false,
    val detail: String = "手机活动",
)

@Entity(tableName = "events", foreignKeys = [ForeignKey(entity = Night::class,
    parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)], indices = [Index("nightId")])
data class NightEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nightId: Long,
    val at: Long,
    val type: String,
    val detail: String = "",
)

data class NightRecord(
    @Embedded val night: Night,
    @Relation(parentColumn = "id", entityColumn = "nightId") val interruptions: List<Interruption>,
    @Relation(parentColumn = "id", entityColumn = "nightId") val events: List<NightEvent>,
) {
    val disruptions get() = interruptions.filterNot { it.morning }.sortedBy { it.detectedAt }
    val pending get() = disruptions.lastOrNull { it.reason.isNullOrBlank() }
    val finalBedAt: Long? get() = night.lastSleepAt.takeIf { t -> disruptions.none { it.detectedAt >= t } }
    val hasGap get() = events.any { it.type == "GAP" }
    val valid get() = night.wakeAt != null && finalBedAt != null && !hasGap
    val interval: Long? get() = finalBedAt?.let { bed -> night.wakeAt?.let { (it - bed).coerceAtLeast(0) } }
    val lastScreenOff get() = events.filter { it.type == "SCREEN_OFF" && it.at >= night.lastSleepAt && it.at <= (night.wakeAt ?: Long.MAX_VALUE) }.maxByOrNull { it.at }?.at
    val morningUnlock get() = night.wakeAt?.let { wake -> events.filter { it.type == "UNLOCK" && it.at in (wake - 300_000)..wake }.maxByOrNull { it.at }?.at }
}

fun nightDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    return local.toLocalDate().minusDays(if (local.hour < 12) 1 else 0).toString()
}
fun clock(t: Long?): String = t?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—"
fun minutesText(m: Int) = "%02d:%02d".format(m / 60, m % 60)
fun intervalText(ms: Long?): String = ms?.let { "${it / 3_600_000}小时${it / 60_000 % 60}分" } ?: "未完成记录"
fun localMinutes(t: Long): Int = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
fun bedMinutes(t: Long): Int = localMinutes(t).let { if (it < 720) it + 1440 else it }
fun bedGoal(m: Int) = if (m < 720) m + 1440 else m

/** 编辑后的整晚数据；时间戳由「这一晚的日期 + 输入的时分」还原。 */
data class NightEdit(val bedAt: Long, val wakeAt: Long, val reasons: Map<Long, String>, val removedIds: Set<Long>)

private val CLOCK_INPUT = Regex("""^(\d{1,2})[:：]?(\d{2})$""")
fun parseClock(text: String): Int? {
    val match = CLOCK_INPUT.find(text.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
fun bedTimestamp(date: String, minutes: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.parse(date).atStartOfDay(zone).plusMinutes((if (minutes < 720) minutes + 1440 else minutes).toLong()).toInstant().toEpochMilli()
fun wakeTimestamp(date: String, minutes: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.parse(date).plusDays(1).atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

data class Statistics(val total: Int, val valid: Int, val averageBed: String, val averageWake: String, val rate: Int?)
fun statistics(records: List<NightRecord>): Statistics {
    val valid = records.filter { it.valid }
    val avgBed = valid.map { bedMinutes(it.finalBedAt!!) }.average()
    val avgWake = valid.map { localMinutes(it.night.wakeAt!!) }.average()
    val successes = valid.count { bedMinutes(it.finalBedAt!!) <= bedGoal(it.night.targetBed) && localMinutes(it.night.wakeAt!!) <= it.night.targetWake }
    return Statistics(records.size, valid.size,
        if (valid.isEmpty()) "—" else minutesText(avgBed.roundToInt() % 1440),
        if (valid.isEmpty()) "—" else minutesText(avgWake.roundToInt() % 1440),
        if (valid.isEmpty()) null else (100.0 * successes / valid.size).roundToInt())
}

/** Pure streaming detector. Samples are never persisted. */
class MotionDetector(private val sensitivity: String = "中") {
    private var gravity = doubleArrayOf(0.0, 0.0, 9.81)
    private var base: DoubleArray? = null
    private var initialized = false
    private var tiltSince: Long? = null
    private var moveSince: Long? = null
    private var lastSample: Long? = null
    fun sample(x: Float, y: Float, z: Float, timeMs: Long, armed: Boolean): String? {
        val raw = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
        val previous = lastSample
        lastSample = timeMs
        if (previous != null && timeMs - previous > 500) { tiltSince = null; moveSince = null }
        if (!initialized) { gravity = raw.copyOf(); initialized = true }
        val dt = if (previous == null) 0.1 else ((timeMs - previous) / 1000.0).coerceIn(0.001, 1.0)
        val alpha = 0.8 / (0.8 + dt)
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * raw[i]
        if (!armed) { base = gravity.copyOf(); tiltSince = null; moveSince = null; return null }
        val baseline = base ?: gravity.copyOf().also { base = it }
        fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })
        val denominator = norm(gravity) * norm(baseline)
        val angle = if (denominator < 1) 0.0 else Math.toDegrees(acos((gravity.indices.sumOf { gravity[it] * baseline[it] } / denominator).coerceIn(-1.0, 1.0)))
        val acceleration = norm(DoubleArray(3) { raw[it] - gravity[it] })
        val angleLimit = when (sensitivity) { "低" -> 30.0; "高" -> 12.0; else -> 20.0 }
        val moveLimit = when (sensitivity) { "低" -> 2.2; "高" -> 1.0; else -> 1.5 }
        tiltSince = if (angle > angleLimit) tiltSince ?: timeMs else null
        moveSince = if (acceleration > moveLimit) moveSince ?: timeMs else null
        return when {
            tiltSince?.let { timeMs - it >= 500 } == true -> "倾斜 ${angle.roundToInt()}°"
            moveSince?.let { timeMs - it >= 300 } == true -> "移动 ${"%.1f".format(acceleration)} m/s²"
            else -> null
        }
    }
}
