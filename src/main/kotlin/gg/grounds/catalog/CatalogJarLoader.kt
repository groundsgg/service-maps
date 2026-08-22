package gg.grounds.catalog

import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.scene.format.AssetCatalog
import java.net.HttpURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

class CatalogContentException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Downloads and loads a generated catalog only after treating its JAR as hostile input. */
class CatalogJarLoader(
    private val cacheDirectory: Path,
    private val maxCatalogBytes: Long = 32L * 1024 * 1024,
) : AutoCloseable {
    private val loaded = mutableListOf<URLClassLoader>()

    init {
        require(maxCatalogBytes > 0)
    }

    fun load(candidate: AssetCatalogCandidate): AssetCatalog {
        require(candidate.size in 1..maxCatalogBytes) {
            "Catalog size is outside the accepted limit."
        }
        require(Regex("[0-9a-f]{64}").matches(candidate.sha256)) { "Catalog SHA-256 is invalid." }
        require(candidate.uri.scheme == "https") { "Catalog URL must use HTTPS." }
        val bytes = download(candidate)
        inspect(bytes)
        Files.createDirectories(cacheDirectory)
        val jar = Files.createTempFile(cacheDirectory, "catalog-", ".jar")
        Files.write(jar, bytes)
        try {
            val loader =
                URLClassLoader(arrayOf(jar.toUri().toURL()), AssetCatalog::class.java.classLoader)
            val owner = loader.loadClass(OWNER_CLASS)
            val instance = owner.getField("INSTANCE").get(null)
            val catalog =
                owner.getMethod("getCatalog").invoke(instance) as? AssetCatalog
                    ?: throw CatalogContentException(
                        "Catalog owner did not return an AssetCatalog."
                    )
            if (catalog.id.value != candidate.id || catalog.version != candidate.version) {
                throw CatalogContentException(
                    "Catalog identity does not match the validated candidate."
                )
            }
            loaded += loader
            return catalog
        } catch (failure: CatalogContentException) {
            Files.deleteIfExists(jar)
            throw failure
        } catch (failure: Exception) {
            Files.deleteIfExists(jar)
            throw CatalogContentException("Catalog owner could not be loaded.", failure)
        }
    }

    private fun download(candidate: AssetCatalogCandidate): ByteArray {
        val connection = candidate.uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val response = connection
            if (response.responseCode !in 200..299)
                throw CatalogContentException("Catalog download failed.")
            if (response.contentLengthLong >= 0 && response.contentLengthLong != candidate.size)
                throw CatalogContentException("Catalog response size does not match manifest.")
            val bytes =
                response.inputStream.use { input -> input.readNBytes((candidate.size + 1).toInt()) }
            if (bytes.size.toLong() != candidate.size || sha256(bytes) != candidate.sha256)
                throw CatalogContentException("Catalog digest does not match manifest.")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun inspect(bytes: ByteArray) {
        val temp = Files.createTempFile("catalog-inspect-", ".jar")
        try {
            Files.write(temp, bytes)
            CommonsZipFile(temp.toFile()).use { zip ->
                val names = mutableSetOf<String>()
                var entries = 0
                var expanded = 0L
                var owners = 0
                zip.entries.asSequence().forEach { entry: ZipArchiveEntry ->
                    if (++entries > MAX_ENTRIES || !safeName(entry.name) || !names.add(entry.name))
                        throw CatalogContentException("Catalog JAR has unsafe entries.")
                    if (entry.isUnixSymlink)
                        throw CatalogContentException("Catalog JAR contains a symlink.")
                    expanded += entry.size.coerceAtLeast(0)
                    if (expanded > MAX_EXPANDED_BYTES)
                        throw CatalogContentException("Catalog JAR expands too far.")
                    if (entry.name == OWNER_ENTRY) owners++
                }
                if (owners != 1)
                    throw CatalogContentException(
                        "Catalog JAR must define exactly one catalog owner."
                    )
            }
        } catch (failure: CatalogContentException) {
            throw failure
        } catch (failure: Exception) {
            throw CatalogContentException("Catalog JAR is invalid.", failure)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith('/') &&
            !name.contains('\\') &&
            name.split('/').none { it == ".." || it == "." || it.isEmpty() }

    override fun close() {
        loaded.forEach(URLClassLoader::close)
        loaded.clear()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val OWNER_CLASS = "gg.grounds.resourcepacks.catalog.GroundsAssetCatalog"
        const val OWNER_ENTRY = "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
        const val MAX_ENTRIES = 512
        const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
    }
}
