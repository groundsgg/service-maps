import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete

plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.30.6"
}

val cleanProductionOpenApi =
    tasks.register<Delete>("cleanProductionOpenApi") {
        delete(layout.buildDirectory.dir("generated/openapi"))
        delete(layout.buildDirectory.dir("quarkus"))
        delete(layout.buildDirectory.dir("quarkus-app"))
        delete(layout.buildDirectory.dir("quarkus-build"))
    }

// The snapshot must come from a clean production build: a dev-mode schema carries endpoints the
// deployed service does not have, and the published reference would describe a service nobody runs.
val quarkusBuildTask = tasks.named("quarkusBuild") { mustRunAfter(cleanProductionOpenApi) }

tasks.register<Copy>("generateOpenApiSnapshot") {
    group = "documentation"
    dependsOn(cleanProductionOpenApi, quarkusBuildTask)
    from(layout.buildDirectory.file("generated/openapi/openapi.json"))
    into(layout.buildDirectory.dir("api-reference"))
    rename { "openapi.json" }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.30.8"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    // HTTP + JSON rather than gRPC, unlike the gameplay services: the callers are
    // the portal's BFF proxy, the build server's Paper plugin and staff tooling.
    // Game servers never call this service at all — they read static objects off
    // the CDN — so there is no in-cluster RPC client to serve.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    // The derive worker constructs its strict mapper outside Quarkus, so it must carry Kotlin
    // constructor metadata rather than relying on the application-managed ObjectMapper.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    implementation("gg.grounds:scene-format:0.1.0")
    implementation("gg.grounds:resourcepacks-client:0.5.1")
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.5")
    // Keycloak bearer tokens, not projected ServiceAccount tokens: the build
    // server lives on the grounds-dev spoke while this runs on core, and a k8s
    // SA token from one cluster means nothing to the other cluster's JWKS.
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.quarkus:quarkus-micrometer")
    // Real probes rather than a TCP check: the readiness probe then also fails when the
    // database is unreachable, which is the failure a tcpSocket probe reports as healthy.
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-scheduler")
    // Produces the application-scoped KubernetesClient used by the Job gateway. Depending on the
    // raw Fabric8 client alone leaves CDI with no producer in the packaged Quarkus application.
    implementation("io.quarkus:quarkus-kubernetes-client")
    // Plain AWS SDK v2 against R2, the same way grounds-lod's generator talks to it.
    // UrlConnectionHttpClient rather than the Netty async client: every call here is a
    // presign or a small metadata write, so an event loop buys nothing.
    implementation(platform("software.amazon.awssdk:bom:2.54.3"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-security")
    testImplementation("io.rest-assured:rest-assured")
    // Version comes from the Quarkus BOM. Pinning one here is how you end up asking
    // Maven Central for a testcontainers release that does not exist while the BOM
    // quietly resolves a different one.
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

configurations.configureEach {
    // scene-format 0.1.0 uses Jackson 3.1, whose compatibility annotations require 2.21.
    resolutionStrategy.force("com.fasterxml.jackson.core:jackson-annotations:2.21")
}
