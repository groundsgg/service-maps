package gg.grounds.catalog

import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.scene.format.AssetCatalog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
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
    private val allowLoopbackHttp: Boolean = false,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxEntryExpandedBytes: Long = MAX_ENTRY_EXPANDED_BYTES,
    private val maxExpandedBytes: Long = MAX_EXPANDED_BYTES,
    internal val deleteJar: (Path) -> Unit = { Files.deleteIfExists(it) },
    internal val beforeLoad: () -> Unit = {},
) : AutoCloseable {
    init {
        require(maxCatalogBytes > 0)
        require(maxEntries > 0)
        require(maxEntryExpandedBytes > 0)
        require(maxExpandedBytes >= maxEntryExpandedBytes)
    }

    fun load(candidate: AssetCatalogCandidate): AssetCatalog {
        require(candidate.size in 1..maxCatalogBytes) {
            "Catalog size is outside the accepted limit."
        }
        require(HEX_SHA256.matches(candidate.sha256)) { "Catalog SHA-256 is invalid." }
        requireAllowedUri(candidate)
        val bytes = download(candidate)
        Files.createDirectories(cacheDirectory)
        val jar = Files.createTempFile(cacheDirectory, "catalog-", ".jar")
        var primary: Throwable? = null
        try {
            Files.write(jar, bytes)
            inspect(jar)
            beforeLoad()
            return loadCatalog(jar, candidate)
        } catch (failure: CatalogContentException) {
            primary = failure
            throw failure
        } catch (failure: LinkageError) {
            val content = CatalogContentException("Catalog owner could not be loaded.", failure)
            primary = content
            throw content
        } catch (failure: Exception) {
            val content = CatalogContentException("Catalog owner could not be loaded.", failure)
            primary = content
            throw content
        } catch (failure: ThreadDeath) {
            primary = failure
            throw failure
        } catch (failure: Error) {
            primary = failure
            throw failure
        } finally {
            try {
                deleteJar(jar)
            } catch (cleanup: Throwable) {
                primary?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    private fun requireAllowedUri(candidate: AssetCatalogCandidate) {
        when (candidate.uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" ->
                require(allowLoopbackHttp && isLoopbackHost(candidate.uri.host)) {
                    "Catalog URL must use HTTPS."
                }
            else -> throw IllegalArgumentException("Catalog URL must use HTTPS.")
        }
    }

    private fun isLoopbackHost(host: String?): Boolean =
        !host.isNullOrBlank() &&
            runCatching { InetAddress.getAllByName(host).all { it.isLoopbackAddress } }
                .getOrDefault(false)

    private fun download(candidate: AssetCatalogCandidate): ByteArray {
        val connection =
            candidate.uri.toURL().openConnection() as? HttpURLConnection
                ?: throw CatalogContentException("Catalog URL is not HTTP.")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            if (connection.responseCode !in 200..299)
                throw CatalogContentException("Catalog download failed.")
            if (
                connection.contentLengthLong >= 0 && connection.contentLengthLong != candidate.size
            ) {
                throw CatalogContentException("Catalog response size does not match manifest.")
            }
            val bytes = ByteArrayOutputStream(candidate.size.toInt())
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var received = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > candidate.size || received > maxCatalogBytes) {
                        throw CatalogContentException("Catalog response exceeds manifest size.")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            val result = bytes.toByteArray()
            if (result.size.toLong() != candidate.size) {
                throw CatalogContentException("Catalog response size does not match manifest.")
            }
            if (sha256(result) != candidate.sha256) {
                throw CatalogContentException("Catalog digest does not match manifest.")
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun inspect(jar: Path) {
        try {
            CommonsZipFile(jar.toFile()).use { zip ->
                val names = mutableSetOf<String>()
                var entries = 0
                var totalExpanded = 0L
                var baseOwnerDefinitions = 0
                var additionalOwnerDefinitions = 0
                zip.entries.asSequence().forEach { entry: ZipArchiveEntry ->
                    val name = entry.rawName?.toString(StandardCharsets.UTF_8) ?: entry.name
                    if (++entries > maxEntries || !safeName(name) || !names.add(name)) {
                        throw CatalogContentException("Catalog JAR has unsafe entries.")
                    }
                    if (!safeEntryType(entry))
                        throw CatalogContentException("Catalog JAR has an unsafe entry type.")
                    var entryExpanded = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryExpanded += count
                            totalExpanded += count
                            if (
                                entryExpanded > maxEntryExpandedBytes ||
                                    totalExpanded > maxExpandedBytes
                            ) {
                                throw CatalogContentException("Catalog JAR expands too far.")
                            }
                        }
                    }
                    if (name == OWNER_ENTRY) baseOwnerDefinitions++
                    else if (isAdditionalOwnerDefinition(name)) additionalOwnerDefinitions++
                }
                if (baseOwnerDefinitions != 1 || additionalOwnerDefinitions != 0) {
                    throw CatalogContentException(
                        "Catalog JAR must define exactly one catalog owner."
                    )
                }
            }
        } catch (failure: CatalogContentException) {
            throw failure
        } catch (failure: Exception) {
            throw CatalogContentException("Catalog JAR is invalid.", failure)
        }
    }

    private fun loadCatalog(jar: Path, candidate: AssetCatalogCandidate): AssetCatalog {
        CatalogClassLoader(arrayOf(jar.toUri().toURL()), AssetCatalog::class.java.classLoader)
            .use { loader ->
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
                return catalog
            }
    }

    private fun safeName(name: String): Boolean {
        if (
            name.isBlank() ||
                name.indexOf('\u0000') >= 0 ||
                name.startsWith('/') ||
                name.contains('\\')
        )
            return false
        if (WINDOWS_DRIVE.matches(name)) return false
        val components = name.removeSuffix("/").split('/')
        return components.isNotEmpty() &&
            components.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun safeEntryType(entry: ZipArchiveEntry): Boolean {
        if (entry.isUnixSymlink) return false
        val type = entry.unixMode and UNIX_FILE_TYPE_MASK
        return type == 0 ||
            (entry.isDirectory && type == UNIX_DIRECTORY) ||
            (!entry.isDirectory && type == UNIX_REGULAR)
    }

    private fun isAdditionalOwnerDefinition(name: String): Boolean = name.endsWith("/$OWNER_ENTRY")

    override fun close() = Unit

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class CatalogClassLoader(urls: Array<URL>, parent: ClassLoader) :
        URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)
                    ?: run {
                            if (name == OWNER_CLASS) {
                                findClass(name)
                            } else if (name.startsWith(CATALOG_PACKAGE)) {
                                try {
                                    findClass(name)
                                } catch (_: ClassNotFoundException) {
                                    parent.loadClass(name)
                                }
                            } else {
                                parent.loadClass(name)
                            }
                        }
                        .also { if (resolve) resolveClass(it) }
            }
    }

    private companion object {
        val HEX_SHA256 = Regex("[0-9a-f]{64}")
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
        const val OWNER_CLASS = "gg.grounds.resourcepacks.catalog.GroundsAssetCatalog"
        const val CATALOG_PACKAGE = "gg.grounds.resourcepacks.catalog."
        const val OWNER_ENTRY = "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
        const val MAX_ENTRIES = 512
        const val MAX_ENTRY_EXPANDED_BYTES = 16L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
        const val UNIX_FILE_TYPE_MASK = 0xF000
        const val UNIX_REGULAR = 0x8000
        const val UNIX_DIRECTORY = 0x4000
    }
}
