package gg.grounds.derive

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.catalog.CatalogJarLoader
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveWorkerMainIT {
    @Test
    fun `catalog connection refusal is acknowledged as retryable system failure`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        workerServer(source) { server, uploads ->
            val refusedPort = ServerSocket(0).use { it.localPort }
            val candidate =
                AssetCatalogCandidate(
                    "stable",
                    "grounds:assets",
                    "1",
                    "coord",
                    "catalog.jar",
                    URI("http://127.0.0.1:$refusedPort/catalog"),
                    digest(catalog),
                    catalog.size.toLong(),
                )

            run(server, request(server, digest(source)).copy(catalogCandidates = listOf(candidate)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", marker.scope.name)
            assertTrue(marker.retryable)
        }
    }

    @Test
    fun `valid generated catalog resolves grounds actions and uploads canonical scene artifacts`() {
        val catalog = generatedCatalogJar()
        val version = "37"
        val source =
            archive(
                mapOf(
                    "scene.json" to
                        validGroundsScene
                            .replace(
                                "\"id\":\"grounds:assets\",\"version\":\"1\"",
                                "\"id\":\"grounds:assets\",\"version\":\"$version\"",
                            )
                            .encodeToByteArray()
                )
            )
        catalogWorkerServer(source, catalog) { server, uploads, requests ->
            val base = "http://127.0.0.1:${server.address.port}"
            val request =
                request(server, digest(source))
                    .copy(
                        catalogCandidates =
                            listOf(
                                AssetCatalogCandidate(
                                    "stable",
                                    "grounds:assets",
                                    "1",
                                    "gg.grounds:resourcepacks-catalog:1",
                                    "catalog.jar",
                                    URI("$base/catalog?signature=catalog-secret"),
                                    digest(catalog),
                                    catalog.size.toLong(),
                                )
                            )
                    )

            System.setProperty("grounds.catalog.fixture.version", version)
            try {
                run(
                    server,
                    request.copy(
                        catalogCandidates =
                            request.catalogCandidates.map { it.copy(version = version) }
                    ),
                )
            } finally {
                System.clearProperty("grounds.catalog.fixture.version")
            }

            assertEquals(listOf("/source", "/catalog", "/bundle", "/manifest", "/result"), requests)
            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.last().second) as DeriveSuccess
            assertEquals("grounds:assets", result.scene.assetCatalog?.id)
            assertEquals(version, result.scene.assetCatalog?.version)
            assertEquals("grounds:actions", result.scene.actionCatalog?.id)
            assertEquals("1", result.scene.actionCatalog?.version)
            assertTrue(result.scene.present)
            assertEquals("1", result.scene.schemaVersion)
            assertEquals(emptyList<String>(), result.scene.requiredActions)
            val authoredScene =
                validGroundsScene
                    .replace(
                        "\"id\":\"grounds:assets\",\"version\":\"1\"",
                        "\"id\":\"grounds:assets\",\"version\":\"$version\"",
                    )
                    .encodeToByteArray()
            val manifest = CanonicalJson.readManifest(uploads[1].second)
            assertEquals(result.scene, manifest.scene)
            assertEquals(digest(source), manifest.sourceSha256)
            val bundle = bundleEntries(uploads[0].second)
            val canonicalScene = bundle.getValue("scene.json")
            assertEquals(digest(canonicalScene), result.scene.sha256)
            assertFalse(authoredScene.contentEquals(canonicalScene))
            assertEquals(
                "grounds:assets",
                CanonicalJson.readManifest(uploads[1].second).scene.assetCatalog?.id,
            )
            assertEquals(
                uploads[1].second.toList(),
                bundle.getValue("grounds/derived-manifest.json").toList(),
            )
        }
    }

    @Test
    fun `structurally valid hostile catalog jar and loaded identity mismatch are content markers`() {
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        listOf(generatedCatalogJar("../hostile") to "1", generatedCatalogJar() to "2").forEach {
            (catalog, version) ->
            val scene =
                archive(
                    mapOf(
                        "scene.json" to
                            validGroundsScene
                                .replace(
                                    "\"id\":\"grounds:assets\",\"version\":\"1\"",
                                    "\"id\":\"grounds:assets\",\"version\":\"$version\"",
                                )
                                .encodeToByteArray()
                    )
                )
            catalogWorkerServer(scene, catalog) { server, uploads, _ ->
                run(
                    server,
                    request(server, digest(scene))
                        .copy(
                            catalogCandidates =
                                listOf(
                                    AssetCatalogCandidate(
                                        "stable",
                                        "grounds:assets",
                                        version,
                                        "coord",
                                        "catalog.jar",
                                        URI("http://127.0.0.1:${server.address.port}/catalog"),
                                        digest(catalog),
                                        catalog.size.toLong(),
                                    )
                                )
                        ),
                )
                assertEquals(listOf("/result"), uploads.map { it.first })
                val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                assertEquals("CONTENT", marker.scope.name)
                assertFalse(marker.retryable)
            }
        }
    }

    @Test
    fun `chunked catalog declared size mismatch is a nonretryable content marker`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        catalogWorkerServer(source, catalog, catalogChunked = true) { server, uploads, _ ->
            run(
                server,
                request(server, digest(source))
                    .copy(
                        catalogCandidates =
                            listOf(
                                AssetCatalogCandidate(
                                    "stable",
                                    "grounds:assets",
                                    "1",
                                    "coord",
                                    "catalog.jar",
                                    URI("http://127.0.0.1:${server.address.port}/catalog"),
                                    digest(catalog),
                                    catalog.size.toLong() + 1,
                                )
                            )
                    ),
            )
            val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", marker.scope.name)
            assertFalse(marker.retryable)
        }
    }

    @Test
    fun `catalog content failures produce only a nonretryable marker`() {
        val source =
            archive(
                mapOf(
                    "scene.json" to
                        """{"schemaVersion":1,"id":"grounds:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"grounds:assets","version":"1"},"actions":{"id":"grounds:actions","version":"1"}},"groups":[],"elements":[]}"""
                            .encodeToByteArray()
                )
            )
        catalogWorkerServer(source, "hostile-not-a-jar".encodeToByteArray()) { server, uploads, _ ->
            val bad = "hostile-not-a-jar".encodeToByteArray()
            val request =
                request(server, digest(source))
                    .copy(
                        catalogCandidates =
                            listOf(
                                AssetCatalogCandidate(
                                    "stable",
                                    "grounds:assets",
                                    "1",
                                    "coord",
                                    "catalog.jar",
                                    URI("http://127.0.0.1:${server.address.port}/catalog"),
                                    digest(bad),
                                    bad.size.toLong(),
                                )
                            )
                    )

            run(server, request)

            assertEquals(listOf("/result"), uploads.map { it.first })
            val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", marker.scope.name)
            assertFalse(marker.retryable)
        }
    }

    @Test
    fun `catalog manifest size and digest mismatches are nonretryable content`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        catalogWorkerServer(source, catalog) { server, uploads, _ ->
            val base = "http://127.0.0.1:${server.address.port}"
            listOf(
                    "size" to
                        { candidate: AssetCatalogCandidate ->
                            candidate.copy(size = candidate.size + 1)
                        },
                    "digest" to
                        { candidate: AssetCatalogCandidate ->
                            candidate.copy(sha256 = "0".repeat(64))
                        },
                )
                .forEach { (_, corrupt) ->
                    uploads.clear()
                    val candidate =
                        AssetCatalogCandidate(
                            "stable",
                            "grounds:assets",
                            "1",
                            "coord",
                            "catalog.jar",
                            URI("$base/catalog"),
                            digest(catalog),
                            catalog.size.toLong(),
                        )
                    run(
                        server,
                        request(server, digest(source))
                            .copy(catalogCandidates = listOf(corrupt(candidate))),
                    )
                    val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                    assertEquals("CONTENT", marker.scope.name)
                    assertFalse(marker.retryable)
                }
        }
    }

    @Test
    fun `catalog status and redirect failures are acknowledged retryable system markers`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        listOf(503, 302).forEach { status ->
            catalogWorkerServer(source, catalog, catalogStatus = status) { server, uploads, requests
                ->
                val candidate =
                    AssetCatalogCandidate(
                        "stable",
                        "grounds:assets",
                        "1",
                        "coord",
                        "catalog.jar",
                        URI("http://127.0.0.1:${server.address.port}/catalog"),
                        digest(catalog),
                        catalog.size.toLong(),
                    )
                run(
                    server,
                    request(server, digest(source)).copy(catalogCandidates = listOf(candidate)),
                )

                assertEquals(listOf("/source", "/catalog", "/result"), requests)
                assertFalse(requests.contains("/redirect-target"))
                val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                assertEquals("SYSTEM", marker.scope.name)
                assertTrue(marker.retryable)
            }
        }
    }

    @Test
    fun `catalog abort before response headers is acknowledged as a system marker`() =
        catalogTransportFailure(catalogAbortBeforeHeaders = true)

    @Test
    fun `catalog abort mid-stream body is acknowledged as a system marker`() =
        catalogTransportFailure(catalogAbortMidStream = true)

    @Test
    fun `catalog whole request deadline is acknowledged as a system marker`() =
        catalogTransportFailure(catalogResponseDelayMillis = 100)

    @Test
    fun `zero duplicate and nonexact catalog selections are nonretryable content`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        catalogWorkerServer(source, catalog) { server, uploads, requests ->
            val candidate =
                AssetCatalogCandidate(
                    "stable",
                    "grounds:assets",
                    "1",
                    "coord",
                    "catalog.jar",
                    URI("http://127.0.0.1:${server.address.port}/catalog"),
                    digest(catalog),
                    catalog.size.toLong(),
                )
            listOf(
                    emptyList(),
                    listOf(candidate, candidate.copy(channel = "edge")),
                    listOf(candidate.copy(version = "2")),
                )
                .forEach { candidates ->
                    uploads.clear()
                    requests.clear()
                    run(
                        server,
                        request(server, digest(source)).copy(catalogCandidates = candidates),
                    )
                    assertEquals(listOf("/source", "/result"), requests)
                    val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                    assertEquals("CONTENT", marker.scope.name)
                    assertFalse(marker.retryable)
                }
        }
    }

    @Test
    fun `unsupported action namespace and action catalog mismatch are nonretryable content`() {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        catalogWorkerServer(source, catalog) { server, uploads, _ ->
            val candidate =
                AssetCatalogCandidate(
                    "stable",
                    "grounds:assets",
                    "1",
                    "coord",
                    "catalog.jar",
                    URI("http://127.0.0.1:${server.address.port}/catalog"),
                    digest(catalog),
                    catalog.size.toLong(),
                )
            listOf(
                    validGroundsScene.replace("grounds:actions", "other:actions"),
                    validGroundsScene.replace(
                        "\"version\":\"1\"}},\"groups\"",
                        "\"version\":\"2\"}},\"groups\"",
                    ),
                )
                .forEach { scene ->
                    uploads.clear()
                    val altered = archive(mapOf("scene.json" to scene.encodeToByteArray()))
                    run(
                        server,
                        request(server, digest(altered)).copy(catalogCandidates = listOf(candidate)),
                    )
                    assertEquals(listOf("/result"), uploads.map { it.first })
                    val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                    assertEquals("CONTENT", marker.scope.name)
                    assertFalse(marker.retryable)
                }
        }
    }

    @Test
    fun `reports a declared source compressed limit as nonretryable content`() =
        workerServer(byteArrayOf(1, 2)) { server, uploads ->
            run(
                server,
                request(server, digest(byteArrayOf(1, 2))),
                WorkerHttpTransfer(true, maxSourceBytes = 1),
            )

            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports a chunked source compressed limit as nonretryable content`() =
        workerServer(byteArrayOf(1, 2), sourceChunked = true) { server, uploads ->
            run(
                server,
                request(server, digest(byteArrayOf(1, 2))),
                WorkerHttpTransfer(true, maxSourceBytes = 1),
            )

            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `source header failure and whole request deadline are acknowledged system markers`() {
        listOf(
                { server: HttpServer -> run(server, request(server, "0".repeat(64))) },
                { server: HttpServer ->
                    run(
                        server,
                        request(server, "0".repeat(64)),
                        WorkerHttpTransfer(true, requestDeadlineMillis = 25),
                    )
                },
            )
            .forEachIndexed { index, invoke ->
                val source = byteArrayOf(1)
                workerServer(
                    source,
                    sourceAbortBeforeHeaders = index == 0,
                    sourceDelayMillis = if (index == 1) 100 else 0,
                ) { server, uploads ->
                    invoke(server)
                    assertEquals(listOf("/result"), uploads.map { it.first })
                    val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
                    assertEquals("SYSTEM", marker.scope.name)
                    assertTrue(marker.retryable)
                }
            }
    }

    @Test
    fun `uploads bundle then manifest then success result for a valid loopback request`() =
        workerServer(archive(mapOf("level.dat" to "world".encodeToByteArray()))) { server, uploads
            ->
            run(
                server,
                request(server, digest(archive(mapOf("level.dat" to "world".encodeToByteArray())))),
            )

            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.last().second) as DeriveSuccess
            assertEquals(DeriveResultKind.SUCCESS, result.kind)
            assertEquals(digest(uploads[0].second), result.bundleSha256)
            assertEquals(uploads[0].second.size.toLong(), result.bundleSize)
            assertEquals(digest(uploads[1].second), result.manifestSha256)
            assertEquals(uploads[1].second.size.toLong(), result.manifestSize)
        }

    @Test
    fun `reports digest mismatch as content failure without artifact uploads`() =
        workerServer(archive(mapOf("level.dat" to "world".encodeToByteArray()))) { server, uploads
            ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports malformed source archive as content failure without artifact uploads`() =
        workerServer("not-a-zstd-frame".encodeToByteArray()) { server, uploads ->
            run(server, request(server, digest("not-a-zstd-frame".encodeToByteArray())))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports failed bundle upload as retryable system failure without manifest or success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            bundleStatus = 500,
        ) { server, uploads ->
            run(
                server,
                request(server, digest(archive(mapOf("level.dat" to "world".encodeToByteArray())))),
            )

            assertEquals(listOf("/bundle", "/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.last().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
            assertFalse(uploads.last().second.decodeToString().contains("secret=redact"))
        }

    @Test
    fun `failed fallback marker after bundle failure returns nonzero with no later success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            bundleStatus = 500,
            resultStatuses = listOf(500),
        ) { server, uploads ->
            assertEquals(
                1,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                    expectedExit = 1,
                ),
            )
            assertEquals(listOf("/bundle", "/result"), uploads.map { it.first })
        }

    @Test
    fun `failed manifest upload sends only the acknowledged system fallback marker`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            manifestStatus = 500,
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )

            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
            val fallback = CanonicalJson.readResult(uploads.last().second) as DeriveFailure
            assertEquals("SYSTEM", fallback.scope.name)
            assertTrue(fallback.retryable)
        }

    @Test
    fun `failed fallback marker after manifest failure returns nonzero with no later success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            manifestStatus = 500,
            resultStatuses = listOf(500),
        ) { server, uploads ->
            assertEquals(
                1,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                    expectedExit = 1,
                ),
            )
            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
        }

    @Test
    fun `failed result upload followed by failed fallback returns nonzero with no later artifact`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(500, 500),
        ) { server, uploads ->
            assertEquals(
                1,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                    expectedExit = 1,
                ),
            )

            assertEquals(
                listOf("/bundle", "/manifest", "/result", "/result"),
                uploads.map { it.first },
            )
        }

    @Test
    fun `content marker upload failure returns nonzero and never uploads artifacts`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(500),
        ) { server, uploads ->
            assertEquals(1, run(server, request(server, "0".repeat(64)), expectedExit = 1))

            assertEquals(listOf("/result"), uploads.map { it.first })
        }

    @Test
    fun `preflight rejects every unsigned request URL and unused catalog without HTTP`() {
        var sourceGets = 0
        workerServer(ByteArray(0), onSourceRequest = { sourceGets++ }) { server, uploads ->
            val valid = request(server, "0".repeat(64))
            val invalid =
                listOf<DeriveRequest.() -> DeriveRequest>(
                    { copy(sourceUrl = URI("http://example.invalid/source")) },
                    { copy(bundleUrl = URI("http://example.invalid/bundle")) },
                    { copy(manifestUrl = URI("http://example.invalid/manifest")) },
                    { copy(resultUrl = URI("http://example.invalid/result")) },
                    {
                        copy(
                            catalogCandidates =
                                listOf(
                                    AssetCatalogCandidate(
                                        "stable",
                                        "unused",
                                        "1",
                                        "unused:1",
                                        "unused.jar",
                                        URI("http://example.invalid/catalog"),
                                        "0".repeat(64),
                                        1,
                                    )
                                )
                        )
                    },
                )
            invalid.forEach { mutate -> assertEquals(2, runRequest(mutate(valid))) }
            assertTrue(uploads.isEmpty())
            assertEquals(0, sourceGets)
        }
    }

    @Test
    fun `reports transient source failure as retryable system failure`() =
        workerServer(ByteArray(0), sourceStatus = 503) { server, uploads ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
        }

    @Test
    fun `failed result upload after source system failure returns nonzero without artifacts`() =
        workerServer(ByteArray(0), sourceStatus = 503, resultStatuses = listOf(500)) {
            server,
            uploads ->
            assertEquals(1, run(server, request(server, "0".repeat(64)), expectedExit = 1))
            assertEquals(listOf("/result"), uploads.map { it.first })
        }

    @Test
    fun `reports expired source URL as retryable system failure`() =
        workerServer(ByteArray(0), sourceStatus = 403) { server, uploads ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
        }

    @Test
    fun `source failure redacts a signed URL while retaining its endpoint`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val signed = "http://127.0.0.1:${server.address.port}/source?token=source-secret"
            val transfer =
                object : WorkerHttpTransfer(true) {
                    override fun download(uri: URI, destination: java.nio.file.Path): String {
                        throw WorkerTransferException("source failed at $signed")
                    }
                }
            assertEquals(0, run(server, request(server, "0".repeat(64)), transfer))
            val marker = uploads.single().second.decodeToString()
            assertFalse(marker.contains("source-secret"))
            assertTrue(marker.contains("/source?<redacted-query>"))
        }

    @Test
    fun `artifact failure redacts a signed URL while retaining its endpoint`() =
        workerServer(archive(mapOf("level.dat" to "world".encodeToByteArray()))) { server, uploads
            ->
            val signed = "http://127.0.0.1:${server.address.port}/bundle?token=artifact-secret"
            var uploadsAttempted = 0
            val transfer =
                object : WorkerHttpTransfer(true) {
                    override fun upload(
                        uri: URI,
                        source: java.nio.file.Path,
                        expectedDigest: String?,
                    ) {
                        if (uploadsAttempted++ == 0)
                            throw WorkerTransferException("artifact failed at $signed")
                        super.upload(uri, source, expectedDigest)
                    }
                }
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                    transfer,
                ),
            )
            val marker = uploads.last().second.decodeToString()
            assertFalse(marker.contains("artifact-secret"))
            assertTrue(marker.contains("/bundle?<redacted-query>"))
        }

    @Test
    fun `source redirect is rejected and produces only a system marker`() =
        workerServer(ByteArray(0), sourceStatus = 302) { server, uploads ->
            assertEquals(0, run(server, request(server, "0".repeat(64))))
            assertEquals(listOf("/result"), uploads.map { it.first })
            assertEquals(
                "SYSTEM",
                (CanonicalJson.readResult(uploads.single().second) as DeriveFailure).scope.name,
            )
            assertFalse(uploads.any { it.first == "/redirect-target" })
        }

    @Test
    fun `artifact redirect stops the pipeline before manifest and success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            bundleStatus = 302,
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )
            assertEquals(listOf("/bundle", "/result"), uploads.map { it.first })
            assertFalse(uploads.any { it.first == "/redirect-target" })
        }

    @Test
    fun `result redirect is rejected then replaced by a final system fallback marker`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(302, 200),
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )
            assertEquals(
                listOf("/bundle", "/manifest", "/result", "/result"),
                uploads.map { it.first },
            )
            assertEquals(
                "SYSTEM",
                (CanonicalJson.readResult(uploads.last().second) as DeriveFailure).scope.name,
            )
            assertFalse(uploads.any { it.first == "/redirect-target" })
        }

    @Test
    fun `mutation contacting any URL before malformed request validation is rejected`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val malformed =
                "{\"sourceUrl\":\"http://127.0.0.1:${server.address.port}/source\"}"
                    .encodeToByteArray()
            assertEquals(2, runRaw(malformed, "--request-file", "--allow-loopback-http"))
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun `mutation accepting production HTTP preflight is rejected before source GET`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val request = request(server, "0".repeat(64))
            val file = Files.createTempFile("derive-request-", ".json")
            try {
                Files.write(file, CanonicalJson.write(request))
                assertEquals(2, DeriveWorkerMain.run(arrayOf("--request-file", file.toString())))
            } finally {
                Files.deleteIfExists(file)
            }
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun `production request-env rejects HTTP before any request`() =
        workerServer(ByteArray(0)) { server, uploads ->
            assertEquals(
                2,
                DeriveWorkerMain.run(
                    arrayOf("--request-env", "DERIVE_REQUEST_JSON"),
                    { WorkerHttpTransfer(it) },
                    { CanonicalJson.write(request(server, "0".repeat(64))).decodeToString() },
                ),
            )
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun `worker removes its owned workspace after a terminal source failure`() =
        workerServer(ByteArray(0), sourceStatus = 503) { server, uploads ->
            val root = Files.createTempDirectory("derive-worker-owned-")
            assertEquals(
                0,
                DeriveWorkerMain.run(
                    arrayOf("--request-env", "DERIVE_REQUEST_JSON"),
                    { WorkerHttpTransfer(true) },
                    { CanonicalJson.write(request(server, "0".repeat(64))).decodeToString() },
                    { root },
                ),
            )
            assertFalse(Files.exists(root))
            assertEquals(listOf("/result"), uploads.map { it.first })
        }

    @Test
    fun `invalid CLI invocations return nonzero before any HTTP request`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val file = Files.createTempFile("derive-request-", ".json")
            try {
                Files.write(file, CanonicalJson.write(request(server, "0".repeat(64))))
                listOf(
                        arrayOf("--unknown"),
                        arrayOf("--request-file"),
                        arrayOf("--request-env"),
                        arrayOf(
                            "--request-env",
                            "DERIVE_REQUEST_JSON",
                            "--request-file",
                            file.toString(),
                        ),
                        arrayOf("--request-file", file.toString()),
                    )
                    .forEach { args -> assertEquals(2, DeriveWorkerMain.run(args)) }
            } finally {
                Files.deleteIfExists(file)
            }
            assertTrue(uploads.isEmpty())
        }

    private fun workerServer(
        source: ByteArray,
        sourceStatus: Int = 200,
        sourceChunked: Boolean = false,
        sourceAbortBeforeHeaders: Boolean = false,
        sourceDelayMillis: Long = 0,
        bundleStatus: Int = 200,
        manifestStatus: Int = 200,
        resultStatuses: List<Int> = listOf(200),
        onSourceRequest: () -> Unit = {},
        block: (HttpServer, MutableList<Pair<String, ByteArray>>) -> Unit,
    ) {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/redirect-target") { exchange ->
                uploads += "/redirect-target" to exchange.requestBody.readBytes()
                respond(exchange, 200, ByteArray(0))
            }
            server.createContext("/source") { exchange ->
                onSourceRequest()
                if (sourceAbortBeforeHeaders) {
                    exchange.close()
                } else if (sourceDelayMillis > 0 && sourceStatus == 200) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(source.copyOf(1))
                    exchange.responseBody.flush()
                    Thread.sleep(sourceDelayMillis)
                    exchange.responseBody.close()
                } else if (sourceChunked && sourceStatus == 200) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(source) }
                } else {
                    respond(exchange, sourceStatus, source)
                }
            }
            var resultAttempt = 0
            listOf("/bundle", "/manifest", "/result").forEach { path ->
                server.createContext(path) { exchange ->
                    uploads += path to exchange.requestBody.readBytes()
                    val status =
                        when (path) {
                            "/bundle" -> bundleStatus
                            "/manifest" -> manifestStatus
                            else ->
                                resultStatuses.getOrElse(resultAttempt++) { resultStatuses.last() }
                        }
                    if (status in 300..399)
                        exchange.responseHeaders.add("Location", "/redirect-target")
                    respond(exchange, status, ByteArray(0))
                }
            }
            server.start()
            block(server, uploads)
        } finally {
            server.stop(0)
        }
    }

    private fun catalogWorkerServer(
        source: ByteArray,
        catalog: ByteArray,
        catalogStatus: Int = 200,
        catalogChunked: Boolean = false,
        catalogAbortBeforeHeaders: Boolean = false,
        catalogAbortMidStream: Boolean = false,
        catalogResponseDelayMillis: Long = 0,
        block: (HttpServer, MutableList<Pair<String, ByteArray>>, MutableList<String>) -> Unit,
    ) {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/redirect-target") { exchange ->
                requests += "/redirect-target"
                respond(exchange, 200, ByteArray(0))
            }
            server.createContext("/source") { exchange ->
                requests += "/source"
                respond(exchange, 200, source)
            }
            server.createContext("/catalog") { exchange ->
                requests += "/catalog"
                if (catalogAbortBeforeHeaders) {
                    exchange.close()
                } else if (catalogStatus in 300..399)
                    exchange.responseHeaders.add("Location", "/redirect-target")
                if (catalogAbortMidStream && catalogStatus == 200) {
                    exchange.sendResponseHeaders(200, catalog.size.toLong())
                    exchange.responseBody.write(catalog.copyOf(1))
                    exchange.responseBody.close()
                } else if (catalogResponseDelayMillis > 0 && catalogStatus == 200) {
                    Thread.sleep(catalogResponseDelayMillis)
                    respond(exchange, 200, catalog)
                } else if (catalogChunked && catalogStatus == 200) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(catalog) }
                } else respond(exchange, catalogStatus, catalog)
            }
            listOf("/bundle", "/manifest", "/result").forEach { path ->
                server.createContext(path) { exchange ->
                    requests += path
                    uploads += path to exchange.requestBody.readBytes()
                    respond(exchange, 200, ByteArray(0))
                }
            }
            server.start()
            block(server, uploads, requests)
        } finally {
            server.stop(0)
        }
    }

    private fun run(
        server: HttpServer,
        request: DeriveRequest,
        transfer: WorkerHttpTransfer = WorkerHttpTransfer(true),
        expectedExit: Int = 0,
        catalogRequestDeadlineMillis: Long? = null,
    ): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, CanonicalJson.write(request))
            val exit =
                DeriveWorkerMain.run(
                    arrayOf("--request-file", file.toString(), "--allow-loopback-http"),
                    { transfer },
                    System::getenv,
                    catalogLoaderFactory = { directory, loopback ->
                        CatalogJarLoader(
                            directory,
                            allowLoopbackHttp = loopback,
                            requestDeadlineMillis =
                                catalogRequestDeadlineMillis
                                    ?: gg.grounds.transfer.RequestDeadline.DEFAULT_TIMEOUT_MILLIS,
                        )
                    },
                )
            assertEquals(expectedExit, exit)
            return exit
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun catalogTransportFailure(
        catalogAbortBeforeHeaders: Boolean = false,
        catalogAbortMidStream: Boolean = false,
        catalogResponseDelayMillis: Long = 0,
    ) {
        val catalog = generatedCatalogJar()
        val source = archive(mapOf("scene.json" to validGroundsScene.encodeToByteArray()))
        catalogWorkerServer(
            source,
            catalog,
            catalogAbortBeforeHeaders = catalogAbortBeforeHeaders,
            catalogAbortMidStream = catalogAbortMidStream,
            catalogResponseDelayMillis = catalogResponseDelayMillis,
        ) { server, uploads, requests ->
            val candidate =
                AssetCatalogCandidate(
                    "stable",
                    "grounds:assets",
                    "1",
                    "coord",
                    "catalog.jar",
                    URI("http://127.0.0.1:${server.address.port}/catalog"),
                    digest(catalog),
                    catalog.size.toLong(),
                )
            val request =
                request(server, digest(source)).copy(catalogCandidates = listOf(candidate))
            assertEquals(0, run(server, request, catalogRequestDeadlineMillis = 25))
            assertEquals(
                listOf("/source") +
                    List(if (catalogAbortBeforeHeaders || catalogAbortMidStream) 2 else 1) {
                        "/catalog"
                    } +
                    "/result",
                requests,
            )
            assertEquals(listOf("/result"), uploads.map { it.first })
            val marker = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", marker.scope.name)
            assertTrue(marker.retryable)
        }
    }

    private fun runRequest(request: DeriveRequest): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, CanonicalJson.write(request))
            return DeriveWorkerMain.run(
                arrayOf("--request-file", file.toString(), "--allow-loopback-http")
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun runRaw(bytes: ByteArray, vararg flags: String): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, bytes)
            return DeriveWorkerMain.run(
                arrayOf(
                    *flags.take(1).toTypedArray(),
                    file.toString(),
                    *flags.drop(1).toTypedArray(),
                )
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun request(server: HttpServer, sourceSha256: String): DeriveRequest {
        val base = "http://127.0.0.1:${server.address.port}"
        return DeriveRequest(
            mapId = UUID.randomUUID(),
            version = 1,
            attempt = UUID.randomUUID(),
            sourceSha256 = sourceSha256,
            sourceUrl = URI("$base/source?secret=redact"),
            bundleUrl = URI("$base/bundle?secret=redact"),
            manifestUrl = URI("$base/manifest?secret=redact"),
            resultUrl = URI("$base/result?secret=redact"),
            catalogCandidates = emptyList(),
        )
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            files.forEach { (name, bytes) ->
                output.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
        return ByteArrayOutputStream()
            .also { compressed -> ZstdOutputStream(compressed).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun generatedCatalogJar(extraEntry: String? = null): ByteArray {
        val owner =
            requireNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"
                    )
                )
                .use { it.readBytes() }
        return ByteArrayOutputStream().use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(
                    JarEntry("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class")
                )
                jar.write(owner)
                jar.closeEntry()
                extraEntry?.let { name ->
                    jar.putNextEntry(JarEntry(name))
                    jar.write(byteArrayOf(1))
                    jar.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val validGroundsScene =
            """{"schemaVersion":1,"id":"grounds:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"grounds:assets","version":"1"},"actions":{"id":"grounds:actions","version":"1"}},"groups":[],"elements":[]}"""
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun bundleEntries(bytes: ByteArray): Map<String, ByteArray> =
        ZstdInputStream(bytes.inputStream()).use { compressed ->
            TarArchiveInputStream(compressed).use { tar ->
                buildMap {
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isDirectory) put(entry.name, tar.readBytes())
                    }
                }
            }
        }
}
