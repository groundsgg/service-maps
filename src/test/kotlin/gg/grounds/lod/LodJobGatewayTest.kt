package gg.grounds.lod

import com.sun.net.httpserver.HttpServer
import gg.grounds.domain.*
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class LodJobGatewayTest {
    private val claim =
        LodClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            MapTrust.FIRST_PARTY,
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "ghcr.io/test/image@sha256:${"e".repeat(64)}",
            Instant.parse("2026-11-08T12:00:00Z"),
        )

    @Test
    fun `job is isolated bounded pinned to an existing node and never retries`() {
        KubernetesClientBuilder()
            .withConfig(
                io.fabric8.kubernetes.client
                    .ConfigBuilder()
                    .withMasterUrl("http://127.0.0.1:1")
                    .build()
            )
            .build()
            .use { client ->
                val job =
                    Fabric8LodJobGateway(client, "maps", "worker", "existing-node").job(claim, "{}")
                assertEquals(true, job.spec.suspend)
                assertEquals(claim.jobName, job.metadata.name)
                assertEquals(0, job.spec.backoffLimit)
                assertEquals(900, job.spec.activeDeadlineSeconds)
                assertEquals(1, job.spec.parallelism)
                val pod = job.spec.template.spec
                assertFalse(pod.automountServiceAccountToken)
                assertEquals("Never", pod.restartPolicy)
                assertEquals(mapOf("kubernetes.io/hostname" to "existing-node"), pod.nodeSelector)
                assertEquals("worker", pod.serviceAccountName)
                val container = pod.containers.single()
                assertEquals(claim.workerImage, container.image)
                assertEquals("2", container.resources.limits.getValue("cpu").amount)
                assertEquals("2", container.resources.limits.getValue("memory").amount)
                assertEquals("Gi", container.resources.limits.getValue("memory").format)
                assertEquals("4", pod.volumes.single().emptyDir.sizeLimit.amount)
                assertEquals(listOf("LOD_REQUEST_JSON", "TMPDIR"), container.env.map { it.name })
                assertTrue(container.securityContext.readOnlyRootFilesystem)
                assertFalse(container.securityContext.allowPrivilegeEscalation)
                assertFailsWith<IllegalArgumentException> {
                    Fabric8LodJobGateway(client, "maps", "worker", "").job(claim, "{}")
                }
            }
    }

    @Test
    fun `deleting a job cannot release its slot while any associated pod is running`() {
        val running = AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val pods = exchange.requestURI.path.endsWith("/pods")
            val code = if (pods || exchange.requestMethod == "DELETE") 200 else 404
            val body =
                if (pods)
                    """{"apiVersion":"v1","kind":"PodList","items":${if(running.get()) """[{"metadata":{"name":"worker"},"status":{"phase":"Running"}}]""" else "[]"}}"""
                else if (code == 200) """{"apiVersion":"v1","kind":"Status","status":"Success"}"""
                else
                    """{"apiVersion":"v1","kind":"Status","status":"Failure","reason":"NotFound","code":404}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            KubernetesClientBuilder()
                .withConfig(
                    io.fabric8.kubernetes.client
                        .ConfigBuilder()
                        .withMasterUrl("http://127.0.0.1:${server.address.port}")
                        .build()
                )
                .build()
                .use { client ->
                    val gateway = Fabric8LodJobGateway(client, "maps", "worker", "existing-node")
                    assertFalse(gateway.stopAndConfirm(claim))
                    running.set(false)
                    assertTrue(gateway.stopAndConfirm(claim))
                }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `activation patch cannot target another job UID or revision`() {
        val patch =
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>()
        val existing =
            java.util.concurrent.atomic.AtomicReference<
                io.fabric8.kubernetes.api.model.batch.v1.Job
            >()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        server.createContext("/") { exchange ->
            val isPatch = exchange.requestMethod == "PATCH"
            if (isPatch) patch.set(json.readTree(exchange.requestBody.readAllBytes()))
            val body =
                if (isPatch)
                    """{"apiVersion":"v1","kind":"Status","status":"Failure","reason":"Conflict","code":409}"""
                else io.fabric8.kubernetes.client.utils.Serialization.asJson(existing.get())
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(
                if (isPatch) 409 else 200,
                body.toByteArray().size.toLong(),
            )
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            KubernetesClientBuilder()
                .withConfig(
                    io.fabric8.kubernetes.client
                        .ConfigBuilder()
                        .withMasterUrl("http://127.0.0.1:${server.address.port}")
                        .build()
                )
                .build()
                .use { client ->
                    val gateway = Fabric8LodJobGateway(client, "maps", "worker", "node")
                    existing.set(
                        gateway.job(claim, "{}").also {
                            it.metadata.uid = "original-uid"
                            it.metadata.resourceVersion = "8"
                        }
                    )
                    assertFailsWith<io.fabric8.kubernetes.client.KubernetesClientException> {
                        gateway.activate(claim)
                    }
                    assertEquals("test", patch.get()[0]["op"].asText())
                    assertEquals("/metadata/uid", patch.get()[0]["path"].asText())
                    assertEquals("original-uid", patch.get()[0]["value"].asText())
                    assertEquals("/metadata/resourceVersion", patch.get()[1]["path"].asText())
                    assertEquals("8", patch.get()[1]["value"].asText())
                    assertEquals("/spec/suspend", patch.get()[2]["path"].asText())
                    assertFalse(patch.get()[2]["value"].asBoolean())
                }
        } finally {
            server.stop(0)
        }
    }
}
