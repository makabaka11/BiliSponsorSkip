package com.retrsoft.bilisponsorskip

import android.app.AndroidAppHelper
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal class SkipController(
    private val client: SponsorBlockClient = SponsorBlockClient(),
    private val settings: SettingsRepository,
) {
    data class VideoKey(val bvid: String, val cid: String)

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
    private var suppressUntil = 0L

    fun updateVideo(bvid: String, cid: String) {
        if (!bvid.startsWith("BV") || cid.isBlank() || cid == "0") return
        val preferences = settings.refresh()
        if (!preferences.enabled || preferences.enabledCategories.isEmpty()) return
        val next = VideoKey(bvid.trim(), cid.trim())
        if (activeVideo.getAndSet(next) != next) {
            Log.d("video changed: ${next.bvid}+${next.cid}")
        }
        val cached = segmentCache[next]
        if (cached == null) ensureLoaded(next) else notifySegmentsFound(next, cached, preferences)
    }

    fun bindPlayer(player: Any, fallbackSeekMethod: Method) {
        playerRef = WeakReference(player)
        seekMethod = fallbackSeekMethod
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
        val now = System.currentTimeMillis()
        lastPositionAt = now
        if (now < suppressUntil || now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now

        val key = activeVideo.get() ?: return
        val preferences = settings.current
        if (!preferences.enabled || !preferences.autoSkip) return
        val segments = segmentCache[key]?.selectedBy(preferences) ?: run {
            ensureLoaded(key)
            return
        }
        val segment = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs } ?: return
        if (!preferences.skipOnSeek && positionMs > segment.startMs + SEGMENT_START_WINDOW_MS) return
        val player = playerRef?.get() ?: run {
            reportPlayerFailure("播放器实例", detail = "已进入片段，但播放器引用为空")
            return
        }

        if (seek(player, segment.endMs, segment)) {
            suppressUntil = now + SEEK_COOLDOWN_MS
            Log.d("skipped ${segment.startMs}..${segment.endMs} (${segment.uuid})")
            if (preferences.notifySkipped) {
                val durationSeconds = ((segment.endMs - segment.startMs) / 1000.0).toInt().coerceAtLeast(1)
                showToast("已跳过：${segment.category.categoryLabel()}（约 ${durationSeconds} 秒）")
            }
        }
    }

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

    private fun notifySegmentsFound(
        key: VideoKey,
        segments: List<SponsorBlockClient.Segment>,
        preferences: SettingsSnapshot,
    ) {
        val selected = segments.selectedBy(preferences)
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

    private fun List<SponsorBlockClient.Segment>.selectedBy(
        preferences: SettingsSnapshot,
    ): List<SponsorBlockClient.Segment> {
        val minimumMs = preferences.minDurationSeconds * 1000
        return filter { segment ->
            segment.category in preferences.enabledCategories && segment.endMs - segment.startMs >= minimumMs
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 750L
        const val SEEK_COOLDOWN_MS = 3_000L
        const val SEGMENT_START_WINDOW_MS = 2_000
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        const val TRANSIENT_FAILURE_COOLDOWN_MS = 30_000L
        const val PERMANENT_FAILURE_COOLDOWN_MS = 5 * 60_000L
        const val DIAGNOSTIC_DELAY_MS = 8_000L
        const val MAX_DIAGNOSTIC_TEXT_LENGTH = 180
    }
}
