package com.stone.sleepguard

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
internal fun PlanScreen(
    today: LocalDate,
    selectedDate: LocalDate,
    tasks: List<PlanTask>,
    habits: List<Habit>,
    reminders: List<HabitReminder>,
    days: List<HabitDay>,
    scrollState: ScrollState,
    onSelectDate: (LocalDate) -> Unit,
    onAddTask: (String) -> Unit,
    onToggleTask: (Long) -> Unit,
    onEditTask: (Long, String) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onSaveHabit: (Habit, List<Int>) -> Unit,
    onDeleteHabit: (Long) -> Unit,
    onHabitStep: (Long, LocalDate) -> Unit,
) {
    var addTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<PlanTask?>(null) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var creatingHabit by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val baseMonth = remember { YearMonth.from(today) }
    val pager = rememberPagerState(initialPage = 120, pageCount = { 240 })
    val baseWeek = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val weekPager = rememberPagerState(initialPage = 120, pageCount = { 240 })
    val shownMonth = if (expanded) baseMonth.plusMonths(pager.currentPage - 120L) else YearMonth.from(selectedDate)
    LaunchedEffect(pager.currentPage) {
        if (expanded && YearMonth.from(selectedDate) != shownMonth) {
            val day = selectedDate.dayOfMonth.coerceAtMost(shownMonth.lengthOfMonth())
            onSelectDate(shownMonth.atDay(day))
        }
    }
    LaunchedEffect(weekPager.currentPage) {
        if (!expanded) {
            val week = baseWeek.plusWeeks(weekPager.currentPage - 120L)
            val target = week.plusDays((selectedDate.dayOfWeek.value - 1).toLong())
            if (target != selectedDate) onSelectDate(target)
        }
    }
    LaunchedEffect(selectedDate) {
        if (!expanded) {
            val selectedWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val target = 120 + ChronoUnit.WEEKS.between(baseWeek, selectedWeek).toInt()
            if (target in 0 until 240 && weekPager.currentPage != target) weekPager.scrollToPage(target)
        }
        val target = 120 + ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(selectedDate)).toInt()
        if (target in 0 until 240 && pager.currentPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            val target = 120 + ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(selectedDate)).toInt()
            if (target in 0 until 240) pager.scrollToPage(target)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${shownMonth.year}年${shownMonth.monthValue}月", fontSize = 22.sp, color = Cream,
            fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
        if (selectedDate != today) TextButton(onClick = { onSelectDate(today) }) { Text("今天") }
        if (expanded) TextButton(onClick = { expanded = false }) { Text("收起") }
    }
    Column(Modifier.fillMaxWidth().pointerInput(expanded, scrollState.value) {
        if (expanded) {
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onVerticalDrag = { change, amount ->
                    if (amount < 0) {
                        change.consume(); dragY += amount
                        if (dragY < -80f) { expanded = false; dragY = 0f }
                    }
                })
        } else {
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onVerticalDrag = { change, amount ->
                    if (scrollState.value == 0 && amount > 0) {
                        change.consume(); dragY += amount
                        if (dragY > 80f) { expanded = true; dragY = 0f }
                    }
                })
        }
    }) {
        Box(Modifier.fillMaxWidth().height(12.dp).clickable { expanded = !expanded }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Muted))
        }
        if (expanded) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(274.dp)) { page ->
                MonthGrid(baseMonth.plusMonths(page - 120L), selectedDate, onSelectDate)
            }
        } else {
            HorizontalPager(state = weekPager, modifier = Modifier.fillMaxWidth().height(68.dp)) { page ->
                val weekStart = baseWeek.plusWeeks(page - 120L)
                Row(Modifier.fillMaxWidth()) {
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
            }
        }
    }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("计划", fontSize = 18.sp, color = Cream, modifier = Modifier.weight(1f))
            TextButton(onClick = { addTask = true }) { Text("+", fontSize = 24.sp, color = Mint) }
        }
        if (tasks.isEmpty()) Text("没有计划", color = Muted, fontSize = 12.sp)
        tasks.forEach { task ->
            Row(Modifier.fillMaxWidth().clickable { editingTask = task }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = task.completedAt != null, onCheckedChange = { onToggleTask(task.id) },
                    colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted, checkmarkColor = Ink))
                Text(task.title, color = if (task.completedAt == null) Cream else Muted, fontSize = 15.sp,
                    textDecoration = if (task.completedAt == null) null else androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    modifier = Modifier.weight(1f))
            }
        }
    }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("习惯", fontSize = 18.sp, color = Cream, modifier = Modifier.weight(1f))
            TextButton(onClick = { creatingHabit = true }) { Text("+", fontSize = 24.sp, color = Mint) }
        }
        val due = habits.filter { it.isDue(selectedDate) }
        if (due.isEmpty()) Text("没有习惯", color = Muted, fontSize = 12.sp)
        due.forEach { habit ->
            val day = days.firstOrNull { it.habitId == habit.id }
            val count = day?.count ?: 0
            val target = habit.targetCount.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().clickable { editingHabit = habit }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target == 1) {
                    Checkbox(checked = count >= 1, onCheckedChange = { onHabitStep(habit.id, selectedDate) },
                        colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted, checkmarkColor = Ink))
                } else {
                    Text("$count/$target", color = if (count >= target) Mint else Cream, fontSize = 13.sp,
                        modifier = Modifier.width(52.dp))
                    TextButton(enabled = count < target, onClick = { onHabitStep(habit.id, selectedDate) }) { Text("+1") }
                }
                Text(habit.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
    }
    if (addTask) AddTaskDialog(onDismiss = { addTask = false }, onSave = { title -> addTask = false; onAddTask(title) })
    editingTask?.let { task ->
        TaskEditorDialog(task, onDismiss = { editingTask = null }, onSave = { title ->
            editingTask = null; onEditTask(task.id, title)
        }, onDelete = { editingTask = null; onDeleteTask(task.id) })
    }
    if (creatingHabit) HabitEditorDialog(null, selectedDate, onDismiss = { creatingHabit = false }) { habit, mins ->
        creatingHabit = false; onSaveHabit(habit, mins)
    }
    editingHabit?.let { habit ->
        val initial = reminders.filter { it.habitId == habit.id }.map { it.minute }.sorted()
        HabitEditorDialog(habit, selectedDate, initial, onDismiss = { editingHabit = null },
            onDelete = { editingHabit = null; onDeleteHabit(habit.id) }) { edited, mins ->
            editingHabit = null; onSaveHabit(edited, mins)
        }
    }
}

@Composable private fun MonthGrid(month: YearMonth, selectedDate: LocalDate, onSelectDate: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            (0..6).forEach { i ->
                Text(LocalDate.of(2026, 3, 2).plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINA),
                    color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        repeat(6) { row ->
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { col ->
                    val date = start.plusDays((row * 7 + col).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val selected = date == selectedDate
                    Box(Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(50))
                            .background(if (selected) Mint else Color.Transparent)
                            .clickable { onSelectDate(date) }, contentAlignment = Alignment.Center) {
                            Text("${date.dayOfMonth}", fontSize = 14.sp,
                                color = when { selected -> Ink; inMonth -> Cream; else -> Color(0xFF506974) },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun AddTaskDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加计划") }, text = {
        OutlinedTextField(value = title, onValueChange = { title = it }, placeholder = { Text("要做什么？") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                focusedTextColor = Cream, unfocusedTextColor = Cream, focusedBorderColor = Mint, unfocusedBorderColor = Color(0xFF3B505C), cursorColor = Mint))
    }, confirmButton = {
        TextButton(onClick = { if (title.isNotBlank()) onSave(title.trim()) }, enabled = title.isNotBlank()) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable private fun TaskEditorDialog(task: PlanTask, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑计划") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                    focusedTextColor = Cream, unfocusedTextColor = Cream, focusedBorderColor = Mint, unfocusedBorderColor = Color(0xFF3B505C), cursorColor = Mint))
            TextButton(onClick = onDelete) { Text("删除计划", color = Color(0xFFF2ACA5)) }
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable private fun HabitEditorDialog(
    existing: Habit?,
    date: LocalDate,
    initialTimes: List<Int> = emptyList(),
    onDismiss: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onSave: (Habit, List<Int>) -> Unit,
) {
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var frequency by rememberSaveable(existing?.id) { mutableStateOf(existing?.frequency ?: FREQ_DAILY) }
    var mask by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.weekdayMask ?: 0b1111111) }
    var target by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.targetCount ?: 1) }
    var mode by rememberSaveable(existing?.id) { mutableStateOf(existing?.reminderMode ?: "ALARM") }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每天完成次数", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (target > 1) target-- }) { Text("−") }
                Text("$target", color = Cream, fontSize = 16.sp)
                TextButton(onClick = { if (target < 99) target++ }) { Text("+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("NOTIFY" to "通知", "ALARM" to "闹钟").forEach { (value, label) ->
                    FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) })
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
            if (existing != null && onDelete != null) TextButton(onClick = onDelete) { Text("删除习惯", color = Color(0xFFF2ACA5)) }
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && times.isNotEmpty(), onClick = {
            val habit = (existing ?: Habit(title = title, startDate = date.toEpochDay().toInt()))
                .copy(title = title.trim(), frequency = frequency, weekdayMask = mask, active = true,
                    targetCount = target.coerceAtLeast(1), reminderMode = mode)
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
