package io.legado.app.service.mimo

import kotlin.math.roundToLong

object MiMoPlaybackMath {
    fun playbackSpeed(speechRatePlay: Int): Float =
        ((speechRatePlay + 5) / 10f).coerceAtLeast(0.1f)

    fun characterDelayMillis(durationMillis: Long, textLength: Int, speed: Float): Long {
        if (durationMillis <= 0 || textLength <= 0 || speed <= 0f) return 0L
        return (durationMillis.toDouble() / textLength / speed).roundToLong().coerceAtLeast(1L)
    }
}
