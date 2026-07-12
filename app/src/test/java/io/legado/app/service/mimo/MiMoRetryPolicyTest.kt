package io.legado.app.service.mimo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MiMoRetryPolicyTest {
    @Test
    fun `network and server errors retry after one and three seconds`() {
        assertEquals(RetryDecision(true, 1_000), MiMoRetryPolicy.decide(MiMoTtsException.Network(IOException()), 0))
        assertEquals(RetryDecision(true, 3_000), MiMoRetryPolicy.decide(MiMoTtsException.Server(503, null, null), 1))
        assertEquals(RetryDecision(false, 0), MiMoRetryPolicy.decide(MiMoTtsException.Server(503, null, null), 2))
    }

    @Test
    fun `invalid response retries exactly once`() {
        assertTrue(MiMoRetryPolicy.decide(MiMoTtsException.InvalidResponse(null), 0).retry)
        assertFalse(MiMoRetryPolicy.decide(MiMoTtsException.InvalidResponse(null), 1).retry)
    }

    @Test
    fun `rate limit honors at most thirty seconds`() {
        assertEquals(12_000L, MiMoRetryPolicy.decide(MiMoTtsException.RateLimited(12_000, null, null), 0).delayMillis)
        assertFalse(MiMoRetryPolicy.decide(MiMoTtsException.RateLimited(31_000, null, null), 0).retry)
    }

    @Test
    fun `rate limit boundary is retryable and missing delay uses backoff`() {
        assertEquals(RetryDecision(true, 30_000), MiMoRetryPolicy.decide(MiMoTtsException.RateLimited(30_000, null, null), 0))
        assertEquals(RetryDecision(true, 3_000), MiMoRetryPolicy.decide(MiMoTtsException.RateLimited(null, null, null), 1))
        assertEquals(RetryDecision(false, 0), MiMoRetryPolicy.decide(MiMoTtsException.RateLimited(1_000, null, null), 2))
    }

    @Test
    fun `authentication and bad request never retry and stop prefetch immediately`() {
        val authentication = MiMoTtsException.Authentication(401, null, null)
        val badRequest = MiMoTtsException.BadRequest(400, null, null)

        assertEquals(RetryDecision(false, 0), MiMoRetryPolicy.decide(authentication, 0))
        assertEquals(RetryDecision(false, 0), MiMoRetryPolicy.decide(badRequest, 0))
        assertTrue(MiMoRetryPolicy.isImmediatePrefetchFailure(authentication))
        assertTrue(MiMoRetryPolicy.isImmediatePrefetchFailure(badRequest))
        assertFalse(MiMoRetryPolicy.isImmediatePrefetchFailure(MiMoTtsException.Network(IOException())))
    }
}
