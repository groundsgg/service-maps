package gg.grounds.derive

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.utils.Serialization
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class Fabric8DeriveJobGateway(
    private val client: KubernetesClient,
    private val namespace: String,
    private val image: String,
    private val serviceAccount: String,
    private val enabled: Boolean,
) : DeriveJobGateway {
    @Inject
    constructor(
        client: KubernetesClient,
        @ConfigProperty(name = "grounds.maps.derive.namespace") namespace: String,
        @ConfigProperty(name = "grounds.maps.derive.image") image: Optional<String>,
        @ConfigProperty(name = "grounds.maps.derive.service-account") serviceAccount: String,
        @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
        enabled: Boolean,
    ) : this(client, namespace, image.orElse(""), serviceAccount, enabled)

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
            (status?.conditions ?: emptyList()).any {
                it.type == "Failed" && it.status == "True"
            } -> DeriveJobStatus.FAILED
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

    override fun watch(onEvent: () -> Unit, onClose: (Throwable?) -> Unit): AutoCloseable? {
        if (!enabled) return null
        val notified = AtomicBoolean(false)
        fun closed(cause: Throwable?) {
            if (notified.compareAndSet(false, true)) onClose(cause)
        }
        val watch =
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(DERIVE_LABEL, DERIVE_LABEL_VALUE)
                .watch(
                    object : Watcher<Job> {
                        override fun eventReceived(action: Watcher.Action, resource: Job) {
                            // Keep a local guard as defense in depth if a proxy returns a stream
                            // that does not honor the Kubernetes label selector.
                            if (resource.metadata?.labels?.get(DERIVE_LABEL) == DERIVE_LABEL_VALUE)
                                onEvent()
                        }

                        override fun onClose(cause: WatcherException?) = closed(cause)
                    }
                )
        return AutoCloseable {
            watch.close()
            closed(null)
        }
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
                it.namespace = namespace
                it.labels = labels
            }
        val template = PodTemplateSpecBuilder().withMetadata(templateMeta).withSpec(pod).build()
        val job =
            JobBuilder()
                .withMetadata(jobMeta)
                .withNewSpec()
                .withBackoffLimit(2)
                .withActiveDeadlineSeconds(1200)
                .withTtlSecondsAfterFinished(3600)
                .withTemplate(template)
                .endSpec()
                .build()
        job.metadata.annotations = mapOf(CONTRACT_FINGERPRINT to fingerprint(job))
        return job
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

    private fun sameImmutableJob(existing: Job, expected: Job): Boolean {
        val expectedFingerprint = fingerprint(expected)
        if (existing.metadata?.annotations?.get(CONTRACT_FINGERPRINT) != expectedFingerprint)
            return false
        val stored = normalizedJob(existing, stored = true) ?: return false
        val desired = normalizedJob(expected, stored = false) ?: return false
        return stored == desired
    }

    private fun fingerprint(job: Job): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(CanonicalJson.write(requireNotNull(normalizedJob(job, stored = false))))
            .joinToString("") { "%02x".format(it) }

    private fun normalizedJob(job: Job, stored: Boolean): JsonNode? =
        runCatching {
                val root = mapper.readTree(Serialization.asJson(job)) as ObjectNode
                val metadata = root.objectNode("metadata") ?: return null
                metadata.remove(SERVER_METADATA)
                metadata.objectNode("annotations")?.let { annotations ->
                    annotations.remove(CONTRACT_FINGERPRINT)
                    if (annotations.isEmpty) metadata.remove("annotations")
                }
                root.remove("status")
                val spec = root.objectNode("spec") ?: return null
                if (stored) {
                    verifyAndRemoveControllerFields(job, spec) ?: return null
                    normalizeJobDefaults(spec)
                }
                val pod = spec.objectNode("template")?.objectNode("spec") ?: return null
                if (stored) normalizePodDefaults(pod)
                root
            }
            .getOrNull()

    private fun verifyAndRemoveControllerFields(job: Job, spec: ObjectNode): Unit? {
        val uid = job.metadata?.uid?.takeIf { it.isNotBlank() } ?: return null
        val name = job.metadata?.name?.takeIf { it.isNotBlank() } ?: return null
        val selector = spec.objectNode("selector") ?: return null
        val matchLabels = selector.objectNode("matchLabels") ?: return null
        if (
            selector.path("matchExpressions").size() != 0 ||
                !controllerUidLabelsMatch(matchLabels, uid)
        )
            return null
        val labels =
            spec.objectNode("template")?.objectNode("metadata")?.objectNode("labels") ?: return null
        if (!controllerTemplateLabelsMatch(labels, uid, name)) return null
        spec.remove("selector")
        CONTROLLER_LABELS.forEach(labels::remove)
        return Unit
    }

    private fun controllerUidLabelsMatch(labels: ObjectNode, uid: String): Boolean =
        labels.size() in 1..2 &&
            labels.fields().asSequence().all { (key, value) ->
                key in CONTROLLER_UID_LABELS && value.asText() == uid
            }

    private fun controllerTemplateLabelsMatch(
        labels: ObjectNode,
        uid: String,
        name: String,
    ): Boolean =
        CONTROLLER_UID_LABELS.all { key -> labels.path(key).asText() == uid } &&
            CONTROLLER_NAME_LABELS.all { key -> labels.path(key).asText() == name }

    private fun normalizeJobDefaults(spec: ObjectNode) {
        spec.removeIfExact("completions", 1)
        spec.removeIfExact("parallelism", 1)
        spec.removeIfExact("completionMode", "NonIndexed")
        spec.removeIfExact("suspend", false)
        spec.removeIfExact("manualSelector", false)
        spec.removeIfExact("podReplacementPolicy", "TerminatingOrFailed")
    }

    private fun normalizePodDefaults(pod: ObjectNode) {
        if (
            pod.path("serviceAccount").isTextual &&
                pod.path("serviceAccount").asText() == pod.path("serviceAccountName").asText()
        )
            pod.remove("serviceAccount")
        pod.removeIfExact("dnsPolicy", "ClusterFirst")
        pod.removeIfExact("schedulerName", "default-scheduler")
        pod.removeIfExact("terminationGracePeriodSeconds", 30)
        pod.removeIfExact("enableServiceLinks", true)
        pod.path("containers").forEach { container ->
            (container as? ObjectNode)?.apply {
                removeIfExact("terminationMessagePath", "/dev/termination-log")
                removeIfExact("terminationMessagePolicy", "File")
            }
        }
    }

    private fun ObjectNode.objectNode(name: String): ObjectNode? = get(name) as? ObjectNode

    private fun ObjectNode.removeIfExact(name: String, value: String) {
        if (path(name).asText() == value) remove(name)
    }

    private fun ObjectNode.removeIfExact(name: String, value: Int) {
        if (path(name).asInt() == value) remove(name)
    }

    private fun ObjectNode.removeIfExact(name: String, value: Boolean) {
        if (path(name).asBoolean() == value) remove(name)
    }

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
        const val DERIVE_LABEL = "app.kubernetes.io/name"
        const val DERIVE_LABEL_VALUE = "service-maps-derive"
        const val UUID_BASE36_WIDTH = 25
        const val CONTRACT_FINGERPRINT = "grounds.gg/derive-contract-sha256"
        val CONTROLLER_UID_LABELS = setOf("controller-uid", "batch.kubernetes.io/controller-uid")
        val CONTROLLER_NAME_LABELS = setOf("job-name", "batch.kubernetes.io/job-name")
        val CONTROLLER_LABELS = CONTROLLER_UID_LABELS + CONTROLLER_NAME_LABELS
        val SERVER_METADATA =
            listOf(
                "uid",
                "resourceVersion",
                "generation",
                "creationTimestamp",
                "deletionTimestamp",
                "deletionGracePeriodSeconds",
                "managedFields",
                "selfLink",
            )
        val mapper = ObjectMapper()
    }
}
