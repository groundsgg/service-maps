package gg.grounds.lod.runtime

/** Lifetime failure budget shared across local UUIDs and reconnects; never schedules polling. */
internal class LodRecovery(private val nanos: () -> Long) {
    private var failures = 0
    private var retryAt: Long? = null
    var closed = false
        private set

    fun ready() = !closed && retryAt?.let { nanos() - it >= 0 } != false

    fun failed(error: Exception) {
        failures++
        if (error !is LodUnavailable || error.permanent || failures >= 22) closed = true
        // Covers the backend's 15-minute job deadline plus 30-second readiness poll.
        else retryAt = nanos() + minOf(60L, 1L shl (failures - 1)) * 1000000000L
    }
}

internal class LodAccessDenied : LodUnavailable()
