package gg.grounds.blob

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobStorePromotionTest {

    @Test
    fun `object key helpers have only the documented upload and derive paths`() {
        val map = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val attempt = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertEquals("tmp/uploads/upload/source.tar.zst", BlobStore.uploadKey("upload"))
        assertEquals("tmp/uploads/upload/source.zip", BlobStore.legacyUploadKey("upload"))
        assertEquals(
            "tmp/derive/$map/3/$attempt/bundle.tar.zst",
            BlobStore.deriveBundleKey(map, 3, attempt),
        )
        assertEquals(
            "tmp/derive/$map/3/$attempt/derived-manifest.json",
            BlobStore.deriveManifestKey(map, 3, attempt),
        )
        assertEquals(
            "tmp/derive/$map/3/$attempt/result.json",
            BlobStore.deriveResultKey(map, 3, attempt),
        )
    }

    @Test
    fun `conditional copy binds the selected source version and creates only an absent destination`() {
        val request =
            conditionalCopyRequest(
                sourceBucket = "private",
                sourceKey = "tmp/derive/source",
                sourceVersionToken = "opaque-etag",
                destinationBucket = "public",
                destinationKey = "bundle/digest",
            )

        val headers = request.overrideConfiguration().orElseThrow().headers()
        assertEquals("opaque-etag", headers["x-amz-copy-source-if-match"]?.single())
        assertEquals("*", headers["cf-copy-destination-if-none-match"]?.single())
        assertFalse(headers.keys.any { it.contains("checksum", ignoreCase = true) })
    }

    @Test
    fun `precondition recovery accepts a destination with the expected size`() {
        assertTrue(
            resolveCopyPreconditionFailure(BlobMetadata(sizeBytes = 6), expectedSizeBytes = 6)
        )
    }

    @Test
    fun `precondition recovery rejects a conflicting destination`() {
        assertThrows(BlobIntegrityException::class.java) {
            resolveCopyPreconditionFailure(BlobMetadata(sizeBytes = 7), expectedSizeBytes = 6)
        }
    }

    @Test
    fun `precondition recovery fails closed when no valid destination exists`() {
        assertThrows(BlobCopyPreconditionException::class.java) {
            resolveCopyPreconditionFailure(null, expectedSizeBytes = 6)
        }
    }
}
