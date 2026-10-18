package com.retrsoft.bilisponsorskip

/** One seek attempt per traversal, including when the player silently ignores/clamps a seek. */
internal class SegmentSkipGuard {
    private data class Attempt(val uuid: String, val startMs: Int, val endMs: Int)
    private val attempts = mutableSetOf<Attempt>()

    @Synchronized
    fun observe(positionMs: Int) {
        attempts.removeAll { positionMs < it.startMs || positionMs >= it.endMs }
    }

    @Synchronized
    fun tryAcquire(uuid: String, startMs: Int, endMs: Int): Boolean =
        attempts.add(Attempt(uuid, startMs, endMs))

    @Synchronized
    fun clear() = attempts.clear()
}
