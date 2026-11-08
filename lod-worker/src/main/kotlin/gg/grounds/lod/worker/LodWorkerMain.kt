package gg.grounds.lod.worker

import com.google.gson.Gson
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.MinecraftServer

/** A local CLI for the measurement step; no listener, scheduler, upload or credentials. */
fun main(args: Array<String>) {
    require(args.size == 6) { "usage: --bundle <tar.zst> --sha256 <digest> --output <directory>" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys == setOf("--bundle", "--sha256", "--output")) { "unsupported arguments" }
    val bundle = Path.of(options.getValue("--bundle"))
    val output = Path.of(options.getValue("--output")).toAbsolutePath()
    Files.createDirectories(output.parent)
    val scratch = Files.createTempDirectory(output.parent, ".lod-input-")
    val start = System.nanoTime()
    val cpu = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    val beforeCpu = cpu?.processCpuTime
    try {
        val world = scratch.resolve("world")
        SourceArchive.unpack(bundle, options.getValue("--sha256"), world)
        MinecraftServer.init()
        val manifest = LodGenerator().generate(world, output, options.getValue("--sha256"))
        println(Gson().toJson(mapOf(
            "schemaVersion" to 1,
            "format" to manifest.format,
            "manifestSha256" to sha256(output.resolve("manifest.json")),
            "columns" to manifest.columns,
            "tiles" to manifest.tiles.size,
            "compressedTileBytes" to manifest.sizeBytes,
            "rawColumnBytes" to manifest.columnBytes,
            "elapsedMillis" to (System.nanoTime() - start) / 1_000_000,
            "cpuMillis" to if (beforeCpu != null) (cpu!!.processCpuTime - beforeCpu) / 1_000_000 else null,
            "peakHeapBytes" to ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used },
        )))
    } finally {
        if (MinecraftServer.process() != null) MinecraftServer.stopCleanly()
        scratch.toFile().deleteRecursively()
    }
}
