package gg.grounds.derive

import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDecodeResult
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.SceneValidation
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ResolvedSceneCatalogs(val assets: AssetCatalog, val actions: ActionCatalog)

fun interface SceneCatalogResolver {
    fun resolve(references: SceneCatalogReferences): ResolvedSceneCatalogs
}

sealed interface SceneDerivationOutcome {
    data class Valid(
        val bundle: DerivedBundleArtifact,
        val manifest: ByteArray,
        val scene: DerivedScene,
    ) : SceneDerivationOutcome

    data class Invalid(val problems: List<DeriveProblem>) : SceneDerivationOutcome
}

class SceneDeriver(
    private val catalogResolver: SceneCatalogResolver,
    private val limits: ArchiveLimits = ArchiveLimits(),
    private val bundleWriter: DerivedBundleWriter = DerivedBundleWriter(),
) {
    fun derive(
        source: InputStream,
        sourceSha256: String,
        workerDirectory: Path,
    ): SceneDerivationOutcome {
        val spool =
            try {
                SafeTarZstdReader(limits).read(source, workerDirectory)
            } catch (failure: ArchiveContentException) {
                return SceneDerivationOutcome.Invalid(listOf(failure.problem))
            }
        if (spool.any { it.path == "grounds/derived-manifest.json" }) {
            return invalid(
                "source archive reserves grounds/derived-manifest.json",
                "grounds/derived-manifest.json",
            )
        }
        val authored = spool.singleOrNull { it.path == "scene.json" }
        if (authored?.directory == true)
            return invalid("scene.json must be a regular file", "scene.json")
        val sceneResult =
            if (authored == null) noScene()
            else {
                if (Files.size(authored.file) > limits.maxSceneBytes) {
                    return invalid("scene.json exceeds size limit", "scene.json")
                }
                deriveScene(Files.readAllBytes(authored.file!!))
            }
        if (sceneResult is SceneParseResult.Invalid)
            return SceneDerivationOutcome.Invalid(sceneResult.problems)
        val sceneFacts = (sceneResult as SceneParseResult.Facts).facts
        val scene = sceneFacts.scene
        val updated =
            spool.filterNot { it.path == "scene.json" } +
                listOfNotNull(
                    sceneFacts.canonical?.let { canonical ->
                        val output =
                            Files.createTempFile(workerDirectory, ".canonical-scene-", ".json")
                        Files.write(output, canonical)
                        SpoolEntry("scene.json", output, false)
                    }
                )
        val manifest =
            CanonicalJson.write(
                DerivedManifest(
                    sourceSha256 = sourceSha256,
                    bundleDigestInputs = updated.map { it.path },
                    scene = scene,
                )
            )
        val output =
            Files.createTempDirectory(workerDirectory, ".derived-bundle-").resolve("bundle.tar.zst")
        return SceneDerivationOutcome.Valid(
            bundleWriter.write(updated, manifest, output),
            manifest,
            scene,
        )
    }

    private fun noScene() =
        SceneParseResult.Facts(
            SceneFacts(DerivedScene(false, null, null, null, null, emptyList()), null)
        )

    private fun deriveScene(bytes: ByteArray): SceneParseResult {
        val decoded = SceneJson.decode(bytes)
        if (decoded is SceneDecodeResult.Failure)
            return invalidScene(decoded.problems.map(::toProblem))
        val document = (decoded as SceneDecodeResult.Success).scene
        val catalogs =
            try {
                catalogResolver.resolve(document.catalogs)
            } catch (failure: RuntimeException) {
                return invalidScene(
                    "scene catalog could not be resolved: ${failure.message}",
                    "scene.json",
                )
            }
        val validation =
            SceneValidation.validateCatalogs(document, catalogs.assets, catalogs.actions)
        if (!validation.isValid) return invalidScene(validation.problems.map(::toProblem))
        val encoded = SceneJson.encode(document)
        if (encoded is SceneEncodeResult.Failure)
            return invalidScene(encoded.problems.map(::toProblem))
        val canonical = (encoded as SceneEncodeResult.Success).bytes
        return SceneParseResult.Facts(
            SceneFacts(
                DerivedScene(
                    true,
                    document.schemaVersion.toString(),
                    sha256(canonical),
                    CatalogReference(
                        document.catalogs.assets.id.value,
                        document.catalogs.assets.version,
                    ),
                    CatalogReference(
                        document.catalogs.actions.id.value,
                        document.catalogs.actions.version,
                    ),
                    requiredActions(document),
                ),
                canonical,
            )
        )
    }

    private fun requiredActions(document: SceneDocument): List<String> =
        document.elements
            .flatMap { element ->
                when (element) {
                    is gg.grounds.scene.format.Npc ->
                        element.bindings.flatMap { binding -> binding.actions }
                    else -> emptyList()
                }
            }
            .filterIsInstance<ApplicationAction>()
            .map { it.key.value }
            .distinct()
            .sorted()

    private fun invalid(message: String, path: String? = null) =
        invalid(listOf(DeriveProblem(DeriveFailureScope.CONTENT, path, "SCENE", null, message)))

    private fun invalid(problems: List<DeriveProblem>) = SceneDerivationOutcome.Invalid(problems)

    private fun invalidScene(problems: List<DeriveProblem>) = SceneParseResult.Invalid(problems)

    private fun invalidScene(message: String, path: String) =
        invalidScene(
            listOf(DeriveProblem(DeriveFailureScope.CONTENT, path, "SCENE", null, message))
        )

    private fun toProblem(problem: gg.grounds.scene.format.SceneProblem) =
        DeriveProblem(
            DeriveFailureScope.CONTENT,
            "scene.json${problem.path}",
            problem.code.name,
            problem.qualifiedIdentity,
            problem.message,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class SceneFacts(val scene: DerivedScene, val canonical: ByteArray?)

    private sealed interface SceneParseResult {
        data class Facts(val facts: SceneFacts) : SceneParseResult

        data class Invalid(val problems: List<DeriveProblem>) : SceneParseResult
    }
}
