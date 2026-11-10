package gg.grounds.lod.runtime

import gg.grounds.lod.format.LodPayloadGrant
import java.util.UUID

class LodSendPermit
internal constructor(private val lease: LodLease<LodPayloadGrant>, val reservedBytes: Int) {
    fun valid(nanos: Long) = lease.remaining(nanos) > 0
}

/** Shared by every local peer, including reconnects. Only the background sender calls reserve. */
class LodCreditGate(
    private val control: LodControl,
    players: Set<UUID>,
    private val nanos: () -> Long = System::nanoTime,
    private val entitled: Boolean = false,
) {
    private class Credit(val lease: LodLease<LodPayloadGrant>, var remaining: Int)

    private val players = players.toSet()
    private val credits = HashMap<UUID, Credit>()
    private val inFlight = HashMap<UUID, Any>()
    private var closed = false
    private val recovery = LodRecovery(nanos)
    private val opened = nanos()
    private val retryAfter = HashMap<UUID, Long>()
    private val denials = HashMap<UUID, Int>()
    private val blocked = HashSet<UUID>()

    init {
        require(if (entitled) players.isEmpty() else players.size in 1..5)
    }

    fun reserve(player: UUID, bytes: Int): LodSendPermit? {
        require(bytes in 1..262144)
        val token = Any()
        synchronized(this) {
            if (closed || (!entitled && player !in players) || player in blocked) return null
            if (entitled && !recovery.ready()) return null
            if (entitled && player !in tracked() && tracked().size >= 64) return null
            if (!entitled && nanos() - opened >= 1800000000000L) {
                closed = true
                credits.clear()
                return null
            }
            val at = nanos()
            val credit = credits[player]
            if (credit != null && credit.remaining >= bytes && credit.lease.remaining(at) > 0) {
                return spend(player, credit, bytes)
            }
            if (player in inFlight || retryAfter[player]?.let { at - it < 0 } == true) return null
            credits.remove(player)
            inFlight[player] = token
        }
        // The control call must not block disconnect/lifecycle callbacks. The token prevents a
        // late grant from repopulating state after disconnect or a replacement connection.
        val credit =
            try {
                val lease = control.payload(player, 262144)
                lease.value.validate()
                require(lease.value.bytes == 262144)
                if (lease.remaining(nanos()) == 0L) throw LodRateLimited()
                Credit(lease, lease.value.bytes)
            } catch (error: Exception) {
                synchronized(this) {
                    val current = inFlight[player] === token
                    if (current) inFlight.remove(player)
                    if (error is LodRateLimited) {
                        if (current) rateLimited(player)
                    } else if (entitled) {
                        // Failed attempts consume the lifetime envelope even across disconnect.
                        recovery.failed(error)
                        if (recovery.closed) {
                            closed = true
                            credits.clear()
                        }
                    } else {
                        // Finite pilots preserve their original no-retry failure semantics.
                        closed = true
                        credits.clear()
                    }
                }
                return null
            }
        return synchronized(this) {
            if (inFlight[player] !== token) return@synchronized null
            inFlight.remove(player)
            if (closed) return@synchronized null
            if (credit.lease.remaining(nanos()) == 0L) {
                rateLimited(player)
                return@synchronized null
            }
            credits[player] = credit
            spend(player, credit, bytes)
        }
    }

    private fun spend(player: UUID, credit: Credit, bytes: Int): LodSendPermit {
        denials.remove(player)
        retryAfter.remove(player)
        credit.remaining -= bytes // Enqueue/drop/disconnect never refunds credits.
        return LodSendPermit(credit.lease, bytes)
    }

    private fun tracked(): Set<UUID> =
        credits.keys + inFlight.keys + retryAfter.keys + denials.keys + blocked

    @Synchronized internal fun trackedPlayers() = tracked().size

    @Synchronized
    internal fun remove(player: UUID) {
        if (!entitled) return
        credits.remove(player)
        inFlight.remove(player)
        retryAfter.remove(player)
        denials.remove(player)
        blocked.remove(player)
    }

    private fun rateLimited(player: UUID) {
        val count = (denials[player] ?: 0) + 1
        denials[player] = count
        if (count >= 8) blocked.add(player) else retryAfter[player] = nanos() + 1000000000L
    }
}
