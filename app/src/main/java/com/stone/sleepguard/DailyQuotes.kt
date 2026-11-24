package com.stone.sleepguard

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** 首页清晨/晚上轮播文字；内容来自 assets/quotes.json，按日期固定轮换。 */
object DailyQuotes {
    private data class Cached(val date: LocalDate, val evening: Boolean, val text: String)

    @Volatile private var cached: Cached? = null

    fun current(context: Context, now: LocalDateTime = LocalDateTime.now()): String {
        val evening = now.hour >= 18 || now.hour < 5
        val date = now.toLocalDate()
        cached?.let { if (it.date == date && it.evening == evening) return it.text }
        val text = runCatching {
            val json = JSONObject(context.assets.open("quotes.json").bufferedReader().use { it.readText() })
            val array = json.optJSONArray(if (evening) "evening" else "morning") ?: return@runCatching null
            if (array.length() == 0) return@runCatching null
            val offset = if (evening) 7 else 0
            array.optString(Math.floorMod(date.dayOfYear + offset, array.length())).takeIf { it.isNotBlank() }
        }.getOrNull() ?: "今天也要开心"
        cached = Cached(date, evening, text)
        return text
    }
}
