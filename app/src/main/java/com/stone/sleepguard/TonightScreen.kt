package com.stone.sleepguard

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun TonightScreen(active: NightRecord?, lastAwake: NightRecord?, now: Long, power: PowerStatus,
    settings: SleepSettings, busy: Boolean, partner: String, onSleep: () -> Unit, onWake: () -> Unit,
    onReason: (Long, String) -> Unit, onFeeling: (Long) -> Unit, onPhoto: (Long) -> Unit,
    onShare: (Long) -> Unit, onPermissions: () -> Unit, permissionTick: Int, context: Context) {
    SectionLabel("晚安", "GOOD NIGHT")
    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.CHINA)), fontSize = 13.sp, color = Muted, modifier = Modifier.offset(y = (-12).dp))
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(82.dp).padding(10.dp)) {
            val halo = Color(0xFF213B42)
            drawCircle(halo, size.width * .5f)
            drawCircle(Mint, size.width * .33f, androidx.compose.ui.geometry.Offset(size.width * .45f, size.height * .52f))
            drawCircle(halo, size.width * .29f, androidx.compose.ui.geometry.Offset(size.width * .61f, size.height * .36f))
            drawCircle(Amber, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * .83f, size.height * .2f))
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val size = (maxWidth.value / 3.1f / scale).coerceAtMost(76f)
            Text(clock(now), fontSize = size.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, color = Cream, lineHeight = (size * 1.18f).sp)
        }
        Text(Phase.label(active?.night?.phase ?: lastAwake?.night?.phase), fontSize = 14.sp, color = Muted)
    }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (power.eligible) Mint else Amber))
            Text(power.label, fontSize = 12.sp, color = if (power.eligible) Mint else Amber)
        }
        val phase = active?.night?.phase
        val countdown = (((active?.night?.armedAt ?: now) - now).coerceAtLeast(0) + 999) / 1000
        Text(when (phase) {
            Phase.SETTLING -> "轻轻放下手机，$countdown 秒"
            Phase.MONITORING -> "今晚也要好好睡"
            Phase.PENDING -> "怎么又拿起手机啦？"
            Phase.TEMP -> "忙完啦，再和 $partner 说晚安"
            Phase.RECOVERY -> "刚刚走神了，继续守着"
            else -> "准备好了吗？"
        }, color = Cream, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
        if (active?.pending != null && phase == Phase.PENDING) ReasonEditor(active.pending!!, onReason)
        if (phase == Phase.TEMP) active.disruptions.lastOrNull()?.let { interruption ->
            Text("${interruption.reason}\n已经过了 ${((now - interruption.detectedAt) / 60000).coerceAtLeast(0)} 分钟", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
        }
        val monitoring = phase in listOf(Phase.SETTLING, Phase.MONITORING, Phase.PENDING)
        Button(onClick = onSleep, enabled = !busy && !monitoring && (power.eligible || phase == Phase.RECOVERY),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink,
                disabledContainerColor = Color(0xFF293F45), disabledContentColor = Color(0xFF9FB8B9))) {
            Text(when (phase) {
                Phase.RECOVERY -> "继续守候"
                Phase.MONITORING -> "晚安，做个好梦"
                Phase.SETTLING -> "放好手机 · $countdown 秒"
                Phase.PENDING -> "先写下原因哦"
                else -> if (power.eligible) "和 $partner 说晚安 💤 mua 💞" else "先接上充电器，再说晚安"
            }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (active != null) OutlinedButton(onClick = onWake, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("我醒了，去和 $partner 说早安 ☀️", color = Cream, fontSize = 15.sp) }
        Text("${if (phase == Phase.MONITORING) "今晚我替你守着，明早和 $partner 说早安。" else "和 $partner 说晚安，明早见。"}\n目标 ${minutesText(settings.targetBed)} 放下手机 · ${minutesText(settings.targetWake)} 起床", fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
    }
    val overlayGranted = remember(permissionTick) { Settings.canDrawOverlays(context) }
    val notifyGranted = remember(permissionTick) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    if (!overlayGranted || !notifyGranted) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF302D27)).clickable(onClick = onPermissions).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${if (!overlayGranted) "开启悬浮提醒" else "开启通知"}\n活动仍会记录，授权后提醒更完整", fontSize = 12.sp, color = Amber, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            Text("去设置 ›", color = Amber, fontSize = 12.sp)
        }
    }
    if (lastAwake != null && active == null) {
        Text("最近一晚", fontSize = 16.sp, color = Cream, fontWeight = FontWeight.Medium)
        SoftCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("最终就寝", clock(lastAwake.finalBedAt), Modifier.weight(1f)); Metric("点击醒了", clock(lastAwake.night.wakeAt), Modifier.weight(1f)); Metric("夜间中断", "${lastAwake.disruptions.size} 次", Modifier.weight(1f))
            }
            Text("就寝时间为本人声明，记录间隔不等于实际睡眠。", fontSize = 11.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onFeeling(lastAwake.night.id) }) { Text(lastAwake.night.feeling ?: "补充感受") }
                TextButton(onClick = { onPhoto(lastAwake.night.id) }) { Text(if (lastAwake.night.photo == null) "拍张户外照片" else "重新拍照") }
            }
            OutlinedButton(onClick = { onShare(lastAwake.night.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("预览并分享给 $partner") }
        }
    }
}

@Composable
internal fun ReasonEditor(pending: Interruption, onReason: (Long, String) -> Unit) {
    var text by rememberSaveable(pending.id) { mutableStateOf("") }
    Text("${clock(pending.detectedAt)} · 怎么啦？", color = Muted, fontSize = 12.sp)
    OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("告诉我为什么又拿起手机，写下来才能继续睡哦") },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp))
    Button(onClick = { onReason(pending.id, text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("写好了，继续睡") }
}
