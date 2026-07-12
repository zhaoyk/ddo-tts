package io.legado.app.service.mimo

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class MiMoTtsProtocolTest {

    @Test
    fun `every MiMo protocol field declares its JSON wire name`() {
        assertWireNames(
            MiMoChatRequest::class.java,
            mapOf("model" to "model", "messages" to "messages", "audio" to "audio")
        )
        assertWireNames(MiMoMessage::class.java, mapOf("role" to "role", "content" to "content"))
        assertWireNames(MiMoAudioRequest::class.java, mapOf("format" to "format", "voice" to "voice"))
        assertWireNames(MiMoChatResponse::class.java, mapOf("choices" to "choices"))
        assertWireNames(MiMoChoice::class.java, mapOf("message" to "message"))
        assertWireNames(MiMoResponseMessage::class.java, mapOf("audio" to "audio"))
        assertWireNames(MiMoResponseAudio::class.java, mapOf("data" to "data"))
        assertWireNames(MiMoErrorResponse::class.java, mapOf("error" to "error"))
        assertWireNames(MiMoApiError::class.java, mapOf("code" to "code", "message" to "message"))
    }

    @Test
    fun `nested MiMo request serializes with server field names`() {
        val payload = MiMoChatRequest(
            model = "mimo-v2.5-tts",
            messages = listOf(MiMoMessage("assistant", "正文")),
            audio = MiMoAudioRequest("wav", "冰糖")
        )

        assertEquals(
            JsonParser.parseString(
                """{"model":"mimo-v2.5-tts","messages":[{"role":"assistant","content":"正文"}],"audio":{"format":"wav","voice":"冰糖"}}"""
            ),
            JsonParser.parseString(GSON.toJson(payload))
        )
    }

    private fun assertWireNames(type: Class<*>, expected: Map<String, String>) {
        val actual = type.declaredFields
            .filterNot { it.isSynthetic }
            .associate { field ->
                field.name to field.getAnnotation(SerializedName::class.java)?.value
            }
        assertEquals(expected, actual)
    }
}
