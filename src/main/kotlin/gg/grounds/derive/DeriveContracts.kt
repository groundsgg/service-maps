package gg.grounds.derive

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import java.net.URI
import java.util.UUID

private const val CONTRACT_SCHEMA_VERSION = 1
private val SHA_256 = Regex("[0-9a-f]{64}")

data class AssetCatalogCandidate(
    val channel: String,
    val id: String,
    val version: String,
    val coordinate: String,
    val file: String,
    val uri: URI,
    val sha256: String,
    val size: Long,
) {
    init {
        require(channel.isNotBlank() && id.isNotBlank() && version.isNotBlank()) {
            "catalog identity is required"
        }
        require(coordinate.isNotBlank() && file.isNotBlank()) { "catalog location is required" }
        requireSha256("sha256", sha256)
        requireNonNegative("size", size)
        requireHttpUrl("uri", uri)
    }
}

data class DeriveRequest(
    val schemaVersion: Int = CONTRACT_SCHEMA_VERSION,
    val mapId: UUID,
    val version: Int,
    val attempt: UUID,
    val sourceSha256: String,
    val sourceUrl: URI,
    val bundleUrl: URI,
    val manifestUrl: URI,
    val resultUrl: URI,
    val catalogCandidates: List<AssetCatalogCandidate>,
) {
    init {
        requireSchema(schemaVersion)
        require(version >= 0) { "version must be non-negative" }
        requireSha256("sourceSha256", sourceSha256)
        requireHttpUrl("sourceUrl", sourceUrl)
        requireHttpUrl("bundleUrl", bundleUrl)
        requireHttpUrl("manifestUrl", manifestUrl)
        requireHttpUrl("resultUrl", resultUrl)
    }
}

enum class DeriveResultKind {
    SUCCESS,
    FAILURE,
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = DeriveSuccess::class, name = "SUCCESS"),
    JsonSubTypes.Type(value = DeriveFailure::class, name = "FAILURE"),
)
sealed interface DeriveResult {
    val kind: DeriveResultKind
    val schemaVersion: Int
    val mapId: UUID
    val version: Int
    val attempt: UUID
    val sourceSha256: String
}

@JsonTypeName("SUCCESS")
data class DeriveSuccess
@JsonCreator
constructor(
    @param:JsonProperty("schemaVersion") override val schemaVersion: Int = CONTRACT_SCHEMA_VERSION,
    @param:JsonProperty("mapId") override val mapId: UUID,
    @param:JsonProperty("version") override val version: Int,
    @param:JsonProperty("attempt") override val attempt: UUID,
    @param:JsonProperty("sourceSha256") override val sourceSha256: String,
    @param:JsonProperty("bundleSha256") val bundleSha256: String,
    @param:JsonProperty("bundleSize") val bundleSize: Long,
    @param:JsonProperty("manifestSha256") val manifestSha256: String,
    @param:JsonProperty("manifestSize") val manifestSize: Long,
    @param:JsonProperty("scene") val scene: DerivedScene,
    @param:JsonProperty("kind") override val kind: DeriveResultKind = DeriveResultKind.SUCCESS,
) : DeriveResult {
    init {
        require(kind == DeriveResultKind.SUCCESS) { "success result kind must be SUCCESS" }
        requireIdentity(schemaVersion, version, sourceSha256)
        requireSha256("bundleSha256", bundleSha256)
        requireNonNegative("bundleSize", bundleSize)
        requireSha256("manifestSha256", manifestSha256)
        requireNonNegative("manifestSize", manifestSize)
    }
}

@JsonTypeName("FAILURE")
data class DeriveFailure
@JsonCreator
constructor(
    @param:JsonProperty("schemaVersion") override val schemaVersion: Int = CONTRACT_SCHEMA_VERSION,
    @param:JsonProperty("mapId") override val mapId: UUID,
    @param:JsonProperty("version") override val version: Int,
    @param:JsonProperty("attempt") override val attempt: UUID,
    @param:JsonProperty("sourceSha256") override val sourceSha256: String,
    @param:JsonProperty("scope") val scope: DeriveFailureScope,
    @param:JsonProperty("retryable") val retryable: Boolean,
    @param:JsonProperty("problems") val problems: List<DeriveProblem>,
    @param:JsonProperty("kind") override val kind: DeriveResultKind = DeriveResultKind.FAILURE,
) : DeriveResult {
    init {
        require(kind == DeriveResultKind.FAILURE) { "failure result kind must be FAILURE" }
        requireIdentity(schemaVersion, version, sourceSha256)
        require(problems.isNotEmpty()) { "failure result requires at least one problem" }
        require(retryable == (scope == DeriveFailureScope.SYSTEM)) {
            "retryable must match failure scope"
        }
    }
}

class DerivedScene
@JsonCreator
constructor(
    @param:JsonProperty("present") val present: Boolean,
    @param:JsonProperty("schemaVersion") val schemaVersion: String?,
    @param:JsonProperty("sha256") val sha256: String?,
    @param:JsonProperty("assetCatalog") val assetCatalog: CatalogReference?,
    @param:JsonProperty("actionCatalog") val actionCatalog: CatalogReference?,
    @JsonProperty("requiredActions") requiredActions: List<String>,
) {
    val requiredActions: List<String> = requiredActions.distinct().sorted()

    init {
        if (present) {
            require(!schemaVersion.isNullOrBlank()) { "present scene requires schemaVersion" }
            require(!sha256.isNullOrBlank()) { "present scene requires sha256" }
            requireSha256("scene.sha256", sha256)
            require(assetCatalog != null) { "present scene requires assetCatalog" }
            require(actionCatalog != null) { "present scene requires actionCatalog" }
        } else {
            require(schemaVersion == null && sha256 == null) {
                "absent scene cannot have scene metadata"
            }
            require(assetCatalog == null && actionCatalog == null) {
                "absent scene cannot have catalogs"
            }
            require(requiredActions.isEmpty()) { "absent scene cannot require actions" }
        }
        require(this.requiredActions.none { it.isBlank() }) {
            "required action IDs cannot be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DerivedScene &&
            present == other.present &&
            schemaVersion == other.schemaVersion &&
            sha256 == other.sha256 &&
            assetCatalog == other.assetCatalog &&
            actionCatalog == other.actionCatalog &&
            requiredActions == other.requiredActions

    override fun hashCode(): Int =
        listOf(present, schemaVersion, sha256, assetCatalog, actionCatalog, requiredActions)
            .hashCode()

    override fun toString(): String =
        "DerivedScene(present=$present, schemaVersion=$schemaVersion, sha256=$sha256, assetCatalog=$assetCatalog, actionCatalog=$actionCatalog, requiredActions=$requiredActions)"
}

class DerivedManifest
@JsonCreator
constructor(
    @param:JsonProperty("schemaVersion") val schemaVersion: Int = CONTRACT_SCHEMA_VERSION,
    @param:JsonProperty("sourceSha256") val sourceSha256: String,
    @JsonProperty("bundleDigestInputs") bundleDigestInputs: List<String>,
    @param:JsonProperty("scene") val scene: DerivedScene,
) {
    val bundleDigestInputs: List<String> = bundleDigestInputs.distinct().sorted()

    init {
        requireSchema(schemaVersion)
        requireSha256("sourceSha256", sourceSha256)
        require(this.bundleDigestInputs.none { it.isBlank() }) {
            "bundle digest inputs cannot be blank"
        }
    }
}

private fun requireIdentity(schemaVersion: Int, version: Int, sourceSha256: String) {
    requireSchema(schemaVersion)
    require(version >= 0) { "version must be non-negative" }
    requireSha256("sourceSha256", sourceSha256)
}

private fun requireSchema(schemaVersion: Int) {
    require(schemaVersion == CONTRACT_SCHEMA_VERSION) {
        "unsupported schemaVersion: $schemaVersion"
    }
}

private fun requireSha256(name: String, value: String) {
    require(SHA_256.matches(value)) { "$name must be 64 lowercase hexadecimal characters" }
}

private fun requireNonNegative(name: String, value: Long) {
    require(value >= 0) { "$name must be non-negative" }
}

private fun requireHttpUrl(name: String, value: URI) {
    require(value.scheme == "https" || value.scheme == "http") { "$name must use http or https" }
    require(!value.host.isNullOrBlank() && value.userInfo == null && value.fragment == null) {
        "$name must be an absolute URL without user info or fragment"
    }
}
