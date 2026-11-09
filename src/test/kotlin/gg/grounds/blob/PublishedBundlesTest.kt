package gg.grounds.blob

import gg.grounds.domain.MapTrust
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublishedBundlesTest {
    private val sha = "a".repeat(64)
    private val store = Objects()
    private val bundles = PublishedBundles(store)

    @Test
    fun `source selection uses an existing public object instead of a missing fork bucket`() {
        store.objects[sha to MapTrust.FIRST_PARTY] = BlobMetadata(12)

        assertEquals(MapTrust.FIRST_PARTY, bundles.sourceTrust(sha, MapTrust.UNTRUSTED))
    }

    @Test
    fun `a cross-trust fork copies the accepted object once without recomputing content`() {
        store.objects[sha to MapTrust.FIRST_PARTY] = BlobMetadata(12)

        bundles.ensure(sha, MapTrust.UNTRUSTED, 12)
        bundles.ensure(sha, MapTrust.UNTRUSTED, 12)

        assertEquals(listOf(sha to MapTrust.UNTRUSTED), store.copies)
        assertTrue(bundles.available(sha, MapTrust.UNTRUSTED, 12))
    }

    @Test
    fun `a conflicting immutable destination is never overwritten or reported available`() {
        store.objects[sha to MapTrust.FIRST_PARTY] = BlobMetadata(12)
        store.objects[sha to MapTrust.UNTRUSTED] = BlobMetadata(13)

        assertFalse(bundles.available(sha, MapTrust.UNTRUSTED, 12))
        assertThrows(BlobIntegrityException::class.java) {
            bundles.ensure(sha, MapTrust.UNTRUSTED, 12)
        }
        assertTrue(store.copies.isEmpty())
    }

    @Test
    fun `a missing source stays unavailable and failed copies can be retried`() {
        assertFalse(bundles.available(sha, MapTrust.UNTRUSTED))
        assertThrows(BlobIntegrityException::class.java) { bundles.ensure(sha, MapTrust.UNTRUSTED) }
        store.objects[sha to MapTrust.FIRST_PARTY] = BlobMetadata(12)
        store.failCopy = true
        assertThrows(IllegalStateException::class.java) { bundles.ensure(sha, MapTrust.UNTRUSTED) }
        assertFalse(bundles.available(sha, MapTrust.UNTRUSTED))
        store.failCopy = false

        bundles.ensure(sha, MapTrust.UNTRUSTED)

        assertTrue(bundles.available(sha, MapTrust.UNTRUSTED, 12))
    }

    @Test
    fun `successful copy must be verified in the target bucket`() {
        store.objects[sha to MapTrust.FIRST_PARTY] = BlobMetadata(12)
        store.dropCopy = true

        assertThrows(BlobIntegrityException::class.java) {
            bundles.ensure(sha, MapTrust.UNTRUSTED, 12)
        }
        assertFalse(bundles.available(sha, MapTrust.UNTRUSTED))
    }

    private class Objects : PublicBundleObjects {
        val objects = mutableMapOf<Pair<String, MapTrust>, BlobMetadata>()
        val copies = mutableListOf<Pair<String, MapTrust>>()
        var failCopy = false
        var dropCopy = false

        override fun head(sha256: String, trust: MapTrust) = objects[sha256 to trust]

        override fun copy(sha256: String, source: MapTrust, target: MapTrust, sizeBytes: Long) {
            if (failCopy) error("temporary store outage")
            copies += sha256 to target
            if (!dropCopy) objects[sha256 to target] = objects.getValue(sha256 to source)
        }
    }
}
