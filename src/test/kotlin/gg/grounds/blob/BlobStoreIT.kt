package gg.grounds.blob

import gg.grounds.MinioResource
import gg.grounds.domain.MapTrust
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@QuarkusTest
@QuarkusTestResource(MinioResource::class)
class BlobStoreIT {

    @Inject lateinit var blobs: BlobStore

    @Test
    fun `presigned derive URLs are exact private keys and expire after thirty minutes`() {
        val map = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val source = BlobStore.uploadKey(UUID.randomUUID().toString())
        val result = BlobStore.deriveResultKey(map, 7, attempt)

        val putUrl = blobs.presignPut(result)
        val getUrl = blobs.presignGet(source)

        assertEquals("1800", URI.create(putUrl).queryParameters()["X-Amz-Expires"])
        assertEquals("1800", URI.create(getUrl).queryParameters()["X-Amz-Expires"])
        assertTrue(URI.create(putUrl).path.endsWith("/${MinioResource.PRIVATE}/$result"))
        assertTrue(URI.create(getUrl).path.endsWith("/${MinioResource.PRIVATE}/$source"))
        assertTrue(BlobStore.legacyUploadKey("legacy").endsWith("/source.zip"))

        val put =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(putUrl))
                        .PUT(HttpRequest.BodyPublishers.ofString("result"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
        assertEquals(200, put.statusCode())
        assertEquals(BlobMetadata(sizeBytes = 6), blobs.headPrivate(result))
        assertEquals("result", readPrivate(result))
    }

    @Test
    fun `head returns metadata for present objects and null for missing objects`() {
        val privateKey = "tmp/test/${UUID.randomUUID()}/private"
        val publicKey = "bundle/test/${UUID.randomUUID()}"
        putPrivate(privateKey, "private")
        putPublic(publicKey, "public")

        assertEquals(BlobMetadata(sizeBytes = 7), blobs.headPrivate(privateKey))
        assertEquals(BlobMetadata(sizeBytes = 6), blobs.headPublic(publicKey, MapTrust.FIRST_PARTY))
        assertNull(blobs.headPrivate("tmp/test/${UUID.randomUUID()}/missing"))
        assertNull(blobs.headPublic("bundle/test/${UUID.randomUUID()}", MapTrust.FIRST_PARTY))
    }

    @Test
    fun `promotion copies an absent destination then verifies it`() {
        val source = "tmp/derive/${UUID.randomUUID()}/bundle.tar.zst"
        val destination = "bundle/test/${UUID.randomUUID()}.tar.zst"
        putPrivate(source, "bundle")

        blobs.copyPrivateToPublic(source, destination, expectedSizeBytes = 6, MapTrust.FIRST_PARTY)

        assertEquals(
            BlobMetadata(sizeBytes = 6),
            blobs.headPublic(destination, MapTrust.FIRST_PARTY),
        )
        assertEquals("bundle", readPublic(destination))
    }

    @Test
    fun `promotion accepts a matching destination without replacing it`() {
        val source = "tmp/derive/${UUID.randomUUID()}/bundle.tar.zst"
        val destination = "bundle/test/${UUID.randomUUID()}.tar.zst"
        putPrivate(source, "source")
        putPublic(destination, "public")

        blobs.copyPrivateToPublic(source, destination, expectedSizeBytes = 6, MapTrust.FIRST_PARTY)

        assertEquals("public", readPublic(destination))
    }

    @Test
    fun `promotion rejects a conflicting destination size`() {
        val source = "tmp/derive/${UUID.randomUUID()}/bundle.tar.zst"
        val destination = "bundle/test/${UUID.randomUUID()}.tar.zst"
        putPrivate(source, "source")
        putPublic(destination, "different")

        assertThrows(BlobIntegrityException::class.java) {
            blobs.copyPrivateToPublic(
                source,
                destination,
                expectedSizeBytes = 6,
                MapTrust.FIRST_PARTY,
            )
        }
        assertEquals("different", readPublic(destination))
    }

    private fun putPrivate(key: String, body: String) = put(MinioResource.PRIVATE, key, body)

    private fun putPublic(key: String, body: String) = put(MinioResource.PUBLIC, key, body)

    private fun put(bucket: String, key: String, body: String) {
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromString(body),
            )
        }
    }

    private fun readPrivate(key: String): String = read(MinioResource.PRIVATE, key)

    private fun readPublic(key: String): String = read(MinioResource.PUBLIC, key)

    private fun read(bucket: String, key: String): String =
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asUtf8String()
        }

    private fun URI.queryParameters(): Map<String, String> =
        requireNotNull(rawQuery).split('&').associate { parameter ->
            val (name, value) = parameter.split('=', limit = 2)
            name to value
        }
}
