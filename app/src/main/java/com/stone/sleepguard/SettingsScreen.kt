package com.stone.sleepguard

import android.app.NotificationManager
import android.content.Context
import android.hardware.*
import android.os.*
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*

@Composable internal fun SettingsScreen(settings: SleepSettings, tick: Int, context: Context, onSave: (SleepSettings) -> Unit,
    onNotifications: () -> Unit, onOverlay: () -> Unit, onBattery: () -> Unit, onVivo: () -> Unit,
    onDiagnostic: () -> Unit, onExport: () -> Unit) {
    SectionLabel("按你的节奏", "PREFERENCES")
    var editBed by remember { mutableStateOf(false) }; var editWake by remember { mutableStateOf(false) }
    SoftCard {
        Text("目标作息", fontSize = 17.sp, color = Cream)
        SettingRow("放下手机", minutesText(settings.targetBed)) { editBed = true }
        SettingRow("起床时间", minutesText(settings.targetWake)) { editWake = true }
        Text("修改只影响之后开始的夜晚，保留每一晚自己的目标。", fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
    }
    SoftCard {
        Text("移动检测灵敏度", fontSize = 17.sp, color = Cream)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("低", "中", "高").forEach { value -> FilterChip(selected = settings.sensitivity == value, onClick = { onSave(settings.copy(sensitivity = value)) }, label = { Text(value) }) }
        }
        Text("只在晚安后检测。低灵敏度减少轻触误报，高灵敏度更容易察觉移动。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        OutlinedButton(onClick = onDiagnostic, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("拿起检测自测") }
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
        Text("晚安 0.1.0", color = Muted, fontSize = 11.sp)
    }
    if (editBed) EditTimeDialog(settings.targetBed, "几点放下手机", { editBed = false }) { onSave(settings.copy(targetBed = it)); editBed = false }
    if (editWake) EditTimeDialog(settings.targetWake, "几点起床", { editWake = false }) { onSave(settings.copy(targetWake = it)); editWake = false }
}
@Composable private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = Cream, modifier = Modifier.weight(1f)); Text("$value  ›", fontSize = 13.sp, color = Mint)
    }
}
@Composable private fun EditTimeDialog(initial: Int, title: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableStateOf(minutesText(initial)) }
    val parsed = value.split(':').takeIf { it.size == 2 }?.let {
        val h = it[0].toIntOrNull(); val m = it[1].toIntOrNull()
        if (h != null && h in 0..23 && m != null && m in 0..59) h * 60 + m else null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("24小时制，例如 23:30") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
    }, confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable internal fun DiagnosticDialog(context: Context, sensitivity: String, onDismiss: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }; var reading by remember { mutableStateOf("等待传感器") }
    var lastResult by remember { mutableStateOf("先静置3秒，再拿起手机。") }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        var detector = MotionDetector(sensitivity); var baselineUntil = SystemClock.elapsedRealtime() + 3000
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val t = event.timestamp / 1_000_000
                reading = "X ${"%.1f".format(event.values[0])}   Y ${"%.1f".format(event.values[1])}   Z ${"%.1f".format(event.values[2])}"
                val result = detector.sample(event.values[0], event.values[1], event.values[2], t, t >= baselineUntil)
                if (result != null) { count++; lastResult = "$result · 请放下静置3秒后重试"; detector = MotionDetector(sensitivity); baselineUntil = t + 3000 }
            }
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null || !manager.registerListener(listener, sensor, 100_000)) lastResult = "无法启用传感器"
        onDispose { manager.unregisterListener(listener) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("拿起检测自测") }, text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("已检测 $count 次", color = Mint, fontSize = 28.sp); Text(lastResult, color = Cream); Text(reading, color = Muted, fontSize = 12.sp)
        Text("这是前台自测，不会写入夜间记录。锁屏后台检测另需实际试用。", color = Muted, fontSize = 12.sp)
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
