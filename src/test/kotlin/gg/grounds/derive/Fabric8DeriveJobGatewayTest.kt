package gg.grounds.derive

import gg.grounds.domain.DeriveIdentity
import java.lang.reflect.Proxy
import java.util.UUID
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
            Fabric8DeriveJobGateway(client(), "", "registry/maps:sha", "worker", true)
        }
        assertThrows<IllegalArgumentException> {
            Fabric8DeriveJobGateway(client(), "maps", "registry/maps:sha", "", true)
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
        assertEquals("registry/maps:sha", c.image)
        assertEquals("IfNotPresent", c.imagePullPolicy)
        assertTrue(c.command.contains("gg.grounds.derive.DeriveWorkerMain"))
        assertEquals("DERIVE_REQUEST_JSON", c.env.single().name)
        assertEquals(setOf("DERIVE_REQUEST_JSON"), c.env.map { it.name }.toSet())
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

    private fun gateway(): Fabric8DeriveJobGateway {
        return Fabric8DeriveJobGateway(client(), "maps", "registry/maps:sha", "worker", true)
    }

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
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(io.fabric8.kubernetes.client.KubernetesClient::class.java),
        ) { _, _, _ ->
            error("pure manifest test")
        } as io.fabric8.kubernetes.client.KubernetesClient
}
