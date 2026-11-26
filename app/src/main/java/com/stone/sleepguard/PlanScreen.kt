package com.stone.sleepguard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun PlanScreen(
    today: LocalDate,
    selectedDate: LocalDate,
    tasks: List<PlanTask>,
    habits: List<Habit>,
    reminders: List<HabitReminder>,
    checkIns: List<HabitCheckIn>,
    onSelectDate: (LocalDate) -> Unit,
    onAddTask: (String) -> Unit,
    onToggleTask: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onSaveHabit: (Habit, List<Int>) -> Unit,
    onDeleteHabit: (Long) -> Unit,
    onToggleSlot: (Long, LocalDate, Int) -> Unit,
) {
    var addTask by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var creatingHabit by remember { mutableStateOf(false) }
    var addChoice by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${selectedDate.year}年${selectedDate.monthValue}月", fontSize = 22.sp, color = Cream,
                fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            if (selectedDate != today) TextButton(onClick = { onSelectDate(today) }) { Text("今天") }
            TextButton(onClick = { addChoice = true }) {
                Text("+", fontSize = 28.sp, color = Mint)
            }
        }
        val weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..6).forEach { offset ->
                val date = weekStart.plusDays(offset.toLong())
                val selected = date == selectedDate
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onSelectDate(date) }
                    .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINA), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (selected) Mint else Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Text("${date.dayOfMonth}", color = if (selected) Ink else Cream, fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onSelectDate(weekStart.minusWeeks(1)) }) { Text("‹ 上一周") }
            Text(if (selectedDate == today) "今天" else selectedDate.format(DateTimeFormatter.ofPattern("M月d日")),
                color = Muted, fontSize = 12.sp)
            TextButton(onClick = { onSelectDate(weekStart.plusWeeks(1)) }) { Text("下一周 ›") }
        }
    }
    SoftCard {
        Text("计划", fontSize = 18.sp, color = Cream)
        if (tasks.isEmpty()) Text("没有计划", color = Muted, fontSize = 12.sp)
        tasks.forEach { task ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = task.completedAt != null, onCheckedChange = { onToggleTask(task.id) },
                    colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted, checkmarkColor = Ink))
                Text(task.title, color = if (task.completedAt == null) Cream else Muted, fontSize = 15.sp,
                    textDecoration = if (task.completedAt == null) null else androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onDeleteTask(task.id) }) { Text("删除", color = Muted, fontSize = 11.sp) }
            }
        }
    }
    SoftCard {
        Text("习惯", fontSize = 18.sp, color = Cream)
        if (habits.none { it.isDue(selectedDate) }) Text("没有习惯", color = Muted, fontSize = 12.sp)
        habits.filter { it.isDue(selectedDate) }.forEach { habit ->
            val times = reminders.filter { it.habitId == habit.id }.map { it.minute }.sorted()
            val completed = times.count { minute -> checkIns.any { it.habitId == habit.id && it.minute == minute && it.completedAt != null } }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(habit.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("$completed/${times.size}", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = { editingHabit = habit }) { Text("编辑", fontSize = 11.sp) }
                    TextButton(onClick = { onDeleteHabit(habit.id) }) { Text("删除", fontSize = 11.sp, color = Muted) }
                }
                Text(frequencyText(habit), color = Muted, fontSize = 11.sp)
                times.forEach { minute ->
                    val done = checkIns.any { it.habitId == habit.id && it.minute == minute && it.completedAt != null }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = done, onCheckedChange = { onToggleSlot(habit.id, selectedDate, minute) },
                            colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted, checkmarkColor = Ink))
                        Text(minutesText(minute), color = if (done) Muted else Cream, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    if (addChoice) AddChoiceDialog(onDismiss = { addChoice = false }, onTask = { addChoice = false; addTask = true },
        onHabit = { addChoice = false; creatingHabit = true })
    if (addTask) AddTaskDialog(onDismiss = { addTask = false }, onSave = { title -> addTask = false; onAddTask(title) })
    if (creatingHabit) HabitEditorDialog(null, selectedDate, onDismiss = { creatingHabit = false }) { habit, mins ->
        creatingHabit = false; onSaveHabit(habit, mins)
    }
    editingHabit?.let { habit ->
        val initial = reminders.filter { it.habitId == habit.id }.map { it.minute }.sorted()
        HabitEditorDialog(habit.copy(weekdayMask = habit.weekdayMask), selectedDate, initial) { edited, mins ->
            editingHabit = null; onSaveHabit(edited, mins)
        }
    }
}

@Composable private fun AddChoiceDialog(onDismiss: () -> Unit, onTask: () -> Unit, onHabit: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("创建") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTask, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("计划") }
            OutlinedButton(onClick = onHabit, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("习惯") }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable private fun AddTaskDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加待办") }, text = {
        OutlinedTextField(value = title, onValueChange = { title = it }, placeholder = { Text("要做什么？") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                focusedTextColor = Cream, unfocusedTextColor = Cream, focusedBorderColor = Mint, unfocusedBorderColor = Color(0xFF3B505C), cursorColor = Mint))
    }, confirmButton = {
        TextButton(onClick = { if (title.isNotBlank()) onSave(title.trim()) }, enabled = title.isNotBlank()) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable private fun HabitEditorDialog(
    existing: Habit?,
    today: LocalDate,
    initialTimes: List<Int> = emptyList(),
    onDismiss: () -> Unit = {},
    onSave: (Habit, List<Int>) -> Unit,
) {
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var frequency by rememberSaveable(existing?.id) { mutableStateOf(existing?.frequency ?: FREQ_DAILY) }
    var mask by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.weekdayMask ?: 0b1111111) }
    var times by remember(existing?.id) { mutableStateOf(initialTimes.distinct().sorted()) }
    var pickingTime by remember { mutableStateOf(false) }
    if (pickingTime) WheelTimeDialog(initial = 9 * 60, title = "提醒时间", confirmLabel = "添加",
        onDismiss = { pickingTime = false }, onConfirm = { minute ->
            if (times.size < 5 && minute !in times) times = (times + minute).sorted()
            pickingTime = false
        })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加习惯" else "编辑习惯") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = title, onValueChange = { title = it }, placeholder = { Text("例如：喝水") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                    focusedTextColor = Cream, unfocusedTextColor = Cream, focusedBorderColor = Mint, unfocusedBorderColor = Color(0xFF3B505C), cursorColor = Mint))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(FREQ_DAILY to "每天", FREQ_WEEKDAYS to "每周", FREQ_WORKDAYS to "工作日").forEach { (value, label) ->
                    FilterChip(selected = frequency == value, onClick = { frequency = value }, label = { Text(label) })
                }
            }
            if (frequency == FREQ_WEEKDAYS) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { bit ->
                        val day = LocalDate.of(2026, 3, 2).plusDays(bit.toLong())
                        FilterChip(selected = (mask and (1 shl bit)) != 0, onClick = { mask = mask xor (1 shl bit) },
                            label = { Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)) })
                    }
                }
            }
            Text("提醒时间", color = Muted, fontSize = 12.sp)
            times.forEach { minute ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(minutesText(minute), color = Cream, modifier = Modifier.weight(1f))
                    TextButton(onClick = { times = times - minute }) { Text("移除", fontSize = 11.sp) }
                }
            }
            if (times.size < 5) TextButton(onClick = { pickingTime = true }) { Text("添加提醒时间") }
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && times.isNotEmpty(), onClick = {
            val habit = (existing ?: Habit(title = title, startDate = today.toEpochDay().toInt()))
                .copy(title = title.trim(), frequency = frequency, weekdayMask = mask, active = true)
            onSave(habit, times)
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

private fun frequencyText(habit: Habit): String = when (habit.frequency) {
    FREQ_WEEKDAYS -> (0..6).filter { (habit.weekdayMask and (1 shl it)) != 0 }
        .joinToString("、") { LocalDate.of(2026, 3, 2).plusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA) }
    FREQ_WORKDAYS -> "工作日"
    else -> "每天"
}
