package io.legado.app.service.mimo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MiMoSegmentTest {
    @Test
    fun `plans remaining current chapter and ten next chapter paragraphs`() {
        assertEquals(listOf(3, 4, 5), MiMoPrefetchPlan.currentChapterIndices(3, 6))
        assertEquals((0..9).toList(), MiMoPrefetchPlan.nextChapterIndices(18))
        assertEquals(listOf(0, 1), MiMoPrefetchPlan.nextChapterIndices(2))
    }

    @Test
    fun `plans no indices for invalid ranges or empty next chapter`() {
        assertEquals(emptyList<Int>(), MiMoPrefetchPlan.currentChapterIndices(-1, 6))
        assertEquals(emptyList<Int>(), MiMoPrefetchPlan.currentChapterIndices(6, 6))
        assertEquals(emptyList<Int>(), MiMoPrefetchPlan.nextChapterIndices(0))
        assertEquals(emptyList<Int>(), MiMoPrefetchPlan.nextChapterIndices(-1))
    }

    @Test
    fun `cache signature ignores key but changes for voice style and text`() {
        val base = MiMoTtsConfig("key-a", "冰糖", "平静")
        val first = MiMoSegment.create(1, 2, "正文", base)
        val otherKey = MiMoSegment.create(1, 2, "正文", base.copy(apiKey = "key-b"))
        assertEquals(first.cacheKey, otherKey.cacheKey)
        assertEquals(first.mediaId, otherKey.mediaId)
        assertNotEquals(first.cacheKey, MiMoSegment.create(1, 2, "正文", base.copy(voice = "苏打")).cacheKey)
        assertNotEquals(first.cacheKey, MiMoSegment.create(1, 2, "正文", base.copy(style = "激动")).cacheKey)
        assertNotEquals(first.cacheKey, MiMoSegment.create(1, 2, "另一段", base).cacheKey)
    }

    @Test
    fun `media identity changes with paragraph position but not playback speed`() {
        val config = MiMoTtsConfig("key-a", "冰糖", "平静")
        val segment = MiMoSegment.create(1, 2, "正文", config)
        val speeds = listOf(5, 10).map(MiMoPlaybackMath::playbackSpeed)

        assertEquals(listOf(1.0f, 1.5f), speeds)
        assertEquals(segment, MiMoSegment.create(1, 2, "正文", config))
        assertNotEquals(segment.mediaId, MiMoSegment.create(1, 3, "正文", config).mediaId)
    }
}
