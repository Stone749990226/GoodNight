package com.stone.sleepguard

import android.content.Context
import android.graphics.*
import android.text.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Paginated image export keeps long reasons intact without allocating an unbounded bitmap. */
class ShareExporter(private val context: Context, private val partner: String = "你") {
    private val directory get() = File(context.cacheDir, "exports").apply { mkdirs() }
    private val stamp get() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

    fun images(records: List<NightRecord>): List<File> {
        val files = mutableListOf<File>(); val prefix = stamp
        val width = 900; val height = 9000; val margin = 64f
        val render = 1.25f
        val outWidth = (width * render).toInt(); val outHeight = (height * render).toInt()
        fun page() = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        fun canvasOf(target: Bitmap) = Canvas(target).apply { scale(render, render) }
        var bitmap = page(); var canvas = canvasOf(bitmap); var y = 74f
        val ink = Color.rgb(13, 23, 31); val cream = Color.rgb(232, 239, 233)
        val mint = Color.rgb(168, 220, 203); val muted = Color.rgb(159, 178, 185)
        canvas.drawColor(ink)
        fun finish() {
            val used = (y + 66).toInt().coerceIn(240, height)
            val output = Bitmap.createBitmap(bitmap, 0, 0, outWidth, (used * render).toInt().coerceIn(1, outHeight))
            val file = File(directory, "晚安_${prefix}_${files.size + 1}.png")
            file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (output !== bitmap) output.recycle()
            bitmap.recycle(); files.add(file)
        }
        fun nextPage() {
            finish(); bitmap = page(); canvas = canvasOf(bitmap); canvas.drawColor(ink); y = 74f
        }
        fun paragraph(text: String, size: Float = 28f, color: Int = cream, bold: Boolean = false) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - (margin * 2).toInt())
                .setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
            for (line in 0 until layout.lineCount) {
                val lineHeight = size * 1.6f
                if (y + lineHeight > height - 80) nextPage()
                val value = text.substring(layout.getLineStart(line), layout.getLineEnd(line)).trimEnd('\n', '\r')
                canvas.drawText(value, margin, y + size, paint); y += lineHeight
            }
            y += 10
        }
        paragraph("晚安", 64f, mint, true)
        paragraph(if (records.size == 1) "${records.first().night.date} · 夜间记录" else "${records.last().night.date} — ${records.first().night.date} · 作息记录", 26f, muted)
        val stats = statistics(records)
        paragraph("平均就寝 ${stats.averageBed}    平均醒来 ${stats.averageWake}", 28f)
        paragraph("${stats.total} 晚记录 · ${stats.valid} 晚有效 · 达标率 ${stats.rate?.let { "$it%" } ?: "—"}", 25f, muted)
        y += 24
        records.forEach { r ->
            if (y + 350 > height - 80) nextPage()
            canvas.drawLine(margin, y, width - margin, y, Paint().apply { color = Color.rgb(47, 66, 76); strokeWidth = 2f }); y += 26
            paragraph("${r.night.date} 晚", 36f, mint, true)
            paragraph("${clock(r.finalBedAt)}  →  ${clock(r.night.wakeAt)}", 48f, cream)
            paragraph("首次晚安 ${clock(r.night.firstSleepAt)}  ·  ${r.disruptions.size} 次中断  ·  ${r.night.feeling ?: "感受待补充"}", 25f, muted)
            paragraph("记录间隔 ${intervalText(r.interval)}", 25f, muted)
            if (r.hasGap) paragraph("含监控缺口，不参与作息平均。", 25f, Color.rgb(231, 195, 141))
            r.interruptions.sortedBy { it.detectedAt }.forEach { interruption ->
                paragraph(if (interruption.morning) "${clock(interruption.detectedAt)} · 起床操作" else "${clock(interruption.detectedAt)} → ${clock(interruption.returnedAt)}  ${interruption.reason ?: "原因待补充"}", 27f)
            }
            r.events.filter { it.type in listOf("GAP", "RESUME", "POWER_OFF", "POWER_ON") }.sortedBy { it.at }.forEach { paragraph("${clock(it.at)} · ${eventText(it)}", 23f, muted) }
            r.night.photo?.let { path ->
                val photo = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                        val targetWidth = 772f * render
                        val scale = (targetWidth / info.size.width).coerceAtMost(1f)
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
                if (photo != null) {
                    val imageHeight = (photo.height * (772f / photo.width)).coerceAtMost(640f)
                    if (y + imageHeight + 36 > height - 80) nextPage()
                    val dest = RectF(margin, y, width - margin, y + imageHeight)
                    val cropHeight = (photo.width * imageHeight / 772f).toInt().coerceAtMost(photo.height)
                    val src = Rect(0, (photo.height - cropHeight) / 2, photo.width, (photo.height + cropHeight) / 2)
                    canvas.save(); val clip = Path().apply { addRoundRect(dest, 24f, 24f, Path.Direction.CW) }; canvas.clipPath(clip)
                    canvas.drawBitmap(photo, src, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); canvas.restore(); photo.recycle(); y += imageHeight + 28
                }
            }
            y += 20
        }
        paragraph("晚安，分享给 $partner", 23f, mint)
        finish()
        return files
    }
    private fun eventText(e: NightEvent) = when (e.type) {
        "POWER_OFF" -> "拔掉电源，继续监控"
        "POWER_ON" -> "重新接上电源"
        else -> e.detail
    }
}
