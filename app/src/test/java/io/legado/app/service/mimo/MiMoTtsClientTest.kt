package io.legado.app.service.mimo

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class MiMoTtsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MiMoTtsClient
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
    private val config = MiMoTtsConfig("secret-key", "冰糖", "平静自然")
    private val wav = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        4, 0, 0, 0,
        'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = MiMoTtsClient.createForTesting(
            endpoint = server.url("/v1/chat/completions"),
            clock = fixedClock
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `public API exposes only fixed endpoint constructor`() {
        val publicConstructorParameterCounts = MiMoTtsClient::class.java.constructors
            .filterNot { it.isSynthetic }
            .map { it.parameterCount }
            .sorted()

        assertEquals(listOf(0), publicConstructorParameterCounts)
    }

    @Test
    fun `sends style then assistant text and decodes wav`() = runBlocking {
        server.enqueue(successResponse())
        assertTrue(client.synthesize(config, "正文").contentEquals(wav))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("secret-key", request.headers["api-key"])
        val json = JsonParser.parseString(request.body!!.utf8()).asJsonObject
        assertEquals("mimo-v2.5-tts", json["model"].asString)
        assertFalse(json.has("stream"))
        val messages = json.getAsJsonArray("messages")
        assertEquals("user", messages[0].asJsonObject["role"].asString)
        assertEquals("平静自然", messages[0].asJsonObject["content"].asString)
        assertEquals("assistant", messages[1].asJsonObject["role"].asString)
        assertEquals("正文", messages[1].asJsonObject["content"].asString)
        assertEquals("wav", json.getAsJsonObject("audio")["format"].asString)
        assertEquals("冰糖", json.getAsJsonObject("audio")["voice"].asString)
    }

    @Test
    fun `omits user message when style is blank`() = runBlocking {
        server.enqueue(successResponse())
        client.synthesize(config.copy(style = ""), "正文")
        val messages = JsonParser.parseString(server.takeRequest().body!!.utf8())
            .asJsonObject.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("assistant", messages[0].asJsonObject["role"].asString)
    }

    @Test
    fun `maps authentication and server errors without leaking key`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":{\"code\":\"invalid_key\"}}").build())
        val auth = capture { client.synthesize(config, "正文") }
        assertTrue(auth is MiMoTtsException.Authentication)
        assertFalse(auth.message.orEmpty().contains("secret-key"))

        server.enqueue(MockResponse.Builder().code(503).body("service unavailable").build())
        val serverError = capture { client.synthesize(config, "正文") }
        assertTrue(serverError is MiMoTtsException.Server)
        assertEquals(503, (serverError as MiMoTtsException.Server).status)
    }

    @Test
    fun `maps bad request forbidden offline and timeout`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":{\"code\":\"bad_text\"}}").build())
        assertTrue(capture { client.synthesize(config, "正文") } is MiMoTtsException.BadRequest)

        server.enqueue(MockResponse.Builder().code(403).build())
        assertTrue(capture { client.synthesize(config, "正文") } is MiMoTtsException.Authentication)

        val offlineHttp = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()
        val offlineClient = MiMoTtsClient.createForTesting(
            offlineHttp,
            server.url("/v1/chat/completions"),
            fixedClock
        )
        assertTrue(capture { offlineClient.synthesize(config, "正文") } is MiMoTtsException.Network)

        server.enqueue(
            MockResponse.Builder()
                .bodyDelay(1, TimeUnit.SECONDS)
                .body(successJson(Base64.getEncoder().encodeToString(wav)))
                .build()
        )
        val timeoutHttp = OkHttpClient.Builder().callTimeout(50, TimeUnit.MILLISECONDS).build()
        val timeoutClient = MiMoTtsClient.createForTesting(
            timeoutHttp,
            server.url("/v1/chat/completions"),
            fixedClock
        )
        assertTrue(capture { timeoutClient.synthesize(config, "正文") } is MiMoTtsException.Network)
    }

    @Test
    fun `parses retry after and rejects delay above thirty seconds`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "12").build())
        val short = capture { client.synthesize(config, "正文") } as MiMoTtsException.RateLimited
        assertEquals(12_000L, short.retryAfterMillis)

        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").build())
        val long = capture { client.synthesize(config, "正文") } as MiMoTtsException.RateLimited
        assertEquals(60_000L, long.retryAfterMillis)

        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "Fri, 10 Jul 2026 00:00:12 GMT")
                .build()
        )
        val date = capture { client.synthesize(config, "正文") } as MiMoTtsException.RateLimited
        assertEquals(12_000L, date.retryAfterMillis)
    }

    @Test
    fun `saturates retry after when seconds overflow milliseconds`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", Long.MAX_VALUE.toString())
                .build()
        )

        val error = capture { client.synthesize(config, "正文") } as MiMoTtsException.RateLimited

        assertEquals(Long.MAX_VALUE, error.retryAfterMillis)
    }

    @Test
    fun `rejects invalid base64 and non wav bytes`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertTrue(capture { client.synthesize(config, "正文") } is MiMoTtsException.InvalidResponse)

        server.enqueue(MockResponse.Builder().body(successJson("not-base64")).build())
        assertTrue(capture { client.synthesize(config, "正文") } is MiMoTtsException.InvalidResponse)

        val plain = Base64.getEncoder().encodeToString("plain".toByteArray())
        server.enqueue(MockResponse.Builder().body(successJson(plain)).build())
        assertTrue(capture { client.synthesize(config, "正文") } is MiMoTtsException.InvalidResponse)
    }

    @Test
    fun `cancel current call surfaces cancellation`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .bodyDelay(5, TimeUnit.SECONDS)
                .body(successJson(Base64.getEncoder().encodeToString(wav)))
                .build()
        )
        val result = async(Dispatchers.IO) { capture { client.synthesize(config, "正文") } }
        withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) }
        client.cancelCurrent()
        assertTrue(result.await() is CancellationException)
    }

    private suspend fun capture(block: suspend () -> Unit): Throwable {
        return try {
            block()
            AssertionError("expected failure")
        } catch (error: Throwable) {
            error
        }
    }

    private fun successResponse() = MockResponse.Builder()
        .body(successJson(Base64.getEncoder().encodeToString(wav)))
        .build()

    private fun successJson(data: String) =
        """{"choices":[{"message":{"audio":{"data":"$data"}}}]}"""
}
