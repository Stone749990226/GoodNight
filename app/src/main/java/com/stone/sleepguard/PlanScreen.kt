package com.stone.sleepguard

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    marks: Map<Int, List<Boolean>>,
    scrollState: ScrollState,
    onSelectDate: (LocalDate) -> Unit,
    onSaveTask: (PlanTask) -> Unit,
    repo: PlanRepository,
    bedMinute: Int,
    addTask: Boolean,
    closeAdd: () -> Unit,
    onToggleTask: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onSaveHabit: (Habit, List<Int>) -> Unit,
    onDeleteHabit: (Long) -> Unit,
    onHabitStep: (Long, LocalDate, Int?, Boolean) -> Unit,
    onHabitSwipe: (Long, LocalDate) -> Unit,
    onCompleted: (String, (() -> Unit)?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("plan_view", 0) }
    var timeline by remember { mutableStateOf(prefs.getBoolean("timeline", false)) }
    var detail by remember { mutableStateOf<Habit?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<PlanTask?>(null) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var creatingHabit by rememberSaveable { mutableStateOf(false) }
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
        TextButton(onClick = { timeline = !timeline; prefs.edit().putBoolean("timeline", timeline).apply() }) {
            Text(if (timeline) "☷ 清单" else "◷ 时间线")
        }
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
                MonthGrid(baseMonth.plusMonths(page - 120L), selectedDate, marks, onSelectDate)
            }
        } else {
            HorizontalPager(state = weekPager, modifier = Modifier.fillMaxWidth().height(76.dp)) { page ->
                val weekStart = baseWeek.plusWeeks(page - 120L)
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { offset ->
                        val date = weekStart.plusDays(offset.toLong())
                        val selected = date == selectedDate
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onSelectDate(date) }
                            .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINA), color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(3.dp))
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(if (selected) Mint else Color.Transparent),
                                contentAlignment = Alignment.Center) {
                                Text("${date.dayOfMonth}", color = if (selected) Ink else Cream, fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                            DateDots(marks[date.toEpochDay().toInt()])
                        }
                    }
                }
            }
        }
    }
    val due = habits.filter { it.isDue(selectedDate) }
    val checks by remember(selectedDate) { repo.dao.observeCheckIns(selectedDate.toEpochDay().toInt()) }.collectAsStateWithLifecycle(emptyList())
    val now = java.time.LocalTime.now().toSecondOfDay() / 60
    if (selectedDate == today && (bedMinute - now + 1440) % 1440 <= 60 && tasks.any { it.completedAt == null }) {
        var reviewing by rememberSaveable { mutableStateOf(false) }
        SoftCard {
            TextButton(onClick = { reviewing = !reviewing }) { Text("睡前收尾 · 还有 ${tasks.count { it.completedAt == null }} 项计划") }
            if (reviewing) tasks.filter { it.completedAt == null }.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, Modifier.weight(1f), color = Cream)
                    TextButton(onClick = { editingTask = task }) { Text("调整") }
                    TextButton(onClick = { onSaveTask(task.copy(dueDate = task.dueDate + 1)) }) { Text("移到明天") }
                }
            }
        }
    }
    DayAgenda(tasks, due, reminders, days, timeline, selectedDate <= today, checks,
        onEditTask = { editingTask = it }, onToggleTask = onToggleTask,
        onHabit = { detail = it }, onStep = { id, minute, undo -> onHabitStep(id, selectedDate, minute, undo) },
        onSwipeTask = onToggleTask, onSwipeHabit = { id -> onHabitSwipe(id, selectedDate) },
        onCompleted = onCompleted,
        onStats = { showStats = true })
    Spacer(Modifier.height(64.dp))
    if (addTask || editingTask != null) TaskSheet(editingTask, selectedDate,
        onDismiss = { closeAdd(); editingTask = null },
        onHabit = { closeAdd(); creatingHabit = true },
        onDelete = { onDeleteTask(it); editingTask = null },
        onSave = { onSaveTask(it); closeAdd(); editingTask = null })
    if (showStats || detail != null) HabitInsights(habits, detail, repo, selectedDate,
        onChange = { id, date, undo -> onHabitStep(id, date, null, undo) }, onPick = { detail = it }, onEdit = { editingHabit = it; detail = null; showStats = false },
        onDismiss = { detail = null; showStats = false })
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

@Composable private fun MonthGrid(month: YearMonth, selectedDate: LocalDate, marks: Map<Int, List<Boolean>>, onSelectDate: (LocalDate) -> Unit) {
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
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(30.dp).clip(RoundedCornerShape(50))
                                .background(if (selected) Mint else Color.Transparent)
                                .clickable { onSelectDate(date) }, contentAlignment = Alignment.Center) {
                                Text("${date.dayOfMonth}", fontSize = 14.sp,
                                    color = when { selected -> Ink; inMonth -> Cream; else -> Color(0xFF506974) },
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                            DateDots(marks[date.toEpochDay().toInt()])
                        }
                    }
                }
            }
        }
    }
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
    var icon by rememberSaveable(existing?.id) { mutableStateOf(existing?.icon.orEmpty()) }
    var frequency by rememberSaveable(existing?.id) { mutableStateOf(existing?.frequency ?: FREQ_DAILY) }
    var mask by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.weekdayMask ?: 0b1111111) }
    var target by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.targetCount ?: 1) }
    var mode by rememberSaveable(existing?.id) { mutableStateOf(existing?.reminderMode ?: "ALARM") }
    var times by remember(existing?.id) { mutableStateOf(initialTimes.distinct().sorted()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var iconsExpanded by remember { mutableStateOf(false) }
    if (pickingTime) WheelTimeDialog(initial = java.time.LocalTime.now().hour * 60, title = "提醒时间", confirmLabel = "添加",
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
            val preview = (listOf(icon).filter { it.isNotBlank() } + habitIcons).distinct().take(6)
            if (iconsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    habitIcons.chunked(7).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { glyph -> IconChoice(glyph, icon == glyph) { icon = glyph } }
                        }
                    }
                    TextButton(onClick = { iconsExpanded = false }) { Text("收起") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    preview.forEach { glyph -> IconChoice(glyph, icon == glyph) { icon = glyph } }
                    TextButton(onClick = { iconsExpanded = true }) { Text("⌄") }
                }
            }
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
            if (times.isNotEmpty()) ReminderAccess(mode == "ALARM")
            Text("提醒时间", color = Muted, fontSize = 12.sp)
            times.forEach { minute ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(minutesText(minute), color = Cream, modifier = Modifier.weight(1f))
                    TextButton(onClick = { times = times - minute }) { Text("移除", fontSize = 11.sp) }
                }
            }
            if (times.size < 5) TextButton(onClick = { pickingTime = true }) { Text("添加提醒时间") }
            if (existing != null && onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("删除习惯", color = Color(0xFFF2ACA5)) }
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && (frequency != FREQ_WEEKDAYS || mask != 0), onClick = {
            val habit = (existing ?: Habit(title = title, startDate = date.toEpochDay().toInt()))
                .copy(title = title.trim(), icon = icon, frequency = frequency, weekdayMask = mask, active = true,
                    targetCount = target.coerceAtLeast(1), reminderMode = mode)
            onSave(habit, times)
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
    if (confirmDelete && onDelete != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这个习惯和打卡记录？") }, confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } })

}

@Composable private fun DateDots(marks: List<Boolean>?) {    Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (marks ?: emptyList()).take(3).forEach { done ->
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(50))
                .background(if (done) Mint else Color(0xFFE88C8C)))
        }
    }
}

@Composable private fun IconChoice(glyph: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50))
        .background(if (selected) Mint.copy(alpha = .28f) else Color.Transparent)
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 21.sp)
    }
}

private fun frequencyText(habit: Habit): String = when (habit.frequency) {
    FREQ_WEEKDAYS -> (0..6).filter { (habit.weekdayMask and (1 shl it)) != 0 }
        .joinToString("、") { LocalDate.of(2026, 3, 2).plusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA) }
    FREQ_WORKDAYS -> "工作日"
    else -> "每天"
}
