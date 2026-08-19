package com.retrsoft.bilisponsorskip

import android.app.Application
import android.content.Context
import java.security.MessageDigest

internal data class LocalSkipStatsSnapshot(
    val packageName: String,
    val processName: String,
    val generation: String,
    val count: Long,
    val savedMs: Long,
)

internal data class LocalSkipStatsDelta(
    val count: Long,
    val savedMs: Long,
)

internal object LocalSkipStatsProtocol {
    fun delta(
        previousCount: Long,
        previousSavedMs: Long,
        snapshot: LocalSkipStatsSnapshot,
    ) = LocalSkipStatsDelta(
        count = (snapshot.count - previousCount).coerceAtLeast(0L),
        savedMs = (snapshot.savedMs - previousSavedMs).coerceAtLeast(0L),
    )

    fun sourceKey(snapshot: LocalSkipStatsSnapshot): String = sha256(
        "${snapshot.packageName}\u0000${snapshot.processName}\u0000${snapshot.generation}",
    ).take(32)

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal class LocalSkipStatsStore(private val application: Application) {
    private val processName = currentProcessName(application)
    private val preferences = application.getSharedPreferences(
        "${PREFERENCES_PREFIX}_${LocalSkipStatsProtocol.sha256(processName).take(16)}",
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    fun record(savedDurationMs: Long): LocalSkipStatsSnapshot? {
        if (savedDurationMs <= 0L) return null
        return synchronized(lock) {
            val generation = preferences.getString(KEY_GENERATION, null)
                ?.takeIf(Identity::isValid)
                ?: Identity.generate()
            val count = preferences.getLong(KEY_COUNT, 0L).coerceAtLeast(0L).saturatedAdd(1L)
            val savedMs = preferences.getLong(KEY_SAVED_MS, 0L)
                .coerceAtLeast(0L)
                .saturatedAdd(savedDurationMs)
            val committed = preferences.edit()
                .putString(KEY_GENERATION, generation)
                .putLong(KEY_COUNT, count)
                .putLong(KEY_SAVED_MS, savedMs)
                .commit()
            if (!committed) {
                Log.e("failed to persist local skip statistics snapshot")
                null
            } else {
                snapshot(generation, count, savedMs)
            }
        }
    }

    fun currentSnapshot(): LocalSkipStatsSnapshot? = synchronized(lock) {
        val generation = preferences.getString(KEY_GENERATION, null)
            ?.takeIf(Identity::isValid)
            ?: return@synchronized null
        val count = preferences.getLong(KEY_COUNT, 0L).coerceAtLeast(0L)
        val savedMs = preferences.getLong(KEY_SAVED_MS, 0L).coerceAtLeast(0L)
        if (count == 0L && savedMs == 0L) null else snapshot(generation, count, savedMs)
    }

    private fun snapshot(generation: String, count: Long, savedMs: Long) = LocalSkipStatsSnapshot(
        packageName = application.packageName,
        processName = processName,
        generation = generation,
        count = count,
        savedMs = savedMs,
    )

    private fun Long.saturatedAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private companion object {
        const val PREFERENCES_PREFIX = "bili_sponsor_skip_local_stats_v2"
        const val KEY_GENERATION = "generation"
        const val KEY_COUNT = "count"
        const val KEY_SAVED_MS = "saved_ms"

        fun currentProcessName(application: Application): String =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName().orEmpty().ifBlank { application.packageName }
            } else {
                runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentProcessName")
                        .invoke(null) as? String
                }.getOrNull().orEmpty().ifBlank { application.packageName }
            }
    }
}
