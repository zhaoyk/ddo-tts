package io.legado.app.service.mimo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MiMoLifecycleStateTest {

    private class BusinessFailure : RuntimeException()

    @Test
    fun `rapid restart serializes calls and stale generation cannot commit`() = runBlocking {
        val coordinator = MiMoWorkCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val activeCalls = AtomicInteger()
        val maxCalls = AtomicInteger()
        val commits = mutableListOf<String>()

        coordinator.restart(scope, "book-a", 3, cancelCall = {}) { token ->
            coordinator.serializedCall(token) {
                val active = activeCalls.incrementAndGet()
                maxCalls.updateAndGet { maxOf(it, active) }
                firstEntered.complete(Unit)
                try {
                    withContext(NonCancellable) { releaseFirst.await() }
                    "old"
                } finally {
                    activeCalls.decrementAndGet()
                }
            }
            if (coordinator.canCommit(token)) commits += "old"
        }
        firstEntered.await()

        coordinator.restart(scope, "book-a", 3, cancelCall = {}) { token ->
            val value = coordinator.serializedCall(token) {
                val active = activeCalls.incrementAndGet()
                maxCalls.updateAndGet { maxOf(it, active) }
                activeCalls.decrementAndGet()
                "new"
            }
            if (coordinator.canCommit(token)) commits += value
        }
        delay(50)
        assertEquals(1, maxCalls.get())
        releaseFirst.complete(Unit)
        coordinator.joinCurrent()

        assertEquals(1, maxCalls.get())
        assertEquals(listOf("new"), commits)
    }

    @Test
    fun `restart after invalidation joins the cancelled owner`() = runBlocking {
        val coordinator = MiMoWorkCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        coordinator.restart(scope, "book-a", 3, cancelCall = {}) {
            firstEntered.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstEntered.await()

        coordinator.invalidate(cancelCall = {})
        coordinator.restart(scope, "book-a", 3, cancelCall = {}) {
            secondEntered.complete(Unit)
        }
        delay(50)
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        coordinator.joinCurrent()

        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `stale business failure becomes cancellation and cannot be recorded`() = runBlocking {
        val coordinator = MiMoWorkCoordinator()
        val failures = MiMoGenerationFailure<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val token = coordinator.restart(scope, "book-a", 3, cancelCall = {}) {}
        val failure = BusinessFailure()

        val thrown = runCatching {
            coordinator.serializedCall(token) {
                coordinator.invalidate(cancelCall = {})
                throw failure
            }
        }.exceptionOrNull()
        if (thrown is BusinessFailure) {
            coordinator.commitIfCurrent(token) { failures.record(token, thrown) }
        }

        assertTrue(thrown is CancellationException)
        assertNull(failures.get(token))
    }

    @Test
    fun `current failure is generation scoped and clears on restart or success`() = runBlocking {
        val coordinator = MiMoWorkCoordinator()
        val failures = MiMoGenerationFailure<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = coordinator.restart(scope, "book-a", 3, cancelCall = {}) {}
        val firstFailure = BusinessFailure()

        val thrown = runCatching {
            coordinator.serializedCall(first) { throw firstFailure }
        }.exceptionOrNull()
        coordinator.commitIfCurrent(first) { failures.record(first, thrown!!) }
        assertEquals(firstFailure, failures.get(first))

        val second = coordinator.restart(scope, "book-a", 3, cancelCall = {}) {}
        failures.clear()
        assertNull(failures.get(first))
        assertNull(failures.get(second))

        val secondFailure = BusinessFailure()
        coordinator.commitIfCurrent(second) { failures.record(second, secondFailure) }
        assertEquals(secondFailure, failures.get(second))
        failures.clear(second)
        assertNull(failures.get(second))
    }

    @Test
    fun `automatic chapter transition retains only target chapter cache`() {
        val config = MiMoTtsConfig("key", "冰糖", "")
        val current = MiMoSegment.create(2, 0, "current", config, "book-a")
        val target = MiMoSegment.create(3, 0, "target", config, "book-a")
        val otherBook = MiMoSegment.create(3, 1, "other", config, "book-b")
        val audio = linkedMapOf(
            current.mediaId to byteArrayOf(1),
            target.mediaId to byteArrayOf(2),
            otherBook.mediaId to byteArrayOf(3)
        )
        val segments = linkedMapOf(
            current.mediaId to current,
            target.mediaId to target,
            otherBook.mediaId to otherBook
        )

        MiMoCacheRetention.retainChapter(audio, segments, "book-a", 3)

        assertEquals(setOf(target.mediaId), audio.keys)
        assertEquals(setOf(target.mediaId), segments.keys)
    }

    @Test
    fun `snapshot identity requires generation book and chapter to match`() {
        val coordinator = MiMoWorkCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val token = coordinator.restart(scope, "book-a", 4, cancelCall = {}) {}

        assertTrue(coordinator.canCommit(token, "book-a", 4))
        assertFalse(coordinator.canCommit(token, "book-b", 4))
        assertFalse(coordinator.canCommit(token, "book-a", 5))
        coordinator.invalidate(cancelCall = {})
        assertFalse(coordinator.canCommit(token, "book-a", 4))
    }

    @Test
    fun `playback recovery retries once and seeks to saved position`() {
        val recovery = MiMoPlaybackRecovery()

        assertEquals(
            MiMoRecoveryDecision.Reload("media-1", 1_234L, 7L),
            recovery.onError("media-1", 1_234L, 7L)
        )
        assertNull(recovery.takeSeekPosition("media-1", 6L))
        recovery.carryReload("media-1", 1_234L, 7L, 8L)
        assertNull(recovery.takeSeekPosition("media-1", 7L))
        assertEquals(1_234L, recovery.takeSeekPosition("media-1", 8L))
        assertEquals(
            MiMoRecoveryDecision.Pause("media-1", 1_234L, 8L),
            recovery.onError("media-1", 1_234L, 8L)
        )
    }

    @Test
    fun `leading trim maps synthesized offsets back to original text`() {
        val normalized = MiMoTextNormalizer.normalize(" \t正文  ") { false }

        assertEquals("正文", normalized.text)
        assertEquals(2, normalized.leadingOffset)
        assertEquals(2, normalized.originalOffset(0))
        assertEquals(4, normalized.originalOffset(2))
    }
}
