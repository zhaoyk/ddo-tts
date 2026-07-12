package io.legado.app.service.mimo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

data class MiMoWorkToken(
    val generation: Long,
    val bookIdentity: String,
    val chapterIndex: Int
)

class MiMoWorkCoordinator {
    private val generation = AtomicLong()
    private val callGate = Mutex()

    @Volatile
    private var currentToken: MiMoWorkToken? = null

    @Volatile
    private var ownerJob: Job? = null

    @Synchronized
    fun restart(
        scope: CoroutineScope,
        bookIdentity: String,
        chapterIndex: Int,
        cancelCall: () -> Unit,
        block: suspend (MiMoWorkToken) -> Unit
    ): MiMoWorkToken {
        val token = MiMoWorkToken(generation.incrementAndGet(), bookIdentity, chapterIndex)
        currentToken = token
        val previous = ownerJob
        previous?.cancel()
        cancelCall()
        ownerJob = scope.launch {
            previous?.join()
            coroutineContext.ensureActive()
            ensureCurrent(token)
            block(token)
        }
        return token
    }

    @Synchronized
    fun invalidate(cancelCall: () -> Unit) {
        generation.incrementAndGet()
        currentToken = null
        ownerJob?.cancel()
        cancelCall()
    }

    suspend fun <T> serializedCall(token: MiMoWorkToken, block: suspend () -> T): T =
        callGate.withLock {
            coroutineContext.ensureActive()
            ensureCurrent(token)
            try {
                block().also {
                    coroutineContext.ensureActive()
                    ensureCurrent(token)
                }
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                ensureCurrent(token)
                throw error
            }
        }

    fun canCommit(token: MiMoWorkToken): Boolean = currentToken == token

    fun canCommit(token: MiMoWorkToken, bookIdentity: String, chapterIndex: Int): Boolean =
        token.bookIdentity == bookIdentity &&
            token.chapterIndex == chapterIndex &&
            canCommit(token)

    val isOwnerActive: Boolean
        get() = ownerJob?.isActive == true

    fun requireCurrent(token: MiMoWorkToken) {
        ensureCurrent(token)
    }

    @Synchronized
    fun commitIfCurrent(token: MiMoWorkToken, commit: () -> Unit): Boolean {
        if (!canCommit(token)) return false
        commit()
        return true
    }

    suspend fun joinCurrent() {
        ownerJob?.join()
    }

    private fun ensureCurrent(token: MiMoWorkToken) {
        if (!canCommit(token)) throw CancellationException("Stale MiMo work generation")
    }
}

data class MiMoGenerationFailureRecord<T>(val generation: Long, val value: T)

class MiMoGenerationFailure<T> {
    @Volatile
    private var record: MiMoGenerationFailureRecord<T>? = null

    @Synchronized
    fun record(token: MiMoWorkToken, value: T) {
        record = MiMoGenerationFailureRecord(token.generation, value)
    }

    fun get(token: MiMoWorkToken): T? =
        record?.takeIf { it.generation == token.generation }?.value

    @Synchronized
    fun clear(token: MiMoWorkToken) {
        if (record?.generation == token.generation) record = null
    }

    @Synchronized
    fun clear() {
        record = null
    }
}

object MiMoCacheRetention {
    fun retainChapter(
        audioCache: MutableMap<String, ByteArray>,
        segments: MutableMap<String, MiMoSegment>,
        bookIdentity: String,
        chapterIndex: Int
    ) {
        val retained = segments.values.asSequence()
            .filter { it.bookIdentity == bookIdentity && it.chapterIndex == chapterIndex }
            .mapTo(hashSetOf(), MiMoSegment::mediaId)
        segments.keys.retainAll(retained)
        audioCache.keys.retainAll(retained)
    }
}

sealed interface MiMoRecoveryDecision {
    data class Reload(val mediaId: String, val positionMillis: Long, val generation: Long) :
        MiMoRecoveryDecision

    data class Pause(val mediaId: String, val positionMillis: Long, val generation: Long) :
        MiMoRecoveryDecision
}

class MiMoPlaybackRecovery {
    private val attempted = hashSetOf<Pair<Long, String>>()
    private var pending: MiMoRecoveryDecision.Reload? = null

    fun onError(mediaId: String, positionMillis: Long, generation: Long): MiMoRecoveryDecision {
        val safePosition = positionMillis.coerceAtLeast(0L)
        val key = generation to mediaId
        return if (attempted.add(key)) {
            MiMoRecoveryDecision.Reload(mediaId, safePosition, generation).also { pending = it }
        } else {
            MiMoRecoveryDecision.Pause(mediaId, safePosition, generation)
        }
    }

    fun takeSeekPosition(mediaId: String, generation: Long): Long? {
        val recovery = pending
        if (recovery?.mediaId != mediaId || recovery.generation != generation) return null
        pending = null
        return recovery.positionMillis
    }

    fun carryReload(
        mediaId: String,
        positionMillis: Long,
        previousGeneration: Long,
        newGeneration: Long
    ) {
        attempted.remove(previousGeneration to mediaId)
        attempted.add(newGeneration to mediaId)
        pending = MiMoRecoveryDecision.Reload(mediaId, positionMillis, newGeneration)
    }

    fun release(mediaId: String, generation: Long) {
        attempted.remove(generation to mediaId)
        if (pending?.mediaId == mediaId && pending?.generation == generation) pending = null
    }

    fun clear() {
        attempted.clear()
        pending = null
    }
}

data class MiMoNormalizedText(val text: String, val leadingOffset: Int) {
    fun originalOffset(synthesizedOffset: Int): Int =
        leadingOffset + synthesizedOffset.coerceAtLeast(0)
}

object MiMoTextNormalizer {
    fun normalize(source: String, isNotReadAloud: (String) -> Boolean): MiMoNormalizedText {
        val cleaned = if (isNotReadAloud(source)) "" else source
        val leadingOffset = cleaned.indexOfFirst { !it.isWhitespace() }
            .let { if (it < 0) cleaned.length else it }
        return MiMoNormalizedText(cleaned.trim(), leadingOffset)
    }
}
