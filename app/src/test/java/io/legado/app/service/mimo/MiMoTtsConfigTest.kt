package io.legado.app.service.mimo

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoTtsConfigTest {

    private val global = MiMoGlobalConfig(
        apiKey = "secret-key",
        voice = "冰糖",
        style = "平静自然"
    )

    @Test
    fun `blank book style inherits global style`() {
        assertEquals(
            MiMoTtsConfig("secret-key", "冰糖", "平静自然"),
            MiMoTtsConfigStore.resolve(global, "   ")
        )
    }

    @Test
    fun `nonblank book style replaces global style`() {
        assertEquals(
            MiMoTtsConfig("secret-key", "冰糖", "悬疑且克制"),
            MiMoTtsConfigStore.resolve(global, "  悬疑且克制  ")
        )
    }

    @Test
    fun `unsupported voice falls back to mimo default`() {
        val resolved = MiMoTtsConfigStore.resolve(global.copy(voice = "unknown"), null)
        assertEquals(MiMoTtsContract.DEFAULT_VOICE, resolved.voice)
    }

    @Test
    fun `legacy read config without mimo style remains readable`() {
        val config = Book.Converters().stringToReadConfig(
            """{"ttsEngine":"{\"title\":\"Edge\",\"value\":\"edgeinner\"}"}"""
        )
        assertNull(config?.mimoTtsStyle)
    }

    @Test
    fun `global config diagnostics redact api key`() {
        val config = MiMoGlobalConfig(
            apiKey = "global-sensitive-key",
            voice = "冰糖",
            style = "平静自然"
        )

        val diagnostics = config.toString()

        assertFalse(diagnostics.contains(config.apiKey))
        assertTrue(diagnostics.contains("apiKey=<redacted>"))
        assertTrue(diagnostics.contains("voice=冰糖"))
        assertTrue(diagnostics.contains("style=平静自然"))
    }

    @Test
    fun `resolved config diagnostics redact api key`() {
        val config = MiMoTtsConfig(
            apiKey = "resolved-sensitive-key",
            voice = "Mia",
            style = "gentle"
        )

        val diagnostics = config.toString()

        assertFalse(diagnostics.contains(config.apiKey))
        assertTrue(diagnostics.contains("apiKey=<redacted>"))
        assertTrue(diagnostics.contains("voice=Mia"))
        assertTrue(diagnostics.contains("style=gentle"))
    }
}
