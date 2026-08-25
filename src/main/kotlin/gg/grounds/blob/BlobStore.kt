package gg.grounds.blob

import gg.grounds.derive.DeriveArtifactStore
import gg.grounds.derive.DeriveArtifactUnavailableException
import gg.grounds.domain.MapTrust
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.time.Duration
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

/** A public content-addressed key names bytes that conflict with its recorded size. */
class BlobIntegrityException(message: String) : RuntimeException(message)

/** A conditional copy did not leave a public object whose size can be safely accepted. */
class BlobCopyPreconditionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * R2, reached with the plain AWS SDK exactly as grounds-lod's generator does.
 *
 * Three buckets, and the splits are load-bearing rather than tidiness.
 *
 * Uploads and unpublished blobs land in the **private** bucket, and **promoting an object into a
 * public one is a server-side copy that only the approval path performs**. The copy is the
 * moderation gate as a physical property, not as a conditional somebody can forget.
 *
 * First-party and creator content then go to **different public buckets on different hosts**. An
 * abuse report against one creator's world is handled by blocking or purging on the creator host,
 * and the lobby every player loads is not in the blast radius; a browser also treats the two as
 * different origins.
 *
 * No object this service writes is ever large. Bundles travel between the client and R2 directly,
 * via a presigned PUT, so a 256 MB world never passes through this process — which also keeps it
 * clear of the ~100 MB request-body limit at the edge.
 */
@ApplicationScoped
class BlobStore
@Inject
constructor(
    @ConfigProperty(name = "grounds.maps.r2.endpoint") private val endpoint: String,
    @ConfigProperty(name = "grounds.maps.r2.region") private val region: String,
    @ConfigProperty(name = "grounds.maps.r2.access-key") private val accessKey: String,
    @ConfigProperty(name = "grounds.maps.r2.secret-key") private val secretKey: String,
    @ConfigProperty(name = "grounds.maps.r2.private-bucket") val privateBucket: String,
    @ConfigProperty(name = "grounds.maps.r2.public-bucket") val publicBucket: String,
    @ConfigProperty(name = "grounds.maps.r2.ugc-bucket") val ugcBucket: String,
    /** Where first-party content is read from, e.g. `https://content.grounds.gg`. */
    @ConfigProperty(name = "grounds.maps.cdn.content-base") val contentBaseUrl: String,
    /** Where creator content is read from. A different origin on purpose. */
    @ConfigProperty(name = "grounds.maps.cdn.ugc-base") val ugcBaseUrl: String,
) : DeriveArtifactStore {

    private val credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))

    private val client: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region.ifBlank { "auto" }))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    private val presigner: S3Presigner by lazy {
        S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region.ifBlank { "auto" }))
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    /**
     * A URL the client PUTs the upload to directly.
     *
     * The content type is deliberately not pinned: SigV4 presigning signs only `host`, so a content
     * type handed to the presigner is dropped silently and whatever the client sends is what gets
     * stored. Where the stored type matters it is set on the way out, by [copyToPublic], which
     * replaces metadata.
     */
    fun presignPut(key: String, ttl: Duration = PRESIGN_TTL): String =
        presigner
            .presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(
                        PutObjectRequest.builder().bucket(privateBucket).key(key).build()
                    )
                    .build()
            )
            .url()
            .toExternalForm()

    /** A URL that grants the worker access to exactly one private source object. */
    fun presignGet(key: String, ttl: Duration = PRESIGN_TTL): String =
        presigner
            .presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(
                        GetObjectRequest.builder().bucket(privateBucket).key(key).build()
                    )
                    .build()
            )
            .url()
            .toExternalForm()

    override fun headPrivate(key: String): BlobMetadata? = artifactAccess {
        head(privateBucket, key)
    }

    /** Completion markers are small, private, and read only after the worker Job succeeds. */
    override fun getPrivate(key: String, maxBytes: Long): ByteArray {
        return artifactAccess {
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            val response: ResponseInputStream<GetObjectResponse> =
                client.getObject(GetObjectRequest.builder().bucket(privateBucket).key(key).build())
            response.use {
                val declared = it.response().contentLength()
                if (declared != null && declared > maxBytes) {
                    throw BlobIntegrityException("private object $key exceeds $maxBytes bytes")
                }
                val output =
                    java.io.ByteArrayOutputStream(
                        (declared ?: 0)
                            .coerceAtMost(maxBytes)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    )
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    if (output.size().toLong() + read > maxBytes) {
                        throw BlobIntegrityException("private object $key exceeds $maxBytes bytes")
                    }
                    output.write(buffer, 0, read)
                }
                if (declared != null && output.size().toLong() != declared) {
                    throw BlobIntegrityException(
                        "private object $key ended before its declared size"
                    )
                }
                output.toByteArray()
            }
        }
    }

    override fun promotePrivateBundle(
        sourceKey: String,
        destinationKey: String,
        expectedSizeBytes: Long,
        trust: MapTrust,
    ) {
        artifactAccess { copyPrivateToPublic(sourceKey, destinationKey, expectedSizeBytes, trust) }
    }

    fun headPublic(key: String, trust: MapTrust): BlobMetadata? = head(publicBucketFor(trust), key)

    /**
     * Creates a public derived object or proves that an already-public object has the expected
     * size. R2 independently conditions selection of the private source and commit of the public
     * destination; those checks are documented as non-atomic relative to one another. The final
     * head therefore remains the only fact this service accepts for its database transition.
     */
    fun copyPrivateToPublic(
        sourceKey: String,
        destinationKey: String,
        expectedSizeBytes: Long,
        trust: MapTrust,
    ) {
        require(expectedSizeBytes >= 0) { "expected size must be non-negative" }
        val destinationBucket = publicBucketFor(trust)
        val beforeCopy = head(destinationBucket, destinationKey)
        if (beforeCopy != null) {
            requireMatchingSize(
                "public destination $destinationKey",
                beforeCopy.sizeBytes,
                expectedSizeBytes,
            )
            return
        }

        val source = headPrivate(sourceKey) ?: throw noSuchPrivateObject(sourceKey)
        requireMatchingSize("private source $sourceKey", source.sizeBytes, expectedSizeBytes)
        val sourceVersionToken =
            source.eTag
                ?: throw BlobCopyPreconditionException(
                    "private source $sourceKey has no version token"
                )

        try {
            client.copyObject(
                conditionalCopyRequest(
                    sourceBucket = privateBucket,
                    sourceKey = sourceKey,
                    sourceVersionToken = sourceVersionToken,
                    destinationBucket = destinationBucket,
                    destinationKey = destinationKey,
                )
            )
        } catch (e: S3Exception) {
            if (e.statusCode() != 412) throw e
            resolveCopyPreconditionFailure(
                head(destinationBucket, destinationKey),
                expectedSizeBytes,
                e,
            )
            return
        }

        val afterCopy = head(destinationBucket, destinationKey)
        if (afterCopy == null) {
            throw BlobCopyPreconditionException(
                "copy did not create public destination $destinationKey"
            )
        }
        requireMatchingSize(
            "public destination $destinationKey",
            afterCopy.sizeBytes,
            expectedSizeBytes,
        )
    }

    /** Small metadata objects only — the pin file. Never a bundle. */
    fun putPublic(key: String, body: String, contentType: String, cacheControl: String) {
        client.putObject(
            PutObjectRequest.builder()
                .bucket(publicBucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(cacheControl)
                .build(),
            RequestBody.fromString(body),
        )
    }

    /** The bucket a map's content is served from, decided by where it came from. */
    fun publicBucketFor(trust: MapTrust): String =
        if (trust == MapTrust.UNTRUSTED) ugcBucket else publicBucket

    /** The host it is read from. Different origins, deliberately. */
    fun publicBaseFor(trust: MapTrust): String =
        (if (trust == MapTrust.UNTRUSTED) ugcBaseUrl else contentBaseUrl).trimEnd('/')

    /** Promotes an object out of the private bucket. This is the moderation gate. */
    fun copyToPublic(
        sourceKey: String,
        destinationKey: String,
        contentType: String,
        trust: MapTrust,
    ) {
        client.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(privateBucket)
                .sourceKey(sourceKey)
                .destinationBucket(publicBucketFor(trust))
                .destinationKey(destinationKey)
                .contentType(contentType)
                .metadataDirective("REPLACE")
                .build()
        )
    }

    private fun head(bucket: String, key: String): BlobMetadata? =
        try {
            val response =
                client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            BlobMetadata(sizeBytes = response.contentLength(), eTag = response.eTag())
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) null else throw e
        }

    private fun <T> artifactAccess(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (
                e is DeriveArtifactUnavailableException ||
                    e is NoSuchKeyException ||
                    e is BlobIntegrityException ||
                    e is BlobCopyPreconditionException ||
                    e is IllegalArgumentException ||
                    (e is S3Exception && e.statusCode() == 404)
            )
                throw e
            throw DeriveArtifactUnavailableException(e)
        }

    private fun requireMatchingSize(subject: String, actual: Long, expected: Long) {
        if (actual != expected) {
            throw BlobIntegrityException("$subject is $actual bytes, expected $expected")
        }
    }

    private fun noSuchPrivateObject(key: String): NoSuchKeyException =
        NoSuchKeyException.builder().message("private object is missing: $key").build()

    companion object {
        private const val READ_BUFFER_BYTES = 8 * 1024
        val PRESIGN_TTL: Duration = Duration.ofMinutes(30)

        /** The only write key for a newly opened upload. */
        fun uploadKey(uploadId: String): String = "tmp/uploads/$uploadId/source.tar.zst"

        /** Read-only compatibility key for rows committed before tar.zst uploads existed. */
        fun legacyUploadKey(uploadId: String): String = "tmp/uploads/$uploadId/source.zip"

        fun deriveBundleKey(map: UUID, version: Int, attempt: UUID): String =
            "tmp/derive/$map/$version/$attempt/bundle.tar.zst"

        fun deriveManifestKey(map: UUID, version: Int, attempt: UUID): String =
            "tmp/derive/$map/$version/$attempt/derived-manifest.json"

        fun deriveResultKey(map: UUID, version: Int, attempt: UUID): String =
            "tmp/derive/$map/$version/$attempt/result.json"

        /**
         * Content-addressed, fanned out by the first byte of the digest so no prefix ever holds the
         * whole corpus. Nothing in the key says which map or which version, which is what makes a
         * rename free and a fork cost zero bytes.
         */
        fun bundleKey(sha256: String): String = "bundle/sha256/${sha256.take(2)}/$sha256.tar.zst"

        /** The one deliberately mutable object in the design. */
        fun pinFileKey(environment: String): String = "pins/$environment.json"
    }
}

internal fun conditionalCopyRequest(
    sourceBucket: String,
    sourceKey: String,
    sourceVersionToken: String,
    destinationBucket: String,
    destinationKey: String,
): CopyObjectRequest =
    CopyObjectRequest.builder()
        .sourceBucket(sourceBucket)
        .sourceKey(sourceKey)
        .destinationBucket(destinationBucket)
        .destinationKey(destinationKey)
        .overrideConfiguration { configuration ->
            configuration.putHeader("x-amz-copy-source-if-match", sourceVersionToken)
            configuration.putHeader("cf-copy-destination-if-none-match", "*")
        }
        .build()

/** Handles the 412 race outcome: only a now-visible public object of the expected size is safe. */
internal fun resolveCopyPreconditionFailure(
    destination: BlobMetadata?,
    expectedSizeBytes: Long,
    cause: Throwable? = null,
): Boolean {
    if (destination == null) {
        throw BlobCopyPreconditionException(
            "conditional copy failed without a public destination to verify",
            cause,
        )
    }
    if (destination.sizeBytes != expectedSizeBytes) {
        throw BlobIntegrityException(
            "public destination is ${destination.sizeBytes} bytes, expected $expectedSizeBytes"
        )
    }
    return true
}
