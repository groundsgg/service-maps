package gg.grounds.derive

import gg.grounds.blob.BlobStore
import gg.grounds.catalog.PackSetCatalogProvider
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.VersionState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.time.Duration
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class DeriveCoordinator
@Inject
constructor(
    private val versions: MapVersionRepository,
    private val jobs: DeriveJobGateway,
    private val blobs: BlobStore?,
    private val catalogs: PackSetCatalogProvider?,
    @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "grounds.maps.derive.url-ttl", defaultValue = "30m")
    private val urlTtl: Duration,
) {
    init {
        require(!enabled || urlTtl >= MINIMUM_URL_TTL) {
            "derive URL TTL must be at least 30 minutes"
        }
    }

    /** Test-only constructor keeps orchestration independent of R2 and a live PackSet client. */
    private var testCandidates: (() -> List<AssetCatalogCandidate>)? = null

    constructor(
        versions: MapVersionRepository,
        jobs: DeriveJobGateway,
        candidates: () -> List<AssetCatalogCandidate>,
        enabled: Boolean,
    ) : this(versions, jobs, null, null, enabled, BlobStore.PRESIGN_TTL) {
        testCandidates = candidates
    }

    fun coordinate(mapId: UUID, version: Int) {
        if (!enabled) return
        val claimed = versions.claimForDerive(mapId, version, UUID.randomUUID()) ?: return
        createFor(
            claimed.mapId,
            claimed.version,
            requireNotNull(claimed.deriveAttempt),
            requireNotNull(claimed.sourceSha256),
            requireNotNull(claimed.sourceKey),
        )
    }

    fun ensure(record: gg.grounds.domain.MapVersionRecord) {
        val attempt = record.deriveAttempt ?: return
        val sourceSha256 = record.sourceSha256 ?: return
        ensure(
            record,
            jobs.find(DeriveIdentity(record.mapId, record.version, attempt, sourceSha256)),
        )
    }

    fun ensure(record: gg.grounds.domain.MapVersionRecord, status: DeriveJobStatus?) {
        if (!enabled || record.state != VersionState.DERIVING) return
        val attempt = record.deriveAttempt ?: return
        val identity =
            DeriveIdentity(
                record.mapId,
                record.version,
                attempt,
                requireNotNull(record.sourceSha256),
            )
        if (status == null || status == DeriveJobStatus.MISSING)
            createFor(
                record.mapId,
                record.version,
                attempt,
                identity.sourceSha256,
                requireNotNull(record.sourceKey),
            )
    }

    private fun createFor(
        mapId: UUID,
        version: Int,
        attempt: UUID,
        sourceSha256: String,
        sourceKey: String,
    ) {
        val identity = DeriveIdentity(mapId, version, attempt, sourceSha256)
        val test = testCandidates
        val request =
            if (test != null)
                DeriveRequest(
                    mapId = mapId,
                    version = version,
                    attempt = attempt,
                    sourceSha256 = sourceSha256,
                    sourceUrl = URI("https://example.test/source"),
                    bundleUrl = URI("https://example.test/bundle"),
                    manifestUrl = URI("https://example.test/manifest"),
                    resultUrl = URI("https://example.test/result"),
                    catalogCandidates = test().take(2),
                )
            else
                DeriveRequest(
                    mapId = mapId,
                    version = version,
                    attempt = attempt,
                    sourceSha256 = sourceSha256,
                    sourceUrl = URI(requireNotNull(blobs).presignGet(sourceKey, urlTtl)),
                    bundleUrl =
                        URI(
                            requireNotNull(blobs)
                                .presignPut(
                                    BlobStore.deriveBundleKey(mapId, version, attempt),
                                    urlTtl,
                                )
                        ),
                    manifestUrl =
                        URI(
                            requireNotNull(blobs)
                                .presignPut(
                                    BlobStore.deriveManifestKey(mapId, version, attempt),
                                    urlTtl,
                                )
                        ),
                    resultUrl =
                        URI(
                            requireNotNull(blobs)
                                .presignPut(
                                    BlobStore.deriveResultKey(mapId, version, attempt),
                                    urlTtl,
                                )
                        ),
                    catalogCandidates = requireNotNull(catalogs).candidates().take(2),
                )
        jobs.create(DeriveJobRequest(identity, CanonicalJson.write(request).decodeToString()))
    }

    private companion object {
        val MINIMUM_URL_TTL: Duration = Duration.ofMinutes(30)
    }
}
