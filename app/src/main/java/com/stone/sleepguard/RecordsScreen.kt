package com.stone.sleepguard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun recordsForDays(records: List<NightRecord>, days: Int): List<NightRecord> {
    if (days == 1) return records.take(1)
    val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
    return records.filter { LocalDate.parse(it.night.date) >= cutoff }
}
fun recordsForRange(records: List<NightRecord>, from: LocalDate?, to: LocalDate?): List<NightRecord> {
    if (from == null || to == null) return records
    return records.filter { val date = LocalDate.parse(it.night.date); !date.isBefore(from) && !date.isAfter(to) }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalDate.shortText(): String = "$monthValue.$dayOfMonth"

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RecordsScreen(records: List<NightRecord>, onFeeling: (Long) -> Unit, onPhoto: (Long) -> Unit,
    onShare: (List<NightRecord>) -> Unit, onReason: (Long, String) -> Unit,
    onEdit: (Long, NightEdit) -> Unit, onDelete: (Long) -> Unit, scrollState: ScrollState) {
    SectionLabel("每一晚", "YOUR RHYTHM")
    Text("看看时间，也记得看看自己。", color = Muted, fontSize = 13.sp)
    var days by rememberSaveable { mutableIntStateOf(7) }
    var fromDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    val from = fromDay?.let { LocalDate.ofEpochDay(it) }
    val to = toDay?.let { LocalDate.ofEpochDay(it) }
    val custom = from != null && to != null
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(selected = !custom && days == 7, onClick = { days = 7; fromDay = null; toDay = null }, label = { Text("最近7天") })
        FilterChip(selected = !custom && days == 30, onClick = { days = 30; fromDay = null; toDay = null }, label = { Text("最近30天") })
        FilterChip(selected = custom, onClick = { showRangePicker = true },
            label = { Text(if (custom) "${from!!.shortText()}–${to!!.shortText()}" else "自定义日期") })
    }
    val selected = if (custom) recordsForRange(records, from, to) else recordsForDays(records, days)
    val stats = statistics(selected)
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("平均就寝", stats.averageBed, Modifier.weight(1f)); Metric("平均醒来", stats.averageWake, Modifier.weight(1f)); Metric("达标率", stats.rate?.let { "$it%" } ?: "—", Modifier.weight(1f))
        }
        Text("${stats.total} 晚记录 · ${stats.valid} 晚有效；缺失、未完成、监控中断及超过时长上限的记录不参与平均。", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
        Trend(selected)
    }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var jumpTarget by remember { mutableStateOf<Long?>(null) }
    var jumpTick by remember { mutableIntStateOf(0) }
    val cardTops = remember { mutableStateMapOf<Long, Int>() }
    val density = LocalDensity.current
    if (selected.isEmpty()) SoftCard {
        Text(if (custom) "所选日期没有记录。" else "第一声晚安，还没开始。", fontSize = 20.sp, color = Cream)
        Text(if (custom) "换一段日期看看，或点回最近7天。" else "今晚接上电，点一下“我去睡了”。明早回来，就有第一条记录。", color = Muted, lineHeight = 24.sp)
    } else {
        OutlinedButton(onClick = { onShare(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (custom) "预览并分享所选日期" else "预览并分享这${days}天") }
        SummaryTable(selected) { id ->
            expandedId = id
            jumpTarget = id
            jumpTick++
        }
        Text("点表格里的任意一晚，跳到下面的详情。", color = Muted, fontSize = 11.sp)
        selected.forEach { record ->
            RecordCard(record, onFeeling, onPhoto, onShare, onReason,
                onEdit = { editingId = record.night.id }, onDelete = { deletingId = record.night.id },
                expanded = expandedId == record.night.id,
                onToggle = { expandedId = if (expandedId == record.night.id) null else record.night.id },
                modifier = Modifier.onGloballyPositioned { coordinates -> cardTops[record.night.id] = coordinates.positionInParent().y.toInt() })
        }
        LaunchedEffect(jumpTick) {
            val id = jumpTarget ?: return@LaunchedEffect
            repeat(3) { withFrameNanos { } }
            val margin = with(density) { 12.dp.toPx() }.toInt()
            cardTops[id]?.let { top ->
                val target = (top - margin).coerceIn(0, scrollState.maxValue)
                scrollState.animateScrollTo(target)
                withFrameNanos { }
                cardTops[id]?.let { settled ->
                    val expected = (settled - margin).coerceIn(0, scrollState.maxValue)
                    if (kotlin.math.abs(expected - scrollState.value) > 8) scrollState.animateScrollTo(expected)
                }
            }
        }
    }
    records.firstOrNull { it.night.id == editingId }?.let { record ->
        EditNightDialog(record, onSave = { edit -> onEdit(record.night.id, edit); editingId = null }, onDismiss = { editingId = null })
    }
    records.firstOrNull { it.night.id == deletingId }?.let { record ->
        DeleteNightDialog(record, onConfirm = { onDelete(record.night.id); deletingId = null }, onDismiss = { deletingId = null })
    }
    if (showRangePicker) RangePickerDialog(from, to,
        onPick = { start, end -> fromDay = start.toEpochDay(); toDay = end.toEpochDay(); showRangePicker = false },
        onDismiss = { showRangePicker = false })
}

@Composable private fun Trend(records: List<NightRecord>) {
    val sorted = records.filter { it.valid }.sortedBy { it.night.firstSleepAt }
    listOf("就寝声明" to Mint, "点击醒来" to Amber).forEachIndexed { band, (label, color) ->
        val values = if (band == 0) sorted.map { bedMinutes(it.finalBedAt!!) } else sorted.map { localMinutes(it.night.wakeAt!!) }
        val low = ((values.minOrNull() ?: if (band == 0) 1410 else 450) / 30 * 30) - 30
        val high = ((values.maxOrNull() ?: if (band == 0) 1410 else 450) / 30 * 30) + 60
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = color, fontSize = 11.sp)
            Text("${minutesText(low.mod(1440))} — ${minutesText(high.mod(1440))}", color = Muted, fontSize = 10.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val w = size.width; val h = size.height
            for (i in 0..2) drawLine(Color(0xFF2C414B), Offset(0f, h * i / 2), Offset(w, h * i / 2), 1.dp.toPx())
            val points = values.mapIndexed { i, value -> Offset(if (values.size == 1) w / 2 else 8f + i * (w - 16f) / (values.size - 1), h - 6f - (value - low).toFloat() / (high - low) * (h - 12f)) }
            val path = Path(); points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            points.forEach { drawCircle(color, 3.dp.toPx(), it) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(sorted.firstOrNull()?.night?.date?.drop(5) ?: "尚无有效数据", color = Muted, fontSize = 10.sp)
        Text(sorted.lastOrNull()?.night?.date?.drop(5) ?: "", color = Muted, fontSize = 10.sp)
    }
}
@Composable private fun SummaryTable(records: List<NightRecord>, onSelect: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        fun Modifier.cell() = width(112.dp).padding(vertical = 10.dp, horizontal = 4.dp)
        Row { listOf("夜晚", "首次说晚安", "最终就寝", "点击醒了", "中断次数", "记录间隔", "感受").forEach { Text(it, Modifier.cell(), fontSize = 12.sp, color = Mint) } }
        records.forEach { r ->
            HorizontalDivider(color = Color(0xFF2B3C46))
            Row(Modifier.fillMaxWidth().clickable { onSelect(r.night.id) }) {
                listOf(r.night.date.drop(5), clock(r.night.firstSleepAt), clock(r.finalBedAt), clock(r.night.wakeAt), "${r.disruptions.size}", intervalText(r.interval) + if (r.overslept) "·超长" else "", r.night.feeling ?: "待补充").forEachIndexed { index, value ->
                    Text(value, Modifier.cell(), fontSize = 12.sp, color = if (index == 0) Mint else Cream)
                }
            }
        }
    }
}
@Composable private fun RecordCard(record: NightRecord, onFeeling: (Long) -> Unit, onPhoto: (Long) -> Unit,
    onShare: (List<NightRecord>) -> Unit, onReason: (Long, String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    SoftCard(modifier) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                Text("${record.night.date} 晚", color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("${clock(record.finalBedAt)} → ${clock(record.night.wakeAt)} · ${record.disruptions.size} 次中断", color = Muted, fontSize = 12.sp)
            }
            Text(if (expanded) "收起 −" else "展开 +", color = Mint, fontSize = 12.sp)
        }
        if (record.hasGap) Text("包含监控缺口，未计入作息平均。", color = Amber, fontSize = 12.sp)
        if (record.overslept) Text("超过设定的最多睡眠时长（${record.night.maxSleepMinutes / 60} 小时），未计入平均。", color = Amber, fontSize = 12.sp)
        if (expanded) {
            HorizontalDivider(color = Color(0xFF30424C))
            Text("首次晚安 ${clock(record.night.firstSleepAt)}\n最终就寝 ${clock(record.finalBedAt)}（本人声明）\n末次灭屏 ${clock(record.lastScreenOff)} · 晨起解锁参考 ${clock(record.morningUnlock)}\n记录间隔 ${intervalText(record.interval)}", color = Muted, fontSize = 13.sp, lineHeight = 24.sp)
            record.interruptions.sortedBy { it.detectedAt }.forEach { i ->
                if (i.morning) Text("${clock(i.detectedAt)} · 起床操作", color = Mint, fontSize = 13.sp)
                else {
                    Text("${clock(i.detectedAt)} → ${clock(i.returnedAt)}\n${i.reason ?: "原因待补充"}", color = Cream, fontSize = 14.sp, lineHeight = 22.sp)
                    if (i.reason.isNullOrBlank()) ReasonEditor(i, onReason)
                }
            }
            record.events.sortedBy { it.at }.filter { it.type in listOf("POWER_OFF", "POWER_ON", "GAP", "RESUME", "ARMED") }.forEach { e ->
                Text("${clock(e.at)} · ${when(e.type) { "POWER_OFF" -> "拔掉电源，继续监控"; "POWER_ON" -> "接上电源"; else -> e.detail }}", color = if (e.type == "GAP") Amber else Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            if (record.night.wakeAt != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onFeeling(record.night.id) }) { Text(record.night.feeling ?: "补充感受") }
                TextButton(onClick = { onPhoto(record.night.id) }) { Text(if (record.night.photo == null) "拍照" else "重新拍照") }
                TextButton(onClick = { onShare(listOf(record)) }) { Text("分享") }
            }
            if (record.night.phase == Phase.AWAKE) {
                HorizontalDivider(color = Color(0xFF30424C))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text("编辑这一晚", color = Mint) }
                    TextButton(onClick = onDelete) { Text("删除这一晚", color = Amber) }
                }
                Text("点错了、只是测试的记录，可以改时间或删除；删除后能撤销。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            } else Text("今晚还在守候；点「我醒了」结束监控后，才能编辑或删除。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RangePickerDialog(from: LocalDate?, to: LocalDate?, onPick: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (from ?: today.minusDays(29)).toUtcMillis(),
        initialSelectedEndDateMillis = (to ?: today).toUtcMillis())
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(enabled = start != null && end != null, onClick = {
            if (start != null && end != null) onPick(start.toUtcDate(), end.toUtcDate())
        }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) {
        DateRangePicker(state = state, showModeToggle = false, modifier = Modifier.weight(1f))
    }
}

@Composable private fun EditNightDialog(record: NightRecord, onSave: (NightEdit) -> Unit, onDismiss: () -> Unit) {
    var bedMinutes by remember(record.night.id) { mutableIntStateOf(localMinutes(record.night.lastSleepAt)) }
    var wakeMinutes by remember(record.night.id) { mutableIntStateOf(record.night.wakeAt?.let { localMinutes(it) } ?: record.night.targetWake) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reasons by remember(record.night.id) { mutableStateOf(record.disruptions.associate { it.id to (it.reason ?: "") }) }
    var removed by remember(record.night.id) { mutableStateOf(emptySet<Long>()) }
    val bedAt = bedTimestamp(record.night.date, bedMinutes)
    val wakeAt = wakeTimestamp(record.night.date, wakeMinutes)
    val error = if (wakeAt <= bedAt) "醒来时间需要晚于就寝时间" else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑 ${record.night.date} 晚") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeField("最终就寝", minutesText(bedMinutes), Modifier.weight(1f)) { editing = "bed" }
                TimeField("点击醒了", minutesText(wakeMinutes), Modifier.weight(1f)) { editing = "wake" }
            }
            val visible = record.disruptions.filterNot { it.id in removed }
            if (visible.isNotEmpty()) {
                Text("中断原因", color = Mint, fontSize = 12.sp)
                visible.forEach { i ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${clock(i.detectedAt)} · ${i.detail}", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { removed = removed + i.id }) { Text("移除", color = Amber, fontSize = 12.sp) }
                        }
                        OutlinedTextField(value = reasons[i.id].orEmpty(), onValueChange = { reasons = reasons + (i.id to it) },
                            placeholder = { Text("原因待补充") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (removed.isNotEmpty()) Text("已标记移除 ${removed.size} 次误报中断，保存后生效。", fontSize = 11.sp, color = Amber)
            if (wakeAt - bedAt > record.night.maxSleepMinutes * 60_000L)
                Text("这次超过设定的 ${record.night.maxSleepMinutes / 60} 小时，保存后不计入平均。", fontSize = 11.sp, color = Amber)
            Text("若就寝时间晚于某次中断，这一晚将显示为未完成，不参与作息平均。", fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
            error?.let { Text(it, color = Amber, fontSize = 12.sp) }
        }
    }, confirmButton = {
        TextButton(enabled = error == null, onClick = { onSave(NightEdit(bedAt, wakeAt, reasons.filterKeys { it !in removed }, removed)) }) { Text("保存修改") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    when (editing) {
        "bed" -> WheelTimeDialog(initial = bedMinutes, title = "最终就寝", onDismiss = { editing = null }) { bedMinutes = it; editing = null }
        "wake" -> WheelTimeDialog(initial = wakeMinutes, title = "点击醒了", onDismiss = { editing = null }) { wakeMinutes = it; editing = null }
    }
}

@Composable private fun TimeField(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2D38)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 11.sp, color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 20.sp, color = Cream, modifier = Modifier.weight(1f))
            Text("›", color = Mint, fontSize = 16.sp)
        }
    }
}

@Composable private fun DeleteNightDialog(record: NightRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除这一晚？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${record.night.date} 晚的记录会从本机删除，包括 ${record.disruptions.size} 次中断和 ${record.events.size} 条事件。")
            Text("误删可以点下方的「撤销」恢复。", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("保留") } })
}
