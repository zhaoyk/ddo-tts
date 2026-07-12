package io.legado.app.service.mimo

import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class MiMoTtsClient private constructor(
    private val httpClient: OkHttpClient,
    private val endpoint: HttpUrl,
    private val clock: Clock
) {
    constructor() : this(
        defaultHttpClient(),
        MiMoTtsContract.ENDPOINT.toHttpUrl(),
        Clock.systemUTC()
    )

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var explicitlyCancelledCall: Call? = null

    suspend fun synthesize(config: MiMoTtsConfig, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = buildRequest(config, text)
            val call = httpClient.newCall(request)
            currentCall = call
            try {
                call.execute().use { response -> parseResponse(response) }
            } catch (error: MiMoTtsException) {
                throw error
            } catch (error: IOException) {
                if (explicitlyCancelledCall === call) {
                    throw CancellationException("MiMo request cancelled").apply { initCause(error) }
                }
                throw MiMoTtsException.Network(error)
            } finally {
                if (currentCall === call) currentCall = null
                if (explicitlyCancelledCall === call) explicitlyCancelledCall = null
            }
        }

    fun cancelCurrent() {
        val call = currentCall
        explicitlyCancelledCall = call
        call?.cancel()
        currentCall = null
    }

    private fun buildRequest(config: MiMoTtsConfig, text: String): Request {
        val messages = buildList {
            if (config.style.isNotBlank()) add(MiMoMessage("user", config.style))
            add(MiMoMessage("assistant", text))
        }
        val payload = MiMoChatRequest(
            model = MiMoTtsContract.MODEL,
            messages = messages,
            audio = MiMoAudioRequest(format = "wav", voice = config.voice)
        )
        return Request.Builder()
            .url(endpoint)
            .header("api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .post(GSON.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseResponse(response: Response): ByteArray {
        val requestId = response.header("x-request-id") ?: response.header("request-id")
        val body = response.body.string()
        if (!response.isSuccessful) throw mapHttpError(response.code, body, requestId, response.header("Retry-After"))
        val data = runCatching {
            GSON.fromJson(body, MiMoChatResponse::class.java)
                .choices?.firstOrNull()?.message?.audio?.data
        }.getOrElse { throw MiMoTtsException.InvalidResponse(requestId, it) }
        if (data.isNullOrBlank()) throw MiMoTtsException.InvalidResponse(requestId)
        val bytes = runCatching { Base64.getDecoder().decode(data) }
            .getOrElse { throw MiMoTtsException.InvalidResponse(requestId, it) }
        if (!isWave(bytes)) throw MiMoTtsException.InvalidResponse(requestId)
        return bytes
    }

    private fun mapHttpError(
        status: Int,
        body: String,
        requestId: String?,
        retryAfter: String?
    ): MiMoTtsException {
        val errorCode = runCatching {
            GSON.fromJson(body, MiMoErrorResponse::class.java).error?.code
        }.getOrNull()
        return when (status) {
            400 -> MiMoTtsException.BadRequest(status, requestId, errorCode)
            401, 403 -> MiMoTtsException.Authentication(status, requestId, errorCode)
            429 -> MiMoTtsException.RateLimited(parseRetryAfter(retryAfter), requestId, errorCode)
            in 500..599 -> MiMoTtsException.Server(status, requestId, errorCode)
            else -> MiMoTtsException.BadRequest(status, requestId, errorCode)
        }
    }

    private fun parseRetryAfter(value: String?): Long? {
        val normalized = value?.trim().orEmpty()
        normalized.toLongOrNull()?.let { seconds ->
            val nonNegativeSeconds = seconds.coerceAtLeast(0L)
            return if (nonNegativeSeconds > Long.MAX_VALUE / 1_000L) {
                Long.MAX_VALUE
            } else {
                nonNegativeSeconds * 1_000L
            }
        }
        return runCatching {
            val retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(clock.instant(), retryAt).toMillis().coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun isWave(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
    }

    companion object {
        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        @JvmSynthetic
        internal fun createForTesting(
            httpClient: OkHttpClient = defaultHttpClient(),
            endpoint: HttpUrl,
            clock: Clock = Clock.systemUTC()
        ) = MiMoTtsClient(httpClient, endpoint, clock)
    }
}
