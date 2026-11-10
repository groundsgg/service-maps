package gg.grounds.lod.runtime

import gg.grounds.lod.format.ColumnPosition
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Opaque identity of a committed world generation, with the actually loaded source hashes. */
data class LodBinding(val instanceKey: Any, val mapSha256: String?, val sourceSha256: String?)

internal interface LodPeer {
    val id: UUID
    val online: Boolean

    fun world(): LodBinding

    fun mayReceive(): Boolean

    fun writable(): Boolean = true

    fun enqueue(delivery: LodDelivery)
}

/** Revalidate every writer attempt; completion is separate from a pre-write event. */
internal class LodDelivery(
    val reply: LodReply,
    private val guard: (Boolean) -> Boolean,
    private val finished: (Boolean) -> Unit,
) {
    private var admitted = false
    private var completed = false

    @Synchronized
    fun authorize(): Boolean {
        if (completed) return false
        val allowed = guard(!admitted)
        if (allowed) admitted = true
        return allowed
    }

    @Synchronized
    fun complete(serialized: Boolean) {
        if (!completed) {
            completed = true
            finished(serialized && admitted)
        }
    }

    fun drop() = complete(false)
}

/** One background pump. Game/network callback locks are never held during control HTTP. */
internal class LodSessionEngine(
    private val cache: LodCache,
    private val gate: LodCreditGate,
    private val expected: LodExpectation,
    players: Set<UUID>,
    private val dataVersion: Int,
    private val nanos: () -> Long = System::nanoTime,
    private val entitled: Boolean = false,
) : AutoCloseable {
    private data class Input(val channel: String, val bytes: ByteArray)

    private data class Written(val at: Long, val bytes: Int)

    private class Session(val peer: LodPeer) {
        var world = peer.world()
        var allowed = false
        var epoch = 0L
        var negotiation = 0L
        var capabilities: Int? = null
        var clientVersion: Int? = null
        var loading = false
        var failed = false
        var source: LoadedLod? = null
        var wantConfig = false
        var announced = false
        var enabled = false
        var prepared: Prepared? = null
        var pending: LodDelivery? = null
        val input = ArrayDeque<Input>()
        val inboundTimes = ArrayDeque<Long>()
        val requests = LinkedHashMap<ColumnPosition, LodColumnRequest>()
        var summary: ByteArray? = null
        val written = ArrayDeque<Written>()
        var windowBytes = 0
    }

    private class Prepared(
        val reply: LodReply,
        val epoch: Long,
        val world: Any,
        val negotiation: Long,
        val requests: List<ColumnPosition> = emptyList(),
        val configuration: Boolean? = null,
    )

    private val lock = Any()
    private val players = players.toSet()
    private val sessions = HashMap<UUID, Session>()
    private var closed = false
    val sentBodyBytes = AtomicLong()
    val discardedFrames = AtomicLong()
    val serializedTerrainBodyBytes = AtomicLong()
    val serializedControlBodyBytes = AtomicLong()
    val serializedFrames = AtomicLong()

    init {
        require((if (entitled) players.isEmpty() else players.size in 1..5) && dataVersion > 0)
    }

    fun receive(peer: LodPeer, channel: String, bytes: ByteArray) {
        if ((!entitled && peer.id !in players) || channel !in Lss20.inbound) return
        synchronized(lock) {
            if (closed || !peer.online) return
            var state = sessions[peer.id]
            if (state == null || state.peer !== peer) {
                if (entitled && !permitted(peer)) return
                if (entitled && state == null && sessions.size >= 64) return
                if (entitled && state != null) gate.remove(peer.id)
                state = Session(peer)
                sessions[peer.id] = state
            }
            refreshWorld(state)
            refreshAuthorization(state)
            val at = nanos()
            while (state.inboundTimes.firstOrNull()?.let { at - it >= 1000000000 } == true) state
                .inboundTimes
                .removeFirst()
            val limited = state.inboundTimes.size >= 32
            if (channel == Lss20.HANDSHAKE || channel == Lss20.CLIENT_INFO) {
                // Tiny control frames fence old packets immediately, even while the pump waits
                // on HTTP. Over-limit/malformed declarations fail closed without growing queues.
                state.negotiation++
                state.input.clear()
                state.requests.clear()
                state.summary = null
                state.prepared = null
                state.enabled = false
                if (channel == Lss20.HANDSHAKE) state.capabilities = null
                else state.clientVersion = null
                if (!limited) {
                    state.inboundTimes.addLast(at)
                    try {
                        if (channel == Lss20.HANDSHAKE) state.capabilities = Lss20.handshake(bytes)
                        else state.clientVersion = Lss20.clientInfo(bytes)
                    } catch (_: Exception) {}
                }
                state.wantConfig = validClient(state) && (supported(state) || state.announced)
                return
            }
            if (limited || bytes.size > 16389) return
            state.inboundTimes.addLast(at)
            if (channel == Lss20.BATCH_REQUEST) {
                // LSS declares the COMPLETE want set, including an empty overload stop signal.
                state.requests.clear()
                if (state.prepared?.requests?.isNotEmpty() == true) state.prepared = null
                state.input.removeAll { it.channel == Lss20.BATCH_REQUEST }
            }
            if (state.input.size < 4) state.input.addLast(Input(channel, bytes.copyOf()))
        }
    }

    fun transition(peer: LodPeer) =
        synchronized(lock) {
            sessions[peer.id]?.takeIf { it.peer === peer }?.let { resetWorld(it, peer.world()) }
        }

    fun remove(peer: LodPeer) =
        synchronized(lock) {
            if (sessions[peer.id]?.peer === peer) {
                sessions.remove(peer.id)
                gate.remove(peer.id)
            }
            Unit
        }

    internal fun sessionCount() = synchronized(lock) { sessions.size }

    fun pump() {
        val current = synchronized(lock) { if (closed) return else sessions.values.toList() }
        for (state in current) {
            val prepared =
                synchronized(lock) {
                    if (closed || sessions[state.peer.id] !== state || !state.peer.online) null
                    else {
                        refreshWorld(state)
                        refreshAuthorization(state)
                        state.input.removeFirstOrNull()?.let { process(state, it) }
                        maybeLoad(state)
                        // The carrier slot is UUID-wide across old/new connection sessions. Never
                        // spend another reservation just to discover a still occupied old socket.
                        if (state.pending != null || !state.peer.writable()) null
                        else prepare(state)
                    }
                } ?: continue
            val permit = gate.reserve(state.peer.id, prepared.reply.body.size + 512) ?: continue
            val delivery =
                synchronized(lock) {
                    if (
                        closed ||
                            sessions[state.peer.id] !== state ||
                            state.prepared !== prepared ||
                            state.epoch != prepared.epoch ||
                            state.negotiation != prepared.negotiation ||
                            state.peer.world().instanceKey !== prepared.world
                    )
                        null
                    else {
                        state.prepared = null
                        prepared.requests.forEach { state.requests.remove(it) }
                        val frame =
                            LodDelivery(
                                prepared.reply,
                                { first -> authorize(state, prepared, permit, first) },
                                { serialized ->
                                    synchronized(lock) {
                                        // A consumed barrier confirms the writer's final
                                        // cancellation
                                        // state. Count completed application bodies once, even if a
                                        // world/permission change happened after serialization.
                                        if (serialized) {
                                            serializedFrames.incrementAndGet()
                                            val counter =
                                                if (prepared.reply.channel == Lss20.VOXEL)
                                                    serializedTerrainBodyBytes
                                                else serializedControlBodyBytes
                                            counter.addAndGet(prepared.reply.body.size.toLong())
                                        }
                                        if (
                                            serialized &&
                                                !closed &&
                                                sessions[state.peer.id] === state &&
                                                state.epoch == prepared.epoch &&
                                                state.negotiation == prepared.negotiation &&
                                                state.peer.world().instanceKey === prepared.world
                                        ) {
                                            prepared.configuration?.let { enabled ->
                                                // An acknowledged disable fulfils the client's
                                                // outstanding enable obligation. Denied peers
                                                // cannot keep buying disable frames by declaring
                                                // the same capabilities again.
                                                state.announced = enabled
                                                state.enabled = enabled
                                                state.wantConfig =
                                                    enabled !=
                                                        (supported(state) && state.source != null)
                                            }
                                        }
                                        state.pending = null
                                    }
                                },
                            )
                        state.pending = frame
                        frame
                    }
                } ?: continue
            try {
                state.peer.enqueue(delivery)
            } catch (_: Exception) {
                delivery.drop()
            }
        }
    }

    private fun validClient(state: Session) =
        state.capabilities != null && state.clientVersion == dataVersion

    private fun supported(state: Session) =
        state.allowed &&
            state.world.mapSha256 == expected.mapSha256 &&
            state.world.sourceSha256 == expected.sourceSha256

    private fun permitted(peer: LodPeer): Boolean =
        try {
            peer.mayReceive()
        } catch (_: Exception) {
            false
        }

    private fun refreshAuthorization(state: Session) {
        val allowed = permitted(state.peer)
        if (allowed == state.allowed) return
        state.allowed = allowed
        state.negotiation++
        state.input.clear()
        state.requests.clear()
        state.summary = null
        state.prepared = null
        state.enabled = false
        // A previously queued configuration may already have reached the serializer.
        // Keep the final disable notice even when its acknowledgement is still pending.
        state.wantConfig =
            validClient(state) && (allowed || state.announced || state.pending != null)
    }

    private fun resetWorld(state: Session, world: LodBinding) {
        state.world = world
        state.epoch++
        state.input.clear()
        state.requests.clear()
        state.summary = null
        state.prepared = null
        state.enabled = false
        state.wantConfig = state.announced || (supported(state) && validClient(state))
    }

    private fun refreshWorld(state: Session) {
        val now = state.peer.world()
        if (
            now.instanceKey !== state.world.instanceKey ||
                now.mapSha256 != state.world.mapSha256 ||
                now.sourceSha256 != state.world.sourceSha256
        )
            resetWorld(state, now)
    }

    private fun process(state: Session, input: Input) {
        try {
            when (input.channel) {
                Lss20.BATCH_REQUEST ->
                    if (validClient(state) && supported(state) && state.source != null) {
                        state.requests.clear()
                        for (request in Lss20.batch(input.bytes)) state.requests[request.position] =
                            request
                    }
                Lss20.SUMMARY_REQUEST ->
                    if (validClient(state) && supported(state)) {
                        require(input.bytes.size <= 300)
                        state.summary = input.bytes
                    }
            }
        } catch (_: Exception) {}
    }

    private fun maybeLoad(state: Session) {
        if (
            !validClient(state) ||
                !supported(state) ||
                state.loading ||
                state.failed ||
                state.source != null ||
                !cache.readyToLoad()
        )
            return
        state.loading = true
        val admittedWorld = state.world.instanceKey
        cache
            .load(state.peer.id) {
                synchronized(lock) {
                    !closed &&
                        sessions[state.peer.id] === state &&
                        state.peer.online &&
                        permitted(state.peer) &&
                        state.peer.world().let {
                            it.instanceKey === admittedWorld &&
                                it.mapSha256 == expected.mapSha256 &&
                                it.sourceSha256 == expected.sourceSha256
                        }
                }
            }
            .whenComplete { source, error ->
                synchronized(lock) {
                    if (!closed && sessions[state.peer.id] === state) {
                        state.loading = false
                        if (error != null) {
                            state.failed =
                                !entitled || (error as? LodUnavailable)?.permanent == true
                        } else {
                            state.source = source
                            state.wantConfig = true
                        }
                    }
                }
            }
    }

    private fun prepare(state: Session): Prepared? {
        state.prepared?.let {
            return it
        }
        if (!validClient(state)) return null
        fun frame(
            reply: LodReply,
            requests: List<ColumnPosition> = emptyList(),
            config: Boolean? = null,
        ) =
            Prepared(
                reply,
                state.epoch,
                state.world.instanceKey,
                state.negotiation,
                requests,
                config,
            )
        val next =
            when {
                state.wantConfig -> {
                    val enabled = supported(state) && state.source != null
                    frame(
                        LodReply(
                            Lss20.SESSION,
                            Lss20.session(state.capabilities!!, dataVersion, enabled),
                        ),
                        config = enabled,
                    )
                }
                !state.enabled || !supported(state) -> null
                state.summary != null -> {
                    val request = state.summary!!
                    state.summary = null
                    val body =
                        try {
                            Lss20.summary(state.source!!, request)
                        } catch (_: Exception) {
                            null
                        }
                    body?.let { frame(LodReply(Lss20.SUMMARY, it)) }
                }
                state.requests.isNotEmpty() -> {
                    val source = state.source!!
                    val statuses =
                        state.requests.values.take(64).takeWhile {
                            Lss20.statusCode(source, it) != null
                        }
                    if (statuses.isNotEmpty())
                        frame(Lss20.statusBatch(source, statuses), statuses.map { it.position })
                    else {
                        val request = state.requests.values.first()
                        frame(Lss20.reply(source, request), listOf(request.position))
                    }
                }
                else -> null
            }
        state.prepared = next
        return next
    }

    private fun authorize(
        state: Session,
        frame: Prepared,
        permit: LodSendPermit,
        first: Boolean,
    ): Boolean =
        synchronized(lock) {
            val at = nanos()
            val world = state.peer.world()
            val valid =
                !closed &&
                    sessions[state.peer.id] === state &&
                    state.peer.online &&
                    state.epoch == frame.epoch &&
                    state.negotiation == frame.negotiation &&
                    world.instanceKey === frame.world &&
                    world.mapSha256 == state.world.mapSha256 &&
                    world.sourceSha256 == state.world.sourceSha256 &&
                    validClient(state) &&
                    (frame.configuration == false || permitted(state.peer)) &&
                    permit.valid(at)
            while (state.written.firstOrNull()?.let { at - it.at >= 1000000000 } == true) {
                state.windowBytes -= state.written.removeFirst().bytes
            }
            if (!valid || (first && state.windowBytes + permit.reservedBytes > 524288)) {
                discardedFrames.incrementAndGet()
                return@synchronized false
            }
            if (first) {
                state.written.addLast(Written(at, permit.reservedBytes))
                state.windowBytes += permit.reservedBytes
                sentBodyBytes.addAndGet(frame.reply.body.size.toLong())
            }
            true
        }

    override fun close() =
        synchronized(lock) {
            closed = true
            sessions.clear()
        }
}
