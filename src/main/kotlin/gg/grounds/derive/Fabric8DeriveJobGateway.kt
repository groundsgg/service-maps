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
import java.security.MessageDigest
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
        val contract = requireNotNull(ownedContract(job)) { "derive Job contract is incomplete" }
        job.metadata.annotations = mapOf(CONTRACT_FINGERPRINT to fingerprint(contract))
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
        val expectedContract = ownedContract(expected) ?: return false
        val existingContract = ownedContract(existing) ?: return false
        val expectedFingerprint = fingerprint(expectedContract)
        return (hasExpectedLabels(existing.metadata?.labels, expected.metadata?.labels) &&
            hasExpectedLabels(
                existing.spec?.template?.metadata?.labels,
                expected.spec?.template?.metadata?.labels,
            ) &&
            existing.metadata?.annotations?.get(CONTRACT_FINGERPRINT) == expectedFingerprint &&
            fingerprint(existingContract) == expectedFingerprint &&
            existingContract == expectedContract)
    }

    /**
     * Kubernetes may default a selector and add controller labels to a persisted Job. This records
     * only the fields this gateway owns, so generated fields neither make a valid recovery fail nor
     * weaken the comparison of the request, image, execution environment, and isolation settings.
     */
    private fun ownedContract(job: Job): OwnedJobContract? {
        val metadata = job.metadata ?: return null
        val labels = metadata.labels ?: return null
        val spec = job.spec ?: return null
        val pod = spec.template?.spec ?: return null
        val worker = pod.containers?.singleOrNull { it.name == "derive-worker" } ?: return null
        val environment = worker.env?.associate { it.name to it.value } ?: return null
        if (
            environment.size != worker.env.size ||
                worker.env.any {
                    it.name.isNullOrBlank() || it.value == null || it.valueFrom != null
                }
        )
            return null
        val request = environment["DERIVE_REQUEST_JSON"] ?: return null
        val tempDirectory = environment["TMPDIR"] ?: return null
        val workVolume = pod.volumes?.singleOrNull { it.name == "work" } ?: return null
        val workMount = worker.volumeMounts?.singleOrNull { it.name == "work" } ?: return null
        if (worker.volumeMounts.size != 1) return null
        return OwnedJobContract(
            metadata.name ?: return null,
            IDENTITY_LABELS.associateWith { labels[it] ?: return null },
            request,
            worker.image ?: return null,
            worker.imagePullPolicy,
            worker.command ?: return null,
            environment,
            tempDirectory,
            spec.backoffLimit,
            spec.activeDeadlineSeconds,
            spec.ttlSecondsAfterFinished,
            pod.restartPolicy,
            pod.serviceAccountName,
            pod.automountServiceAccountToken,
            pod.securityContext?.runAsNonRoot,
            pod.securityContext?.seccompProfile?.type,
            workVolume.emptyDir != null,
            workVolume.emptyDir?.medium,
            workVolume.emptyDir?.sizeLimit?.amount + workVolume.emptyDir?.sizeLimit?.format,
            workMount.mountPath,
            workMount.readOnly,
            quantities(worker.resources?.requests),
            quantities(worker.resources?.limits),
            worker.securityContext?.runAsNonRoot,
            worker.securityContext?.readOnlyRootFilesystem,
            worker.securityContext?.allowPrivilegeEscalation,
            worker.securityContext?.capabilities?.drop?.toSortedSet() ?: emptySet(),
            worker.securityContext?.capabilities?.add?.toSortedSet() ?: emptySet(),
        )
    }

    private fun quantities(
        quantities: Map<String, io.fabric8.kubernetes.api.model.Quantity>?
    ): Map<String, String> =
        quantities.orEmpty().mapValues { (_, value) -> value.amount + value.format }.toSortedMap()

    private fun hasExpectedLabels(
        actual: Map<String, String>?,
        expected: Map<String, String>?,
    ): Boolean =
        actual != null && expected != null && expected.all { (key, value) -> actual[key] == value }

    private fun fingerprint(contract: OwnedJobContract): String =
        MessageDigest.getInstance("SHA-256").digest(CanonicalJson.write(contract)).joinToString(
            ""
        ) {
            "%02x".format(it)
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
        const val UUID_BASE36_WIDTH = 25
        const val CONTRACT_FINGERPRINT = "grounds.gg/derive-contract-sha256"
        val IDENTITY_LABELS =
            setOf(
                "app.kubernetes.io/name",
                "grounds.gg/map",
                "grounds.gg/version",
                "grounds.gg/attempt",
            )
    }

    private data class OwnedJobContract(
        val name: String,
        val identityLabels: Map<String, String>,
        val request: String,
        val image: String,
        val imagePullPolicy: String?,
        val command: List<String>,
        val environment: Map<String, String>,
        val tempDirectory: String,
        val backoffLimit: Int?,
        val activeDeadlineSeconds: Long?,
        val ttlSecondsAfterFinished: Int?,
        val restartPolicy: String?,
        val serviceAccountName: String?,
        val automountServiceAccountToken: Boolean?,
        val podRunAsNonRoot: Boolean?,
        val seccompProfile: String?,
        val workEmptyDir: Boolean,
        val workEmptyDirMedium: String?,
        val workEmptyDirSizeLimit: String?,
        val workMountPath: String?,
        val workMountReadOnly: Boolean?,
        val requests: Map<String, String>,
        val limits: Map<String, String>,
        val runAsNonRoot: Boolean?,
        val readOnlyRootFilesystem: Boolean?,
        val allowPrivilegeEscalation: Boolean?,
        val droppedCapabilities: Set<String>,
        val addedCapabilities: Set<String>,
    )
}
