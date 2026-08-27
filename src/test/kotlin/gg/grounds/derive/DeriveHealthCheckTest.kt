package gg.grounds.derive

import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeriveHealthCheckTest {
    @Test
    fun `disabled health does not probe the gateway`() {
        var probes = 0
        val check =
            DeriveHealthCheck(
                false,
                "",
                object : DeriveJobGateway {
                    override fun create(request: DeriveJobRequest) = error("unused")

                    override fun find(identity: gg.grounds.domain.DeriveIdentity) = null

                    override fun ready(): Boolean {
                        probes++
                        return false
                    }
                },
            )
        assertEquals("UP", check.call().status.name)
        assertEquals(0, probes)
    }

    @Test
    fun `enabled health reports unavailable Job API`() {
        val check = DeriveHealthCheck(true, "registry/service-maps@sha256:abc", gateway(false))
        assertEquals("DOWN", check.call().status.name)
    }

    @Test
    fun `enabled health reports a missing worker image as unconfigured`() {
        val check = DeriveHealthCheck(true, Optional.empty(), gateway(true))
        val response = check.call()
        assertEquals("DOWN", response.status.name)
        assertEquals("image is not configured", response.data.orElseThrow()["reason"])
    }

    @Test
    fun `enabled health reports available Job API without creating a Job`() {
        val check = DeriveHealthCheck(true, "registry/service-maps@sha256:abc", gateway(true))
        assertEquals("UP", check.call().status.name)
    }

    private fun gateway(ready: Boolean) =
        object : DeriveJobGateway {
            override fun create(request: DeriveJobRequest) = error("health must not create Jobs")

            override fun find(identity: gg.grounds.domain.DeriveIdentity) =
                error("health must not find Jobs")

            override fun ready() = ready
        }
}
