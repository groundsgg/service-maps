package gg.grounds.catalog

import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetHttpResponse
import gg.grounds.resourcepacks.client.PackSetHttpTransport
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackSetCatalogProviderTest {
    @Test
    fun `configured clients start unavailable with separate channel caches`() {
        val root = Files.createTempDirectory("packset-catalogs")
        PackSetCatalogProvider.configured(URI("https://cdn.grounds.gg"), "grounds-global", root)
            .use { provider ->
                assertFalse(provider.ready())
                assertTrue(provider.candidates().isEmpty())
                assertTrue(provider.channelStates().values.none { state -> state.available })
            }
    }

    @Test
    fun `stable and edge clients retain deterministic empty ordering until validated snapshots arrive`() {
        val root = Files.createTempDirectory("packset-catalogs")
        val stable = client(PackSetChannel.STABLE, root.resolve("stable"), "1.2.3")
        val edge = client(PackSetChannel.EDGE, root.resolve("edge"), "2.0.0-edge.1")
        PackSetCatalogProvider(stable, edge).use { provider ->
            assertTrue(provider.candidates().isEmpty())
            assertEquals(listOf("stable", "edge"), provider.channelStates().keys.toList())
        }
    }

    private fun client(
        channel: PackSetChannel,
        cache: java.nio.file.Path,
        version: String,
    ): PackSetClient {
        val source = PackSetSource(URI("https://assets.example.test"), "global", channel)
        val manifest = manifest(channel, version).encodeToByteArray()
        val target = if (channel == PackSetChannel.STABLE) "v$version" else "a".repeat(40)
        val type = if (channel == PackSetChannel.STABLE) "release" else "build"
        val location =
            if (channel == PackSetChannel.STABLE) "releases/$target" else "builds/$target"
        val channelJson =
            """{"channel":"${channel.name.lowercase()}","manifest":{"sha256":"${sha256(manifest)}","size":${manifest.size},"url":"https://assets.example.test/resourcepacks/packsets/global/$location/manifest.json"},"packSet":"global","schemaVersion":2,"sequence":1,"target":{"id":"$target","type":"$type"}}""" +
                "\n"
        val transport =
            object : PackSetHttpTransport {
                override fun get(
                    uri: URI,
                    ifNoneMatch: String?,
                    timeout: java.time.Duration,
                ): PackSetHttpResponse {
                    val bytes: ByteArray =
                        if (uri == source.channelUri) channelJson.encodeToByteArray() else manifest
                    return PackSetHttpResponse(200, null, ByteArrayInputStream(bytes))
                }
            }
        return PackSetClient(PackSetClientConfig(source, cache), transport)
    }

    private fun manifest(channel: PackSetChannel, version: String): String {
        val target = if (channel == PackSetChannel.STABLE) "v$version" else "a".repeat(40)
        val type = if (channel == PackSetChannel.STABLE) "release" else "build"
        return """{"catalog":{"coordinate":"gg.grounds:resourcepacks-catalog:$version","file":"catalog-$version.jar","id":"grounds:resourcepacks","sha256":"${"a".repeat(64)}","size":3,"version":"$version"},"minecraft":{"resourcePackFormat":88,"version":"26.2"},"packSet":"global","packs":[],"provenance":{"commit":"${"b".repeat(40)}","repository":"groundsgg/resourcepacks"},"publication":{"id":"$target","type":"$type"},"schemaVersion":2,"version":"$version"}""" +
            "\n"
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
