package gg.grounds

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.URI
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

/**
 * A real object store, because the two properties worth testing are properties of the store: that a
 * presigned URL an outside client PUTs to actually accepts the bytes, and that the pin file lands
 * in the *public* bucket while uploads stay in the private one.
 */
class MinioResource : QuarkusTestResourceLifecycleManager {

    private lateinit var container: GenericContainer<*>

    override fun start(): Map<String, String> {
        container =
            GenericContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
                .withEnv("MINIO_ROOT_USER", KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET)
                .withCommand("server", "/data", "--address", ":9000")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        container.start()

        endpoint = "http://${container.host}:${container.getMappedPort(9000)}"
        client(endpoint!!).use { s3 ->
            s3.createBucket(CreateBucketRequest.builder().bucket(PRIVATE).build())
            s3.createBucket(CreateBucketRequest.builder().bucket(PUBLIC).build())
        }

        return mapOf(
            "grounds.maps.r2.endpoint" to endpoint!!,
            "grounds.maps.r2.region" to "us-east-1",
            "grounds.maps.r2.access-key" to KEY,
            "grounds.maps.r2.secret-key" to SECRET,
            "grounds.maps.r2.private-bucket" to PRIVATE,
            "grounds.maps.r2.public-bucket" to PUBLIC,
        )
    }

    override fun stop() {
        if (this::container.isInitialized) container.stop()
        endpoint = null
    }

    companion object {
        const val KEY = "testkey"
        const val SECRET = "testsecret"
        const val PRIVATE = "maps-private"
        const val PUBLIC = "maps-public"

        /** Set while the container runs, so a test can look into the buckets itself. */
        var endpoint: String? = null
            private set

        fun client(endpoint: String): S3Client =
            S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("us-east-1"))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(KEY, SECRET))
                )
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build()
                )
                .build()
    }
}
