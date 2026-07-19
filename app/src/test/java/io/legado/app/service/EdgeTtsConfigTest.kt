package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsConfigTest {
    @Test
    fun `builds neutral rate ssml`() {
        val ssml = EdgeTtsConfig.buildSsml("正文", "zh-CN-XiaoxiaoNeural")

        assertTrue(ssml.contains("rate='+0%'"))
        assertTrue(ssml.contains("<voice name='zh-CN-XiaoxiaoNeural'>"))
    }

    @Test
    fun `cache identity includes voice but not playback speed`() {
        val xiaoxiaoKey = EdgeTtsConfig.cacheKey("zh-CN-XiaoxiaoNeural", "正文")
        val repeatedKey = listOf(0, 45).map {
            EdgeTtsConfig.cacheKey("zh-CN-XiaoxiaoNeural", "正文")
        }

        assertEquals(listOf(xiaoxiaoKey, xiaoxiaoKey), repeatedKey)
        assertNotEquals(
            xiaoxiaoKey,
            EdgeTtsConfig.cacheKey("zh-CN-YunxiNeural", "正文")
        )
    }
}
