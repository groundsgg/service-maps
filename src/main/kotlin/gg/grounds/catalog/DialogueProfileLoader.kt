package gg.grounds.catalog

import gg.grounds.derive.DialogueContentContracts
import gg.grounds.derive.DialogueHostProfile
import gg.grounds.derive.WorkerHttpTransfer
import gg.grounds.domain.DialogueProfilePin
import java.nio.file.Files
import java.nio.file.Path

/** Reuses bounded worker transfers; downloaded bytes cannot become executable catalog code. */
class DialogueProfileLoader internal constructor(private val transfer: WorkerHttpTransfer) {
    constructor() : this(WorkerHttpTransfer(false, 1048576))

    fun load(pin: DialogueProfilePin): DialogueHostProfile {
        var directory: Path? = null
        try {
            directory = Files.createTempDirectory("dialogue-profile-")
            val file = directory.resolve("profile.json")
            val actualDigest = transfer.download(pin.uri, file)
            if (actualDigest != pin.sha256 || Files.size(file) != pin.sizeBytes)
                throw CatalogTransferException("Dialogue profile digest or size mismatch")
            val profile = DialogueContentContracts.readProfile(Files.readAllBytes(file))
            if (profile.id != pin.id || profile.version != pin.version)
                throw CatalogTransferException("Dialogue profile identity mismatch")
            return profile
        } catch (failure: CatalogTransferException) {
            throw failure
        } catch (failure: Exception) {
            throw CatalogTransferException("Dialogue host profile is unavailable or invalid", failure)
        } finally {
            directory?.let { path ->
                Files.deleteIfExists(path.resolve("profile.json"))
                Files.deleteIfExists(path)
            }
        }
    }
}
