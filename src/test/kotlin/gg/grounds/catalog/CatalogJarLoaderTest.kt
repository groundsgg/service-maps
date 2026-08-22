package gg.grounds.catalog

import com.sun.net.httpserver.HttpServer
import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.listDirectoryEntries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogJarLoaderTest {
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
    fun `rejects redirects and manifest digest mismatches`() {
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
                    assertThrows(CatalogContentException::class.java) {
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
                    maxExpandedBytes = 8,
                )
                .use { loader ->
                    assertThrows(CatalogContentException::class.java) {
                        loader.load(candidate(server, expanded))
                    }
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
                catalogJar(includeOwner = false),
                catalogJar(mapOf(versionedOwner to ownerBytes)),
                catalogJar(mapOf(versionedOwner to ownerBytes), includeOwner = false),
            )
            .forEach { bytes ->
                val server = serverFor(bytes)
                try {
                    CatalogJarLoader(
                            Files.createTempDirectory("catalog-loader"),
                            allowLoopbackHttp = true,
                        )
                        .use { loader ->
                            assertThrows(CatalogContentException::class.java) {
                                loader.load(candidate(server, bytes))
                            }
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
    fun `enforces entry count and aggregate expanded byte limits independently`() {
        val tooMany = catalogJar((1..3).associate { "entry-$it" to byteArrayOf(1) })
        val aggregate = catalogJar(mapOf("one" to ByteArray(3), "two" to ByteArray(3)))
        listOf(Triple(tooMany, 2, 32L), Triple(aggregate, 20, 5L)).forEach { (bytes, entries, total)
            ->
            val server = serverFor(bytes)
            try {
                CatalogJarLoader(
                        Files.createTempDirectory("catalog-loader"),
                        allowLoopbackHttp = true,
                        maxEntries = entries,
                        maxEntryExpandedBytes = 4,
                        maxExpandedBytes = total,
                    )
                    .use { loader ->
                        assertThrows(CatalogContentException::class.java) {
                            loader.load(candidate(server, bytes))
                        }
                    }
            } finally {
                server.stop(0)
            }
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
                public final class GroundsAssetCatalog {
                  public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog();
                  public Object getCatalog() { return gg.grounds.catalog.CatalogJarLoaderFixtures.catalog(); }
                }
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
}

object CatalogJarLoaderFixtures {
    @JvmStatic
    fun catalog(): AssetCatalog =
        AssetCatalog(
            CatalogId("grounds:assets"),
            "1",
            CatalogVersionRange(CatalogId("grounds:assets"), "1", "1"),
            emptyMap(),
        )
}
