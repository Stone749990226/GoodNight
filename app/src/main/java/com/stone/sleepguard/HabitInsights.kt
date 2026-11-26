package com.stone.sleepguard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth

internal fun streaks(habit: Habit, rows: List<HabitDay>, today: LocalDate): Pair<Int, Int> {
    val completed = rows.filter { it.completedAt != null }.map { it.date }.toSet()
    val first = completed.minOrNull() ?: return 0 to 0
    var run = 0
    var best = 0
    for (day in first..today.toEpochDay().toInt()) {
        when {
            day in completed -> { run++; best = maxOf(best, run) }
            day < today.toEpochDay() && habit.isDue(LocalDate.ofEpochDay(day.toLong())) -> run = 0
        }
    }
    return run to best
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HabitInsights(habits: List<Habit>, selected: Habit?, repo: PlanRepository, date: LocalDate,
    onChange: (Long, LocalDate, Boolean) -> Unit, onPick: (Habit) -> Unit, onEdit: (Habit) -> Unit, onDismiss: () -> Unit) {
    var period by rememberSaveable { mutableStateOf("本周") }
    var offset by rememberSaveable(period) { mutableIntStateOf(0) }
    var correcting by remember { mutableStateOf<Pair<Habit, LocalDate>?>(null) }
    val today = LocalDate.now()
    val anchor = minOf(today, date)
    val month = YearMonth.from(anchor).plusMonths(offset.toLong())
    val monday = anchor.minusDays(anchor.dayOfWeek.value - 1L).plusWeeks(offset.toLong())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.let { "${it.glyph()}  ${it.title}" } ?: "习惯统计", Modifier.weight(1f), color = Cream, fontSize = 21.sp)
                if (selected != null) TextButton(onClick = { onEdit(selected) }) { Text("编辑习惯") }
            }
            Choices(listOf("本周", "本月", "历史"), period) { period = it }
            if (period != "历史") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset-- }) { Text("上一${if (period == "本周") "周" else "月"}") }
                Text(if (period == "本月") "${month.year}年${month.monthValue}月" else "$monday 起", color = Cream)
                TextButton(enabled = offset < 0, onClick = { offset++ }) { Text("下一${if (period == "本周") "周" else "月"}") }
            }
            if (habits.isEmpty()) Text("创建习惯后，在这里查看打卡记录。", color = Muted)
            (selected?.let(::listOf) ?: habits).forEach { h -> key(h.id) {
                val history by remember(repo, h.id) { repo.dao.history(h.id) }.collectAsStateWithLifecycle(emptyList())
                val byDate = remember(history) { history.associateBy { it.date } }
                if (selected == null) TextButton(onClick = { onPick(h) }) { Text("${h.glyph()}  ${h.title}  ›") }
                if (period == "历史") {
                    val (current, best) = remember(h, history, today) { streaks(h, history, today) }
                    Text("累计打卡 ${history.sumOf { it.count }} 次  ·  达标 ${history.count { it.completedAt != null }} 天", color = Cream)
                    Text("连续 $current 个计划日  ·  最长 $best 个计划日", color = Mint, fontSize = 14.sp)
                } else {
                    val first = if (period == "本周") monday else month.atDay(1)
                    val length = if (period == "本周") 7 else month.lengthOfMonth()
                    val cells = List<LocalDate?>(first.dayOfWeek.value - 1) { null } + List(length) { first.plusDays(it.toLong()) }
                    Row { "一二三四五六日".forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it.toString(), color = Muted, fontSize = 12.sp) } } }
                    cells.chunked(7).forEach { week -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(7) { index ->
                            val d = week.getOrNull(index)
                            val row = byDate[d?.toEpochDay()?.toInt()]
                            val fill = when { row?.completedAt != null -> Mint; (row?.count ?: 0) > 0 -> Amber; else -> Ink }
                            Box(Modifier.weight(1f).height(34.dp).clickable(enabled = d != null && d <= today && h.isDue(d)) { correcting = h to d!! }.background(if (d == null) Panel else fill, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                if (d != null) Text(d.dayOfMonth.toString(), color = if (fill != Ink) Ink else if (d <= today && h.isDue(d)) Cream else Muted.copy(alpha = .55f))
                            }
                        }
                    } }
                    val range = first.toEpochDay().toInt() until first.toEpochDay().toInt() + length
                    val data = history.filter { it.date in range }
                    Text("薄荷绿 达标  ·  琥珀色 部分完成", color = Muted, fontSize = 12.sp)
                    Text("达标 ${data.count { it.completedAt != null }} 天  ·  打卡 ${data.sumOf { it.count }} 次", color = Cream)
                }
                HorizontalDivider(color = Muted.copy(alpha = .2f))
            } }
        }
    }
    correcting?.let { (h, d) ->
        val records by remember(h.id, d) { repo.days(d) }.collectAsStateWithLifecycle(emptyList())
        val record = records.find { it.habitId == h.id }
        val count = record?.count ?: 0
        val target = record?.dayTarget ?: h.targetCount
        AlertDialog(onDismissRequest = { correcting = null }, title = { Text("${h.glyph()} ${h.title}") }, text = {
            Column { Text("$d  ·  $count/$target 次"); Row {
                TextButton(enabled = count > 0, onClick = { onChange(h.id, d, true) }) { Text("撤销一次") }
                TextButton(enabled = count < target, onClick = { onChange(h.id, d, false) }) { Text("补记一次") }
            } }
        }, confirmButton = { TextButton(onClick = { correcting = null }) { Text("完成") } })
    }

}
