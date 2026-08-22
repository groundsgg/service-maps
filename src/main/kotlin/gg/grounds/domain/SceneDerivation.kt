package gg.grounds.domain

enum class DeriveFailureScope {
    CONTENT,
    SYSTEM,
}

enum class SceneStatus {
    PENDING,
    INVALID,
    NONE,
    VALID,
}

data class CatalogReference(val id: String, val version: String)

data class DeriveProblem(
    val scope: DeriveFailureScope,
    val path: String?,
    val code: String,
    val qualifiedIdentity: String?,
    val message: String,
)

data class SceneProjection(
    val status: SceneStatus,
    val schemaVersion: String?,
    val sha256: String?,
    val assetCatalog: CatalogReference?,
    val actionCatalog: CatalogReference?,
    val requiredActions: List<String>,
    val problems: List<DeriveProblem>,
)
