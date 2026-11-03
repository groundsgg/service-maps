package gg.grounds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import software.amazon.awssdk.checksums.DefaultChecksumAlgorithm

class NativeChecksumProviderTest {
    @Test
    fun `native CRC32C retains the standard checksum result`() {
        val checksum = NativeChecksumProvider.crc32cImplementation()
        checksum.update("123456789".encodeToByteArray())
        assertEquals(0xe3069283L, checksum.value)
        checksum.reset()
        assertEquals(0L, checksum.value)
    }

    @Test
    fun `missing optional CRT algorithms fail explicitly`() {
        assertThrows(RuntimeException::class.java) {
            NativeChecksumProvider.crc64NvmeCrtImplementation()
        }
        assertThrows(RuntimeException::class.java) {
            NativeChecksumProvider.crtXxHash(DefaultChecksumAlgorithm.XXHASH64)
        }
    }
}
