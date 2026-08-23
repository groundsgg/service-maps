package gg.grounds.derive

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigInteger
import java.nio.ByteBuffer
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class Fabric8DeriveJobGateway
@Inject
constructor(
    private val client: KubernetesClient,
    @ConfigProperty(name = "grounds.maps.derive.namespace") private val namespace: String,
    @ConfigProperty(name = "grounds.maps.derive.image") private val image: String,
    @ConfigProperty(name = "grounds.maps.derive.service-account")
    private val serviceAccount: String,
    @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
    private val enabled: Boolean,
) : DeriveJobGateway {
    init {
        require(!enabled || namespace.isNotBlank()) {
            "derive namespace is required when derive is enabled"
        }
        require(!enabled || image.isNotBlank()) {
            "derive image is required when derive is enabled"
        }
        require(!enabled || DIGEST_IMAGE.matches(image)) {
            "derive image must be pinned to an immutable sha256 digest when derive is enabled"
        }
        require(!enabled || serviceAccount.isNotBlank()) {
            "derive worker service account is required when derive is enabled"
        }
    }

    override fun create(request: DeriveJobRequest) {
        val name = name(request)
        val jobs = client.batch().v1().jobs().inNamespace(namespace)
        val expected = job(name, request)
        try {
            jobs.resource(expected).create()
        } catch (failure: KubernetesClientException) {
            if (failure.code != 409) throw failure
            val existing = jobs.withName(name).get()
            require(existing != null && sameImmutableJob(existing, expected)) {
                "derive Job $name conflicts with a different or incomplete immutable attempt"
            }
        }
    }

    /** Pure manifest seam for regression tests; it performs no client operation. */
    internal fun buildJob(request: DeriveJobRequest): Job = job(name(request), request)

    override fun find(identity: gg.grounds.domain.DeriveIdentity): DeriveJobStatus? {
        val job =
            client.batch().v1().jobs().inNamespace(namespace).withName(name(identity)).get()
                ?: return null
        val status = job.status
        return when {
            (status?.failed ?: 0) > 0 &&
                (status.conditions ?: emptyList()).any { it.type == "Failed" } ->
                DeriveJobStatus.FAILED
            (status?.succeeded ?: 0) > 0 -> DeriveJobStatus.SUCCEEDED
            else -> DeriveJobStatus.RUNNING
        }
    }

    override fun ready(): Boolean =
        try {
            client.batch().v1().jobs().inNamespace(namespace).list() != null
        } catch (_: Exception) {
            false
        }

    private fun job(name: String, request: DeriveJobRequest): Job {
        val labels: Map<String, String> = labels(request)
        val pod =
            PodSpecBuilder()
                .withRestartPolicy("Never")
                .withServiceAccountName(serviceAccount)
                .withAutomountServiceAccountToken(false)
                .withSecurityContext(
                    PodSecurityContextBuilder()
                        .withRunAsNonRoot(true)
                        .withSeccompProfile(
                            io.fabric8.kubernetes.api.model
                                .SeccompProfileBuilder()
                                .withType("RuntimeDefault")
                                .build()
                        )
                        .build()
                )
                .withVolumes(
                    VolumeBuilder()
                        .withName("work")
                        .withEmptyDir(EmptyDirVolumeSourceBuilder().build())
                        .build()
                )
                .withContainers(
                    ContainerBuilder()
                        .withName("derive-worker")
                        .withImage(image)
                        .withImagePullPolicy("IfNotPresent")
                        .withCommand(
                            "java",
                            "-Djava.io.tmpdir=/work",
                            "-cp",
                            "/deployments/quarkus-app/app/*:/deployments/quarkus-app/lib/boot/*:/deployments/quarkus-app/lib/main/*",
                            "gg.grounds.derive.DeriveWorkerMain",
                            "--request-env",
                            "DERIVE_REQUEST_JSON",
                        )
                        .addNewEnv()
                        .withName("DERIVE_REQUEST_JSON")
                        .withValue(request.requestJson)
                        .endEnv()
                        .addNewEnv()
                        .withName("TMPDIR")
                        .withValue("/work")
                        .endEnv()
                        .withResources(
                            ResourceRequirementsBuilder()
                                .addToRequests(
                                    mapOf(
                                        "cpu" to io.fabric8.kubernetes.api.model.Quantity("500m"),
                                        "memory" to io.fabric8.kubernetes.api.model.Quantity("1Gi"),
                                        "ephemeral-storage" to
                                            io.fabric8.kubernetes.api.model.Quantity("10Gi"),
                                    )
                                )
                                .addToLimits(
                                    mapOf(
                                        "cpu" to io.fabric8.kubernetes.api.model.Quantity("2"),
                                        "memory" to io.fabric8.kubernetes.api.model.Quantity("4Gi"),
                                        "ephemeral-storage" to
                                            io.fabric8.kubernetes.api.model.Quantity("20Gi"),
                                    )
                                )
                                .build()
                        )
                        .withSecurityContext(
                            SecurityContextBuilder()
                                .withRunAsNonRoot(true)
                                .withReadOnlyRootFilesystem(true)
                                .withAllowPrivilegeEscalation(false)
                                .withCapabilities(
                                    io.fabric8.kubernetes.api.model
                                        .CapabilitiesBuilder()
                                        .withDrop("ALL")
                                        .build()
                                )
                                .build()
                        )
                        .withVolumeMounts(
                            VolumeMountBuilder().withName("work").withMountPath("/work").build()
                        )
                        .build()
                )
                .build()
        val templateMeta = ObjectMeta().also { it.labels = labels }
        val jobMeta =
            ObjectMeta().also {
                it.name = name
                it.labels = labels
            }
        val template = PodTemplateSpecBuilder().withMetadata(templateMeta).withSpec(pod).build()
        return JobBuilder()
            .withMetadata(jobMeta)
            .withNewSpec()
            .withBackoffLimit(2)
            .withActiveDeadlineSeconds(1200)
            .withTtlSecondsAfterFinished(3600)
            .withTemplate(template)
            .endSpec()
            .build()
    }

    private fun labels(request: DeriveJobRequest) = labels(request.identity)

    private fun labels(identity: gg.grounds.domain.DeriveIdentity) =
        mapOf(
            "app.kubernetes.io/name" to "service-maps-derive",
            "grounds.gg/map" to identity.mapId.toString(),
            "grounds.gg/version" to identity.version.toString(),
            "grounds.gg/attempt" to identity.attempt.toString(),
        )

    private fun name(request: DeriveJobRequest) = name(request.identity)

    private fun name(identity: gg.grounds.domain.DeriveIdentity) =
        "d-${base36(identity.mapId)}-${Integer.toUnsignedString(identity.version, 36)}-${base36(identity.attempt)}"

    private fun sameImmutableJob(existing: Job, expected: Job): Boolean =
        existing.metadata?.labels == expected.metadata?.labels && existing.spec == expected.spec

    private fun base36(value: java.util.UUID): String {
        val bytes =
            ByteBuffer.allocate(16)
                .putLong(value.mostSignificantBits)
                .putLong(value.leastSignificantBits)
                .array()
        return BigInteger(1, bytes).toString(36).padStart(UUID_BASE36_WIDTH, '0')
    }

    private companion object {
        val DIGEST_IMAGE = Regex(".+@sha256:[a-f0-9]{64}")
        const val UUID_BASE36_WIDTH = 25
    }
}
