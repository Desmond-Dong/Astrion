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

/** 风向角度 → 中文方位。 */
fun windBearingLabel(deg: Int?): String {
    if (deg == null) return ""
    val points = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val index = ((deg % 360 + 360) % 360 / 45.0).toInt()
    return points[index % 8] + "风"
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
