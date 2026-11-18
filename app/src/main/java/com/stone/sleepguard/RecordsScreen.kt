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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.LocalDate

fun recordsForDays(records: List<NightRecord>, days: Int): List<NightRecord> {
    if (days == 1) return records.take(1)
    val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
    return records.filter { LocalDate.parse(it.night.date) >= cutoff }
}
@Composable internal fun RecordsScreen(records: List<NightRecord>, onFeeling: (Long) -> Unit, onPhoto: (Long) -> Unit,
    onShare: (List<NightRecord>) -> Unit, onReason: (Long, String) -> Unit) {
    SectionLabel("每一晚", "YOUR RHYTHM")
    Text("看看时间，也记得看看自己。", color = Muted, fontSize = 13.sp)
    var days by rememberSaveable { mutableIntStateOf(7) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(7, 30).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("最近${d}天") }) }
    }
    val selected = recordsForDays(records, days)
    val stats = statistics(selected)
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("平均就寝", stats.averageBed, Modifier.weight(1f)); Metric("平均醒来", stats.averageWake, Modifier.weight(1f)); Metric("达标率", stats.rate?.let { "$it%" } ?: "—", Modifier.weight(1f))
        }
        Text("${stats.total} 晚记录 · ${stats.valid} 晚有效；缺失、未完成及监控中断记录不参与平均。", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
        Trend(selected)
    }
    if (selected.isEmpty()) SoftCard {
        Text("第一声晚安，还没开始。", fontSize = 20.sp, color = Cream)
        Text("今晚接上电，点一下“我去睡了”。明早回来，就有第一条记录。", color = Muted, lineHeight = 24.sp)
    } else {
        OutlinedButton(onClick = { onShare(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("预览并分享这${days}天") }
        SummaryTable(selected)
        selected.forEach { RecordCard(it, onFeeling, onPhoto, onShare, onReason) }
    }
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
@Composable private fun SummaryTable(records: List<NightRecord>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        fun Modifier.cell() = width(112.dp).padding(vertical = 10.dp, horizontal = 4.dp)
        Row { listOf("夜晚", "首次说晚安", "最终就寝", "点击醒了", "中断次数", "记录间隔", "感受").forEach { Text(it, Modifier.cell(), fontSize = 12.sp, color = Mint) } }
        records.forEach { r ->
            HorizontalDivider(color = Color(0xFF2B3C46))
            Row { listOf(r.night.date.drop(5), clock(r.night.firstSleepAt), clock(r.finalBedAt), clock(r.night.wakeAt), "${r.disruptions.size}", intervalText(r.interval), r.night.feeling ?: "待补充").forEach { Text(it, Modifier.cell(), fontSize = 12.sp, color = Cream) } }
        }
    }
}
@Composable private fun RecordCard(record: NightRecord, onFeeling: (Long) -> Unit, onPhoto: (Long) -> Unit,
    onShare: (List<NightRecord>) -> Unit, onReason: (Long, String) -> Unit) {
    var expanded by rememberSaveable(record.night.id) { mutableStateOf(false) }
    SoftCard {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                Text("${record.night.date} 晚", color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("${clock(record.finalBedAt)} → ${clock(record.night.wakeAt)} · ${record.disruptions.size} 次中断", color = Muted, fontSize = 12.sp)
            }
            Text(if (expanded) "收起 −" else "展开 +", color = Mint, fontSize = 12.sp)
        }
        if (record.hasGap) Text("包含监控缺口，未计入作息平均。", color = Amber, fontSize = 12.sp)
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
        }
    }
}
