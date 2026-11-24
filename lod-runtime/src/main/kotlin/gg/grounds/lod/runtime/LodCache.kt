package gg.grounds.lod.runtime

import com.github.luben.zstd.Zstd
import gg.grounds.lod.format.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

data class LodExpectation(
    val mapSha256: String,
    val sourceSha256: String,
    val registrySha256: String,
    val generatorSha256: String,
) {
    init {
        require(listOf(mapSha256, sourceSha256, registrySha256, generatorSha256).all(::isDigest))
    }

    fun matches(grant: LodSourceGrant): Boolean =
        grant.mapBundleSha256 == mapSha256 &&
            grant.sourceBundleSha256 == sourceSha256 &&
            grant.registrySha256 == registrySha256 &&
            grant.generatorSha256 == generatorSha256
}

class LoadedLod
internal constructor(
    val expectation: LodExpectation,
    val timestampSeconds: Long,
    internal val columns: Map<ColumnPosition, ByteArray>,
) {
    val columnCount: Int
        get() = columns.size

    internal val tiles =
        columns.keys
            .map { ColumnPosition(Math.floorDiv(it.x, 32), Math.floorDiv(it.z, 32)) }
            .toSet()
}

/**
 * One immutable source per Pod, without world loading, generation or HEAD requests. Finite pilots
 * never retry; entitled serving opts into a bounded demand-driven recovery envelope.
 */
class LodCache(
    private val control: LodControl,
    private val expected: LodExpectation,
    private val directory: Path,
    private val entitled: Boolean = false,
    private val nanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "lod-cache-loader").apply { isDaemon = true }
        }
    private var future: CompletableFuture<LoadedLod>? = null
    private var closed = false
    private val recovery = LodRecovery(nanos)

    @Synchronized internal fun readyToLoad() = !closed && (!entitled || recovery.ready())

    @Synchronized
    fun load(player: UUID, access: () -> Boolean = { true }): CompletableFuture<LoadedLod> {
        if (closed) return CompletableFuture.failedFuture(LodUnavailable(true))
        future?.let {
            return it
        }
        if (entitled && !recovery.ready())
            return CompletableFuture.failedFuture(LodUnavailable(recovery.closed))
        val shared = CompletableFuture<LoadedLod>()
        future = shared
        worker.execute {
            try {
                shared.complete(loadSource(player, access))
            } catch (error: Exception) {
                synchronized(this) {
                    if (entitled) {
                        if (error !is LodAccessDenied) recovery.failed(error)
                        // Keep permanent failure sticky; transient failures become eligible only
                        // after the shared monotonic backoff, regardless of reconnects.
                        if (!recovery.closed) future = null
                    }
                }
                shared.completeExceptionally(
                    if (entitled) LodUnavailable(recovery.closed) else LodUnavailable()
                )
            }
        }
        return shared
    }

    private fun loadSource(player: UUID, access: () -> Boolean): LoadedLod {
        Files.createDirectories(directory)
        FileChannel.open(
                directory.resolve("cache.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { owner ->
                requireNotNull(owner.tryLock()) { "LOD cache already owned" }
                    .use {
                        // A fixed owned stage cannot accumulate across SIGKILL/restart. The OS
                        // releases
                        // this lock on death; never clean another live loader's work.
                        val partial = directory.resolve("terrain.part")
                        Files.deleteIfExists(partial)
                        try {
                            if (!access()) throw LodAccessDenied()
                            val lease = control.source(player)
                            val grant = lease.value
                            grant.validate()
                            require(expected.matches(grant))
                            val cached = directory.resolve("terrain.zip")
                            if (Files.exists(cached)) {
                                try {
                                    return decode(cached, grant)
                                } catch (_: Exception) {
                                    Files.delete(cached)
                                }
                            }
                            if (!access()) throw LodAccessDenied()
                            control.download(lease, partial)
                            val loaded = decode(partial, grant)
                            Files.move(
                                partial,
                                cached,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            return loaded
                        } finally {
                            Files.deleteIfExists(partial)
                        }
                    }
            }
    }

    private fun decode(path: Path, grant: LodSourceGrant): LoadedLod {
        require(Files.size(path) == grant.bundleBytes)
        val bundle = LodBundle.open(path, grant.bundleSha256)
        val manifest = bundle.manifest
        require(
            bundle.manifestSha256 == grant.manifestSha256 &&
                manifest.sourceBundleSha256 == expected.sourceSha256 &&
                manifest.generatorSha256 == expected.generatorSha256 &&
                manifest.registrySha256 == expected.registrySha256
        )
        // The pilot's decoded input and retained compressed data are separately bounded.
        require(manifest.columnBytes <= MAX_CACHE_BYTES)
        val selected = LinkedHashMap<ColumnPosition, ByteArray>()
        var bytes = 0L
        for (tile in manifest.tiles) {
            for ((position, raw) in bundle.readTile(tile.x, tile.z)) {
                val compressed = Zstd.compress(raw, 1)
                require(compressed.size <= MAX_COLUMN_BYTES && !selected.containsKey(position))
                bytes += compressed.size
                require(bytes <= MAX_CACHE_BYTES)
                selected[position] = compressed
            }
        }
        require(selected.size == manifest.columns)
        val stamp = grant.contentTimestampMillis / 1000
        require(stamp > 0)
        return LoadedLod(expected, stamp, Collections.unmodifiableMap(selected))
    }

    override fun close() {
        val pending =
            synchronized(this) {
                closed = true
                future
            }
        // Completion callbacks may enter the engine, which acquires cache bookkeeping in pump.
        pending?.cancel(true)
        worker.shutdownNow()
    }

    companion object {
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        const val MAX_COLUMN_BYTES = 64 * 1024
    }
}
