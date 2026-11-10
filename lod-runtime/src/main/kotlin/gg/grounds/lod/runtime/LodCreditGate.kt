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
) {
    private class Credit(val lease: LodLease<LodPayloadGrant>, var remaining: Int)

    private val players = players.toSet()
    private val credits = HashMap<UUID, Credit>()
    private var closed = false
    private val opened = nanos()
    private val retryAfter = HashMap<UUID, Long>()
    private val denials = HashMap<UUID, Int>()
    private val blocked = HashSet<UUID>()

    init {
        require(players.size in 1..5)
    }

    @Synchronized
    fun reserve(player: UUID, bytes: Int): LodSendPermit? {
        require(bytes in 1..262144)
        if (closed || player !in players || player in blocked) return null
        if (nanos() - opened >= 1800000000000L) {
            closed = true
            credits.clear()
            return null
        }
        val at = nanos()
        var credit = credits[player]
        if (credit == null || credit.remaining < bytes || credit.lease.remaining(at) == 0L) {
            if (retryAfter[player]?.let { at - it < 0 } == true) return null
            // Discard old credit before the request. Failures can never revive it.
            credits.remove(player)
            credit =
                try {
                    val lease = control.payload(player, 262144)
                    lease.value.validate()
                    require(lease.value.bytes == 262144)
                    if (lease.remaining(nanos()) == 0L) throw LodRateLimited()
                    Credit(lease, lease.value.bytes).also { credits[player] = it }
                } catch (_: LodRateLimited) {
                    rateLimited(player)
                    return null
                } catch (_: Exception) {
                    // A service/parse/transport failure disables the optional pilot. No polling
                    // loop can outlive a run or bypass successful-debit budgets with 503s.
                    closed = true
                    credits.clear()
                    return null
                }
        }
        if (credit.lease.remaining(nanos()) == 0L) {
            rateLimited(player)
            return null
        }
        denials.remove(player)
        retryAfter.remove(player)

        credit.remaining -= bytes // Enqueue/drop/disconnect never refunds credits.
        return LodSendPermit(credit.lease, bytes)
    }

    private fun rateLimited(player: UUID) {
        val count = (denials[player] ?: 0) + 1
        denials[player] = count
        if (count >= 8) blocked.add(player) else retryAfter[player] = nanos() + 1000000000L
    }
}
