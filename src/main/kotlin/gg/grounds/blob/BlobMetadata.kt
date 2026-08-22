package gg.grounds.blob

/**
 * Object facts that are safe to use for storage integrity decisions.
 *
 * [eTag] is only an opaque object-version token for conditional requests. It is never a content
 * digest: neither R2 nor the S3 API promise that it represents a SHA-256 hash.
 */
data class BlobMetadata(val sizeBytes: Long, val eTag: String? = null)
