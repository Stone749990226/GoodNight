package com.stone.sleepguard

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import java.time.LocalDate
import kotlin.math.roundToInt

internal val habitIcons = listOf(
    "💧", "💊", "👁️", "📖", "🏃", "🥛", "🌙", "🍎",
    "🥗", "🍽️", "☕", "🧘", "🚶", "🏋️", "🧹", "🧺",
    "🛏️", "🧴", "🪥", "💻", "📝", "🎵", "🎨", "🧠",
    "🙏", "💤", "🚭", "🍬", "🐶", "🌱", "☀️", "❤️",
    "💪", "🚴", "🏊", "🧘‍♀️", "🍵", "🥤", "🍳", "📚",
)
internal fun Habit.glyph() = icon.ifBlank { when {
    "水" in title -> "💧"
    "药" in title -> "💊"
    "眼" in title -> "👁️"
    "读" in title || "书" in title -> "📖"
    "奶" in title || "蛋白" in title -> "🥛"
    "跑" in title -> "🏃"
    "睡" in title -> "🌙"
    else -> "🌱"
} }

private data class AgendaItem(val minute: Int?, val task: PlanTask? = null, val habit: Habit? = null)

@Composable internal fun DayAgenda(tasks: List<PlanTask>, habits: List<Habit>, reminders: List<HabitReminder>,
    days: List<HabitDay>, timeline: Boolean, canCheck: Boolean, checks: List<HabitCheckIn>, onEditTask: (PlanTask) -> Unit,
    onToggleTask: (Long) -> Unit, onHabit: (Habit) -> Unit, onStep: (Long, Int?, Boolean) -> Unit,
    onSwipeTask: (Long) -> Unit, onSwipeHabit: (Long) -> Unit, onCompleted: (String, (() -> Unit)?) -> Unit, onStats: () -> Unit) {
    val times = reminders.groupBy { it.habitId }
    val records = days.associateBy { it.habitId }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (timeline) "当天时间线" else "当天安排", color = Cream, fontSize = 21.sp)
            Text("${tasks.size} 项计划  ·  ${habits.size} 个习惯", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = onStats) { Text("统计") }
    }
    @Composable fun item(entry: AgendaItem) {
        val task = entry.task
        val habit = entry.habit
        val count = records[habit?.id]?.count ?: 0
        val target = records[habit?.id]?.dayTarget ?: habit?.targetCount ?: 1
        val checkedSlot = habit != null && entry.minute != null && checks.any { it.habitId == habit.id && it.minute == entry.minute && it.completedAt != null }
        val done = if (task != null) task.completedAt != null else count >= target || records[habit?.id]?.completedAt != null || checkedSlot
        val alarm = if (task != null) task.reminderMinute != null else !times[habit?.id].isNullOrEmpty()
        val marker = if (!alarm) "" else if (task?.alarm == true || habit?.reminderMode == "ALARM") "⏰" else "🔔"
        val schedule = if (task != null) task.dueMinute?.let { minutesText(it) + (task.endMinute?.let { end -> "–${minutesText(end)}" } ?: "") } ?: "全天"
            else if (timeline) "" else times[habit?.id].orEmpty().joinToString(" · ") { minutesText(it.minute) }
        SwipeRight(revealColor = if (done) Color(0xFFE05B5B) else Color(0xFF24C79A),
            symbol = if (done) "↺" else "✓", onSwipe = {
            if (task != null) {
                if (done) onCompleted("已取消完成：${task.title}") { null }
                else onCompleted("已完成：${task.title}！bb真厉害！💗") { onToggleTask(task.id) }
                onSwipeTask(task.id)
            } else if (habit != null) {
                if (!done) {
                    val text = if (count + 1 >= target) "已完成：${habit.title}！bb真厉害！💗"
                    else "${habit.title} +1，bb真厉害！💗"
                    onCompleted(text) { onStep(habit.id, null, true) }
                } else onCompleted("次数重置：${habit.title}") { null }
                onSwipeHabit(habit.id)
            }
        }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (done) .45f else 1f), verticalAlignment = Alignment.CenterVertically) {
                if (task != null) Checkbox(done, {
                    if (!done) onCompleted("已完成：${task.title}！bb真厉害！💗") { onToggleTask(task.id) }
                    onToggleTask(task.id)
                })
                else Box(Modifier.size(34.dp).background(if (done) Mint else Mint.copy(alpha = .11f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else habit!!.glyph(), color = if (done) Ink else Cream, fontSize = if (done) 20.sp else 19.sp,
                        fontWeight = if (done) FontWeight.Bold else FontWeight.Normal)
                }
                Column(Modifier.weight(1f).clickable { if (task != null) onEditTask(task) else onHabit(habit!!) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(task?.title ?: habit!!.title, color = if (done) Muted else Cream, fontSize = 16.sp)
                    if (task?.note?.isNotBlank() == true) Text(task.note, color = Muted, fontSize = 12.sp, maxLines = 2)
                    if (habit != null) {
                        val meta = listOf(marker, schedule).filter { it.isNotEmpty() }.joinToString("  ")
                        if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 12.sp)
                    }
                }
                if (task != null) Text(
                    listOf(marker, schedule).filter { it.isNotEmpty() }.joinToString(" "),
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                if (habit != null) TextButton(enabled = canCheck && (!done || (entry.minute == null && target == 1)), onClick = {
                    if (!done) {
                        val text = if (target == 1 || count + 1 >= target) "已完成：${habit.title}！bb真厉害！💗"
                        else "${habit.title} +1，bb真厉害！💗"
                        onCompleted(text) { onStep(habit.id, null, true) }
                    }
                    onStep(habit.id, entry.minute, done)
                }) {
                    Text(if (done) "$count/$target ✓" else "$count/$target ＋")
                }
            }
        }
    }
    if (timeline) {
        val entries = tasks.map { AgendaItem(it.dueMinute, task = it) } + habits.flatMap { h ->
            times[h.id].orEmpty().map { AgendaItem(it.minute, habit = h) }.ifEmpty { listOf(AgendaItem(null, habit = h)) }
        }
        if (entries.isEmpty()) Text("这一天还没有安排", color = Muted)
        entries.groupBy { it.minute }.toList().sortedBy { it.first ?: -1 }.forEach { (minute, group) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(48.dp).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(minute?.let(::minutesText) ?: "全天", color = Mint, fontSize = 13.sp)
                    Spacer(Modifier.height(5.dp)); Box(Modifier.size(7.dp).background(Mint, CircleShape))
                }
                Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp)) { group.forEach { item(it) } }
            }
        }
    } else {
        SoftCard {
            Text("计划", color = Cream, fontSize = 17.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val pending = tasks.filter { it.completedAt == null }.sortedBy { it.dueMinute ?: -1 }
                if (tasks.isEmpty()) Text("暂无计划", color = Muted, fontSize = 13.sp)
                pending.forEach { item(AgendaItem(it.dueMinute, task = it)) }
                val finished = tasks.filter { it.completedAt != null }
                if (finished.isNotEmpty()) Text("已完成", color = Muted, fontSize = 12.sp)
                finished.forEach { item(AgendaItem(it.dueMinute, task = it)) }
            }
        }
        SoftCard {
            Text("习惯", color = Cream, fontSize = 17.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (habits.isEmpty()) Text("当天没有需要打卡的习惯", color = Muted, fontSize = 13.sp)
                habits.forEach { item(AgendaItem(null, habit = it)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TaskSheet(task: PlanTask?, selected: LocalDate, onDismiss: () -> Unit, onHabit: () -> Unit,
    onDelete: (Long) -> Unit, onSave: (PlanTask) -> Unit) {
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(task?.note.orEmpty()) }
    var epoch by rememberSaveable { mutableLongStateOf(task?.dueDate?.toLong() ?: selected.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epoch)
    var start by rememberSaveable { mutableStateOf(task?.dueMinute) }
    var end by rememberSaveable { mutableStateOf(task?.endMinute) }
    var mode by rememberSaveable { mutableStateOf(if (task?.reminderMinute == null) "无" else if (task.alarm) "闹钟" else "通知") }
    var advance by rememberSaveable { mutableIntStateOf((task?.dueMinute ?: 0) - (task?.reminderMinute ?: task?.dueMinute ?: 0)) }
    var picking by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val valid = title.isNotBlank() && (end == null || (start != null && end!! > start!!))
    fun save() { if (valid) onSave((task ?: PlanTask(title = "", dueDate = epoch.toInt())).copy(title = title.trim(), note = note.trim(), dueDate = epoch.toInt(),
        dueMinute = start, endMinute = end, alarm = mode == "闹钟", reminderMinute = if (mode == "无") null else start?.minus(advance))) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (task == null) "新计划" else "编辑计划", Modifier.weight(1f), color = Cream, fontSize = 21.sp)
                if (task == null) TextButton(onClick = onHabit) { Text("新建习惯") }
            }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, placeholder = { Text("要做什么？") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("描述（可选）") }, minLines = 2, maxLines = 4)
            val suggestion = remember(title, selected) { quickPlan(title, LocalDate.now(), selected) }
            if (suggestion != null) AssistChip(onClick = {
                title = suggestion.title; epoch = suggestion.date.toEpochDay(); start = suggestion.start; end = suggestion.end
            }, label = { Text("采用 ${suggestion.date.monthValue}月${suggestion.date.dayOfMonth}日 ${minutesText(suggestion.start)}" + (suggestion.end?.let { "–${minutesText(it)}" } ?: "")) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { DatePickerDialog(context, { _, y, m, d -> epoch = LocalDate.of(y, m + 1, d).toEpochDay() }, date.year, date.monthValue - 1, date.dayOfMonth).show() }, label = { Text("${date.monthValue}月${date.dayOfMonth}日") })
                AssistChip(onClick = { picking = "开始" }, label = { Text(start?.let(::minutesText) ?: "全天 · 加时间") })
                if (start != null) AssistChip(onClick = { picking = "结束" }, label = { Text(end?.let(::minutesText) ?: "＋ 结束时间") })
            }
            if (start != null) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(30, 60).filter { start!! + it < 1440 }.forEach { TextButton(onClick = { end = start!! + it }) { Text("${it}分钟") } }
                    if (end != null) TextButton(onClick = { end = null }) { Text("仅时刻") }
                    TextButton(onClick = { start = null; end = null; mode = "无" }) { Text("改为全天") }
                }
                if (!valid && end != null && end!! <= start!!) Text("结束时间需晚于开始时间", color = Amber)
                Text("提醒方式", color = Muted, fontSize = 12.sp)
                Choices(listOf("无", "通知", "闹钟"), mode) { mode = it }
                if (mode != "无") {
                    val offsets = (listOf(0, 5, 10, 30) + advance).distinct().sorted()
                    val labels = offsets.map { if (it == 0) "准点" else "提前${it}分" }
                    Choices(labels, labels[offsets.indexOf(advance)]) { advance = offsets[labels.indexOf(it)] }
                    ReminderAccess(mode == "闹钟")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (task != null) TextButton(onClick = { deleting = true }) { Text("删除", color = Amber) }
                Spacer(Modifier.weight(1f)); Button(enabled = valid, onClick = ::save) { Text("保存计划") }
            }
        }
        LaunchedEffect(Unit) { if (task == null) focus.requestFocus() }
    }
    if (picking.isNotEmpty()) WheelTimeDialog(if (picking == "开始") start ?: 540 else end ?: ((start ?: 540) + 30).coerceAtMost(1439), "${picking}时间", onDismiss = { picking = "" }) {
        if (picking == "开始") start = it else end = it
        picking = ""
    }
    if (deleting && task != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这项计划？") }, confirmButton = { TextButton(onClick = { onDelete(task.id) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留") } })
}

@Composable private fun SwipeRight(revealColor: Color, symbol: String, onSwipe: () -> Unit, content: @Composable () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reveal = revealColor
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f },
            onHorizontalDrag = { change, amount ->
                if (amount > 0) {
                    change.consume()
                    dx = (dx + amount).coerceAtMost(130.dp.toPx())
                }
            },
            onDragEnd = {
                if (dx > 60.dp.toPx()) onSwipe()
                dx = 0f
            })
    }.clip(RoundedCornerShape(12.dp)).background(reveal)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .offset { androidx.compose.ui.unit.IntOffset(dx.roundToInt(), 0) }) { content() }
        if (dx > 0f) {
            Box(Modifier.align(Alignment.CenterStart)
                .width(with(density) { dx.toDp() })
                .fillMaxHeight()
                .background(reveal), contentAlignment = Alignment.Center) {
                if (dx > with(density) { 24.dp.toPx() }) Text(symbol, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable internal fun Choices(labels: List<String>, current: String, select: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label -> FilterChip(label == current, { select(label) }, label = { Text(label) }) }
    }
}

@Composable internal fun ReminderAccess(alarm: Boolean) {
    val context = LocalContext.current
    val notifications = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exact = android.os.Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    if (!notifications || (alarm && !exact)) TextButton(onClick = {
        val intent = if (!notifications) android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            else android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    }) { Text(if (!notifications) "通知未开启 · 去设置" else "准点闹钟未授权 · 去设置", color = Amber) }
}
