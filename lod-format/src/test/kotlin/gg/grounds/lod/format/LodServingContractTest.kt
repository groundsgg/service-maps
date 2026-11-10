package gg.grounds.lod.format

import kotlin.test.*

class LodServingContractTest {
    private val request =
        LodServingDiscoveryRequest(
            mapBundleSha256 = "a".repeat(64),
            sourceBundleSha256 = "b".repeat(64),
            registrySha256 = "c".repeat(64),
            generatorSha256 = "d".repeat(64),
        )

    @Test
    fun `discovery accepts only exact schema and lowercase digests`() {
        request.validate()
        for (bad in
            listOf(
                request.copy(schemaVersion = 2),
                request.copy(mapBundleSha256 = "A".repeat(64)),
                request.copy(sourceBundleSha256 = "../source"),
                request.copy(registrySha256 = ""),
                request.copy(generatorSha256 = "x".repeat(64)),
            )) {
            assertFailsWith<IllegalArgumentException> { bad.validate() }
        }
    }

    @Test
    fun `selection validates canonical run UUID and every identity field`() {
        val selection =
            LodServingSelection(
                runId = "abcdefab-cdef-4abc-8def-abcdefabcdef",
                mapBundleSha256 = request.mapBundleSha256,
                sourceBundleSha256 = request.sourceBundleSha256,
                registrySha256 = request.registrySha256,
                generatorSha256 = request.generatorSha256,
            )
        selection.validate()
        for (bad in
            listOf(
                selection.copy(runId = "0-0-0-0-0"),
                selection.copy(runId = selection.runId.uppercase()),
                selection.copy(schemaVersion = 0),
                selection.copy(sourceBundleSha256 = "f".repeat(63)),
            )) {
            assertFailsWith<IllegalArgumentException> { bad.validate() }
        }
    }
}
