package com.example.astrion.ha

/** 一条天气预报（`weather/get_forecasts` 的单条记录）。 */
data class HaWeatherForecast(
    val datetime: String,
    val condition: String,
    val temperature: Double? = null,
    val templow: Double? = null,
    val precipitationProbability: Double? = null,
    val windSpeed: Double? = null,
    val humidity: Double? = null,
)
