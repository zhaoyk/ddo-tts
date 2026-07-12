package io.legado.app.service.mimo

import org.junit.Assert.assertEquals
import org.junit.Test

class MiMoPlaybackMathTest {
    @Test
    fun `maps speech rate to player speed`() {
        assertEquals(1.0f, MiMoPlaybackMath.playbackSpeed(5), 0f)
        assertEquals(1.5f, MiMoPlaybackMath.playbackSpeed(10), 0f)
    }

    @Test
    fun `playback speed has a positive lower bound`() {
        assertEquals(0.1f, MiMoPlaybackMath.playbackSpeed(-5), 0f)
        assertEquals(0.1f, MiMoPlaybackMath.playbackSpeed(-100), 0f)
    }

    @Test
    fun `character delay follows playback speed`() {
        assertEquals(100L, MiMoPlaybackMath.characterDelayMillis(1_000, 10, 1f))
        assertEquals(50L, MiMoPlaybackMath.characterDelayMillis(1_000, 10, 2f))
        assertEquals(0L, MiMoPlaybackMath.characterDelayMillis(1_000, 0, 1f))
    }

    @Test
    fun `character delay rounds and rejects invalid inputs`() {
        assertEquals(167L, MiMoPlaybackMath.characterDelayMillis(1_000, 3, 2f))
        assertEquals(0L, MiMoPlaybackMath.characterDelayMillis(0, 10, 1f))
        assertEquals(0L, MiMoPlaybackMath.characterDelayMillis(1_000, 10, 0f))
    }
}
