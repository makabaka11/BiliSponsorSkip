package com.retrsoft.bilisponsorskip

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SkipController(
    private val client: SponsorBlockClient = SponsorBlockClient(),
    private val settings: SettingsRepository,
    private val localStatsStore: LocalSkipStatsStore,
) {
    data class VideoKey(val bvid: String, val cid: String)

    data class UiSnapshot(
        val video: VideoKey?,
        val segments: List<SponsorBlockClient.Segment>,
        val allSegments: List<SponsorBlockClient.Segment>,
        val durationMs: Int,
        val currentPositionMs: Int,
        val showTitleLabel: Boolean,
        val showProgressMarkers: Boolean,
        val showSubmissionButton: Boolean,
        val userId: String,
    )

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BiliSponsorSkip-network").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeVideo = AtomicReference<VideoKey?>()
    private val segmentCache = ConcurrentHashMap<VideoKey, List<SponsorBlockClient.Segment>>()
    private val loading = ConcurrentHashMap.newKeySet<VideoKey>()
    private val retryAfter = ConcurrentHashMap<VideoKey, Long>()
    private val notifiedVideos = ConcurrentHashMap.newKeySet<VideoKey>()
    private val diagnosticWatchdogs = ConcurrentHashMap.newKeySet<VideoKey>()
    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()
    private val reportedSettings = ConcurrentHashMap.newKeySet<String>()
    private val uiStateListeners = CopyOnWriteArraySet<() -> Unit>()
    private val uiNotificationScheduled = AtomicBoolean(false)

    @Volatile
    private var playerHookReady = false

    @Volatile
    private var lastPositionAt = 0L

    @Volatile
    private var playerRef: WeakReference<Any>? = null

    @Volatile
    private var seekMethod: Method? = null

    @Volatile
    private var lastCheckAt = 0L

    @Volatile
    private var lastCheckedPositionMs: Int? = null

    @Volatile
    private var suppressUntil = 0L

    @Volatile
    private var durationMs = 0

    @Volatile
    private var currentPositionMs = 0

    @Volatile
    private var playerNotice: InteractivePlayerNotice? = null

    @Volatile
    private var activeManualNoticeKey: String? = null

    init {
        settings.onSettingsChanged = ::notifyUiStateChanged
    }

    fun addUiStateListener(listener: () -> Unit) {
        uiStateListeners += listener
        mainHandler.post(listener)
    }

    private fun notifyUiStateChanged() {
        if (!uiNotificationScheduled.compareAndSet(false, true)) return
        mainHandler.post {
            uiNotificationScheduled.set(false)
            uiStateListeners.forEach { listener ->
                runCatching(listener).onFailure { Log.e("UI state listener failed", it) }
            }
        }
    }

    fun updateVideo(bvid: String, cid: String) {
        if (!bvid.startsWith("BV") || cid.isBlank() || cid == "0") return
        val preferences = settings.refresh()
        val settingsSignature = "submission=${preferences.showSubmissionButton}; userIdConfigured=${Identity.isValid(preferences.userId)}"
        if (reportedSettings.add(settingsSignature)) Log.d("settings refreshed: $settingsSignature")
        if (!preferences.enabled) return
        val next = VideoKey(bvid.trim(), cid.trim())
        if (activeVideo.getAndSet(next) != next) {
            durationMs = 0
            currentPositionMs = 0
            lastCheckAt = 0L
            lastCheckedPositionMs = null
            suppressUntil = 0L
            clearManualSkipNotice()
            Log.d("video changed: ${next.bvid}+${next.cid}")
            notifyUiStateChanged()
        }
        val cached = segmentCache[next]
        if (cached == null) ensureLoaded(next) else notifySegmentsFound(next, cached, preferences)
    }

    fun bindPlayer(player: Any, fallbackSeekMethod: Method) {
        playerRef = WeakReference(player)
        seekMethod = fallbackSeekMethod
    }

    fun bindPlayerNotice(notice: InteractivePlayerNotice) {
        playerNotice = notice
    }

    fun updateDuration(valueMs: Int) {
        if (valueMs > 0 && durationMs != valueMs) {
            durationMs = valueMs
            notifyUiStateChanged()
        }
    }

    fun uiSnapshot(): UiSnapshot {
        val video = activeVideo.get()
        val preferences = settings.current
        val segments = if (video != null && preferences.enabled) {
            segmentCache[video]?.activeBy(preferences).orEmpty()
        } else {
            emptyList()
        }
        return UiSnapshot(
            video = video,
            segments = segments,
            allSegments = if (video != null && preferences.enabled) segmentCache[video].orEmpty() else emptyList(),
            durationMs = durationMs,
            currentPositionMs = currentPositionMs,
            showTitleLabel = preferences.enabled && preferences.showTitleLabel,
            showProgressMarkers = preferences.enabled && preferences.showProgressMarkers,
            showSubmissionButton = preferences.enabled && preferences.showSubmissionButton,
            userId = preferences.userId,
        )
    }

    fun submitSegment(
        category: String,
        startMs: Int,
        endMs: Int,
        callback: (SponsorBlockClient.MutationResult) -> Unit,
    ) {
        val key = activeVideo.get()
        val preferences = settings.refresh()
        val validationError = when {
            key == null -> "当前视频信息尚未就绪"
            !Identity.isValid(preferences.userId) -> "请先在模块设置中填写至少 32 位的提交者 ID"
            category !in SettingsContract.CATEGORIES -> "片段类别无效"
            startMs < 0 || endMs <= startMs -> "片段起止时间无效"
            else -> null
        }
        if (validationError != null || key == null) {
            mainHandler.post { callback(SponsorBlockClient.MutationResult(false, -1, validationError.orEmpty())) }
            return
        }
        executor.execute {
            val result = client.submitSegment(
                bvid = key.bvid,
                cid = key.cid,
                userId = preferences.userId,
                category = category,
                startMs = startMs,
                endMs = endMs,
                durationMs = durationMs,
            )
            if (result.successful) reloadSegments(key)
            mainHandler.post { callback(result) }
        }
    }

    fun vote(
        segment: SponsorBlockClient.Segment,
        type: Int,
        callback: (SponsorBlockClient.MutationResult) -> Unit,
    ) {
        val key = activeVideo.get()
        val preferences = settings.refresh()
        val validationError = when {
            key == null -> "当前视频信息尚未就绪"
            !Identity.isValid(preferences.userId) -> "请先在模块设置中填写至少 32 位的提交者 ID"
            segment.uuid.isBlank() -> "该片段没有可投票的 ID"
            type !in 0..1 -> "投票类型无效"
            else -> null
        }
        if (validationError != null || key == null) {
            mainHandler.post { callback(SponsorBlockClient.MutationResult(false, -1, validationError.orEmpty())) }
            return
        }
        executor.execute {
            val result = client.vote(segment.uuid, preferences.userId, type)
            if (result.successful) {
                segmentCache.computeIfPresent(key) { _, segments ->
                    segments.map {
                        if (it.uuid == segment.uuid) it.copy(votes = it.votes + if (type == 1) 1 else -1) else it
                    }
                }
                notifyUiStateChanged()
            }
            mainHandler.post { callback(result) }
        }
    }

    fun refreshSegments(callback: (SponsorBlockClient.Result) -> Unit) {
        val key = activeVideo.get()
        if (key == null) {
            mainHandler.post { callback(SponsorBlockClient.Result.Failure(false, "当前视频信息尚未就绪")) }
            return
        }
        executor.execute {
            val result = client.getSponsorSegments(key.bvid, key.cid)
            if (result is SponsorBlockClient.Result.Success) {
                segmentCache[key] = result.segments
                retryAfter.remove(key)
                Log.d("refreshed ${result.segments.size} special segment(s) for ${key.bvid}+${key.cid}")
                notifyUiStateChanged()
            }
            mainHandler.post {
                if (activeVideo.get() == key) callback(result)
                else callback(SponsorBlockClient.Result.Failure(false, "刷新期间视频已切换"))
            }
        }
    }

    fun onPlayerHookInstalled(description: String) {
        playerHookReady = true
        Log.d("player hook installed: $description")
    }

    fun reportPlayerFailure(stage: String, error: Throwable? = null, detail: String? = null) {
        val message = buildString {
            append(detail ?: error?.message ?: "未知错误")
            error?.let {
                if (detail != null && !it.message.isNullOrBlank()) append(": ${it.message}")
            }
        }
        Log.e("automatic skip failure [$stage]: $message", error)
        val deduplicationKey = "$stage:${error?.javaClass?.name}:$message"
        if (reportedFailures.add(deduplicationKey)) {
            showToast("自动跳过失败[$stage]：${diagnosticText(error, message)}", Toast.LENGTH_LONG)
        }
    }

    fun onPosition(positionMs: Int) {
        if (positionMs < 0) return
        currentPositionMs = positionMs
        val now = System.currentTimeMillis()
        lastPositionAt = now
        if (now < suppressUntil || now - lastCheckAt < CHECK_INTERVAL_MS) return
        val previousCheckedPositionMs = lastCheckedPositionMs
        val elapsedSincePreviousCheckMs = if (lastCheckAt > 0L) now - lastCheckAt else null
        lastCheckAt = now
        lastCheckedPositionMs = positionMs

        val key = activeVideo.get() ?: return
        val preferences = settings.current
        if (!preferences.enabled) return
        val segments = segmentCache[key]?.activeBy(preferences) ?: run {
            ensureLoaded(key)
            return
        }
        val segment = segments.firstOrNull {
            preferences.categoryMode(it.category) == CategoryMode.AUTO_SKIP &&
                positionMs >= it.startMs && positionMs < it.endMs
        }
        val enteredContinuously = segmentEnteredContinuously(
            previousPositionMs = previousCheckedPositionMs,
            currentPositionMs = positionMs,
            segmentStartMs = segment?.startMs,
            elapsedMs = elapsedSincePreviousCheckMs,
        )
        if (segment == null) {
            val manualSegment = segments.firstOrNull {
                preferences.categoryMode(it.category) == CategoryMode.MANUAL_SKIP &&
                    positionMs >= it.startMs && positionMs < it.endMs
            }
            val manualSegmentEnteredContinuously = segmentEnteredContinuously(
                previousPositionMs = previousCheckedPositionMs,
                currentPositionMs = positionMs,
                segmentStartMs = manualSegment?.startMs,
                elapsedMs = elapsedSincePreviousCheckMs,
            )
            val noticeAlreadyActive = manualSegment != null &&
                activeManualNoticeKey == manualNoticeKey(key, manualSegment)
            if (manualSegment == null || !shouldPresentManualSkipNotice(
                    skipOnSeek = preferences.skipOnSeek,
                    positionMs = positionMs,
                    segmentStartMs = manualSegment.startMs,
                    noticeAlreadyActive = noticeAlreadyActive,
                    enteredContinuously = manualSegmentEnteredContinuously,
                )
            ) {
                clearManualSkipNotice()
            } else {
                showManualSkipNotice(key, manualSegment)
            }
            return
        }
        clearManualSkipNotice()
        if (!shouldAutoSkipSegment(
                skipOnSeek = preferences.skipOnSeek,
                positionMs = positionMs,
                segmentStartMs = segment.startMs,
                enteredContinuously = enteredContinuously,
            )
        ) return
        val player = playerRef?.get() ?: run {
            reportPlayerFailure("播放器实例", detail = "已进入片段，但播放器引用为空")
            return
        }

        if (seek(player, segment.endMs, segment)) {
            suppressUntil = now + SEEK_COOLDOWN_MS
            Log.d("skipped ${segment.startMs}..${segment.endMs} (${segment.uuid})")
            recordLocalSkip((segment.endMs - positionMs).coerceAtLeast(0))
            if (preferences.notifySkipped) {
                val durationSeconds = ((segment.endMs - segment.startMs) / 1000.0).toInt().coerceAtLeast(1)
                showToast("已跳过：${segment.category.categoryLabel()}（约 ${durationSeconds} 秒）")
            }
        }
    }

    private fun showManualSkipNotice(
        key: VideoKey,
        segment: SponsorBlockClient.Segment,
    ) {
        val noticeKey = manualNoticeKey(key, segment)
        if (activeManualNoticeKey == noticeKey) return
        clearManualSkipNotice()
        activeManualNoticeKey = noticeKey
        val shown = playerNotice?.showAction(
            message = "${segment.category.categoryLabel()}片段",
            actionText = "跳过",
            // PlayerToast measures duration using wall-clock time, while segment bounds use
            // playback time. Its 100000 ms sentinel disables native auto-expiry; the controller
            // still dismisses the notice as soon as playback leaves this segment.
            durationMs = PERSISTENT_PLAYER_NOTICE_DURATION_MS,
            onAction = { skipManualSegment(key, segment) },
            onDismiss = {},
        ) == true
        if (shown) {
            Log.d(
                "manual skip notice shown for ${segment.startMs}..${segment.endMs}; " +
                    "lifetime=until-segment-exit",
            )
        }
        if (!shown) {
            Log.e("interactive player notice unavailable for manual segment: ${segment.category}")
            showToast("检测到可手动跳过的${segment.category.categoryLabel()}片段")
        }
    }

    private fun skipManualSegment(key: VideoKey, segment: SponsorBlockClient.Segment) {
        mainHandler.post {
            if (activeVideo.get() != key || currentPositionMs !in segment.startMs until segment.endMs) return@post
            val player = playerRef?.get() ?: run {
                reportPlayerFailure("播放器实例", detail = "手动跳过时播放器引用为空")
                return@post
            }
            val position = currentPositionMs
            if (seek(player, segment.endMs, segment)) {
                suppressUntil = System.currentTimeMillis() + SEEK_COOLDOWN_MS
                recordLocalSkip((segment.endMs - position).coerceAtLeast(0))
                Log.d("manually skipped ${segment.startMs}..${segment.endMs} (${segment.uuid})")
                if (settings.current.notifySkipped) {
                    val durationSeconds = ((segment.endMs - segment.startMs) / 1000.0).toInt().coerceAtLeast(1)
                    showToast("已手动跳过：${segment.category.categoryLabel()}（约 ${durationSeconds} 秒）")
                }
            }
            activeManualNoticeKey = null
        }
    }

    private fun clearManualSkipNotice() {
        if (activeManualNoticeKey == null) return
        activeManualNoticeKey = null
        playerNotice?.dismiss()
    }

    private fun manualNoticeKey(key: VideoKey, segment: SponsorBlockClient.Segment): String =
        "${key.bvid}:${key.cid}:${segment.uuid}:${segment.startMs}:${segment.endMs}"

    private fun ensureLoaded(key: VideoKey) {
        if (System.currentTimeMillis() < (retryAfter[key] ?: 0L)) return
        if (segmentCache.containsKey(key) || !loading.add(key)) return
        executor.execute {
            try {
                var attempt = 0
                var result: SponsorBlockClient.Result
                do {
                    attempt++
                    result = client.getSponsorSegments(key.bvid, key.cid)
                    if (result is SponsorBlockClient.Result.Failure && result.retryable && attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                    }
                } while (result is SponsorBlockClient.Result.Failure && result.retryable && attempt < MAX_ATTEMPTS)

                when (result) {
                    is SponsorBlockClient.Result.Success -> {
                        retryAfter.remove(key)
                        segmentCache[key] = result.segments
                        Log.d("loaded ${result.segments.size} special segment(s) for ${key.bvid}+${key.cid}")
                        notifySegmentsFound(key, result.segments, settings.current)
                        notifyUiStateChanged()
                    }

                    is SponsorBlockClient.Result.Failure -> {
                        retryAfter[key] = System.currentTimeMillis() +
                            if (result.retryable) TRANSIENT_FAILURE_COOLDOWN_MS else PERMANENT_FAILURE_COOLDOWN_MS
                        Log.e("segment request failed for ${key.bvid}+${key.cid}: ${result.message}")
                        if (key == activeVideo.get() && settings.current.notifyFetchFailure) {
                            showToast("特殊片段获取失败，请稍后重试")
                        }
                    }
                }
            } catch (error: Throwable) {
                retryAfter[key] = System.currentTimeMillis() + TRANSIENT_FAILURE_COOLDOWN_MS
                Log.e("unexpected segment request error", error)
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun reloadSegments(key: VideoKey) {
        segmentCache.remove(key)
        retryAfter.remove(key)
        notifyUiStateChanged()
        ensureLoaded(key)
    }

    private fun seek(player: Any, positionMs: Int, segment: SponsorBlockClient.Segment): Boolean {
        val publicSeek = player.javaClass.methods.firstOrNull { method ->
            method.name == "seekTo" &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType!!))
        }
        val selectedMethod = when {
            publicSeek != null -> publicSeek
            seekMethod != null -> seekMethod
            else -> null
        }
        return runCatching {
            when {
                publicSeek != null -> publicSeek.invokeUnwrapped(player, positionMs)
                seekMethod?.parameterTypes?.contentEquals(arrayOf(Int::class.javaPrimitiveType!!)) == true ->
                    seekMethod!!.invokeUnwrapped(player, positionMs)
                seekMethod?.parameterTypes?.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                ) == true -> seekMethod!!.invokeUnwrapped(player, positionMs, false)
                else -> error("no compatible seek method")
            }
        }.onFailure { error ->
            val signature = selectedMethod?.let { "${it.declaringClass.name}.${it.name}${it.parameterTypes.contentToString()}" }
                ?: "未找到方法"
            reportPlayerFailure(
                stage = "seek",
                error = error,
                detail = "$signature，目标=${positionMs}ms，分类=${segment.category}",
            )
        }.isSuccess
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val application = AndroidAppHelper.currentApplication() ?: return
        mainHandler.post { Toast.makeText(application, message, duration).show() }
    }

    private fun recordLocalSkip(savedDurationMs: Int) {
        if (savedDurationMs <= 0) return
        val snapshot = localStatsStore.record(savedDurationMs.toLong()) ?: return
        sendLocalStatsSnapshot(snapshot)
    }

    fun syncLocalStats() {
        localStatsStore.currentSnapshot()?.let(::sendLocalStatsSnapshot)
    }

    private fun sendLocalStatsSnapshot(snapshot: LocalSkipStatsSnapshot) {
        val application = AndroidAppHelper.currentApplication() ?: return
        runCatching {
            val intent = Intent(SettingsContract.ACTION_RECORD_LOCAL_SKIP)
                .setComponent(ComponentName(
                    SettingsContract.MODULE_PACKAGE,
                    "${SettingsContract.MODULE_PACKAGE}.SkipStatsReceiver",
                ))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(SettingsContract.EXTRA_STATS_PACKAGE, snapshot.packageName)
                .putExtra(SettingsContract.EXTRA_STATS_PROCESS, snapshot.processName)
                .putExtra(SettingsContract.EXTRA_STATS_GENERATION, snapshot.generation)
                .putExtra(SettingsContract.EXTRA_STATS_COUNT, snapshot.count)
                .putExtra(SettingsContract.EXTRA_STATS_SAVED_MS, snapshot.savedMs)
            application.sendBroadcast(
                intent,
            )
            Log.d(
                "local skip statistics snapshot sent: source=${snapshot.packageName}/" +
                    "${snapshot.processName}, count=${snapshot.count}, savedMs=${snapshot.savedMs}",
            )
        }.onFailure { Log.e("failed to sync local skip statistics", it) }
    }

    private fun notifySegmentsFound(
        key: VideoKey,
        segments: List<SponsorBlockClient.Segment>,
        preferences: SettingsSnapshot,
    ) {
        val selected = segments.activeBy(preferences)
        if (key == activeVideo.get() && preferences.enabled && selected.isNotEmpty()) {
            schedulePlayerDiagnostic(key)
        }
        if (
            key != activeVideo.get() ||
            !preferences.enabled ||
            !preferences.notifyFound ||
            selected.isEmpty() ||
            !notifiedVideos.add(key)
        ) return
        val labels = selected.map { it.category.categoryLabel() }.distinct().joinToString("、")
        showToast("检测到 ${selected.size} 个特殊片段：$labels")
    }

    private fun schedulePlayerDiagnostic(key: VideoKey) {
        if (!diagnosticWatchdogs.add(key)) return
        mainHandler.postDelayed({
            if (key != activeVideo.get()) return@postDelayed
            when {
                !playerHookReady -> reportPlayerFailure(
                    "Hook",
                    detail = "检测到片段 ${DIAGNOSTIC_DELAY_MS / 1000} 秒后播放器 Hook 仍未安装",
                )
                playerRef?.get() == null -> reportPlayerFailure(
                    "播放器实例",
                    detail = "Hook 已安装，但状态回调/构造回调未提供播放器实例",
                )
                lastPositionAt == 0L -> reportPlayerFailure(
                    "播放进度",
                    detail = "已绑定播放器，但 getCurrentPosition 尚未返回有效结果",
                )
            }
        }, DIAGNOSTIC_DELAY_MS)
    }

    private fun diagnosticText(error: Throwable?, message: String): String {
        val type = error?.javaClass?.simpleName?.takeIf(String::isNotBlank)
        val text = if (type == null) message else "$type: $message"
        return text.replace('\n', ' ').take(MAX_DIAGNOSTIC_TEXT_LENGTH)
    }

    private fun List<SponsorBlockClient.Segment>.activeBy(
        preferences: SettingsSnapshot,
    ): List<SponsorBlockClient.Segment> {
        val minimumMs = preferences.minDurationSeconds * 1000
        return filter { segment ->
            preferences.categoryMode(segment.category) != CategoryMode.DISABLED &&
                segment.endMs - segment.startMs >= minimumMs
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 750L
        const val SEEK_COOLDOWN_MS = 3_000L
        const val SEGMENT_START_WINDOW_MS = 2_000
        const val PERSISTENT_PLAYER_NOTICE_DURATION_MS = 100_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        const val TRANSIENT_FAILURE_COOLDOWN_MS = 30_000L
        const val PERMANENT_FAILURE_COOLDOWN_MS = 5 * 60_000L
        const val DIAGNOSTIC_DELAY_MS = 8_000L
        const val MAX_DIAGNOSTIC_TEXT_LENGTH = 180
    }
}

internal fun shouldPresentManualSkipNotice(
    skipOnSeek: Boolean,
    positionMs: Int,
    segmentStartMs: Int,
    noticeAlreadyActive: Boolean,
    enteredContinuously: Boolean = false,
): Boolean = noticeAlreadyActive || skipOnSeek || enteredContinuously ||
    positionMs <= segmentStartMs + 2_000

internal fun shouldAutoSkipSegment(
    skipOnSeek: Boolean,
    positionMs: Int,
    segmentStartMs: Int,
    enteredContinuously: Boolean,
): Boolean = skipOnSeek || enteredContinuously || positionMs <= segmentStartMs + 2_000

internal fun segmentEnteredContinuously(
    previousPositionMs: Int?,
    currentPositionMs: Int,
    segmentStartMs: Int?,
    elapsedMs: Long?,
): Boolean {
    if (
        previousPositionMs == null ||
        segmentStartMs == null ||
        elapsedMs == null ||
        elapsedMs !in 1..MAX_CONTINUOUS_SAMPLE_GAP_MS ||
        previousPositionMs >= segmentStartMs ||
        currentPositionMs < segmentStartMs
    ) return false

    val advancedMs = currentPositionMs.toLong() - previousPositionMs
    val maximumContinuousAdvanceMs =
        elapsedMs * MAX_CONTINUOUS_PLAYBACK_RATE + CONTINUOUS_PLAYBACK_TOLERANCE_MS
    return advancedMs in 0..maximumContinuousAdvanceMs
}

// Current pink and white clients select at most 3x for long-press playback.
// Keep a small timing allowance without treating an arbitrary seek as continuous playback.
private const val MAX_CONTINUOUS_PLAYBACK_RATE = 3L
private const val CONTINUOUS_PLAYBACK_TOLERANCE_MS = 750L
private const val MAX_CONTINUOUS_SAMPLE_GAP_MS = 5_000L
