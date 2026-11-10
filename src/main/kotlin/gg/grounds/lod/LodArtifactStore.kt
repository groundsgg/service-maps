package gg.grounds.lod

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import gg.grounds.blob.BlobIntegrityException
import gg.grounds.blob.BlobMetadata
import gg.grounds.blob.BlobStore
import gg.grounds.domain.*
import gg.grounds.lod.format.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

class LodInvalidResult(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LodArtifactUnavailable(cause: Throwable) :
    RuntimeException("LOD object transfer unavailable", cause)

interface LodBlobs {
    fun sourceUrl(sha: String, trust: MapTrust): String

    fun workUrl(key: String): String

    fun read(key: String, max: Long): ByteArray

    /** Returns the exact source version observed on both sides of this bounded download. */
    fun download(key: String, into: Path, max: Long): BlobMetadata

    fun pinPrivate(key: String, destination: String, size: Long, expectedETag: String)

    fun promote(key: String, destination: String, size: Long, trust: MapTrust, expectedETag: String)
}

@ApplicationScoped
class R2LodBlobs
@Inject
constructor(
    private val blobs: BlobStore,
    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "grounds.maps.lod.pilot.isolated",
        defaultValue = "false",
    )
    private val isolated: Boolean = false,
) : LodBlobs {
    private val ttl = Duration.ofMinutes(30)

    override fun sourceUrl(sha: String, trust: MapTrust): String {
        if (!isolated) return blobs.presignGetPublic(BlobStore.bundleKey(sha), trust, ttl)
        require(trust == MapTrust.FIRST_PARTY) { "isolated pilot requires first-party input" }
        val base = blobs.publicBaseFor(trust)
        require(java.net.URI.create(base).scheme == "https") { "pilot CDN must use HTTPS" }
        return "$base/${BlobStore.bundleKey(sha)}"
    }

    override fun workUrl(key: String): String = blobs.presignPut(key, ttl)

    override fun read(key: String, max: Long): ByteArray = blobs.getPrivate(key, max)

    override fun download(key: String, into: Path, max: Long): BlobMetadata {
        val before = blobs.headPrivate(key) ?: throw LodInvalidResult("missing LOD output")
        if (before.sizeBytes !in 1..max || before.eTag == null)
            throw LodInvalidResult("invalid LOD output metadata")
        try {
            LodHttp().download(blobs.presignGet(key, ttl), into, max)
        } catch (e: Exception) {
            throw LodArtifactUnavailable(e)
        }
        val after = blobs.headPrivate(key)
        if (after != before || Files.size(into) != before.sizeBytes)
            throw LodInvalidResult("LOD output changed during verification")
        return before
    }

    override fun pinPrivate(key: String, destination: String, size: Long, expectedETag: String) =
        blobs.pinPrivateObject(key, destination, size, expectedETag)

    override fun promote(
        key: String,
        destination: String,
        size: Long,
        trust: MapTrust,
        expectedETag: String,
    ) = blobs.copyPrivateToPublic(key, destination, size, trust, expectedETag)
}

interface LodArtifacts {
    fun requestJson(c: LodClaim): String

    fun accept(c: LodClaim): LodFacts

    fun publish(c: LodClaim)
}

/** Verification is separate from public promotion: a pilot can finish and remain private. */
@ApplicationScoped
class LodArtifactStore @Inject constructor(private val blobs: LodBlobs, json: ObjectMapper) :
    LodArtifacts {
    private val mapper =
        json
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)

    fun request(c: LodClaim): LodJobRequest =
        LodJobRequest(
            attempt = c.attempt.toString(),
            mapBundleSha256 = c.mapBundleSha256,
            sourceBundleSha256 = c.sourceBundleSha256,
            generatorSha256 = c.generatorSha256,
            workerImageSha256 = c.workerImageSha256,
            sourceUrl = blobs.sourceUrl(c.sourceBundleSha256, c.trust),
            bundleUrl = blobs.workUrl(c.workKey("bundle.zip")),
            manifestUrl = blobs.workUrl(c.workKey("manifest.json")),
            resultUrl = blobs.workUrl(c.workKey("result.json")),
        )

    override fun requestJson(c: LodClaim): String = mapper.writeValueAsString(request(c))

    override fun accept(c: LodClaim): LodFacts {
        val verified = verify(c)
        val facts = verified.facts
        blobs.pinPrivate(
            c.workKey("bundle.zip"),
            privateBundleKey(c.trust, facts.bundleSha256),
            facts.bundleBytes,
            verified.bundleETag,
        )
        blobs.pinPrivate(
            c.workKey("manifest.json"),
            privateManifestKey(c.trust, facts.manifestSha256),
            facts.manifestBytes,
            verified.manifestETag,
        )
        return facts
    }

    override fun publish(c: LodClaim) {
        val verified = verify(c, pinned = true)
        require(verified.facts == c.facts) { "ready LOD facts changed" }
        val facts = verified.facts
        blobs.promote(
            privateBundleKey(c.trust, facts.bundleSha256),
            bundleKey(facts.bundleSha256),
            facts.bundleBytes,
            c.trust,
            verified.bundleETag,
        )
        // Manifest is public last, then the DB projection becomes publishable.
        blobs.promote(
            privateManifestKey(c.trust, facts.manifestSha256),
            manifestKey(facts.manifestSha256),
            facts.manifestBytes,
            c.trust,
            verified.manifestETag,
        )
    }

    private fun verify(c: LodClaim, pinned: Boolean = false): Verified {
        val work = Files.createTempDirectory("lod-verify-")
        try {
            val facts =
                if (pinned) requireNotNull(c.facts) { "missing ready facts" }
                else {
                    val result: LodJobResult =
                        try {
                            mapper.readValue(blobs.read(c.workKey("result.json"), 64L * 1024))
                        } catch (e: com.fasterxml.jackson.core.JacksonException) {
                            throw LodInvalidResult("invalid LOD result JSON", e)
                        }
                    require(
                        result.schemaVersion == 1 &&
                            result.format == "lss20" &&
                            result.error == null
                    ) {
                        "worker did not complete"
                    }
                    require(
                        result.attempt == c.attempt.toString() &&
                            result.mapBundleSha256 == c.mapBundleSha256 &&
                            result.sourceBundleSha256 == c.sourceBundleSha256 &&
                            result.generatorSha256 == c.generatorSha256 &&
                            result.workerImageSha256 == c.workerImageSha256 &&
                            result.registrySha256 == c.registrySha256
                    ) {
                        "result identity mismatch"
                    }
                    LodFacts(
                        requireNotNull(result.bundleSha256),
                        requireNotNull(result.bundleBytes),
                        requireNotNull(result.manifestSha256),
                        requireNotNull(result.manifestBytes),
                        requireNotNull(result.columns),
                    )
                }
            val manifestFile = work.resolve("manifest.json")
            val manifestMeta =
                blobs.download(
                    if (pinned) privateManifestKey(c.trust, facts.manifestSha256)
                    else c.workKey("manifest.json"),
                    manifestFile,
                    1024L * 1024,
                )
            require(
                Files.size(manifestFile) == facts.manifestBytes &&
                    digest(manifestFile) == facts.manifestSha256
            ) {
                "manifest integrity mismatch"
            }
            val manifest: LodManifest =
                try {
                    mapper.readValue(Files.readAllBytes(manifestFile))
                } catch (e: com.fasterxml.jackson.core.JacksonException) {
                    throw LodInvalidResult("invalid manifest JSON", e)
                }
            manifest.validate()
            require(
                manifest.sourceBundleSha256 == c.sourceBundleSha256 &&
                    manifest.generatorSha256 == c.generatorSha256 &&
                    manifest.registrySha256 == c.registrySha256 &&
                    manifest.columns == facts.columns
            ) {
                "manifest identity mismatch"
            }
            val bundleFile = work.resolve("bundle.zip")
            val bundleMeta =
                blobs.download(
                    if (pinned) privateBundleKey(c.trust, facts.bundleSha256)
                    else c.workKey("bundle.zip"),
                    bundleFile,
                    LodBounds.MAX_BUNDLE_BYTES,
                )
            require(Files.size(bundleFile) == facts.bundleBytes) { "bundle size mismatch" }
            val bundle = LodBundle.open(bundleFile, facts.bundleSha256)
            require(bundle.manifest == manifest && bundle.manifestSha256 == facts.manifestSha256) {
                "bundle manifest mismatch"
            }
            return Verified(
                facts,
                requireNotNull(bundleMeta.eTag),
                requireNotNull(manifestMeta.eTag),
            )
        } catch (e: IllegalArgumentException) {
            throw LodInvalidResult("LOD output failed validation", e)
        } catch (e: BlobIntegrityException) {
            throw LodInvalidResult("LOD output exceeds bounds", e)
        } catch (e: NoSuchKeyException) {
            throw LodInvalidResult("LOD output is incomplete", e)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private data class Verified(
        val facts: LodFacts,
        val bundleETag: String,
        val manifestETag: String,
    )

    companion object {
        fun privateBundleKey(trust: MapTrust, sha: String) =
            "lod/${trust.name.lowercase()}/lss20/$sha/bundle.zip"

        fun privateManifestKey(trust: MapTrust, sha: String) =
            "lod/${trust.name.lowercase()}/lss20/$sha/manifest.json"

        fun bundleKey(sha: String) = "lod/lss20/$sha/bundle.zip"

        fun manifestKey(sha: String) = "lod/lss20/$sha/manifest.json"
    }
}
