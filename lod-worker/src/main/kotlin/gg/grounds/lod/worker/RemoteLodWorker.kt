package gg.grounds.lod.worker

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import gg.grounds.lod.format.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.minestom.server.MinecraftServer

/** Remote job entry point; the API remains independent of Minestom and world loading. */
fun remoteMain() {
    val raw = requireNotNull(System.getenv("LOD_REQUEST_JSON")) { "LOD_REQUEST_JSON is required" }
    require(raw.length <= 64 * 1024) { "request exceeds limit" }
    val request = remoteJson.fromJson(raw, LodJobRequest::class.java)
    val scratch = Files.createTempDirectory("lod-job-")
    val success =
        try {
            RemoteLodWorker(scratch).run(request)
        } finally {
            scratch.toFile().deleteRecursively()
            if (MinecraftServer.process() != null) MinecraftServer.stopCleanly()
        }
    exitProcess(if (success) 0 else 1)
}

private val remoteJson = GsonBuilder().setStrictness(Strictness.STRICT).create()

class RemoteLodWorker(private val scratch: Path, allowLoopbackHttp: Boolean = false) {
    private val transfer = LodHttp(allowLoopbackHttp)

    /** True means a result marker was acknowledged, including an explicit refusal. */
    fun run(request: LodJobRequest): Boolean {
        // Reject malformed capabilities before using any supplied URL, including the result URL.
        request.validate()
        listOf(request.sourceUrl, request.bundleUrl, request.manifestUrl, request.resultUrl)
            .forEach(transfer::preflight)
        val identity =
            LodJobResult(
                format = request.format,
                attempt = request.attempt,
                mapBundleSha256 = request.mapBundleSha256,
                sourceBundleSha256 = request.sourceBundleSha256,
                generatorSha256 = request.generatorSha256,
                workerImageSha256 = request.workerImageSha256,
            )
        val result =
            try {
                require(request.generatorSha256 == GeneratorBuild.sha256) {
                    "generator build mismatch"
                }
                val input = scratch.resolve("input.tar.zst")
                transfer.download(request.sourceUrl, input, 64L * 1024 * 1024)
                val world = scratch.resolve("world")
                SourceArchive.unpack(input, request.sourceBundleSha256, world)
                if (MinecraftServer.process() == null) MinecraftServer.init()
                val output = scratch.resolve("output")
                val manifest = LodGenerator().generate(world, output, request.sourceBundleSha256)
                val bundle = scratch.resolve("lod.zip")
                LodBundle.pack(output, bundle)
                val manifestFile = output.resolve("manifest.json")
                val facts =
                    identity.copy(
                        bundleSha256 = digest(bundle),
                        bundleBytes = Files.size(bundle),
                        manifestSha256 = digest(manifestFile),
                        manifestBytes = Files.size(manifestFile),
                        registrySha256 = manifest.registrySha256,
                        columns = manifest.columns,
                    )
                transfer.upload(request.bundleUrl, bundle)
                transfer.upload(request.manifestUrl, manifestFile)
                facts
            } catch (e: Exception) {
                // Do not print presigned URLs or exception strings which can contain them.
                identity.copy(error = "LOD job refused or failed (${e.javaClass.simpleName})")
            }
        return try {
            val marker = scratch.resolve("result.json")
            Files.writeString(marker, remoteJson.toJson(result))
            transfer.upload(request.resultUrl, marker)
            println("lod_job outcome=${if (result.error == null) "ready" else "failed"}")
            true
        } catch (_: Exception) {
            System.err.println("lod_job outcome=result_upload_failed")
            false
        }
    }
}
