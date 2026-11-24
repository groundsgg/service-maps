package gg.grounds.lod.worker

/**
 * Build-input fingerprint includes sources and resolved runtime dependency bytes, independent of
 * Git dirtiness.
 */
object GeneratorBuild {
    val sha256: String by lazy {
        requireNotNull(javaClass.getResourceAsStream("/lod-generator.sha256"))
            .bufferedReader()
            .use { it.readText().trim() }
            .also { require(gg.grounds.lod.format.isDigest(it)) }
    }
}
