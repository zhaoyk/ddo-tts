package io.legado.app.service.mimo

import java.io.IOException

sealed class MiMoTtsException(
    message: String,
    cause: Throwable? = null,
    open val requestId: String? = null,
    open val errorCode: String? = null
) : IOException(message, cause) {

    class BadRequest(
        val status: Int,
        override val requestId: String?,
        override val errorCode: String?
    ) : MiMoTtsException("MiMo request was rejected", requestId = requestId, errorCode = errorCode)

    class Authentication(
        val status: Int,
        override val requestId: String?,
        override val errorCode: String?
    ) : MiMoTtsException("MiMo authentication failed", requestId = requestId, errorCode = errorCode)

    class RateLimited(
        val retryAfterMillis: Long?,
        override val requestId: String?,
        override val errorCode: String?
    ) : MiMoTtsException("MiMo request was rate limited", requestId = requestId, errorCode = errorCode)

    class Server(
        val status: Int,
        override val requestId: String?,
        override val errorCode: String?
    ) : MiMoTtsException("MiMo server failed", requestId = requestId, errorCode = errorCode)

    class Network(cause: IOException) : MiMoTtsException("MiMo network request failed", cause)

    class InvalidResponse(
        override val requestId: String?,
        cause: Throwable? = null
    ) : MiMoTtsException("MiMo returned invalid audio", cause, requestId)
}
