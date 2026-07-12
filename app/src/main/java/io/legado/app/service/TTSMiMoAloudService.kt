package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.service.mimo.MiMoCacheRetention
import io.legado.app.service.mimo.MiMoGenerationFailure
import io.legado.app.service.mimo.MiMoMemoryDataSourceFactory
import io.legado.app.service.mimo.MiMoPlaybackMath
import io.legado.app.service.mimo.MiMoPlaybackRecovery
import io.legado.app.service.mimo.MiMoPrefetchPlan
import io.legado.app.service.mimo.MiMoRecoveryDecision
import io.legado.app.service.mimo.MiMoRetryPolicy
import io.legado.app.service.mimo.MiMoSegment
import io.legado.app.service.mimo.MiMoTextNormalizer
import io.legado.app.service.mimo.MiMoTtsClient
import io.legado.app.service.mimo.MiMoTtsConfig
import io.legado.app.service.mimo.MiMoTtsConfigStore
import io.legado.app.service.mimo.MiMoTtsContract
import io.legado.app.service.mimo.MiMoTtsException
import io.legado.app.service.mimo.MiMoWorkCoordinator
import io.legado.app.service.mimo.MiMoWorkToken
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

@SuppressLint("UnsafeOptInUsageError")
class TTSMiMoAloudService : BaseReadAloudService(), Player.Listener {

    private companion object {
        private const val SLOW_MESSAGE_DELAY_MS = 10_000L
    }

    private val audioCache = ConcurrentHashMap<String, ByteArray>()
    private val segments = ConcurrentHashMap<String, MiMoSegment>()
    private val client = MiMoTtsClient()
    private val workCoordinator = MiMoWorkCoordinator()
    private val playbackRecovery = MiMoPlaybackRecovery()
    private val exoPlayer by lazy {
        val sourceFactory = ProgressiveMediaSource.Factory(MiMoMemoryDataSourceFactory(audioCache))
        ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build()
    }
    private val silentBytes by lazy { resources.openRawResource(R.raw.silent_sound).readBytes() }

    private var progressJob: Job? = null
    private var activeMediaId: String? = null
    private var playbackGeneration = 0L
    private val prefetchFailure = MiMoGenerationFailure<MiMoTtsException>()
    private var currentWorkToken: MiMoWorkToken? = null
    private var playerPrepared = false
    private val queuedMediaIds = hashSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        exoPlayer.setPlaybackSpeed(MiMoPlaybackMath.playbackSpeed(AppConfig.speechRatePlay))
    }

    override fun onDestroy() {
        cancelSynthesis(clearAudio = true)
        progressJob?.cancel()
        exoPlayer.release()
        super.onDestroy()
    }

    override fun play() {
        pageChanged = false
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("MiMo 朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        startSynthesis(clearPlayer = true, clearAudio = false)
    }

    override fun playStop() {
        cancelSynthesis(clearAudio = true)
        clearPlayer()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        progressJob?.cancel()
        exoPlayer.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (pageChanged) {
            play()
        } else {
            exoPlayer.play()
            updateProgressLoop()
            val token = currentWorkToken
            if (token != null &&
                workCoordinator.canCommit(token) &&
                prefetchFailure.get(token) != null &&
                !workCoordinator.isOwnerActive
            ) {
                prefetchFailure.clear(token)
                startSynthesis(clearPlayer = false, clearAudio = false)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        exoPlayer.setPlaybackSpeed(MiMoPlaybackMath.playbackSpeed(AppConfig.speechRatePlay))
        updateProgressLoop()
    }

    override fun prevChapter() {
        playStop()
        super.prevChapter()
    }

    override fun nextChapter() {
        playStop()
        super.nextChapter()
    }

    private fun startSynthesis(clearPlayer: Boolean, clearAudio: Boolean): MiMoWorkToken? {
        progressJob?.cancel()
        if (clearPlayer) clearPlayer()
        if (clearAudio) {
            audioCache.clear()
            segments.clear()
        }
        val bookIdentity = ReadBook.aloudBookIdentity()
        val configSnapshot = MiMoTtsConfigStore.resolve(this, ReadBook.book)
        if (bookIdentity.isEmpty() || configSnapshot.apiKey.isBlank()) {
            workCoordinator.invalidate(client::cancelCurrent)
            currentWorkToken = null
            prefetchFailure.clear()
            pauseWithMessage(R.string.mimo_tts_missing_key, null)
            return null
        }
        val chapterIndex = aloudChapterIndex
        val contentSnapshot = contentList.toList()
        val startIndex = nowSpeak
        val startOffset = paragraphStartPos
        val token = workCoordinator.restart(
            lifecycleScope,
            bookIdentity,
            chapterIndex,
            client::cancelCurrent
        ) { generation ->
            synthesizeGeneration(
                generation,
                configSnapshot,
                contentSnapshot,
                startIndex,
                startOffset
            )
        }
        prefetchFailure.clear()
        currentWorkToken = token
        playbackGeneration = token.generation
        return token
    }

    private suspend fun synthesizeGeneration(
        token: MiMoWorkToken,
        configSnapshot: MiMoTtsConfig,
        contentSnapshot: List<String>,
        startIndex: Int,
        startOffset: Int
    ) {
        val indices = MiMoPrefetchPlan.currentChapterIndices(startIndex, contentSnapshot.size)
        for ((order, index) in indices.withIndex()) {
            ensureGeneration(token)
            val original = contentSnapshot[index]
            val from = if (index == startIndex) {
                startOffset.coerceIn(0, original.length)
            } else {
                0
            }
            val normalized = normalizeText(original.substring(from))
            val segment = MiMoSegment.create(
                token.chapterIndex,
                index,
                normalized.text,
                configSnapshot,
                token.bookIdentity,
                normalized.leadingOffset
            )
            val foreground = order == 0
            val bytes = try {
                obtainAudio(token, configSnapshot, segment, foreground)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val mimoError = error as? MiMoTtsException
                    ?: MiMoTtsException.Network(IOException("Unexpected MiMo failure"))
                if (foreground || MiMoRetryPolicy.isImmediatePrefetchFailure(mimoError)) {
                    withContext(Dispatchers.Main) {
                        ensureGeneration(token)
                        pauseWithMessage(messageFor(mimoError), mimoError)
                    }
                } else {
                    recordPrefetchFailure(token, mimoError)
                }
                return
            }
            commitSegment(token, segment, bytes)
            withContext(Dispatchers.Main) {
                ensureGeneration(token)
                if (!workCoordinator.commitIfCurrent(token) { appendMediaItem(segment) }) {
                    throw CancellationException("Stale MiMo media queue")
                }
            }
        }
        if (prefetchNextChapter(token, configSnapshot, token.chapterIndex + 1)) {
            prefetchFailure.clear(token)
        }
    }

    private suspend fun prefetchNextChapter(
        token: MiMoWorkToken,
        configSnapshot: MiMoTtsConfig,
        chapterIndex: Int
    ): Boolean {
        ensureGeneration(token)
        val snapshot = try {
            ReadBook.loadAloudChapterSnapshot(token.bookIdentity, chapterIndex)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.put(
                "MiMo prefetch snapshot failed chapter=$chapterIndex " +
                    "type=${error::class.simpleName}"
            )
            return false
        } ?: return false
        ensureGeneration(token)
        if (snapshot.bookIdentity != token.bookIdentity || snapshot.chapterIndex != chapterIndex) {
            return false
        }
        val paragraphs = snapshot.textChapter.getNeedReadAloud(0, readAloudByPage, 0)
            .split('\n')
            .filter(String::isNotEmpty)
            .mapIndexedNotNull { paragraphIndex, text ->
                val normalized = normalizeText(text)
                normalized.takeIf { it.text.isNotEmpty() }
                    ?.let { Triple(paragraphIndex, it.text, it.leadingOffset) }
            }
        for (listIndex in MiMoPrefetchPlan.nextChapterIndices(paragraphs.size)) {
            ensureGeneration(token)
            val (paragraphIndex, speakText, leadingOffset) = paragraphs[listIndex]
            val segment = MiMoSegment.create(
                chapterIndex,
                paragraphIndex,
                speakText,
                configSnapshot,
                token.bookIdentity,
                leadingOffset
            )
            if (audioCache.containsKey(segment.mediaId)) continue
            try {
                val bytes = obtainAudio(token, configSnapshot, segment, foreground = false)
                commitSegment(token, segment, bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MiMoTtsException) {
                if (MiMoRetryPolicy.isImmediatePrefetchFailure(error)) {
                    withContext(Dispatchers.Main) {
                        ensureGeneration(token)
                        pauseWithMessage(messageFor(error), error)
                    }
                } else {
                    recordPrefetchFailure(token, error)
                }
                return false
            }
        }
        return true
    }

    private fun normalizeText(text: String) =
        MiMoTextNormalizer.normalize(text, AppPattern.notReadAloudRegex::matches)

    private suspend fun obtainAudio(
        token: MiMoWorkToken,
        configSnapshot: MiMoTtsConfig,
        segment: MiMoSegment,
        foreground: Boolean
    ): ByteArray {
        ensureGeneration(token)
        audioCache[segment.mediaId]?.let { return it }
        if (segment.text.isBlank()) return silentBytes
        val slowMessage = if (foreground) {
            lifecycleScope.launch {
                delay(SLOW_MESSAGE_DELAY_MS)
                if (workCoordinator.canCommit(token)) toastOnUi(R.string.mimo_tts_buffering)
            }
        } else {
            null
        }
        var retries = 0
        try {
            while (true) {
                try {
                    return workCoordinator.serializedCall(token) {
                        client.synthesize(configSnapshot, segment.text).also {
                            ensureGeneration(token)
                        }
                    }
                } catch (error: MiMoTtsException) {
                    val decision = MiMoRetryPolicy.decide(error, retries)
                    if (!decision.retry) throw error
                    retries++
                    if (decision.delayMillis > 0) delay(decision.delayMillis)
                }
            }
        } finally {
            slowMessage?.cancel()
        }
    }

    private suspend fun ensureGeneration(token: MiMoWorkToken) {
        coroutineContext.ensureActive()
        workCoordinator.requireCurrent(token)
        if (ReadBook.aloudBookIdentity() != token.bookIdentity ||
            aloudChapterIndex != token.chapterIndex
        ) {
            throw CancellationException("Stale MiMo chapter identity")
        }
    }

    private suspend fun commitSegment(
        token: MiMoWorkToken,
        segment: MiMoSegment,
        bytes: ByteArray
    ) {
        ensureGeneration(token)
        if (!workCoordinator.commitIfCurrent(token) {
                segments[segment.mediaId] = segment
                audioCache[segment.mediaId] = bytes
            }
        ) {
            throw CancellationException("Stale MiMo cache write")
        }
    }

    private suspend fun recordPrefetchFailure(
        token: MiMoWorkToken,
        error: MiMoTtsException
    ) {
        ensureGeneration(token)
        if (!workCoordinator.commitIfCurrent(token) { prefetchFailure.record(token, error) }) {
            throw CancellationException("Stale MiMo prefetch failure")
        }
    }

    private fun appendMediaItem(segment: MiMoSegment) {
        if (!queuedMediaIds.add(segment.mediaId)) return
        exoPlayer.addMediaItem(
            MediaItem.Builder()
                .setMediaId(segment.mediaId)
                .setUri("memory://mimo/${segment.mediaId}".toUri())
                .build()
        )
        preparePlayerOnce()
    }

    private fun preparePlayerOnce() {
        if (playerPrepared || exoPlayer.playbackState != Player.STATE_IDLE) return
        playerPrepared = true
        exoPlayer.prepare()
    }

    private fun clearPlayer() {
        progressJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        activeMediaId = null
        queuedMediaIds.clear()
        playerPrepared = false
    }

    private fun advanceAfterPlayback(): Boolean {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        return if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            true
        } else {
            automaticNextChapter()
            false
        }
    }

    private fun automaticNextChapter() {
        val bookIdentity = ReadBook.aloudBookIdentity()
        workCoordinator.invalidate(client::cancelCurrent)
        currentWorkToken = null
        prefetchFailure.clear()
        playbackRecovery.clear()
        clearPlayer()
        super.nextChapter()
        MiMoCacheRetention.retainChapter(
            audioCache,
            segments,
            bookIdentity,
            aloudChapterIndex
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val nextId = mediaItem?.mediaId
        val previous = activeMediaId
        activeMediaId = nextId
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (previous != null && previous != nextId) releaseMedia(previous)
        advanceAfterPlayback()
        updateProgressLoop()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty) {
            preparePlayerOnce()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            val mediaId = activeMediaId
            if (mediaId != null) {
                playbackRecovery.takeSeekPosition(mediaId, playbackGeneration)?.let(exoPlayer::seekTo)
            }
            if (!pause) {
                exoPlayer.play()
                updateProgressLoop()
            }
        }
        if (playbackState == Player.STATE_ENDED) {
            val endedId = activeMediaId ?: return
            releaseMedia(endedId)
            activeMediaId = null
            val hasMoreInCurrentChapter = advanceAfterPlayback()
            if (hasMoreInCurrentChapter) {
                startSynthesis(clearPlayer = true, clearAudio = false)
            }
        }
    }

    private fun updateProgressLoop() {
        progressJob?.cancel()
        val segment = activeMediaId?.let(segments::get) ?: return
        val chapter = textChapter ?: return
        val duration = exoPlayer.duration
        if (duration <= 0 || segment.text.isEmpty()) return
        progressJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + segment.leadingOffset + 1)
            val speed = MiMoPlaybackMath.playbackSpeed(AppConfig.speechRatePlay)
            val delayMs = MiMoPlaybackMath.characterDelayMillis(duration, segment.text.length, speed)
            val start = (segment.text.length * exoPlayer.currentPosition / duration).toInt()
            for (offset in start until segment.text.length) {
                val originalOffset = segment.leadingOffset + offset
                if (pageIndex + 1 < chapter.pageSize &&
                    readAloudNumber + originalOffset > chapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    upTtsProgress(readAloudNumber + originalOffset)
                    ReadBook.moveToNextPage()
                }
                delay(delayMs)
            }
        }
    }

    private fun releaseMedia(mediaId: String) {
        audioCache.remove(mediaId)
        segments.remove(mediaId)
        playbackRecovery.release(mediaId, playbackGeneration)
    }

    private fun cancelSynthesis(clearAudio: Boolean) {
        workCoordinator.invalidate(client::cancelCurrent)
        currentWorkToken = null
        prefetchFailure.clear()
        playbackRecovery.clear()
        if (clearAudio) {
            audioCache.clear()
            segments.clear()
        }
    }

    private fun messageFor(error: MiMoTtsException): Int = when (error) {
        is MiMoTtsException.Authentication -> R.string.mimo_tts_auth_error
        is MiMoTtsException.BadRequest -> R.string.mimo_tts_bad_request
        is MiMoTtsException.RateLimited -> R.string.mimo_tts_rate_limited
        is MiMoTtsException.Network,
        is MiMoTtsException.Server -> R.string.mimo_tts_network_error
        is MiMoTtsException.InvalidResponse -> R.string.mimo_tts_response_error
    }

    private fun pauseWithMessage(messageRes: Int, error: MiMoTtsException?) {
        toastOnUi(messageRes)
        error?.let {
            AppLog.put(
                "MiMo TTS failed type=${it::class.simpleName} " +
                    "requestId=${it.requestId} code=${it.errorCode}"
            )
        }
        pauseReadAloud()
    }

    override fun onPlayerError(error: PlaybackException) {
        val mediaId = activeMediaId
        if (mediaId == null) {
            pausePlaybackError(error)
            return
        }
        when (val decision = playbackRecovery.onError(
            mediaId,
            exoPlayer.currentPosition,
            playbackGeneration
        )) {
            is MiMoRecoveryDecision.Reload -> {
                audioCache.remove(mediaId)
                val token = startSynthesis(clearPlayer = true, clearAudio = false)
                if (token == null) {
                    pausePlaybackError(error)
                } else {
                    playbackRecovery.carryReload(
                        mediaId,
                        decision.positionMillis,
                        decision.generation,
                        token.generation
                    )
                }
            }

            is MiMoRecoveryDecision.Pause -> pausePlaybackError(error)
        }
    }

    private fun pausePlaybackError(error: PlaybackException) {
        toastOnUi(R.string.mimo_tts_playback_error)
        AppLog.put("MiMo playback failed code=${error.errorCode}")
        pauseReadAloud()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MiMoTtsContract.ACTION_CONFIG_CHANGED) {
            cancelSynthesis(clearAudio = true)
            startSynthesis(clearPlayer = true, clearAudio = true)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? =
        servicePendingIntent<TTSMiMoAloudService>(actionStr)
}
