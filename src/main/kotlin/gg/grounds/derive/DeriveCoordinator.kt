package gg.grounds.derive

import gg.grounds.blob.BlobStore
import gg.grounds.catalog.PackSetCatalogProvider
import gg.grounds.catalog.PinnedDialogueProfiles
import gg.grounds.domain.DeriveExecutionContract
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.VersionState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.time.Duration
import java.util.Optional
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
    private val runtimes: gg.grounds.compatibility.MapRuntimeCompatibility? = null,
    private val maps: gg.grounds.domain.MapRepository? = null,
    @param:ConfigProperty(name = "grounds.maps.derive.image")
    private val configuredImage: Optional<String> = Optional.empty(),
    @param:ConfigProperty(name = "grounds.maps.derive.request-schema-version", defaultValue = "1")
    private val requestSchemaVersion: Int = 1,
    @param:ConfigProperty(name = "grounds.maps.dialogue.profiles-json", defaultValue = "[]")
    profilesJson: String = "[]",
    @param:ConfigProperty(name = "grounds.maps.dialogue.profile-origin", defaultValue = "")
    profileOrigin: Optional<String> = Optional.empty(),
) {
    private val configuredProfiles =
        PinnedDialogueProfiles.parse(profilesJson, URI(profileOrigin.orElse(""))).pins()

    init {
        require(requestSchemaVersion in 1..2) { "unsupported derive request schema" }
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
        runtimes: gg.grounds.compatibility.MapRuntimeCompatibility? = null,
        maps: gg.grounds.domain.MapRepository? = null,
        workerImage: String = "worker-test@sha256:${"a".repeat(64)}",
        requestSchemaVersion: Int = 1,
    ) : this(
        versions,
        jobs,
        null,
        null,
        enabled,
        BlobStore.PRESIGN_TTL,
        runtimes,
        maps,
        Optional.of(workerImage),
        requestSchemaVersion,
    ) {
        testCandidates = candidates
    }

    fun coordinate(mapId: UUID, version: Int) {
        if (!enabled) return
        val claimed = versions.claimForDerive(mapId, version, UUID.randomUUID()) ?: return
        try {
            createFor(claimed)
        } catch (failure: Exception) {
            throw DeriveJobCreationException(claimed, failure)
        }
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
        if (status == null || status == DeriveJobStatus.MISSING) createFor(record)
    }

    private fun createFor(record: MapVersionRecord) {
        val mapId = record.mapId
        val version = record.version
        val attempt = requireNotNull(record.deriveAttempt)
        val sourceSha256 = requireNotNull(record.sourceSha256)
        val sourceKey = requireNotNull(record.sourceKey)
        val identity = DeriveIdentity(mapId, version, attempt, sourceSha256)
        val test = testCandidates
        val execution =
            record.executionContract
                ?: run {
                    val address = maps?.findById(mapId)?.address?.toString()
                    val schema = if (record.deriveContractLegacy) 1 else requestSchemaVersion
                    versions.bindExecution(
                        identity,
                        DeriveExecutionContract(
                            schema,
                            configuredImage.orElse(""),
                            if (schema == 2) configuredProfiles else emptyList(),
                            if (test != null) test() else requireNotNull(catalogs).candidates(),
                            address?.let { runtimes?.normalizationCatalog(it) },
                            address?.let { runtimes?.shapeRequirements(it) },
                        ),
                    )
                }
        fun source() =
            if (test != null) URI("https://example.test/source")
            else URI(requireNotNull(blobs).presignGet(sourceKey, urlTtl))
        fun output(key: String, testName: String) =
            if (test != null) URI("https://example.test/$testName")
            else URI(requireNotNull(blobs).presignPut(key, urlTtl))
        val request =
            DeriveRequest(
                schemaVersion = execution.schemaVersion,
                mapId = mapId,
                version = version,
                attempt = attempt,
                sourceSha256 = sourceSha256,
                sourceUrl = source(),
                bundleUrl = output(BlobStore.deriveBundleKey(mapId, version, attempt), "bundle"),
                manifestUrl =
                    output(BlobStore.deriveManifestKey(mapId, version, attempt), "manifest"),
                resultUrl = output(BlobStore.deriveResultKey(mapId, version, attempt), "result"),
                catalogCandidates = execution.catalogCandidates,
                normalizationCatalog = execution.normalizationCatalog,
                shapeRequirements = execution.shapeRequirements,
                workerImage = if (execution.schemaVersion == 2) execution.workerImage else null,
                dialogueProfiles = execution.dialogueProfiles,
            )
        jobs.create(
            DeriveJobRequest(
                identity,
                CanonicalJson.write(request).decodeToString(),
                execution.workerImage,
            )
        )
    }

    private companion object {
        val MINIMUM_URL_TTL: Duration = Duration.ofMinutes(30)
    }
}

/** Preserves the immutable claimed identity when creating its Job fails transiently. */
class DeriveJobCreationException(val claimed: MapVersionRecord, cause: Exception) :
    RuntimeException("could not create derive Job for claimed version", cause)
