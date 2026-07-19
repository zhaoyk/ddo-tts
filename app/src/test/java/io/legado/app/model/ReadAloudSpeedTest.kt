package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudSpeedTest {
    @Test
    fun `maps speech rate settings to player speed`() {
        assertEquals(0.5f, ReadAloudSpeed.playbackSpeed(0), 0f)
        assertEquals(1.0f, ReadAloudSpeed.playbackSpeed(5), 0f)
        assertEquals(1.5f, ReadAloudSpeed.playbackSpeed(10), 0f)
        assertEquals(5.0f, ReadAloudSpeed.playbackSpeed(45), 0f)
    }

    @Test
    fun `normalizes speech rate settings`() {
        assertEquals(0, ReadAloudSpeed.normalizeSetting(-1))
        assertEquals(0, ReadAloudSpeed.normalizeSetting(0))
        assertEquals(45, ReadAloudSpeed.normalizeSetting(45))
        assertEquals(45, ReadAloudSpeed.normalizeSetting(46))
    }

    @Test
    fun `character delay follows playback speed`() {
        assertEquals(100L, ReadAloudSpeed.characterDelayMillis(1_000, 10, 1f))
        assertEquals(50L, ReadAloudSpeed.characterDelayMillis(1_000, 10, 2f))
        assertEquals(0L, ReadAloudSpeed.characterDelayMillis(1_000, 0, 1f))
    }

    @Test
    fun `character delay rounds and rejects invalid inputs`() {
        assertEquals(167L, ReadAloudSpeed.characterDelayMillis(1_000, 3, 2f))
        assertEquals(0L, ReadAloudSpeed.characterDelayMillis(0, 10, 1f))
        assertEquals(0L, ReadAloudSpeed.characterDelayMillis(1_000, 10, 0f))
    }
}
