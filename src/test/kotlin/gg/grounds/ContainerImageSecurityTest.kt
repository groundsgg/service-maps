package gg.grounds

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerImageSecurityTest {
    @Test
    fun `runtime image uses the numeric distroless nonroot identity`() {
        val dockerfile = Files.readString(Path.of("Dockerfile"))

        assertTrue(dockerfile.lineSequence().any { it == "USER 65532:65532" })
    }
}
