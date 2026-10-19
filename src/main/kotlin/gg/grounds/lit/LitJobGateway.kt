package gg.grounds.lit

import io.fabric8.kubernetes.api.model.CapabilitiesBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty

enum class LitJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

/** Runs one lighting job per bundle attempt. */
interface LitJobGateway {
    /** Creates the Job; one that already exists under [name] is left as it is. */
    fun create(name: String, requestJson: String)

    /** Null when there is no such Job. */
    fun status(name: String): LitJobStatus?
}

/**
 * Lighting Jobs run the lit worker that ships in the service's own image (under
 * `/deployments/lit-worker`), in the derive namespace, under the derive worker's service account
 * and hardening — the same image digest, so nothing new to pin or deploy.
 */
@ApplicationScoped
class Fabric8LitJobGateway(
    private val client: KubernetesClient,
    private val namespace: String,
    private val image: String,
    private val serviceAccount: String,
) : LitJobGateway {
    @Inject
    constructor(
        client: KubernetesClient,
        @ConfigProperty(name = "grounds.maps.derive.namespace") namespace: String,
        @ConfigProperty(name = "grounds.maps.derive.image") image: Optional<String>,
        @ConfigProperty(name = "grounds.maps.derive.service-account") serviceAccount: String,
    ) : this(client, namespace, image.orElse(""), serviceAccount)

    override fun create(name: String, requestJson: String) {
        require(image.isNotBlank()) { "the derive image is required to light maps" }
        try {
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .resource(job(name, requestJson))
                .create()
        } catch (e: KubernetesClientException) {
            if (e.code != 409) throw e
        }
    }

    override fun status(name: String): LitJobStatus? {
        val job =
            client.batch().v1().jobs().inNamespace(namespace).withName(name).get() ?: return null
        val status = job.status
        return when {
            (status?.conditions ?: emptyList()).any {
                it.type == "Failed" && it.status == "True"
            } -> LitJobStatus.FAILED
            (status?.succeeded ?: 0) > 0 -> LitJobStatus.SUCCEEDED
            else -> LitJobStatus.RUNNING
        }
    }

    internal fun job(name: String, requestJson: String): Job {
        val labels = mapOf("app.kubernetes.io/name" to LABEL_VALUE)
        val pod =
            PodSpecBuilder()
                .withRestartPolicy("Never")
                .withServiceAccountName(serviceAccount)
                .withAutomountServiceAccountToken(false)
                .withSecurityContext(
                    PodSecurityContextBuilder()
                        .withRunAsNonRoot(true)
                        .withRunAsUser(65532)
                        .withRunAsGroup(65532)
                        .withFsGroup(65532)
                        .withSeccompProfile(
                            SeccompProfileBuilder().withType("RuntimeDefault").build()
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
                        .withName("lit-worker")
                        .withImage(image)
                        .withImagePullPolicy("IfNotPresent")
                        .withCommand(
                            "java",
                            // One map at a time, in a bounded heap: every pinned map lights in
                            // well under 512 MiB.
                            "-Xmx1g",
                            "-Djava.io.tmpdir=/work",
                            "-cp",
                            "/deployments/lit-worker/lib/*",
                            "gg.grounds.lit.worker.LitWorkerMainKt",
                        )
                        .addNewEnv()
                        .withName(REQUEST_ENV)
                        .withValue(requestJson)
                        .endEnv()
                        .addNewEnv()
                        .withName("TMPDIR")
                        .withValue("/work")
                        .endEnv()
                        .withResources(
                            ResourceRequirementsBuilder()
                                .addToRequests(
                                    mapOf(
                                        "cpu" to Quantity("500m"),
                                        "memory" to Quantity("1280Mi"),
                                        "ephemeral-storage" to Quantity("2Gi"),
                                    )
                                )
                                .addToLimits(
                                    mapOf(
                                        "cpu" to Quantity("2"),
                                        "memory" to Quantity("1536Mi"),
                                        "ephemeral-storage" to Quantity("4Gi"),
                                    )
                                )
                                .build()
                        )
                        .withSecurityContext(
                            SecurityContextBuilder()
                                .withRunAsNonRoot(true)
                                .withReadOnlyRootFilesystem(true)
                                .withAllowPrivilegeEscalation(false)
                                .withCapabilities(CapabilitiesBuilder().withDrop("ALL").build())
                                .build()
                        )
                        .withVolumeMounts(
                            VolumeMountBuilder().withName("work").withMountPath("/work").build()
                        )
                        .build()
                )
                .build()
        return JobBuilder()
            .withMetadata(
                ObjectMeta().also {
                    it.name = name
                    it.namespace = namespace
                    it.labels = labels
                }
            )
            .withNewSpec()
            .withBackoffLimit(1)
            .withActiveDeadlineSeconds(DEADLINE_SECONDS)
            .withTtlSecondsAfterFinished(3600)
            .withTemplate(
                PodTemplateSpecBuilder()
                    .withMetadata(ObjectMeta().also { it.labels = labels })
                    .withSpec(pod)
                    .build()
            )
            .endSpec()
            .build()
    }

    companion object {
        const val REQUEST_ENV = "LIT_REQUEST_JSON"
        const val LABEL_VALUE = "service-maps-lit"
        const val DEADLINE_SECONDS = 900L
    }
}
