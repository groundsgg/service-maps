package gg.grounds.derive

import gg.grounds.catalog.CatalogJarLoader
import gg.grounds.catalog.DefaultNamespaceCatalogResolver
import gg.grounds.catalog.NamespaceCatalogResolver
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.scene.format.SceneCatalogReferences
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Dependency-free process boundary; it never starts Quarkus. */
object DeriveWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    internal fun run(args: Array<String>): Int =
        run(args, { WorkerHttpTransfer(it) }, System::getenv)

    internal fun run(
        args: Array<String>,
        transferFactory: (allowLoopbackHttp: Boolean) -> WorkerHttpTransfer,
    ): Int = run(args, transferFactory, System::getenv)

    internal fun run(
        args: Array<String>,
        transferFactory: (allowLoopbackHttp: Boolean) -> WorkerHttpTransfer,
        environment: (String) -> String?,
        tempRootFactory: () -> Path = { Files.createTempDirectory("derive-worker-") },
        catalogLoaderFactory: (Path, Boolean) -> CatalogJarLoader = { directory, loopback ->
            CatalogJarLoader(directory, allowLoopbackHttp = loopback)
        },
    ): Int {
        val options =
            try {
                options(args)
            } catch (failure: Exception) {
                return 2
            }
        val request =
            try {
                CanonicalJson.readRequest(options.request(environment))
            } catch (failure: Exception) {
                return 2
            }
        val transfer = transferFactory(options.allowLoopbackHttp)
        try {
            listOf(request.sourceUrl, request.bundleUrl, request.manifestUrl, request.resultUrl)
                .plus(request.catalogCandidates.map { it.uri })
                .forEach(transfer::preflight)
        } catch (failure: Exception) {
            return 2
        }
        val root = tempRootFactory()
        try {
            val source = root.resolve("source.tar.zst")
            try {
                val actual = transfer.download(request.sourceUrl, source)
                if (actual != request.sourceSha256)
                    throw ContentFailure("source digest does not match request")
                val result =
                    derive(request, source, root, options.allowLoopbackHttp, catalogLoaderFactory)
                when (result) {
                    is SceneDerivationOutcome.Invalid ->
                        return failure(request, result.problems, transfer)
                    is SceneDerivationOutcome.Valid -> {
                        try {
                            transfer.upload(
                                request.bundleUrl,
                                result.bundle.path,
                                result.bundle.sha256,
                            )
                            val manifest = root.resolve("manifest.json")
                            Files.write(manifest, result.manifest)
                            transfer.upload(request.manifestUrl, manifest, digest(result.manifest))
                            transfer.upload(
                                request.resultUrl,
                                CanonicalJson.write(success(request, result)),
                            )
                            return 0
                        } catch (e: Exception) {
                            return systemFailure(request, e, transfer)
                        }
                    }
                }
            } catch (e: ContentFailure) {
                return failure(
                    request,
                    listOf(problem(DeriveFailureScope.CONTENT, "SOURCE", e.message!!)),
                    transfer,
                )
            } catch (e: WorkerSourceLimitException) {
                return failure(
                    request,
                    listOf(problem(DeriveFailureScope.CONTENT, "SOURCE", e.message!!)),
                    transfer,
                )
            } catch (e: Exception) {
                return systemFailure(request, e, transfer)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun derive(
        request: DeriveRequest,
        source: Path,
        root: Path,
        loopback: Boolean,
        catalogLoaderFactory: (Path, Boolean) -> CatalogJarLoader,
    ): SceneDerivationOutcome {
        val catalogDir = root.resolve("catalogs")
        val deriveDirectory = Files.createDirectory(root.resolve("derive"))
        catalogLoaderFactory(catalogDir, loopback).use { loader ->
            val resolver = workerResolver(request, loader, DefaultNamespaceCatalogResolver())
            return Files.newInputStream(source).use {
                SceneDeriver(resolver).derive(it, request.sourceSha256, deriveDirectory)
            }
        }
    }

    private fun workerResolver(
        request: DeriveRequest,
        loader: CatalogJarLoader,
        actions: NamespaceCatalogResolver,
    ) = SceneCatalogResolver { references: SceneCatalogReferences ->
        val asset = references.assets
        val candidate =
            request.catalogCandidates.singleOrNull {
                it.id == asset.id.value && it.version == asset.version
            } ?: throw ContentFailure("no exact asset catalog candidate")
        val assets =
            try {
                loader.load(candidate)
            } catch (e: IllegalArgumentException) {
                throw ContentFailure("asset catalog is invalid")
            }
        val namespace = references.actions.id.value.substringBefore(':')
        if (namespace != "grounds" || !references.actions.id.value.startsWith("$namespace:")) {
            throw ContentFailure("action catalog namespace is invalid")
        }
        val requested = references.actions
        ResolvedSceneCatalogs(
            assets,
            actions
                .resolve(
                    namespace,
                    CatalogReference(asset.id.value, asset.version),
                    CatalogReference(requested.id.value, requested.version),
                )
                .catalog,
        )
    }

    private fun success(request: DeriveRequest, result: SceneDerivationOutcome.Valid) =
        DeriveSuccess(
            mapId = request.mapId,
            version = request.version,
            attempt = request.attempt,
            sourceSha256 = request.sourceSha256,
            bundleSha256 = result.bundle.sha256,
            bundleSize = result.bundle.size,
            manifestSha256 = digest(result.manifest),
            manifestSize = result.manifest.size.toLong(),
            scene = result.scene,
        )

    private fun failure(
        request: DeriveRequest,
        problems: List<DeriveProblem>,
        transfer: WorkerHttpTransfer,
    ): Int =
        if (
            runCatching {
                    transfer.upload(
                        request.resultUrl,
                        CanonicalJson.write(
                            DeriveFailure(
                                mapId = request.mapId,
                                version = request.version,
                                attempt = request.attempt,
                                sourceSha256 = request.sourceSha256,
                                scope = DeriveFailureScope.CONTENT,
                                retryable = false,
                                problems = problems,
                            )
                        ),
                    )
                }
                .isSuccess
        )
            0
        else 1

    private fun systemFailure(
        request: DeriveRequest,
        e: Exception,
        transfer: WorkerHttpTransfer,
    ): Int =
        if (
            runCatching {
                    transfer.upload(
                        request.resultUrl,
                        CanonicalJson.write(
                            DeriveFailure(
                                mapId = request.mapId,
                                version = request.version,
                                attempt = request.attempt,
                                sourceSha256 = request.sourceSha256,
                                scope = DeriveFailureScope.SYSTEM,
                                retryable = true,
                                problems =
                                    listOf(
                                        problem(
                                            DeriveFailureScope.SYSTEM,
                                            "SYSTEM",
                                            redact(e.message ?: "worker failure"),
                                        )
                                    ),
                            )
                        ),
                    )
                }
                .isSuccess
        )
            0
        else 1

    private fun problem(scope: DeriveFailureScope, code: String, message: String) =
        DeriveProblem(scope, null, code, null, redact(message))

    private fun redact(message: String) =
        message.replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?<redacted-query>")

    private fun digest(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private class ContentFailure(message: String) : RuntimeException(message)

    private data class Options(
        val requestFile: Path?,
        val requestEnv: String?,
        val allowLoopbackHttp: Boolean,
    ) {
        fun request(environment: (String) -> String?): ByteArray =
            requestFile?.let(Files::readAllBytes)
                ?: requireNotNull(environment(requireNotNull(requestEnv))).encodeToByteArray()
    }

    private fun options(args: Array<String>): Options {
        var file: Path? = null
        var env: String? = null
        var loopback = false
        var i = 0
        while (i < args.size) when (args[i++]) {
            "--request-file" -> file = Path.of(args[i++])
            "--request-env" -> env = args[i++]
            "--allow-loopback-http" -> loopback = true
            else -> error("unknown worker option")
        }
        require((file == null) != (env == null)) { "provide exactly one request source" }
        require(file == null || loopback) { "--request-file requires --allow-loopback-http" }
        return Options(file, env, loopback)
    }
}
