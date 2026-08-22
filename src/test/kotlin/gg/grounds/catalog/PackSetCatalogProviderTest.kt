package gg.grounds.catalog

import com.sun.net.httpserver.HttpServer
import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetHttpResponse
import gg.grounds.resourcepacks.client.PackSetHttpTransport
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackSetCatalogProviderTest {
    @Test
    fun `enabled provider starts down then activates stable and edge from their channel routes`() {
        LoopbackCatalogServer().use { server ->
            val root = Files.createTempDirectory("packset-catalogs")
            val stable = server.source(PackSetChannel.STABLE)
            val edge = server.source(PackSetChannel.EDGE)
            server.publish(stable, "1.2.3", 1)
            server.publish(edge, edgeVersion(1), 1)
            val (provider, stableClient, edgeClient) = provider(server, root)
            provider.use {
                assertFalse(provider.ready())
                assertEquals(
                    HealthCheckResponse.Status.DOWN,
                    PackSetCatalogReadiness(provider, true).call().status,
                )
                provider.start()
                await { provider.candidates().size == 2 }
                assertEquals(listOf("stable", "edge"), provider.candidates().map { it.channel })
                assertEquals(
                    listOf("1.2.3", edgeVersion(1)),
                    provider.candidates().map { it.version },
                )
                assertEquals(
                    listOf(
                        "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/grounds-resourcepack-catalog-v1.2.3.jar",
                        "https://assets.example.test/resourcepacks/packsets/global/builds/$BUILD_ID/grounds-resourcepack-catalog-edge-${BUILD_ID.take(12)}.jar",
                    ),
                    provider.candidates().map { it.uri.toString() },
                )
                assertEquals(
                    listOf("READY", "READY"),
                    provider.channelStates().values.map { it.status },
                )
                assertTrue(Files.isDirectory(root.resolve("stable")))
                assertTrue(Files.isDirectory(root.resolve("edge")))
                assertTrue(server.requests.contains(stable.channelUri.path))
                assertTrue(server.requests.contains(edge.channelUri.path))
                assertEquals(2, server.requests.count { it.endsWith("/manifest.json") })
            }
            assertEquals("CLOSED", stableClient.state().status.name)
            assertEquals("CLOSED", edgeClient.state().status.name)
        }
    }

    @Test
    fun `validated snapshots remain candidates while failed and corrupt refreshes degrade channels`() {
        LoopbackCatalogServer().use { server ->
            val root = Files.createTempDirectory("packset-catalogs")
            val stable = server.source(PackSetChannel.STABLE)
            val edge = server.source(PackSetChannel.EDGE)
            server.publish(stable, "1.2.3", 1)
            server.publish(edge, edgeVersion(1), 1)
            val (provider, stableClient, edgeClient) = provider(server, root)
            provider.use {
                provider.start()
                await { provider.candidates().size == 2 }
                val expected = provider.candidates()
                server.fail(stable)
                server.corrupt(edge)
                stableClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
                edgeClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
                assertEquals(expected, provider.candidates())
                assertEquals(
                    listOf("DEGRADED", "DEGRADED"),
                    provider.channelStates().values.map { it.status },
                )
                assertEquals(
                    HealthCheckResponse.Status.UP,
                    PackSetCatalogReadiness(provider, true).call().status,
                )
            }
        }
    }

    @Test
    fun `new served documents replace candidates without reconfiguration and close prevents refresh`() {
        LoopbackCatalogServer().use { server ->
            val root = Files.createTempDirectory("packset-catalogs")
            val stable = server.source(PackSetChannel.STABLE)
            val edge = server.source(PackSetChannel.EDGE)
            server.publish(stable, "1.2.3", 1)
            server.publish(edge, edgeVersion(1), 1)
            val (provider, stableClient, edgeClient) = provider(server, root)
            provider.start()
            await { provider.candidates().size == 2 }
            server.publish(stable, "1.2.4", 2)
            server.publish(edge, edgeVersion(2), 2)
            stableClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
            edgeClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
            await { provider.candidates().map { it.version } == listOf("1.2.4", edgeVersion(2)) }
            assertEquals(listOf("1.2.4", edgeVersion(2)), provider.candidates().map { it.version })
            provider.close()
            assertEquals(
                listOf("CLOSED", "CLOSED"),
                provider.channelStates().values.map { it.status },
            )
            stableClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
            edgeClient.refreshNow().toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(
                listOf("CLOSED", "CLOSED"),
                provider.channelStates().values.map { it.status },
            )
        }
    }

    @Test
    fun `disabled derive health is up with disabled status`() {
        LoopbackCatalogServer().use { server ->
            val (provider, _, _) = provider(server, Files.createTempDirectory("packset-catalogs"))
            provider.use {
                val response = PackSetCatalogReadiness(provider, false).call()
                assertEquals(HealthCheckResponse.Status.UP, response.status)
                assertEquals("disabled", response.data.orElseThrow()["status"])
            }
        }
    }

    private fun provider(server: LoopbackCatalogServer, root: Path): ProviderClients {
        val stable = client(server.source(PackSetChannel.STABLE), root.resolve("stable"), server)
        val edge = client(server.source(PackSetChannel.EDGE), root.resolve("edge"), server)
        return ProviderClients(PackSetCatalogProvider(stable, edge), stable, edge)
    }

    private fun client(source: PackSetSource, cache: Path, server: LoopbackCatalogServer) =
        PackSetClient(
            PackSetClientConfig(
                source,
                cache,
                refreshInterval = Duration.ofHours(1),
                requestTimeout = Duration.ofSeconds(1),
            ),
            server.transport(),
        )

    private data class ProviderClients(
        val provider: PackSetCatalogProvider,
        val stable: PackSetClient,
        val edge: PackSetClient,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private inner class LoopbackCatalogServer : AutoCloseable {
        private val responses = AtomicReference<Map<String, Response>>(emptyMap())
        val requests = mutableListOf<String>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        init {
            server.createContext("/") { exchange ->
                synchronized(requests) { requests += exchange.requestURI.path }
                val response =
                    responses.get()[exchange.requestURI.path] ?: Response(404, ByteArray(0))
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.use { it.write(response.body) }
                exchange.close()
            }
            server.start()
        }

        fun source(channel: PackSetChannel) =
            PackSetSource(URI("https://assets.example.test"), "global", channel)

        fun publish(source: PackSetSource, version: String, sequence: Long) {
            val publication = publication(source.channel, version)
            val manifest = manifest(source, version, publication).encodeToByteArray()
            val channel = channel(source, publication, sequence, manifest).encodeToByteArray()
            responses.updateAndGet {
                it +
                    mapOf(
                        source.channelUri.path to Response(200, channel),
                        publication.manifestPath to Response(200, manifest),
                    )
            }
        }

        fun fail(source: PackSetSource) {
            responses.updateAndGet { it + (source.channelUri.path to Response(500, ByteArray(0))) }
        }

        fun corrupt(source: PackSetSource) {
            responses.updateAndGet {
                it + (source.channelUri.path to Response(200, "{}\n".encodeToByteArray()))
            }
        }

        fun transport() =
            object : PackSetHttpTransport {
                override fun get(
                    uri: URI,
                    ifNoneMatch: String?,
                    timeout: Duration,
                ): PackSetHttpResponse {
                    val connection =
                        URI("http://127.0.0.1:${server.address.port}${uri.path}")
                            .toURL()
                            .openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    try {
                        val status = connection.responseCode
                        val body =
                            (if (status >= 400) connection.errorStream else connection.inputStream)
                                ?.readBytes() ?: ByteArray(0)
                        return PackSetHttpResponse(
                            status,
                            connection.getHeaderField("ETag"),
                            ByteArrayInputStream(body),
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
            }

        override fun close() = server.stop(0)
    }

    private data class Response(val status: Int, val body: ByteArray)

    private data class Publication(val type: String, val id: String, val manifestPath: String)

    private fun publication(channel: PackSetChannel, version: String): Publication =
        if (channel == PackSetChannel.STABLE)
            Publication(
                "release",
                "v$version",
                "/resourcepacks/packsets/global/releases/v$version/manifest.json",
            )
        else
            Publication(
                "build",
                BUILD_ID,
                "/resourcepacks/packsets/global/builds/$BUILD_ID/manifest.json",
            )

    private fun channel(
        source: PackSetSource,
        publication: Publication,
        sequence: Long,
        manifest: ByteArray,
    ) =
        """
        {
          "channel": "${source.channel.name.lowercase()}",
          "manifest": {
            "sha256": "${sha256(manifest)}",
            "size": ${manifest.size},
            "url": "https://assets.example.test${publication.manifestPath}"
          },
          "packSet": "global",
          "schemaVersion": 2,
          "sequence": $sequence,
          "target": {
            "id": "${publication.id}",
            "type": "${publication.type}"
          }
        }
        """
            .trimIndent()
            .plus("\n")

    private fun manifest(source: PackSetSource, version: String, publication: Publication): String {
        val root =
            "https://assets.example.test${publication.manifestPath.removeSuffix("/manifest.json")}"
        val suffix =
            if (publication.type == "release") publication.id else "edge-${BUILD_ID.take(12)}"
        return """
        {
          "catalog": {
            "coordinate": "gg.grounds:resourcepacks-catalog:$version",
            "file": "grounds-resourcepack-catalog-$suffix.jar",
            "id": "grounds:resourcepacks",
            "sha256": "${"a".repeat(64)}",
            "size": 3,
            "version": "$version"
          },
          "minecraft": {
            "resourcePackFormat": 88,
            "version": "26.2"
          },
          "packSet": "${source.packSet}",
          "packs": [
            {
              "id": "grounds-content",
              "order": 0,
              "required": true,
              "resourcePackFormat": 88,
              "role": "content",
              "sha1": "${"b".repeat(40)}",
              "sha256": "${"c".repeat(64)}",
              "size": 4,
              "url": "$root/grounds-content-pack-$suffix.zip",
              "uuid": "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"
            },
            {
              "id": "grounds-platform",
              "order": 1,
              "required": true,
              "resourcePackFormat": 88,
              "role": "platform",
              "sha1": "${"d".repeat(40)}",
              "sha256": "${"e".repeat(64)}",
              "size": 5,
              "url": "$root/grounds-platform-pack-$suffix.zip",
              "uuid": "8da7cffe-bb04-55e0-9868-7789ce5de362"
            }
          ],
          "provenance": {
            "commit": "$BUILD_ID",
            "repository": "groundsgg/resourcepacks"
          },
          "publication": {
            "id": "${publication.id}",
            "type": "${publication.type}"
          },
          "schemaVersion": 2,
          "version": "$version"
        }
        """
            .trimIndent()
            .plus("\n")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun edgeVersion(sequence: Long) = "0.0.0-edge.$sequence.g${BUILD_ID.take(12)}"

    private companion object {
        const val BUILD_ID = "1969c1e6a3799e976de46eab019a16b2ee257ea7"
    }
}
