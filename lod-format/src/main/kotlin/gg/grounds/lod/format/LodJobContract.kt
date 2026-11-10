package gg.grounds.lod.format

import java.util.UUID

/** Three scoped output PUTs, one input GET. No storage credentials or discovery/list operation. */
data class LodJobRequest(
    val schemaVersion: Int = 1,
    val format: String = "lss20",
    val attempt: String,
    val mapBundleSha256: String,
    val sourceBundleSha256: String,
    val generatorSha256: String,
    val workerImageSha256: String,
    val sourceUrl: String,
    val bundleUrl: String,
    val manifestUrl: String,
    val resultUrl: String,
) {
    fun validate() {
        require(schemaVersion == 1 && format == "lss20") { "unsupported job format" }
        require(UUID.fromString(attempt).toString() == attempt) { "invalid attempt" }
        require(
            listOf(mapBundleSha256, sourceBundleSha256, generatorSha256, workerImageSha256)
                .all(::isDigest)
        ) {
            "invalid job identity"
        }
        require(setOf(sourceUrl, bundleUrl, manifestUrl, resultUrl).size == 4) {
            "job URLs must be distinct"
        }
    }
}

data class LodJobResult(
    val schemaVersion: Int = 1,
    val format: String,
    val attempt: String,
    val mapBundleSha256: String,
    val sourceBundleSha256: String,
    val generatorSha256: String,
    val workerImageSha256: String,
    val bundleSha256: String? = null,
    val bundleBytes: Long? = null,
    val manifestSha256: String? = null,
    val manifestBytes: Long? = null,
    val registrySha256: String? = null,
    val columns: Int? = null,
    val error: String? = null,
)
