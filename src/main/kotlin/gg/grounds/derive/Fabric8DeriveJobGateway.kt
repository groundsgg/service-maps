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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
        require(!enabled || serviceAccount.isNotBlank()) {
            "derive worker service account is required when derive is enabled"
        }
    }

    override fun create(request: DeriveJobRequest) {
        val name = name(request)
        val jobs = client.batch().v1().jobs().inNamespace(namespace)
        val existing = jobs.withName(name).get()
        if (existing == null) jobs.resource(job(name, request)).create()
        else
            require(existing.metadata.labels == labels(request)) {
                "derive Job $name labels do not match its attempt"
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
                            "-cp",
                            "/deployments/quarkus-app/app/*:/deployments/quarkus-app/lib/*:/deployments/quarkus-app/quarkus/*",
                            "gg.grounds.derive.DeriveWorkerMain",
                            "--request-env",
                            "DERIVE_REQUEST_JSON",
                        )
                        .addNewEnv()
                        .withName("DERIVE_REQUEST_JSON")
                        .withValue(request.requestJson)
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
        "derive-${identity.mapId.toString().take(8)}-${identity.version}-${identity.attempt.toString().take(8)}"
}
