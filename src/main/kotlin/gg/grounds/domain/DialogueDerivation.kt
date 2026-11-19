package gg.grounds.domain

import java.net.URI

/** UNKNOWN is a legacy/uninspected state, never an absence attestation. */
enum class DialogueStatus {
    UNKNOWN,
    PENDING,
    NONE,
    VALID,
    INVALID,
}

data class DialogueProfileReference(val id: String, val version: String, val sha256: String) {
    init {
        requireIdentity(id, version)
        requireDigest(sha256)
    }
}

data class DialogueProfilePin(
    val id: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val uri: URI,
) {
    init {
        requireIdentity(id, version)
        requireDigest(sha256)
        require(sizeBytes in 1..1048576)
        require(
            uri.scheme == "https" &&
                uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        )
    }

    fun reference() = DialogueProfileReference(id, version, sha256)
}

data class DialogueFilePin(val path: String, val sha256: String, val sizeBytes: Long) {
    init {
        require(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= 1024)
        require(
            !path.startsWith('/') &&
                '\\' !in path &&
                path.split('/').none { it == "." || it == ".." || it.isEmpty() }
        )
        requireDigest(sha256)
        require(sizeBytes >= 0)
    }
}

data class DialogueRequirement(
    val kind: String,
    val key: String,
    val speaker: String? = null,
    val required: Boolean = true,
) {
    init {
        require(kind in setOf("speaker", "gesture", "action", "condition", "capability", "outcome"))
        require(key.isNotBlank() && key.length <= 512)
        require(speaker == null || speaker.isNotBlank())
    }
}

/** Value facts, with defensive collection copies so a caller cannot mutate an accepted proof. */
class DialogueProof(
    val sourceSha256: String,
    val workerImage: String,
    val executionSha256: String,
    val schemaVersion: Int = 1,
    val proof: String = "STATIC_VALIDATION",
    val sceneSha256: String? = null,
    val assetCatalog: CatalogReference? = null,
    val actionCatalog: CatalogReference? = null,
    val validatorCoordinate: String = "gg.grounds:dialogue-json:0.1.0",
    val profile: DialogueProfilePin? = null,
    val hostCatalogSha256: String? = null,
    val contentPin: String? = null,
    files: List<DialogueFilePin> = emptyList(),
    dialogueIds: List<String> = emptyList(),
    requirements: List<DialogueRequirement> = emptyList(),
    val bindingsSchemaVersion: Int? = null,
) {
    val files: List<DialogueFilePin> = java.util.List.copyOf(files)
    val dialogueIds: List<String> = java.util.List.copyOf(dialogueIds)
    val requirements: List<DialogueRequirement> = java.util.List.copyOf(requirements)

    init {
        require(schemaVersion == 1 && proof == "STATIC_VALIDATION")
        requireDigest(sourceSha256)
        requireDigest(executionSha256)
        require(workerImage.matches(Regex("[^\\s]+@sha256:[0-9a-f]{64}")))
        require(validatorCoordinate == "gg.grounds:dialogue-json:0.1.0")
        listOfNotNull(sceneSha256, hostCatalogSha256, contentPin).forEach(::requireDigest)
        require(this.files.map { it.path }.distinct().size == this.files.size)
        require(
            this.dialogueIds.distinct().size == this.dialogueIds.size &&
                this.dialogueIds.all { it.isNotBlank() }
        )
    }

    private fun values(): List<Any?> =
        listOf(
            sourceSha256,
            workerImage,
            executionSha256,
            schemaVersion,
            proof,
            sceneSha256,
            assetCatalog,
            actionCatalog,
            validatorCoordinate,
            profile,
            hostCatalogSha256,
            contentPin,
            files,
            dialogueIds,
            requirements,
            bindingsSchemaVersion,
        )

    override fun equals(other: Any?) = other is DialogueProof && values() == other.values()

    override fun hashCode() = values().hashCode()
}

class DialogueProjection(
    val status: DialogueStatus = DialogueStatus.UNKNOWN,
    val proof: DialogueProof? = null,
    problems: List<DeriveProblem> = emptyList(),
) {
    val problems: List<DeriveProblem> = java.util.List.copyOf(problems)

    init {
        when (status) {
            DialogueStatus.UNKNOWN,
            DialogueStatus.PENDING,
            DialogueStatus.INVALID -> require(proof == null)
            DialogueStatus.NONE -> {
                requireNotNull(proof)
                require(
                    proof.profile == null &&
                        proof.hostCatalogSha256 == null &&
                        proof.contentPin == null &&
                        proof.bindingsSchemaVersion == null &&
                        proof.files.isEmpty() &&
                        proof.dialogueIds.isEmpty() &&
                        proof.requirements.isEmpty()
                )
            }
            DialogueStatus.VALID -> {
                requireNotNull(proof)
                require(
                    proof.profile != null &&
                        proof.hostCatalogSha256 != null &&
                        proof.contentPin != null &&
                        proof.sceneSha256 != null &&
                        proof.assetCatalog != null &&
                        proof.actionCatalog != null &&
                        proof.bindingsSchemaVersion == 1 &&
                        proof.dialogueIds.isNotEmpty() &&
                        proof.files.isNotEmpty()
                )
            }
        }
        if (status == DialogueStatus.NONE || status == DialogueStatus.VALID)
            require(this.problems.none { it.severity == null || it.severity == "ERROR" })
    }

    override fun equals(other: Any?) =
        other is DialogueProjection &&
            status == other.status &&
            proof == other.proof &&
            problems == other.problems

    override fun hashCode() = listOf(status, proof, problems).hashCode()
}

private fun requireDigest(value: String) {
    require(value.matches(Regex("[0-9a-f]{64}")))
}

private fun requireIdentity(id: String, version: String) {
    require(id.isNotBlank() && id.length <= 128 && version.isNotBlank() && version.length <= 128)
}

/**
 * Stored once per attempt; includes existing Scene inputs so restart cannot change normalization.
 */
class DeriveExecutionContract(
    val schemaVersion: Int,
    val workerImage: String,
    dialogueProfiles: List<DialogueProfilePin>,
    catalogCandidates: List<gg.grounds.derive.AssetCatalogCandidate> = emptyList(),
    val normalizationCatalog: CatalogReference? = null,
    shapeRequirements: SceneShapeRequirements? = null,
) {
    val dialogueProfiles: List<DialogueProfilePin> = java.util.List.copyOf(dialogueProfiles)
    val catalogCandidates: List<gg.grounds.derive.AssetCatalogCandidate> =
        java.util.List.copyOf(catalogCandidates)
    val shapeRequirements: SceneShapeRequirements? =
        shapeRequirements?.let {
            SceneShapeRequirements(
                java.util.Collections.unmodifiableSet(LinkedHashSet(it.elements.sorted())),
                java.util.Collections.unmodifiableSet(LinkedHashSet(it.prefixes.sorted())),
            )
        }

    init {
        require(schemaVersion in 1..2)
        require(workerImage.matches(Regex("[^\\s]+@sha256:[0-9a-f]{64}")))
        require(this.dialogueProfiles.size <= 8)
        require(
            this.dialogueProfiles.map { it.id to it.version }.distinct().size ==
                this.dialogueProfiles.size
        )
        require(schemaVersion != 1 || this.dialogueProfiles.isEmpty())
    }

    fun fingerprint(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(gg.grounds.derive.CanonicalJson.write(this))
            .joinToString("") { "%02x".format(it) }

    private fun values() =
        listOf(
            schemaVersion,
            workerImage,
            dialogueProfiles,
            catalogCandidates,
            normalizationCatalog,
            shapeRequirements,
        )

    override fun equals(other: Any?) =
        other is DeriveExecutionContract && values() == other.values()

    override fun hashCode() = values().hashCode()
}
