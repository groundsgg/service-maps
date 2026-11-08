package gg.grounds.lod

import gg.grounds.domain.LodClaim
import io.fabric8.kubernetes.api.model.CapabilitiesBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
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

enum class LodJobStatus {
    SUSPENDED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

interface LodJobGateway {
    fun create(claim: LodClaim, requestJson: String)

    fun activate(claim: LodClaim)

    fun status(claim: LodClaim): LodJobStatus?

    /** Foreground deletion plus absence of associated pods; false keeps the DB slot reserved. */
    fun stopAndConfirm(claim: LodClaim): Boolean
}

@ApplicationScoped
class Fabric8LodJobGateway(
    private val client: KubernetesClient,
    private val namespace: String,
    private val serviceAccount: String,
    private val node: String,
    private val isolated: Boolean = false,
    private val ownerPodName: String = "",
    private val ownerPodUid: String = "",
) : LodJobGateway {
    @Inject
    constructor(
        client: KubernetesClient,
        @ConfigProperty(name = "grounds.maps.derive.namespace") namespace: String,
        @ConfigProperty(
            name = "grounds.maps.derive.service-account",
            defaultValue = "service-maps-derive-worker",
        )
        serviceAccount: String,
        @ConfigProperty(name = "grounds.maps.lod.node") node: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.pilot.isolated", defaultValue = "false")
        isolated: Boolean,
        @ConfigProperty(name = "grounds.maps.lod.pilot.owner-pod-name")
        ownerPodName: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.pilot.owner-pod-uid") ownerPodUid: Optional<String>,
    ) : this(
        client,
        namespace,
        serviceAccount,
        node.orElse(""),
        isolated,
        ownerPodName.orElse(""),
        ownerPodUid.orElse(""),
    )

    init {
        require(
            !isolated ||
                (ownerPodName.isNotBlank() &&
                    ownerPodUid.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        ) {
            "isolated LOD pilot requires its owning API Pod identity"
        }
    }

    override fun create(claim: LodClaim, requestJson: String) {
        require(requestJson.length <= 64 * 1024) { "LOD request exceeds limit" }
        try {
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .resource(job(claim, requestJson))
                .create()
        } catch (e: KubernetesClientException) {
            if (e.code != 409) throw e
            val existing =
                client.batch().v1().jobs().inNamespace(namespace).withName(claim.jobName).get()
            require(
                existing?.metadata?.annotations?.get("grounds.gg/lod-attempt") ==
                    claim.attempt.toString() &&
                    existing.spec.template.spec.containers.single().image == claim.workerImage
            ) {
                "conflicting LOD Job"
            }
        }
    }

    override fun activate(claim: LodClaim) {
        val resource = client.batch().v1().jobs().inNamespace(namespace).withName(claim.jobName)
        val job = requireNotNull(resource.get()) { "LOD job missing before activation" }
        require(
            job.metadata.annotations?.get("grounds.gg/lod-attempt") == claim.attempt.toString() &&
                job.spec.template.spec.containers.single().image == claim.workerImage
        ) {
            "conflicting LOD job"
        }
        require(job.metadata.deletionTimestamp == null) { "LOD job is stopping" }
        if (job.spec.suspend != true) return
        val patch = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
        patch
            .addObject()
            .put("op", "test")
            .put("path", "/metadata/uid")
            .put("value", requireNotNull(job.metadata.uid))
        patch
            .addObject()
            .put("op", "test")
            .put("path", "/metadata/resourceVersion")
            .put("value", requireNotNull(job.metadata.resourceVersion))
        patch.addObject().put("op", "replace").put("path", "/spec/suspend").put("value", false)
        resource.patch(
            io.fabric8.kubernetes.client.dsl.base.PatchContext.of(
                io.fabric8.kubernetes.client.dsl.base.PatchType.JSON
            ),
            patch.toString(),
        )
    }

    override fun status(claim: LodClaim): LodJobStatus? {
        val job =
            client.batch().v1().jobs().inNamespace(namespace).withName(claim.jobName).get()
                ?: return null
        val status = job.status
        return when {
            (status?.conditions ?: emptyList()).any {
                it.type == "Failed" && it.status == "True"
            } -> LodJobStatus.FAILED
            (status?.succeeded ?: 0) > 0 -> LodJobStatus.SUCCEEDED
            job.spec.suspend == true -> LodJobStatus.SUSPENDED
            else -> LodJobStatus.RUNNING
        }
    }

    override fun stopAndConfirm(claim: LodClaim): Boolean {
        val job = client.batch().v1().jobs().inNamespace(namespace).withName(claim.jobName)
        job.withPropagationPolicy(DeletionPropagation.FOREGROUND).delete()
        if (job.get() != null) return false
        return client
            .pods()
            .inNamespace(namespace)
            .withLabel("job-name", claim.jobName)
            .list()
            .items
            .isEmpty()
    }

    internal fun job(claim: LodClaim, requestJson: String): Job {
        require(node.isNotBlank()) { "an existing LOD worker node is required" }
        require(claim.workerImage.matches(Regex("[^\\s]+@sha256:[a-f0-9]{64}"))) {
            "LOD worker image must be immutable"
        }
        val labels = mapOf("app.kubernetes.io/name" to LABEL_VALUE)
        val pod =
            PodSpecBuilder()
                .withRestartPolicy("Never")
                .addToNodeSelector("kubernetes.io/hostname", node)
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
                        .withEmptyDir(
                            EmptyDirVolumeSourceBuilder().withSizeLimit(Quantity("4Gi")).build()
                        )
                        .build()
                )
                .withContainers(
                    ContainerBuilder()
                        .withName("lod-worker")
                        .withImage(claim.workerImage)
                        .withImagePullPolicy(if (isolated) "Never" else "IfNotPresent")
                        .withCommand(
                            "java",
                            "-Xmx1g",
                            "-Xss512k",
                            "-XX:ActiveProcessorCount=2",
                            "-XX:+UseSerialGC",
                            "-XX:MaxDirectMemorySize=256m",
                            "-XX:MaxMetaspaceSize=256m",
                            "-XX:ReservedCodeCacheSize=128m",
                            "--enable-native-access=ALL-UNNAMED",
                            "-Djava.io.tmpdir=/work",
                            "-cp",
                            "/deployments/lod-worker/lib/*",
                            "gg.grounds.lod.worker.LodWorkerMainKt",
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
                                        "cpu" to Quantity("2"),
                                        "memory" to Quantity("2Gi"),
                                        "ephemeral-storage" to Quantity("4Gi"),
                                    )
                                )
                                .addToLimits(
                                    mapOf(
                                        "cpu" to Quantity("2"),
                                        "memory" to Quantity("2Gi"),
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
                    if (isolated)
                        it.ownerReferences =
                            listOf(
                                OwnerReferenceBuilder()
                                    .withApiVersion("v1")
                                    .withKind("Pod")
                                    .withName(ownerPodName)
                                    .withUid(ownerPodUid)
                                    .withBlockOwnerDeletion(false)
                                    .build()
                            )
                    it.name = claim.jobName
                    it.namespace = namespace
                    it.labels = labels
                    it.annotations = mapOf("grounds.gg/lod-attempt" to claim.attempt.toString())
                }
            )
            .withNewSpec()
            .withSuspend(true)
            .withBackoffLimit(0)
            .withParallelism(1)
            .withCompletions(1)
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
        const val REQUEST_ENV = "LOD_REQUEST_JSON"
        const val LABEL_VALUE = "service-maps-lod"
        const val DEADLINE_SECONDS = 900L
    }
}
