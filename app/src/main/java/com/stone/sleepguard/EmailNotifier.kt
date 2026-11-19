package com.stone.sleepguard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 事件邮件的模板与入队。邮件只含本人声明、时间和原因，不含传感器原始数据。 */
object EmailNotifier {
    private data class Payload(val kind: String, val subject: String, val body: String)

    fun sleep(context: Context, record: NightRecord, again: Boolean) = dispatch(context) { config, role ->
        val name = roleName(role)
        val partner = partnerName(role)
        val at = record.night.lastSleepAt
        if (again) Payload("SLEEP_AGAIN", "🌙 ${name}再次说${partner}晚安 💤 mua 💞", """
            $name 在 ${clock(at)} 再次和 $partner 说晚安。
            上次拿起的原因：${record.disruptions.lastOrNull()?.reason ?: "—"}。
            重新开始守候。

            —— 晚安 🫶
        """.trimIndent())
        else Payload("SLEEP", "🌙 ${name}说${partner}晚安 💤 mua 💞", """
            $name 在 ${clock(at)} 和 $partner 说晚安。
            今晚目标起床：${minutesText(record.night.targetWake)}。
            已经开始守候，半夜拿起手机会记录并通知你。

            —— 晚安 🫶
        """.trimIndent())
    }

    fun interruption(context: Context, record: NightRecord, interruption: Interruption) = dispatch(context) { config, role ->
        Payload("INTERRUPTION", "🌙 ${roleName(role)} 半夜拿起了手机 · ${clock(interruption.detectedAt)}", """
            ${roleName(role)} 在 ${clock(interruption.detectedAt)} 拿起了手机。
            原因：${interruption.reason ?: "—"}。

            —— 晚安 🫶
        """.trimIndent())
    }

    fun wake(context: Context, record: NightRecord) = dispatch(context) { config, role ->
        val name = roleName(role)
        val partner = partnerName(role)
        val wake = record.night.wakeAt ?: return@dispatch null
        val lines = record.interruptions.sortedBy { it.detectedAt }.joinToString("\n") { i ->
            val reason = if (i.morning) "起床操作" else (i.reason ?: "原因待补充")
            "  · ${clock(i.detectedAt)} $reason"
        }.ifBlank { "  · 无" }
        val result = when {
            !record.valid && record.overslept -> "未计入有效记录（超过设定的最长睡眠时长）"
            !record.valid -> "未计入有效记录（有监控缺口或中断）"
            bedMinutes(record.finalBedAt!!) <= bedGoal(record.night.targetBed) &&
                localMinutes(wake) <= record.night.targetWake -> "达标"
            else -> "未达标"
        }
        Payload("WAKE", "☀️ ${name}说${partner}早安", """
            $name 在 ${clock(wake)} 和 $partner 说早安。
            醒来感受：${record.night.feeling ?: "—"}
            最终就寝：${clock(record.finalBedAt)}
            记录间隔：${intervalText(record.interval)}
            夜间中断：${record.disruptions.size} 次
            $lines
            目标：${minutesText(record.night.targetBed)} 放下手机 / ${minutesText(record.night.targetWake)} 起床
            结果：$result

            —— 早安，$partner ☀️
        """.trimIndent())
    }

    fun missedWake(context: Context, record: NightRecord, now: Long = System.currentTimeMillis()) = dispatch(context) { config, role ->
        val name = roleName(role)
        Payload("MISSED_WAKE", "😴 ${name}睡过头了", """
            到了 ${clock(now)}，$name 睡过头了。
            昨晚仍在守候；目标起床：${minutesText(record.night.targetWake)}。

            —— 早安，${partnerName(role)} ☀️
        """.trimIndent())
    }

    fun overslept(context: Context, record: NightRecord) = dispatch(context) { config, role ->
        val name = roleName(role)
        val hours = record.night.maxSleepMinutes / 60
        Payload("OVERSLEPT", "😴 ${name}睡过头了 · 超过 $hours 小时", """
            $name 已经超过 $hours 小时还没有和 ${partnerName(role)} 说早安，守候仍在继续。
            这一晚会计为超长，不计入平均。

            —— 早安，${partnerName(role)} ☀️
        """.trimIndent())
    }

    fun gap(context: Context, record: NightRecord) = dispatch(context) { config, role ->
        Payload("GAP", "⚠️ ${roleName(role)} 的守候中断了", """
            ${roleName(role)} 的守候中断过（上次存活：${clock(record.night.heartbeatAt)}）。
            监控缺口已记录，这一晚不计入平均。

            —— 晚安 🫶
        """.trimIndent())
    }

    private fun dispatch(context: Context, build: (EmailSettings, Role?) -> Payload?) {
        val app = context.applicationContext as? SleepApp ?: return
        app.scope.launch(Dispatchers.IO) {
            runCatching {
                val config = app.emailPreferences.flow.first()
                if (!config.enabled || !config.configured) return@launch
                val role = app.rolePreferences.flow.first().role
                val payload = build(config, role) ?: return@launch
                app.repository.dao.insertEmail(EmailJob(
                    kind = payload.kind, subject = payload.subject, body = payload.body, createdAt = System.currentTimeMillis()))
                EmailWork.schedule(context)
                EmailSender(context).sendPending()
            }
        }
    }
}

