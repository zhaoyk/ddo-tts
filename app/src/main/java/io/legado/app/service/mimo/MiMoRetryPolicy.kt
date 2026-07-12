package io.legado.app.service.mimo

data class RetryDecision(val retry: Boolean, val delayMillis: Long)

object MiMoRetryPolicy {
    private val backoff = longArrayOf(1_000L, 3_000L)

    fun decide(error: MiMoTtsException, retriesCompleted: Int): RetryDecision {
        return when (error) {
            is MiMoTtsException.Network,
            is MiMoTtsException.Server -> backoffDecision(retriesCompleted)

            is MiMoTtsException.InvalidResponse ->
                if (retriesCompleted == 0) RetryDecision(true, 0) else RetryDecision(false, 0)

            is MiMoTtsException.RateLimited -> {
                if (retriesCompleted >= 2) return RetryDecision(false, 0)
                val retryAfter = error.retryAfterMillis
                when {
                    retryAfter == null -> backoffDecision(retriesCompleted)
                    retryAfter <= 30_000L -> RetryDecision(true, retryAfter)
                    else -> RetryDecision(false, 0)
                }
            }

            is MiMoTtsException.Authentication,
            is MiMoTtsException.BadRequest -> RetryDecision(false, 0)
        }
    }

    fun isImmediatePrefetchFailure(error: MiMoTtsException): Boolean =
        error is MiMoTtsException.Authentication || error is MiMoTtsException.BadRequest

    private fun backoffDecision(retriesCompleted: Int): RetryDecision =
        backoff.getOrNull(retriesCompleted)
            ?.let { RetryDecision(true, it) }
            ?: RetryDecision(false, 0)
}
