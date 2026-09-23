package com.retrsoft.bilisponsorskip

import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object PlaybackSpeedPolicy {
    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 4.0f

    fun persistedValue(value: Float): Float? = value.takeIf {
        it.isFinite() && it in MIN_SPEED..MAX_SPEED
    }
}

internal class PlaybackSpeedPersistence(
    application: Application,
    private val settings: SettingsRepository,
) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val applying = ThreadLocal<Boolean>()
    private val applyFailureLogged = AtomicBoolean(false)

    @Volatile
    private var appliedPlayer = WeakReference<Any>(null)

    @Volatile
    private var appliedSpeed: Float? = null

    fun isEnabled(): Boolean = settings.current.persistPlaybackSpeed

    fun onSpeedSet(player: Any, value: Float) {
        if (applying.get() == true || !isEnabled()) return
        val speed = PlaybackSpeedPolicy.persistedValue(value) ?: return
        preferences.edit().putFloat(KEY_SPEED, speed).apply()
        if (appliedPlayer.get() === player) appliedSpeed = speed
        Log.d("playback speed persisted: ${speed}x")
    }

    fun applyToPlayer(player: Any, setter: Method) {
        if (!isEnabled() || !preferences.contains(KEY_SPEED)) return
        val speed = PlaybackSpeedPolicy.persistedValue(preferences.getFloat(KEY_SPEED, 1.0f)) ?: return
        if (appliedPlayer.get() === player && appliedSpeed == speed) return

        synchronized(this) {
            if (appliedPlayer.get() === player && appliedSpeed == speed) return
            appliedPlayer = WeakReference(player)
            appliedSpeed = speed
        }

        runCatching {
            applying.set(true)
            setter.invokeUnwrapped(player, speed)
        }.onSuccess {
            applyFailureLogged.set(false)
            Log.d("persisted playback speed applied: ${speed}x; player=${player.javaClass.name}")
        }.onFailure { error ->
            synchronized(this) {
                if (appliedPlayer.get() === player && appliedSpeed == speed) {
                    appliedPlayer = WeakReference(null)
                    appliedSpeed = null
                }
            }
            if (applyFailureLogged.compareAndSet(false, true)) {
                Log.e("failed to apply persisted playback speed", error)
            }
        }.also {
            applying.remove()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bili_sponsor_skip_playback_speed"
        const val KEY_SPEED = "playback_speed"
    }
}
