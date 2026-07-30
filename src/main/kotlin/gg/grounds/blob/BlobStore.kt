package gg.grounds.blob

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

/**
 * R2, reached with the plain AWS SDK exactly as grounds-lod's generator does.
 *
 * Two buckets, and the split is load-bearing rather than tidiness: uploads and unpublished blobs
 * land in the private one, and **promoting an object into the public one is a server-side copy that
 * only the approval path performs**. The copy is the moderation gate as a physical property, not as
 * a conditional somebody can forget.
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
) {

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
    fun presignPut(key: String, ttl: Duration = Duration.ofMinutes(15)): String =
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

    /** Promotes an object out of the private bucket. This is the moderation gate. */
    fun copyToPublic(sourceKey: String, destinationKey: String, contentType: String) {
        client.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(privateBucket)
                .sourceKey(sourceKey)
                .destinationBucket(publicBucket)
                .destinationKey(destinationKey)
                .contentType(contentType)
                .metadataDirective("REPLACE")
                .build()
        )
    }

    companion object {
        /** `tmp/uploads/<id>/source.zip` — the private bucket expires this prefix in a day. */
        fun uploadKey(uploadId: String): String = "tmp/uploads/$uploadId/source.zip"

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
