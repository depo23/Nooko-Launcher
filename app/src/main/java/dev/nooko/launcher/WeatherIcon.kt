package dev.nooko.launcher

/** The icon for the top bar's weather: the sky by Open-Meteo's weather code, by day or night. */
internal fun weatherIcon(code: Int, day: Boolean) = when (code) {
    0 -> if (day) R.drawable.ms_light_mode_fill else R.drawable.ms_dark_mode_fill
    1, 2 -> if (day) R.drawable.ms_partly_cloudy_day_fill else R.drawable.ms_partly_cloudy_night_fill
    45, 48 -> R.drawable.ms_foggy_fill
    in 51..67, in 80..82 -> R.drawable.ms_rainy_fill
    in 71..77, 85, 86 -> R.drawable.ms_weather_snowy_fill
    in 95..99 -> R.drawable.ms_thunderstorm_fill
    else -> R.drawable.ms_cloud_fill
}
