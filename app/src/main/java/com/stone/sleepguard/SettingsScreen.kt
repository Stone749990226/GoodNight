package com.stone.sleepguard

import android.app.NotificationManager
import android.content.Context
import android.hardware.*
import android.os.*
import android.provider.Settings
import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

@Composable internal fun SettingsScreen(settings: SleepSettings, email: EmailSettings, role: Role?, tick: Int, context: Context,
    onSave: (SleepSettings) -> Unit, onSaveEmail: (EmailSettings) -> Unit, onTestEmail: () -> Unit,
    onNotifications: () -> Unit, onOverlay: () -> Unit, onBattery: () -> Unit, onVivo: () -> Unit,
    onDiagnostic: () -> Unit, onExport: () -> Unit) {
    SectionLabel("按你的节奏", "PREFERENCES")
    var editBed by remember { mutableStateOf(false) }; var editWake by remember { mutableStateOf(false) }
    var editMaxSleep by remember { mutableStateOf(false) }
    var editEmail by remember { mutableStateOf(false) }
    SoftCard {
        Text("目标作息", fontSize = 17.sp, color = Cream)
        SettingRow("放下手机", minutesText(settings.targetBed)) { editBed = true }
        SettingRow("起床时间", minutesText(settings.targetWake)) { editWake = true }
        SettingRow("最多睡眠时长", "${settings.maxSleepHours} 小时") { editMaxSleep = true }
        Text("超过这个时长还没点我醒了，就不会计入平均。", fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
    }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("移动检测", fontSize = 16.sp, color = Cream, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDiagnostic) { Text("开始自测") }
        }
    }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("邮件通知", fontSize = 16.sp, color = Cream)
                Text("${roleName(role)} ⇄ ${partnerName(role)} · ${if (email.enabled) "每次事件都会通知对方" else "未开启"}",
                    fontSize = 11.sp, color = Muted)
            }
            Switch(checked = email.enabled, onCheckedChange = { on ->
                if (on && !email.configured) editEmail = true else onSaveEmail(email.copy(enabled = on))
            })
        }
        if (email.configured) Text("${email.from} → ${email.partner}", fontSize = 11.sp, color = Muted)
        SettingRow("邮件设置", if (email.configured) "已配置" else "去设置") { editEmail = true }
        OutlinedButton(onClick = onTestEmail, enabled = email.configured, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("发送测试邮件")
        }
    }
    val notifications = remember(tick) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val overlays = remember(tick) { Settings.canDrawOverlays(context) }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    SoftCard {
        Text("让守候保持在线", fontSize = 17.sp, color = Cream)
        SettingRow("通知", if (notifications) "已开启" else "去开启", onNotifications)
        SettingRow("悬浮提醒", if (overlays) "已开启" else "去开启", onOverlay)
        SettingRow("电池优化豁免", if (battery) "已开启" else "去开启", onBattery)
        SettingRow("vivo 后台与自启动", "去检查", onVivo)
        Text("请允许晚安后台运行，并在最近任务中锁定。厂商后台设置需在系统内确认。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
    }
    SoftCard {
        SettingRow("导出 CSV 表格", "导出", onExport)
        Text("记录与照片保存在本机。分享前先预览，发送给谁由你选择。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Text("晚安 ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 11.sp)
    }
    if (editBed) EditTimeDialog(settings.targetBed, "几点放下手机", { editBed = false }) { onSave(settings.copy(targetBed = it)); editBed = false }
    if (editWake) EditTimeDialog(settings.targetWake, "几点起床", { editWake = false }) { onSave(settings.copy(targetWake = it)); editWake = false }
    if (editMaxSleep) MaxSleepDialog(settings.maxSleepHours, { editMaxSleep = false }) { onSave(settings.copy(maxSleepHours = it)); editMaxSleep = false }
    if (editEmail) EmailConfigDialog(email, { editEmail = false }) { editEmail = false; onSaveEmail(it) }
}
@Composable private fun EmailConfigDialog(current: EmailSettings, onDismiss: () -> Unit, onSave: (EmailSettings) -> Unit) {
    var from by remember { mutableStateOf(current.from) }
    var partner by remember { mutableStateOf(current.partner) }
    var host by remember { mutableStateOf(current.host) }
    var port by remember { mutableStateOf(current.port.toString()) }
    var auth by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun validEmail(value: String) = Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("邮件通知设置") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("QQ 邮箱填 smtp.qq.com / 465；授权码不是登录密码。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            OutlinedTextField(from, { from = it }, label = { Text("发件邮箱") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(auth, { auth = it }, label = { Text("授权码") },
                placeholder = { Text(if (current.hasAuthCode) "已保存，留空不修改" else "邮箱授权码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(partner, { partner = it }, label = { Text("对方邮箱") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("SMTP 服务器") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
            }
            error?.let { Text(it, color = Amber, fontSize = 12.sp) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val portValue = port.toIntOrNull()
            val problem = when {
                !validEmail(from) -> "请填写正确的发件邮箱"
                !validEmail(partner) -> "请填写正确的对方邮箱"
                host.isBlank() -> "请填写 SMTP 服务器"
                portValue == null || portValue !in 1..65535 -> "端口不正确"
                auth.isBlank() && !current.hasAuthCode -> "请填写邮箱授权码"
                else -> null
            }
            if (problem != null) error = problem
            else onSave(current.copy(from = from.trim(), partner = partner.trim(), host = host.trim(),
                port = portValue!!, authCode = auth.trim()))
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable private fun MaxSleepDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hours by remember { mutableIntStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("最多睡多久") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { hours = (hours - 1).coerceAtLeast(4) }, shape = CircleShape,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(54.dp)) { Text("−", fontSize = 24.sp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$hours", fontSize = 44.sp, color = Mint, fontWeight = FontWeight.Light)
                    Text("小时", fontSize = 12.sp, color = Muted)
                }
                OutlinedButton(onClick = { hours = (hours + 1).coerceAtMost(16) }, shape = CircleShape,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(54.dp)) { Text("+", fontSize = 24.sp) }
            }
            Text("超过这个时长还没点我醒了，就不计入平均。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSave(hours) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}
@Composable private fun EditTimeDialog(initial: Int, title: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    WheelTimeDialog(initial = initial, title = title, onDismiss = onDismiss, onConfirm = onSave)
}
@Composable private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = Cream, modifier = Modifier.weight(1f)); Text("$value  ›", fontSize = 13.sp, color = Mint)
    }
}
@Composable internal fun DiagnosticDialog(context: Context, onDismiss: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf("还没有检测到动作。") }
    var round by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(3) }
    LaunchedEffect(round) {
        for (second in 3 downTo 1) { countdown = second; delay(1000) }
        countdown = 0
    }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        var detector = MotionDetector()
        var baselineUntil = SystemClock.elapsedRealtime() + 3000
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val t = event.timestamp / 1_000_000
                val result = detector.sample(event.values[0], event.values[1], event.values[2], t, t >= baselineUntil)
                if (result != null) {
                    count++
                    lastResult = "检测到一次动作，已计入自测。"
                    detector = MotionDetector()
                    baselineUntil = t + 3000
                    round++
                }
            }
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null || !manager.registerListener(listener, sensor, 100_000)) lastResult = "无法启用传感器"
        onDispose { manager.unregisterListener(listener) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("拿起检测自测") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已检测 $count 次", color = Mint, fontSize = 26.sp, modifier = Modifier.weight(1f))
                if (countdown > 0) Text("$countdown", color = Amber, fontSize = 44.sp)
            }
            Text(if (countdown > 0) "静置 $countdown 秒，倒计时结束后再拿起手机" else "现在拿起手机，看看会不会记为一次动作", color = Cream, lineHeight = 22.sp)
            Text(lastResult, color = Cream, fontSize = 13.sp)
            Text("只是自测，不会写入夜间记录。", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
