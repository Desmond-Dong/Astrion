package com.example.astrion.ui

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

/** HA 天气 condition → 原版风格的 emoji 图标与中文描述。 */
fun weatherEmoji(condition: String): String = when (condition) {
    "clear-night" -> "🌙"
    "sunny" -> "☀️"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "rainy" -> "🌧️"
    "pouring" -> "🌧️"
    "lightning" -> "⛈️"
    "lightning-rainy" -> "⛈️"
    "snowy" -> "🌨️"
    "snowy-rainy" -> "🌨️"
    "hail" -> "🌨️"
    "windy", "windy-variant" -> "💨"
    "fog" -> "🌫️"
    "exceptional" -> "🌡️"
    else -> "🌡️"
}

fun weatherLabel(condition: String): String = when (condition) {
    "clear-night" -> "晴夜"
    "sunny" -> "晴"
    "partlycloudy" -> "多云"
    "cloudy" -> "阴"
    "rainy" -> "雨"
    "pouring" -> "大雨"
    "lightning" -> "雷电"
    "lightning-rainy" -> "雷阵雨"
    "snowy" -> "雪"
    "snowy-rainy" -> "雨夹雪"
    "hail" -> "冰雹"
    "windy", "windy-variant" -> "大风"
    "fog" -> "雾"
    "exceptional" -> "异常"
    else -> condition
}

/** 风向：实体值可能是 90 / 92.5 / "NE" / "东北"，统一转中文方位。 */
fun windBearingLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val deg = raw.trim().toIntOrNull()
        ?: raw.trim().toDoubleOrNull()?.toInt()
        ?: textBearing(raw.trim())
    if (deg == null) return ""
    val points = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val index = ((deg % 360 + 360) % 360 / 45.0).toInt()
    return points[index % 8] + "风"
}

/** 文本方位 → 角度（英文缩写 / 全称 / 中文）。 */
private fun textBearing(raw: String): Int? {
    val table = mapOf(
        "n" to 0, "nne" to 22, "ne" to 45, "ene" to 67, "e" to 90,
        "ese" to 112, "se" to 135, "sse" to 157, "s" to 180,
        "ssw" to 202, "sw" to 225, "wsw" to 247, "w" to 270,
        "wnw" to 292, "nw" to 315, "nnw" to 337,
        "北" to 0, "东北" to 45, "东" to 90, "东南" to 135,
        "南" to 180, "西南" to 225, "西" to 270, "西北" to 315,
    )
    val key = raw.lowercase().replace("风", "")
    val abbrev = key.takeWhile { it.isLetter() }.ifBlank { key }
    return table[key] ?: table[abbrev]
}

/**
 * 预报条目的时间标签：日预报（带 templow）显示 今天/明天/周几，
 * 小时预报显示 HH:mm。
 */
fun forecastTimeLabel(datetime: String, daily: Boolean): String = runCatching {
    val time = OffsetDateTime.parse(datetime)
    if (daily) {
        val date = time.toLocalDate()
        val today = LocalDate.now()
        when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        }
    } else {
        "%02d:%02d".format(time.hour, time.minute)
    }
}.getOrDefault(datetime)
