package io.legado.app.model

import kotlin.math.roundToLong

object ReadAloudSpeed {
    const val MIN_SETTING = 0
    const val DEFAULT_SETTING = 5
    const val MAX_SETTING = 45

    fun normalizeSetting(setting: Int): Int =
        setting.coerceIn(MIN_SETTING, MAX_SETTING)

    fun playbackSpeed(setting: Int): Float =
        (normalizeSetting(setting) + 5) / 10f

    fun characterDelayMillis(durationMillis: Long, textLength: Int, speed: Float): Long {
        if (durationMillis <= 0 || textLength <= 0 || speed <= 0f) return 0L
        return (durationMillis.toDouble() / textLength / speed).roundToLong().coerceAtLeast(1L)
    }
}
