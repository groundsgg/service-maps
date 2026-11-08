package gg.grounds.lod.runtime

import dev.vox.lss.common.wire.IdentityCodec
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.event.player.PlayerPluginMessageEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.common.PluginMessagePacket

data class LodRuntimeStats(
    val authorizedPayloadBodyBytes: Long,
    val discardedFrames: Long,
    val queuedFrames: Int,
    val closed: Boolean,
)

/** Optional immutable Lobby pilot. Exclusively owns the four outbound LSS20 channels. */
class MinestomLodAdapter
internal constructor(
    private val parent: EventNode<Event>,
    private val instance: Instance,
    private val expected: LodExpectation,
    control: LodControl,
    players: Set<UUID>,
    cacheDirectory: Path,
    autoPump: Boolean,
    nanos: () -> Long,
) : AutoCloseable {
    private val players = players.toSet()
    private val node = EventNode.all("grounds-lod-pilot")
    private val guardian = EventNode.all("grounds-lod-pilot-guardian").setPriority(Int.MAX_VALUE)
    private val cache = LodCache(control, expected, cacheDirectory)
    private val gate = LodCreditGate(control, players, nanos)
    private val engine =
        LodSessionEngine(cache, gate, expected, players, MinecraftServer.DATA_VERSION, nanos)
    private val closed = AtomicBoolean()
    private val peers = ConcurrentHashMap<UUID, Peer>()

    private class Queued(
        val peer: Peer,
        val packets: List<PluginMessagePacket>,
        val barrier: PluginMessagePacket,
        val delivery: LodDelivery,
    ) {
        val lastEvents = IdentityHashMap<PluginMessagePacket, PlayerPacketOutEvent>()
    }

    private val queueLock = Any()
    private val pending = IdentityHashMap<PluginMessagePacket, Queued>()
    private val slots = HashMap<UUID, Queued>()
    private val worker =
        if (autoPump)
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "lod-pilot-sender").apply { isDaemon = true }
            }
        else null

    init {
        require(instance.dimensionName == Lss20.DIMENSION) {
            "LOD pilot requires an Overworld Lobby"
        }
        require(registrySha256() == expected.registrySha256) { "LOD host registry mismatch" }
        node.addListener(PlayerPluginMessageEvent::class.java) { event ->
            if (
                !closed.get() &&
                    event.player.uuid in this.players &&
                    event.identifier in Lss20.inbound
            ) {
                val peer =
                    peers.compute(event.player.uuid) { _, previous ->
                        if (previous?.player === event.player) previous else Peer(event.player)
                    }!!
                engine.receive(peer, event.identifier, event.message)
            }
        }
        // Publish committed lifecycle transitions rather than sampling Entity.instance from the
        // socket thread. An A->B->A hop always receives a new opaque generation identity.
        guardian.addListener(RemoveEntityFromInstanceEvent::class.java) { event ->
            (event.entity as? Player)?.let { player -> peerFor(player)?.move(null) }
        }
        guardian.addListener(PlayerSpawnEvent::class.java) { event ->
            peerFor(event.player)?.move(event.spawnInstance)
        }
        guardian.addListener(PlayerDisconnectEvent::class.java) { event ->
            peerFor(event.player)?.let { peer ->
                peer.move(null)
                peers.remove(event.player.uuid, peer)
                engine.remove(peer)
            }
            val retired =
                synchronized(queueLock) {
                    slots[event.player.uuid]
                        ?.takeIf { it.peer.player === event.player }
                        ?.also { removeSlot(it) }
                }
            retired?.delivery?.drop()
            detachWhenDrained()
        }
        guardian.addListener(
            EventListener.builder(PlayerPacketOutEvent::class.java)
                .ignoreCancelled(false)
                .handler { event ->
                    val packet = event.packet as? PluginMessagePacket ?: return@handler
                    val queued = synchronized(queueLock) { pending[packet] }
                    if (queued == null) {
                        if (
                            packet.channel() == BARRIER ||
                                (!closed.get() && packet.channel() in Lss20.outbound)
                        )
                            event.isCancelled = true
                        return@handler
                    }
                    if (packet === queued.barrier) {
                        // FIFO reach of this never-written sentinel proves the prior queue entry
                        // was consumed. Its saved event now has the FINAL cancellation state.
                        event.isCancelled = true
                        synchronized(queueLock) { removeSlot(queued) }
                        queued.delivery.complete(
                            event.player === queued.peer.player &&
                                queued.packets.all { queued.lastEvents[it]?.isCancelled == false }
                        )
                        detachWhenDrained()
                    } else {
                        queued.lastEvents[packet] = event
                        if (
                            event.player !== queued.peer.player ||
                                queued.packets
                                    .takeWhile { it !== packet }
                                    .any { queued.lastEvents[it]?.isCancelled != false } ||
                                (!event.isCancelled && !queued.delivery.authorize())
                        )
                            event.isCancelled = true
                        // Do not untrack here: buffer overflow retries this same object, and a
                        // later listener can still cancel this attempt. The barrier acknowledges
                        // it.
                    }
                }
                .build()
        )
        parent.addChild(node)
        MinecraftServer.getGlobalEventHandler().addChild(guardian)
        worker?.scheduleWithFixedDelay(
            {
                try {
                    engine.pump()
                } catch (_: Exception) {
                    close()
                }
            },
            0,
            5,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun peerFor(player: Player): Peer? =
        peers[player.uuid]?.takeIf { it.player === player }
            ?: synchronized(queueLock) { slots[player.uuid]?.peer?.takeIf { it.player === player } }

    /** queueLock is never held while entering the engine or a delivery callback. */
    private fun removeSlot(queued: Queued) {
        queued.packets.forEach { pending.remove(it) }
        pending.remove(queued.barrier)
        if (slots[queued.peer.id] === queued) slots.remove(queued.peer.id)
    }

    private inner class Peer(val player: Player) : LodPeer {
        // Initial snapshot is taken on the C2S event thread; later publications come from
        // committed lifecycle events. Network guards only read this volatile snapshot.
        @Volatile private var binding = snapshot(player.instance)
        override val id: UUID
            get() = player.uuid

        override val online: Boolean
            get() = player.isOnline && player.playerConnection.isOnline

        override fun world() = binding

        private fun snapshot(current: Instance?) =
            if (current === instance) LodBinding(Any(), expected.mapSha256, expected.sourceSha256)
            else LodBinding(Any(), null, null)

        fun move(current: Instance?) {
            binding = snapshot(current)
            engine.transition(this)
        }

        override fun writable() =
            synchronized(queueLock) { !closed.get() && online && id !in slots }

        override fun enqueue(delivery: LodDelivery) {
            val packet = PluginMessagePacket(delivery.reply.channel, delivery.reply.body)
            // Fabric gates request sends with canSend(). Advertise before SessionConfig,
            // under the same permit (the 512-byte framing envelope includes this message).
            val packets =
                if (delivery.reply.channel == Lss20.SESSION)
                    listOf(
                        PluginMessagePacket(
                            "minecraft:register",
                            Lss20.inbound.joinToString("\u0000").toByteArray(Charsets.UTF_8),
                        ),
                        packet,
                    )
                else listOf(packet)
            val barrier = PluginMessagePacket(BARRIER, byteArrayOf())
            val accepted =
                synchronized(queueLock) {
                    if (closed.get() || !online || id in slots) false
                    else {
                        val queued = Queued(this, packets, barrier, delivery)
                        slots[id] = queued
                        packets.forEach { pending[it] = queued }
                        pending[barrier] = queued
                        true
                    }
                }
            if (!accepted) {
                delivery.drop()
                return
            }
            try {
                player.playerConnection.sendPackets(packets + barrier)
            } catch (_: Exception) {
                // A partially accepted batch may still contain the carrier. Retain ownership
                // until its barrier/disconnect; dropping the permit makes every retry cancel.
                delivery.drop()
            }
        }
    }

    internal fun pumpForTest() = engine.pump()

    fun stats() =
        LodRuntimeStats(
            engine.sentBodyBytes.get(),
            engine.discardedFrames.get(),
            synchronized(queueLock) { slots.size },
            closed.get(),
        )

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine.close()
            parent.removeChild(node)
            worker?.shutdownNow()
            cache.close()
            peers.clear()
            detachWhenDrained()
        }
    }

    private fun detachWhenDrained() {
        if (closed.get() && synchronized(queueLock) { pending.isEmpty() })
            MinecraftServer.getGlobalEventHandler().removeChild(guardian)
    }

    companion object {
        private const val BARRIER = "grounds:lod_barrier"

        fun install(
            parent: EventNode<Event>,
            instance: Instance,
            expected: LodExpectation,
            control: LodControl,
            players: Set<UUID>,
            cacheDirectory: Path,
        ): MinestomLodAdapter =
            MinestomLodAdapter(
                parent,
                instance,
                expected,
                control,
                players,
                cacheDirectory,
                true,
                System::nanoTime,
            )

        fun registrySha256(): String {
            val sha = MessageDigest.getInstance("SHA-256")
            for (id in 0 until Block.statesCount()) {
                val block = requireNotNull(Block.fromStateId(id))
                sha.update(
                    (IdentityCodec.canonical(block.key().asString(), block.properties()) + "\n")
                        .toByteArray(Charsets.UTF_8)
                )
            }
            MinecraftServer.getBiomeRegistry()
                .keys()
                .map { it.key().asString() }
                .sorted()
                .forEach { sha.update((it + "\n").toByteArray(Charsets.UTF_8)) }
            return HexFormat.of().formatHex(sha.digest())
        }
    }
}
