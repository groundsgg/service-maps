package gg.grounds.derive

/** Limits for untrusted source archives. Values are deliberately injectable for worker tests. */
data class ArchiveLimits(
    val maxCompressedBytes: Long = 1L shl 30,
    val maxExpandedBytes: Long = 8L shl 30,
    val maxFileBytes: Long = 2L shl 30,
    val maxEntries: Int = 250_000,
    val maxPathBytes: Int = 1_024,
    val maxSceneBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maxCompressedBytes > 0 && maxExpandedBytes > 0 && maxFileBytes > 0)
        require(maxEntries > 0 && maxPathBytes > 0 && maxSceneBytes > 0)
        require(maxSceneBytes <= maxFileBytes)
    }
}
