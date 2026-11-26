package com.stone.sleepguard

import java.time.LocalDate

internal data class QuickPlan(val date: LocalDate, val start: Int, val end: Int?, val title: String)
private const val periodPattern = "(上午|早上|中午|下午|晚上|凌晨)?"
private const val timePattern = "(\\d{1,2}|[一二两三四五六七八九十]{1,3})(?:[:：](\\d{2})|点(半|\\d{1,2}分?)?)"
private val quickPattern = Regex("^(今天|明天|后天)?\\s*$periodPattern$timePattern(?:\\s*(?:到|至|[-–])\\s*$periodPattern$timePattern)?\\s*(.+)$")
private fun hourNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it }
    val number = value.replace('两', '二')
    val digits = "零一二三四五六七八九"
    if ('十' !in number) return digits.indexOf(number).takeIf { it > 0 }
    val parts = number.split('十')
    if (parts.size != 2) return null
    val tens = if (parts.first().isEmpty()) 1 else digits.indexOf(parts.first())
    val ones = if (parts.last().isEmpty()) 0 else digits.indexOf(parts.last())
    return if (tens in 1..2 && ones in 0..9) tens * 10 + ones else null
}
internal fun quickPlan(text: String, today: LocalDate, selected: LocalDate): QuickPlan? {
    val groups = quickPattern.matchEntire(text.trim())?.groupValues ?: return null
    fun minute(index: Int, period: String): Int? {
        var hour = hourNumber(groups[index]) ?: return null
        val tail = groups[index + 1].ifEmpty { groups[index + 2] }
        val min = when (tail) { "" -> 0; "半" -> 30; else -> tail.removeSuffix("分").toIntOrNull() ?: return null }
        if (hour !in 0..23 || min !in 0..59 || (period.isNotEmpty() && hour > 12)) return null
        if (period in listOf("下午", "晚上", "中午") && hour in 1..11) hour += 12
        if (period == "凌晨" && hour == 12) hour = 0
        return hour * 60 + min
    }
    val start = minute(3, groups[2]) ?: return null
    val end = if (groups[7].isEmpty()) null else minute(7, groups[6].ifEmpty { groups[2] }) ?: return null
    val title = groups[10].trim()
    if (title.isEmpty() || title.first() in "到至-–" || (end != null && end <= start)) return null
    val date = when (groups[1]) { "今天" -> today; "明天" -> today.plusDays(1); "后天" -> today.plusDays(2); else -> selected }
    return QuickPlan(date, start, end, title)
}
