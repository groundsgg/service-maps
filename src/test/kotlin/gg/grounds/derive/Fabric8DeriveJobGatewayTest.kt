package gg.grounds.derive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.domain.DeriveIdentity
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Fabric8DeriveJobGatewayTest {
    @Test
    fun `enabled configuration requires a worker image`() {
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "maps", "", "worker", true)
        }
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
        assertTrue(job.metadata.annotations.isNullOrEmpty())
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
                    "GET" -> respond(exchange, 200, requireNotNull(created))
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
                        val mismatched =
                            Serialization.unmarshal(requireNotNull(created), Job::class.java)
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
                        val incomplete =
                            Serialization.unmarshal(requireNotNull(created), Job::class.java)
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

    private fun jobStatusJson(condition: String?, succeeded: Int = 0, failed: Int = 0) =
        """{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"derive"},"status":{"succeeded":$succeeded,"failed":$failed,"conditions":${if (condition == null) "[]" else "[{\"type\":\"$condition\",\"status\":\"True\"}]"}}}"""

    private class AutoCloseableClient(
        val client: io.fabric8.kubernetes.client.KubernetesClient,
        private val server: HttpServer,
    ) : AutoCloseable {
        override fun close() {
            client.close()
            server.stop(0)
        }
    }
}
