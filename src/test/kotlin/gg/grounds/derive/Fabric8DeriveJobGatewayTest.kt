package gg.grounds.derive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.domain.DeriveIdentity
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Fabric8DeriveJobGatewayTest {
    @Test
    fun `watch delivers a loopback Job event and reports stream closure`() {
        val event = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cause = AtomicReference<Throwable?>()
        loopback { exchange, _ ->
                assertEquals("GET", exchange.requestMethod)
                val query =
                    URLDecoder.decode(exchange.requestURI.query.orEmpty(), StandardCharsets.UTF_8)
                assertTrue(query.contains("watch=true"))
                assertTrue(
                    query.contains("labelSelector=app.kubernetes.io/name=service-maps-derive")
                )
                exchange.responseHeaders.add("Content-Type", "application/json")
                val body =
                    """{"type":"MODIFIED","object":{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"derive","labels":{"app.kubernetes.io/name":"service-maps-derive"}}}}""" +
                        "\n"
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            .use { client ->
                val watch =
                    gateway(client.client)
                        .watch(
                            { event.countDown() },
                            { failure ->
                                cause.set(failure)
                                closed.countDown()
                            },
                        )!!
                assertTrue(event.await(2, TimeUnit.SECONDS))
                watch.close()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
        assertEquals(null, cause.get())
    }

    @Test
    fun `watch ignores unrelated loopback Jobs even if the server sends them`() {
        val events = java.util.concurrent.atomic.AtomicInteger()
        val event = CountDownLatch(1)
        val closed = CountDownLatch(1)
        loopback { exchange, _ ->
                val query =
                    URLDecoder.decode(exchange.requestURI.query.orEmpty(), StandardCharsets.UTF_8)
                assertTrue(
                    query.contains("labelSelector=app.kubernetes.io/name=service-maps-derive")
                )
                exchange.responseHeaders.add("Content-Type", "application/json")
                val body =
                    """{"type":"MODIFIED","object":{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"other","labels":{"app.kubernetes.io/name":"other"}}}}""" +
                        "\n" +
                        """{"type":"MODIFIED","object":{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"derive","labels":{"app.kubernetes.io/name":"service-maps-derive"}}}}""" +
                        "\n"
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            .use { client ->
                val watch =
                    gateway(client.client)
                        .watch(
                            {
                                events.incrementAndGet()
                                event.countDown()
                            },
                            { closed.countDown() },
                        )!!
                assertTrue(event.await(2, TimeUnit.SECONDS))
                watch.close()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
        assertEquals(1, events.get())
    }

    @Test
    fun `enabled configuration requires a worker image`() {
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "maps", "", "worker", true)
        }
    }

    @Test
    fun `disabled configuration permits an absent worker image`() {
        Fabric8DeriveJobGateway(client(), "maps", Optional.empty(), "worker", false)
    }

    @Test
    fun `enabled configuration requires namespace and worker service account`() {
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "", image(), "worker", true)
        }
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "maps", image(), "", true)
        }
    }

    @Test
    fun `enabled configuration requires an immutable digest image`() {
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "maps", "registry/maps:latest", "worker", true)
        }
    }

    @Test
    fun `manifest is deterministic and least privilege`() {
        val identity =
            DeriveIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                7,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "a".repeat(64),
            )
        val job =
            gateway()
                .buildJob(
                    DeriveJobRequest(
                        identity,
                        "{\"sourceUrl\":\"https://signed.example/?token=hidden\"}",
                    )
                )
        assertEquals(2, job.spec.backoffLimit)
        assertEquals("maps", job.metadata.namespace)
        assertEquals(1200, job.spec.activeDeadlineSeconds)
        assertEquals(3600, job.spec.ttlSecondsAfterFinished)
        val pod = job.spec.template.spec
        assertEquals(false, pod.automountServiceAccountToken)
        assertEquals("Never", pod.restartPolicy)
        assertTrue(pod.securityContext.runAsNonRoot)
        val c = pod.containers.single()
        assertEquals(image(), c.image)
        assertEquals("IfNotPresent", c.imagePullPolicy)
        assertTrue(c.command.contains("gg.grounds.derive.DeriveWorkerMain"))
        assertEquals(
            listOf(
                "java",
                "-Djava.io.tmpdir=/work",
                "-cp",
                "/deployments/quarkus-app/app/*:/deployments/quarkus-app/lib/boot/*:/deployments/quarkus-app/lib/main/*",
                "gg.grounds.derive.DeriveWorkerMain",
                "--request-env",
                "DERIVE_REQUEST_JSON",
            ),
            c.command,
        )
        assertEquals("DERIVE_REQUEST_JSON", c.env.single { it.name == "DERIVE_REQUEST_JSON" }.name)
        assertEquals(setOf("DERIVE_REQUEST_JSON", "TMPDIR"), c.env.map { it.name }.toSet())
        assertEquals("/work", c.env.single { it.name == "TMPDIR" }.value)
        assertTrue(c.env.none { it.valueFrom != null })
        val requests = requireNotNull(c.resources.requests)
        val limits = requireNotNull(c.resources.limits)
        assertQuantity(requests, "cpu", "500", "m")
        assertQuantity(requests, "memory", "1", "Gi")
        assertQuantity(requests, "ephemeral-storage", "10", "Gi")
        assertQuantity(limits, "cpu", "2", "")
        assertQuantity(limits, "memory", "4", "Gi")
        assertQuantity(limits, "ephemeral-storage", "20", "Gi")
        assertTrue(c.securityContext.runAsNonRoot)
        assertTrue(c.securityContext.readOnlyRootFilesystem)
        assertEquals(false, c.securityContext.allowPrivilegeEscalation)
        assertTrue(c.securityContext.capabilities.drop.contains("ALL"))
        assertEquals(job.metadata.labels, job.spec.template.metadata.labels)
        assertFalse(
            job.metadata.labels.values.any { it.contains("token") || it.contains("signed.example") }
        )
        assertEquals(setOf("grounds.gg/derive-contract-sha256"), job.metadata.annotations.keys)
        assertTrue(
            job.metadata.annotations
                .getValue("grounds.gg/derive-contract-sha256")
                .matches(Regex("[a-f0-9]{64}"))
        )
        assertFalse(
            job.metadata.annotations.values.any {
                it.contains("signed.example") || it.contains("token")
            }
        )
        assertEquals(listOf("work"), pod.volumes.map { it.name })
        assertEquals(listOf("/work"), c.volumeMounts.map { it.mountPath })
    }

    @Test
    fun `job identity encodes complete map version and attempt within DNS length`() {
        val first =
            request(
                mapId = "00000000-0000-0000-0000-000000000001",
                attempt = "00000000-0000-0000-0000-000000000002",
            )
        val samePrefixes =
            request(
                mapId = "00000000-0000-0000-0000-0000000000ff",
                attempt = "00000000-0000-0000-0000-0000000000ee",
            )

        val firstName = gateway().buildJob(first).metadata.name
        val secondName = gateway().buildJob(samePrefixes).metadata.name

        assertTrue(firstName.length <= 63)
        assertTrue(firstName.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
        assertFalse(firstName == secondName)
    }

    @Test
    fun `create serializes secure Job through Kubernetes client`() {
        loopback { exchange, received ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("/apis/batch/v1/namespaces/maps/jobs", exchange.requestURI.path)
                val job = Serialization.unmarshal(received, Job::class.java)
                assertEquals(image(), job.spec.template.spec.containers.single().image)
                assertEquals(
                    "/work",
                    job.spec.template.spec.containers
                        .single()
                        .env
                        .single { it.name == "TMPDIR" }
                        .value,
                )
                respond(exchange, 201, received)
            }
            .use { client -> gateway(client.client).create(request()) }
    }

    @Test
    fun `create recovers a conflict only when the stored immutable job exactly matches`() {
        var created: String? = null
        val methods = CopyOnWriteArrayList<String>()
        loopback { exchange, received ->
                methods += exchange.requestMethod
                when (exchange.requestMethod) {
                    "POST" -> {
                        created = received
                        respond(
                            exchange,
                            409,
                            "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"reason\":\"AlreadyExists\",\"code\":409}",
                        )
                    }
                    "GET" -> {
                        val stored = kubernetesStoredJob(requireNotNull(created))
                        respond(exchange, 200, Serialization.asJson(stored))
                    }
                    else -> respond(exchange, 405, "")
                }
            }
            .use { client -> gateway(client.client).create(request()) }
        assertEquals(listOf("POST", "GET"), methods)
    }

    @Test
    fun `create fails closed when a conflict returns a job with mismatched immutable request`() {
        var created: String? = null
        loopback { exchange, received ->
                when (exchange.requestMethod) {
                    "POST" -> {
                        created = received
                        respond(
                            exchange,
                            409,
                            "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"reason\":\"AlreadyExists\",\"code\":409}",
                        )
                    }
                    "GET" -> {
                        val mismatched = kubernetesStoredJob(requireNotNull(created))
                        mismatched.spec.template.spec.containers
                            .single()
                            .env
                            .single { it.name == "DERIVE_REQUEST_JSON" }
                            .value = "{\"different\":true}"
                        respond(exchange, 200, Serialization.asJson(mismatched))
                    }
                    else -> respond(exchange, 405, "")
                }
            }
            .use { client ->
                assertThrows<IllegalArgumentException> { gateway(client.client).create(request()) }
            }
    }

    @Test
    fun `create fails closed when a conflict returns incomplete attempt labels`() {
        var created: String? = null
        loopback { exchange, received ->
                when (exchange.requestMethod) {
                    "POST" -> {
                        created = received
                        respond(
                            exchange,
                            409,
                            "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"reason\":\"AlreadyExists\",\"code\":409}",
                        )
                    }
                    "GET" -> {
                        val incomplete = kubernetesStoredJob(requireNotNull(created))
                        incomplete.metadata.labels.remove("grounds.gg/attempt")
                        respond(exchange, 200, Serialization.asJson(incomplete))
                    }
                    else -> respond(exchange, 405, "")
                }
            }
            .use { client ->
                assertThrows<IllegalArgumentException> { gateway(client.client).create(request()) }
            }
    }

    @Test
    fun `create fails closed when a conflict returns a job with a mutated image`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.single().image =
                "registry.example/service-maps@sha256:" + "b".repeat(64)
        }
    }

    @Test
    fun `create fails closed when a conflict returns a job with a mutated fingerprint`() {
        assertConflictFails { stored ->
            stored.metadata.annotations =
                stored.metadata.annotations.orEmpty().toMutableMap().apply {
                    put("grounds.gg/derive-contract-sha256", "b".repeat(64))
                }
        }
    }

    @Test
    fun `create fails closed when a conflict returns a sidecar`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.add(
                io.fabric8.kubernetes.api.model
                    .ContainerBuilder()
                    .withName("sidecar")
                    .withImage("registry.example/sidecar@sha256:" + "c".repeat(64))
                    .build()
            )
        }
    }

    @Test
    fun `create fails closed when a conflict returns an init container`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.initContainers =
                listOf(
                    io.fabric8.kubernetes.api.model
                        .ContainerBuilder()
                        .withName("init")
                        .withImage("registry.example/init@sha256:" + "c".repeat(64))
                        .build()
                )
        }
    }

    @Test
    fun `create fails closed when a conflict returns a secret volume`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.volumes.add(
                io.fabric8.kubernetes.api.model
                    .VolumeBuilder()
                    .withName("credentials")
                    .withNewSecret()
                    .withSecretName("should-not-be-mounted")
                    .endSecret()
                    .build()
            )
        }
    }

    @Test
    fun `create fails closed when a conflict enables host networking`() {
        assertConflictFails { stored -> stored.spec.template.spec.hostNetwork = true }
    }

    @Test
    fun `create fails closed when a conflict adds an image pull secret`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.imagePullSecrets =
                listOf(
                    io.fabric8.kubernetes.api.model
                        .LocalObjectReferenceBuilder()
                        .withName("registry-credential")
                        .build()
                )
        }
    }

    @Test
    fun `create fails closed when a conflict returns an arbitrary label`() {
        assertConflictFails { stored ->
            stored.metadata.labels =
                stored.metadata.labels.toMutableMap().apply { put("evil", "true") }
        }
    }

    @Test
    fun `create fails closed when a conflict returns an arbitrary template label`() {
        assertConflictFails { stored ->
            stored.spec.template.metadata.labels =
                stored.spec.template.metadata.labels.toMutableMap().apply { put("evil", "true") }
        }
    }

    @Test
    fun `create fails closed when worker container becomes privileged`() {
        assertConflictFails { stored ->
            val security = stored.spec.template.spec.containers.single().securityContext
            security.privileged = true
        }
    }

    @Test
    fun `create fails closed when worker container gains an unmasked proc mount`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.single().securityContext.procMount = "Unmasked"
        }
    }

    @Test
    fun `create fails closed when worker container gains a run as user`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.single().securityContext.runAsUser = 0
        }
    }

    @Test
    fun `create fails closed when worker container gains args`() {
        assertConflictFails { stored ->
            val worker = stored.spec.template.spec.containers.single()
            worker.args = listOf("unexpected")
        }
    }

    @Test
    fun `create fails closed when worker container gains envFrom`() {
        assertConflictFails { stored ->
            val worker = stored.spec.template.spec.containers.single()
            worker.envFrom =
                listOf(
                    io.fabric8.kubernetes.api.model
                        .EnvFromSourceBuilder()
                        .withNewConfigMapRef()
                        .withName("unexpected")
                        .endConfigMapRef()
                        .build()
                )
        }
    }

    @Test
    fun `create fails closed when a conflict changes Job parallelism`() {
        assertConflictFails { stored -> stored.spec.parallelism = 2 }
    }

    @Test
    fun `create fails closed when a conflict changes Job completions`() {
        assertConflictFails { stored -> stored.spec.completions = 2 }
    }

    @Test
    fun `create fails closed when a conflict suspends the Job`() {
        assertConflictFails { stored -> stored.spec.suspend = true }
    }

    @Test
    fun `create fails closed when a conflict adds a Job pod failure policy`() {
        assertConflictFails { stored ->
            stored.spec.podFailurePolicy =
                io.fabric8.kubernetes.api.model.batch.v1.PodFailurePolicyBuilder().build()
        }
    }

    @Test
    fun `create fails closed when a conflict adds an ephemeral container`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.ephemeralContainers =
                listOf(
                    io.fabric8.kubernetes.api.model
                        .EphemeralContainerBuilder()
                        .withName("debug")
                        .withImage("registry.example/debug@sha256:" + "c".repeat(64))
                        .build()
                )
        }
    }

    @Test
    fun `create fails closed when a conflict adds node scheduling`() {
        assertConflictFails { stored -> stored.spec.template.spec.nodeName = "node-a" }
    }

    @Test
    fun `create fails closed when a conflict adds a node selector`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.nodeSelector =
                mapOf("node-role.kubernetes.io/worker" to "true")
        }
    }

    @Test
    fun `create fails closed when a conflict changes the scheduler`() {
        assertConflictFails { stored -> stored.spec.template.spec.schedulerName = "unexpected" }
    }

    @Test
    fun `create fails closed when a conflict adds affinity`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.affinity =
                io.fabric8.kubernetes.api.model.AffinityBuilder().build()
        }
    }

    @Test
    fun `create fails closed when a conflict adds a toleration`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.tolerations =
                listOf(io.fabric8.kubernetes.api.model.TolerationBuilder().withKey("taint").build())
        }
    }

    @Test
    fun `create fails closed when a conflict adds SELinux options`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.single().securityContext.seLinuxOptions =
                io.fabric8.kubernetes.api.model.SELinuxOptionsBuilder().withLevel("s0").build()
        }
    }

    @Test
    fun `create fails closed when a conflict adds an AppArmor profile`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.containers.single().securityContext.appArmorProfile =
                io.fabric8.kubernetes.api.model
                    .AppArmorProfileBuilder()
                    .withType("Unconfined")
                    .build()
        }
    }

    @Test
    fun `create fails closed when a conflict retains an unknown Job JSON field`() {
        assertConflictFails { stored -> stored.additionalProperties["unexpected"] = "value" }
    }

    @Test
    fun `create fails closed when a conflict changes the service account alias`() {
        assertConflictFails { stored ->
            stored.spec.template.spec.serviceAccount = "different-service-account"
        }
    }

    @Test
    fun `create fails closed when controller UID is not the stored Job UID`() {
        assertConflictFails { stored -> stored.metadata.uid = "different-controller" }
    }

    @Test
    fun `find and readiness use read-only Kubernetes HTTP operations`() {
        val responses = CopyOnWriteArrayList<String>()
        loopback { exchange, _ ->
                responses += exchange.requestMethod + " " + exchange.requestURI.path
                when {
                    exchange.requestURI.path.endsWith("/jobs") ->
                        respond(
                            exchange,
                            200,
                            "{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}",
                        )
                    else -> respond(exchange, 200, jobStatusJson("Succeeded", succeeded = 1))
                }
            }
            .use { client ->
                val gateway = gateway(client.client)
                assertTrue(gateway.ready())
                assertEquals(DeriveJobStatus.SUCCEEDED, gateway.find(request().identity))
            }
        assertTrue(responses.all { it.startsWith("GET ") })
    }

    @Test
    fun `find maps failed and incomplete statuses from Kubernetes responses`() {
        loopback { exchange, _ -> respond(exchange, 200, jobStatusJson("Failed", failed = 1)) }
            .use { client ->
                assertEquals(
                    DeriveJobStatus.FAILED,
                    gateway(client.client).find(request().identity),
                )
            }
        loopback { exchange, _ -> respond(exchange, 200, jobStatusJson(null)) }
            .use { client ->
                assertEquals(
                    DeriveJobStatus.RUNNING,
                    gateway(client.client).find(request().identity),
                )
            }
        loopback { exchange, _ -> respond(exchange, 200, jobStatusJson("Failed", failed = 0)) }
            .use { client ->
                assertEquals(
                    DeriveJobStatus.FAILED,
                    gateway(client.client).find(request().identity),
                )
            }
        loopback { exchange, _ ->
                respond(exchange, 200, jobStatusJson("Failed", conditionStatus = "False"))
            }
            .use { client ->
                assertEquals(
                    DeriveJobStatus.RUNNING,
                    gateway(client.client).find(request().identity),
                )
            }
    }

    @Test
    fun `coordinator rejects URL TTL shorter than thirty minutes`() {
        assertThrows<IllegalArgumentException> { coordinator(Duration.ofSeconds(1799)) }
    }

    @Test
    fun `coordinator accepts a thirty minute URL TTL`() {
        coordinator(Duration.ofSeconds(1800))
    }

    private fun gateway(client: io.fabric8.kubernetes.client.KubernetesClient = client()) =
        Fabric8DeriveJobGateway(client, "maps", image(), "worker", true)

    private fun image() = "registry.example/service-maps@sha256:" + "a".repeat(64)

    private fun request(
        mapId: String = "00000000-0000-0000-0000-000000000001",
        attempt: String = "00000000-0000-0000-0000-000000000002",
    ) =
        DeriveJobRequest(
            DeriveIdentity(UUID.fromString(mapId), 7, UUID.fromString(attempt), "a".repeat(64)),
            "{\"sourceUrl\":\"https://signed.example/?token=hidden\"}",
        )

    private fun assertConflictFails(mutate: (Job) -> Unit) {
        var created: String? = null
        loopback { exchange, received ->
                when (exchange.requestMethod) {
                    "POST" -> {
                        created = received
                        respond(
                            exchange,
                            409,
                            "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"reason\":\"AlreadyExists\",\"code\":409}",
                        )
                    }
                    "GET" -> {
                        val stored = kubernetesStoredJob(requireNotNull(created))
                        mutate(stored)
                        respond(exchange, 200, Serialization.asJson(stored))
                    }
                    else -> respond(exchange, 405, "")
                }
            }
            .use { client ->
                val failure =
                    assertThrows<IllegalArgumentException> {
                        gateway(client.client).create(request())
                    }
                assertFalse(failure.message.orEmpty().contains("signed.example"))
            }
    }

    private fun kubernetesStoredJob(json: String): Job =
        Serialization.unmarshal(json, Job::class.java).also { job ->
            job.metadata.namespace = "maps"
            job.metadata.uid = "generated-controller"
            job.spec.completions = 1
            job.spec.parallelism = 1
            job.spec.completionMode = "NonIndexed"
            job.spec.suspend = false
            job.spec.manualSelector = false
            job.spec.podReplacementPolicy = "TerminatingOrFailed"
            job.spec.selector =
                io.fabric8.kubernetes.api.model.LabelSelector().also {
                    it.matchLabels = mapOf("controller-uid" to "generated-controller")
                }
            job.spec.template.metadata.labels =
                job.spec.template.metadata.labels.toMutableMap().apply {
                    put("controller-uid", "generated-controller")
                    put("job-name", job.metadata.name)
                    put("batch.kubernetes.io/controller-uid", "generated-controller")
                    put("batch.kubernetes.io/job-name", job.metadata.name)
                }
            job.spec.template.spec.dnsPolicy = "ClusterFirst"
            job.spec.template.spec.schedulerName = "default-scheduler"
            job.spec.template.spec.terminationGracePeriodSeconds = 30
            job.spec.template.spec.enableServiceLinks = true
            job.spec.template.spec.serviceAccount = job.spec.template.spec.serviceAccountName
            job.spec.template.spec.containers.single().terminationMessagePath =
                "/dev/termination-log"
            job.spec.template.spec.containers.single().terminationMessagePolicy = "File"
        }

    private fun coordinator(urlTtl: Duration) =
        DeriveCoordinator(unsupportedVersions(), RecordingGateway(), null, null, true, urlTtl)

    private fun assertQuantity(
        values: Map<String, io.fabric8.kubernetes.api.model.Quantity>,
        name: String,
        amount: String,
        format: String?,
    ) {
        val quantity = values.getValue(name)
        assertEquals(amount, quantity.amount, "$name amount")
        assertEquals(format, quantity.format, "$name unit")
    }

    private fun client() =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(io.fabric8.kubernetes.client.KubernetesClient::class.java),
        ) { _, _, _ ->
            error("pure manifest test")
        } as io.fabric8.kubernetes.client.KubernetesClient

    private fun loopback(handler: (HttpExchange, String) -> Unit): AutoCloseableClient {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            handler(exchange, exchange.requestBody.readBytes().decodeToString())
            exchange.close()
        }
        server.start()
        val client =
            KubernetesClientBuilder()
                .withConfig(
                    io.fabric8.kubernetes.client
                        .ConfigBuilder()
                        .withMasterUrl("http://127.0.0.1:${server.address.port}")
                        .withTrustCerts(true)
                        .build()
                )
                .build()
        return AutoCloseableClient(client, server)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    private fun jobStatusJson(
        condition: String?,
        succeeded: Int = 0,
        failed: Int = 0,
        conditionStatus: String = "True",
    ) =
        """{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"derive"},"status":{"succeeded":$succeeded,"failed":$failed,"conditions":${if (condition == null) "[]" else "[{\"type\":\"$condition\",\"status\":\"$conditionStatus\"}]"}}}"""

    private class AutoCloseableClient(
        val client: io.fabric8.kubernetes.client.KubernetesClient,
        private val server: HttpServer,
    ) : AutoCloseable {
        override fun close() {
            client.close()
            server.stop(0)
        }
    }

    private class RecordingGateway : DeriveJobGateway {
        override fun create(request: DeriveJobRequest) = error("unused")

        override fun find(identity: DeriveIdentity): DeriveJobStatus? = error("unused")
    }
}

private fun unsupportedVersions(): gg.grounds.domain.MapVersionRepository =
    object : gg.grounds.domain.MapVersionRepository {
        override fun commit(
            mapId: UUID,
            sourceSha256: String?,
            sourceKey: String?,
            parentVersion: Int?,
            note: String?,
            bySub: String,
        ) = error("unused")

        override fun publish(
            mapId: UUID,
            version: Int,
            facts: gg.grounds.domain.BundleFacts,
            bySub: String,
        ) = error("unused")

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID) = error("unused")

        override fun acceptSuccess(
            identity: DeriveIdentity,
            facts: gg.grounds.domain.DerivedFacts,
            bySub: String,
        ) = error("unused")

        override fun acceptFailure(
            identity: DeriveIdentity,
            failure: gg.grounds.domain.DerivedFailure,
        ) = error("unused")

        override fun listReconcileCandidates() = error("unused")

        override fun retrySystemFailure(mapId: UUID, version: Int) = error("unused")

        override fun find(mapId: UUID, version: Int) = error("unused")

        override fun list(mapId: UUID) = error("unused")

        override fun latestPublished(mapId: UUID) = error("unused")
    }
