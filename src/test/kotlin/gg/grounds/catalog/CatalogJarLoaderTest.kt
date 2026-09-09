package gg.grounds.catalog

import com.sun.net.httpserver.HttpServer
import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.listDirectoryEntries
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogJarLoaderTest {
    @Test
    fun `classifies catalog connection refusal as a transfer failure`() {
        val bytes = catalogJar()
        val port = ServerSocket(0).use { it.localPort }

        CatalogJarLoader(Files.createTempDirectory("catalog-loader"), allowLoopbackHttp = true)
            .use { loader ->
                assertThrows(CatalogTransferException::class.java) {
                    loader.load(candidate(URI("http://127.0.0.1:$port/catalog.jar"), bytes))
                }
            }
    }

    @Test
    fun `classifies a catalog drip deadline as a transfer failure`() {
        val bytes = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(bytes.copyOfRange(0, 1))
            exchange.responseBody.flush()
            Thread.sleep(100)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val failure =
                assertThrows(CatalogTransferException::class.java) {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                            requestDeadlineMillis = 25,
                        )
                        .use { it.load(candidate(server, bytes)) }
                }
            assertTrue(failure.message!!.contains("deadline"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loads a standard catalog jar from an explicit loopback test server and cleans it up`() {
        val jar = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, jar.size.toLong())
            exchange.responseBody.use { it.write(jar) }
        }
        server.start()
        val directory = Files.createTempDirectory("catalog-loader")
        try {
            val catalog =
                CatalogJarLoader(directory, allowLoopbackHttp = true).use { loader ->
                    loader.load(candidate(server, jar))
                }

            assertEquals("grounds:assets", catalog.id.value)
            assertEquals("1", catalog.version)
            assertEquals(0, catalog.assets.size)
            assertEquals(0, directory.listDirectoryEntries().size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects an untrusted catalog scheme before downloading`() {
        CatalogJarLoader(Files.createTempDirectory("catalog-loader")).use { loader ->
            assertThrows(IllegalArgumentException::class.java) {
                loader.load(
                    AssetCatalogCandidate(
                        "stable",
                        "grounds:assets",
                        "1",
                        "gg.grounds:resourcepacks-catalog:1",
                        "catalog.jar",
                        URI("http://localhost/catalog.jar"),
                        "a".repeat(64),
                        1,
                    )
                )
            }
        }
    }

    @Test
    fun `rejects a catalog larger than the absolute cap`() {
        CatalogJarLoader(Files.createTempDirectory("catalog-loader"), maxCatalogBytes = 1).use {
            loader ->
            assertThrows(IllegalArgumentException::class.java) {
                loader.load(
                    AssetCatalogCandidate(
                        "stable",
                        "grounds:assets",
                        "1",
                        "gg.grounds:resourcepacks-catalog:1",
                        "catalog.jar",
                        URI("https://catalogs.example/catalog.jar"),
                        "a".repeat(64),
                        2,
                    )
                )
            }
        }
    }

    @Test
    fun `rejects arbitrary HTTP even when HTTP is enabled for loopback fixtures`() {
        val bytes = catalogJar()
        CatalogJarLoader(Files.createTempDirectory("catalog-loader"), allowLoopbackHttp = true)
            .use { loader ->
                assertThrows(IllegalArgumentException::class.java) {
                    loader.load(candidate(URI("http://example.com/catalog.jar"), bytes))
                }
            }
    }

    @Test
    fun `mutation classifying redirect transport as content is rejected while digest mismatch remains content`() {
        val bytes = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/catalog.jar")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            CatalogJarLoader(Files.createTempDirectory("catalog-loader"), allowLoopbackHttp = true)
                .use { loader ->
                    assertThrows(CatalogTransferException::class.java) {
                        loader.load(
                            candidate(
                                URI("http://127.0.0.1:${server.address.port}/redirect"),
                                bytes,
                            )
                        )
                    }
                    assertThrows(CatalogContentException::class.java) {
                        loader.load(candidate(server, bytes).copy(sha256 = "0".repeat(64)))
                    }
                }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects hostile entry names and streamed expanded bytes`() {
        listOf("../escape", "/absolute", "nul\u0000file", "C:drive").forEach { name ->
            val bytes = catalogJar(mapOf(name to byteArrayOf(1)))
            val server = serverFor(bytes)
            try {
                CatalogJarLoader(
                        Files.createTempDirectory("catalog-loader"),
                        allowLoopbackHttp = true,
                    )
                    .use { loader ->
                        assertThrows(
                            CatalogContentException::class.java,
                            { loader.load(candidate(server, bytes)) },
                            name,
                        )
                    }
            } finally {
                server.stop(0)
            }
        }
        val expanded = catalogJar(mapOf("payload.bin" to ByteArray(8)))
        val server = serverFor(expanded)
        try {
            CatalogJarLoader(
                    Files.createTempDirectory("catalog-loader"),
                    allowLoopbackHttp = true,
                    maxEntryExpandedBytes = 1,
                    maxExpandedBytes = Long.MAX_VALUE,
                )
                .use { loader ->
                    val failure =
                        assertThrows(CatalogContentException::class.java) {
                            loader.load(candidate(server, expanded))
                        }
                    assertEquals("Catalog JAR expands too far.", failure.message)
                }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `requires the base owner and rejects multi-release owner definitions`() {
        val versionedOwner =
            "META-INF/versions/17/gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
        val base = catalogJar()
        val ownerBytes = classBytes(base)
        listOf(
                "zero owner" to catalogJar(includeOwner = false),
                "base plus multi-release owner" to catalogJar(mapOf(versionedOwner to ownerBytes)),
                "multi-release-only owner" to
                    catalogJar(mapOf(versionedOwner to ownerBytes), includeOwner = false),
            )
            .forEach { (case, bytes) ->
                val server = serverFor(bytes)
                try {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                        )
                        .use { loader ->
                            val failure =
                                assertThrows(CatalogContentException::class.java) {
                                    loader.load(candidate(server, bytes))
                                }
                            assertEquals(
                                "Catalog JAR must define exactly one catalog owner.",
                                failure.message,
                                case,
                            )
                        }
                } finally {
                    server.stop(0)
                }
            }
    }

    @Test
    fun `surfaces deletion failures and suppresses them behind primary failures`() {
        val bytes = catalogJar()
        val server = serverFor(bytes)
        val cleanupFailure = IllegalStateException("delete failed")
        try {
            val successFailure =
                assertThrows(IllegalStateException::class.java) {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                            deleteJar = { throw cleanupFailure },
                        )
                        .use { it.load(candidate(server, bytes)) }
                }
            assertEquals(cleanupFailure, successFailure)
            val primary =
                assertThrows(CatalogContentException::class.java) {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                            deleteJar = { throw cleanupFailure },
                        )
                        .use { it.load(candidate(server, bytes).copy(id = "wrong:id")) }
                }
            assertEquals(listOf(cleanupFailure), primary.suppressed.toList())
            assertEquals(
                "Catalog identity does not match the validated candidate.",
                primary.message,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects short HTTP bodies and cleans temporary jars on download and archive failures`() {
        val bytes = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(bytes.copyOf(bytes.size - 1)) }
        }
        server.start()
        val directory = Files.createTempDirectory("catalog-loader")
        try {
            CatalogJarLoader(directory, allowLoopbackHttp = true).use { loader ->
                assertThrows(CatalogContentException::class.java) {
                    loader.load(candidate(server, bytes))
                }
            }
            assertEquals(0, directory.listDirectoryEntries().size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `propagates fatal errors while retaining cleanup failures as suppressed`() {
        val bytes = catalogJar()
        val server = serverFor(bytes)
        val fatal = AssertionError("fatal")
        val cleanup = IllegalStateException("cleanup")
        try {
            val thrown =
                assertThrows(AssertionError::class.java) {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                            deleteJar = { throw cleanup },
                            beforeLoad = { throw fatal },
                        )
                        .use { it.load(candidate(server, bytes)) }
                }
            assertEquals(fatal, thrown)
            assertEquals(listOf(cleanup), thrown.suppressed.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `propagates ThreadDeath and VirtualMachineError without catalog wrapping`() {
        val bytes = catalogJar()
        val server = serverFor(bytes)
        val cleanup = IllegalStateException("cleanup")
        try {
            val threadDeath = ThreadDeath()
            val virtualMachineError = InjectedVirtualMachineError("fatal")
            listOf(threadDeath, virtualMachineError).forEach { fatal ->
                val thrown =
                    assertThrows(Throwable::class.java) {
                        CatalogJarLoader(
                                Files.createTempDirectory("catalog-loader"),
                                allowLoopbackHttp = true,
                                deleteJar = { throw cleanup },
                                beforeLoad = { throw fatal },
                            )
                            .use { it.load(candidate(server, bytes)) }
                    }
                assertEquals(fatal, thrown)
                assertEquals(fatal.javaClass, thrown.javaClass)
                assertEquals(false, thrown is CatalogContentException)
                assertEquals(listOf(cleanup), thrown.suppressed.toList())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `enforces entry count and aggregate expanded byte limits independently`() {
        val tooMany = catalogJar((1..3).associate { "entry-$it" to byteArrayOf(1) })
        val entryCountFailure = loadFailure(tooMany, maxEntries = 2)
        assertEquals("Catalog JAR has unsafe entries.", entryCountFailure.message)

        val aggregate = catalogJar(mapOf("one" to ByteArray(16), "two" to ByteArray(16)))
        val expanded = expandedSizes(aggregate)
        val maxEntry = expanded.max()
        val total = expanded.sum()
        val aggregateFailure =
            loadFailure(
                aggregate,
                maxEntryExpandedBytes = maxEntry.toLong(),
                maxExpandedBytes = (total - 1).toLong(),
            )
        assertEquals("Catalog JAR expands too far.", aggregateFailure.message)
    }

    @Test
    fun `rejects duplicate zip entries before class loading`() {
        val owner = classBytes(catalogJar())
        val bytes = rawCatalogJar(listOf(OWNER_ENTRY to owner, OWNER_ENTRY to owner))

        assertContentFailure(bytes, "Catalog JAR has unsafe entries.")
    }

    @Test
    fun `rejects symlink device fifo and socket unix entry modes`() {
        listOf(0xA000, 0x6000, 0x1000, 0xC000).forEach { mode ->
            val bytes =
                rawCatalogJar(
                    listOf(OWNER_ENTRY to classBytes(catalogJar()), "hostile" to byteArrayOf(1)),
                    mapOf("hostile" to mode),
                )

            assertContentFailure(bytes, "Catalog JAR has an unsafe entry type.")
        }
    }

    @Test
    fun `rejects backslash dot empty segment and empty entry names`() {
        listOf("back\\slash", "./dot", "part/./dot", "part//empty").forEach { name ->
            val bytes =
                rawStoredZip(
                    listOf(OWNER_ENTRY to classBytes(catalogJar()), name to byteArrayOf(1))
                )

            assertContentFailure(bytes, "Catalog JAR has unsafe entries.", name)
        }
        assertContentFailure(
            rawStoredZip(listOf(OWNER_ENTRY to classBytes(catalogJar()), " " to byteArrayOf(1))),
            "Catalog JAR has unsafe entries.",
        )
    }

    @Test
    fun `rejects streamed response body size mismatch before digest validation`() {
        val served = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(served) }
        }
        server.start()
        try {
            val failure =
                assertThrows(CatalogContentException::class.java) {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                        )
                        .use {
                            it.load(candidate(server, served).copy(size = served.size.toLong() + 1))
                        }
                }

            assertEquals("Catalog response size does not match manifest.", failure.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects invalid runtime owner contracts with their reflection causes`() {
        val cases =
            listOf(
                "wrong return type" to
                    "public final class GroundsAssetCatalog { public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog(); public String getCatalog() { return \"wrong\"; } }",
                "wrong version" to
                    ownerSource("gg.grounds.catalog.CatalogJarLoaderFixtures.catalogVersionTwo()"),
                "missing instance" to
                    "public final class GroundsAssetCatalog { public Object getCatalog() { return gg.grounds.catalog.CatalogJarLoaderFixtures.catalog(); } }",
                "missing method" to
                    "public final class GroundsAssetCatalog { public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog(); }",
            )
        cases.forEach { (name, source) ->
            val bytes = catalogJar(ownerSource = source)
            val failure = loadFailure(bytes)

            when (name) {
                "wrong return type" ->
                    assertEquals("Catalog owner did not return an AssetCatalog.", failure.message)
                "wrong version" ->
                    assertEquals(
                        "Catalog identity does not match the validated candidate.",
                        failure.message,
                    )
                "missing instance" -> {
                    assertEquals("Catalog owner could not be loaded.", failure.message)
                    assertEquals(NoSuchFieldException::class.java, failure.cause?.javaClass)
                }
                else -> assertEquals(NoSuchMethodException::class.java, failure.cause?.javaClass)
            }
        }
    }

    @Test
    fun `cleans temporary jars after archive validation and reflection failures`() {
        listOf(
                "archive validation" to
                    rawCatalogJar(
                        listOf(
                            OWNER_ENTRY to classBytes(catalogJar()),
                            "../escape" to byteArrayOf(1),
                        )
                    ),
                "reflection" to
                    catalogJar(
                        ownerSource =
                            "public final class GroundsAssetCatalog { public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog(); }"
                    ),
            )
            .forEach { (stage, bytes) ->
                val directory = Files.createTempDirectory("catalog-loader")
                val server = serverFor(bytes)
                try {
                    val failure =
                        assertThrows(CatalogContentException::class.java) {
                            CatalogJarLoader(directory, allowLoopbackHttp = true).use {
                                it.load(candidate(server, bytes))
                            }
                        }
                    if (stage == "archive validation") {
                        assertEquals("Catalog JAR has unsafe entries.", failure.message)
                    } else {
                        assertEquals("Catalog owner could not be loaded.", failure.message)
                        assertEquals(NoSuchMethodException::class.java, failure.cause?.javaClass)
                    }
                    assertEquals(0, directory.listDirectoryEntries().size)
                } finally {
                    server.stop(0)
                }
            }
    }

    @Test
    fun `does not fall back to a parent owner when exact candidate owner is malformed`() {
        val bytes = rawCatalogJar(listOf(OWNER_ENTRY to byteArrayOf(0, 1, 2, 3)))
        val failure = loadFailure(bytes)

        assertEquals("Catalog owner could not be loaded.", failure.message)
        assertEquals(ClassFormatError::class.java, failure.cause?.javaClass)
    }

    @Test
    fun `does not fall back to parent support after candidate support linkage error`() {
        val bytes =
            catalogJar(
                ownerSource =
                    ownerSource("gg.grounds.resourcepacks.catalog.CatalogSupport.catalog()"),
                extra =
                    mapOf(
                        "gg/grounds/resourcepacks/catalog/CatalogSupport.class" to
                            byteArrayOf(0, 1, 2, 3)
                    ),
            )
        val failure = loadFailure(bytes)

        assertEquals("Catalog owner could not be loaded.", failure.message)
        assertEquals(ClassFormatError::class.java, failure.cause?.cause?.javaClass)
    }

    private fun assertContentFailure(bytes: ByteArray, message: String, context: String? = null) {
        val failure = loadFailure(bytes, context)
        assertEquals(message, failure.message, context)
    }

    private fun loadFailure(
        bytes: ByteArray,
        context: String? = null,
        maxEntries: Int = 512,
        maxEntryExpandedBytes: Long = 16L * 1024 * 1024,
        maxExpandedBytes: Long = 64L * 1024 * 1024,
    ): CatalogContentException {
        val server = serverFor(bytes)
        try {
            return assertThrows(
                CatalogContentException::class.java,
                {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                            maxEntries = maxEntries,
                            maxEntryExpandedBytes = maxEntryExpandedBytes,
                            maxExpandedBytes = maxExpandedBytes,
                        )
                        .use { it.load(candidate(server, bytes)) }
                },
                context,
            )
        } finally {
            server.stop(0)
        }
    }

    private fun candidate(server: HttpServer, bytes: ByteArray): AssetCatalogCandidate =
        candidate(URI("http://127.0.0.1:${server.address.port}/catalog.jar"), bytes)

    private fun candidate(uri: URI, bytes: ByteArray): AssetCatalogCandidate =
        AssetCatalogCandidate(
            "stable",
            "grounds:assets",
            "1",
            "gg.grounds:resourcepacks-catalog:1",
            "catalog.jar",
            uri,
            digest(bytes),
            bytes.size.toLong(),
        )

    private fun serverFor(bytes: ByteArray): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.createContext("/catalog.jar") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

    private fun catalogJar(
        extra: Map<String, ByteArray> = emptyMap(),
        includeOwner: Boolean = true,
        ownerSource: String = ownerSource("gg.grounds.catalog.CatalogJarLoaderFixtures.catalog()"),
    ): ByteArray {
        val sourceDirectory = Files.createTempDirectory("catalog-source")
        val classesDirectory = Files.createTempDirectory("catalog-classes")
        try {
            val source =
                sourceDirectory.resolve("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.java")
            Files.createDirectories(source.parent)
            Files.writeString(
                source,
                """
                package gg.grounds.resourcepacks.catalog;
                $ownerSource
                """
                    .trimIndent(),
            )
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(
                0,
                compiler.run(
                    null,
                    null,
                    null,
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    classesDirectory.toString(),
                    source.toString(),
                ),
            )
            return java.io.ByteArrayOutputStream().use { bytes ->
                JarOutputStream(bytes).use { jar ->
                    listOf(
                            "META-INF/",
                            "gg/",
                            "gg/grounds/",
                            "gg/grounds/resourcepacks/",
                            "gg/grounds/resourcepacks/catalog/",
                        )
                        .forEach {
                            jar.putNextEntry(JarEntry(it))
                            jar.closeEntry()
                        }
                    val classFile =
                        classesDirectory.resolve(
                            "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
                        )
                    if (includeOwner) {
                        jar.putNextEntry(
                            JarEntry("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class")
                        )
                        jar.write(Files.readAllBytes(classFile))
                        jar.closeEntry()
                    }
                    extra.forEach { (name, content) ->
                        jar.putNextEntry(JarEntry(name))
                        jar.write(content)
                        jar.closeEntry()
                    }
                }
                bytes.toByteArray()
            }
        } finally {
            sourceDirectory.toFile().deleteRecursively()
            classesDirectory.toFile().deleteRecursively()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun classBytes(jar: ByteArray): ByteArray =
        java.util.jar.JarInputStream(jar.inputStream()).use { input ->
            generateSequence { input.nextJarEntry }
                .first { it.name.endsWith("GroundsAssetCatalog.class") }
                .let { input.readBytes() }
        }

    private fun expandedSizes(jar: ByteArray): List<Int> =
        java.util.jar.JarInputStream(jar.inputStream()).use { input ->
            buildList {
                while (true) {
                    val entry = input.nextJarEntry ?: break
                    if (!entry.isDirectory) add(input.readBytes().size)
                }
            }
        }

    private fun rawCatalogJar(
        entries: List<Pair<String, ByteArray>>,
        modes: Map<String, Int> = emptyMap(),
    ): ByteArray =
        java.io.ByteArrayOutputStream().use { bytes ->
            ZipArchiveOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    val entry = ZipArchiveEntry(name).apply { unixMode = modes[name] ?: 0x8000 }
                    zip.putArchiveEntry(entry)
                    zip.write(content)
                    zip.closeArchiveEntry()
                }
                zip.finish()
            }
            bytes.toByteArray()
        }

    private fun rawStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        entries.forEach { (name, content) ->
            val nameBytes = name.toByteArray()
            val crc = java.util.zip.CRC32().apply { update(content) }.value
            val offset = output.size()
            output.writeLe(0x04034B50)
            output.writeLe(20, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(crc, 4)
            output.writeLe(content.size, 4)
            output.writeLe(content.size, 4)
            output.writeLe(nameBytes.size, 2)
            output.writeLe(0, 2)
            output.write(nameBytes)
            output.write(content)
            central.writeLe(0x02014B50)
            central.writeLe(20, 2)
            central.writeLe(20, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(crc, 4)
            central.writeLe(content.size, 4)
            central.writeLe(content.size, 4)
            central.writeLe(nameBytes.size, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 2)
            central.writeLe(0, 4)
            central.writeLe(offset, 4)
            central.write(nameBytes)
        }
        val centralOffset = output.size()
        output.write(central.toByteArray())
        output.writeLe(0x06054B50)
        output.writeLe(0, 2)
        output.writeLe(0, 2)
        output.writeLe(entries.size, 2)
        output.writeLe(entries.size, 2)
        output.writeLe(central.size(), 4)
        output.writeLe(centralOffset, 4)
        output.writeLe(0, 2)
        return output.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.writeLe(value: Number, bytes: Int = 4) {
        repeat(bytes) { index -> write(((value.toLong() ushr (index * 8)) and 0xff).toInt()) }
    }

    private fun ownerSource(expression: String): String =
        "public final class GroundsAssetCatalog { public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog(); public Object getCatalog() { return $expression; } }"

    private companion object {
        const val OWNER_ENTRY = "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
    }
}

private class InjectedVirtualMachineError(message: String) : VirtualMachineError(message)

object CatalogJarLoaderFixtures {
    @JvmStatic
    fun catalog(): AssetCatalog {
        val version = System.getProperty("grounds.catalog.fixture.version", "1")
        val compatibility =
            System.getProperty("grounds.catalog.fixture.compatibility", "grounds:assets")
        return AssetCatalog(
            CatalogId("grounds:assets"),
            version,
            CatalogVersionRange(CatalogId(compatibility), version, version),
            emptyMap(),
        )
    }

    @JvmStatic
    fun catalogVersionTwo(): AssetCatalog =
        AssetCatalog(
            CatalogId("grounds:assets"),
            "2",
            CatalogVersionRange(CatalogId("grounds:assets"), "2", "2"),
            emptyMap(),
        )
}
