import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete

buildscript {
    configurations.named("classpath") {
        // Grounds root 0.1.1 uses Kotlin 2.3.0. Quarkus also brings a daemon client through
        // its BOM; keep that client compatible with the compiler runner supplied by Grounds.
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-daemon-client:2.3.0")
    }
}

plugins {
    id("gg.grounds.root") version "0.1.1"
    alias(libs.plugins.quarkus)
}

val zstdVersion = "1.5.7-25"

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
    compileOnly("org.graalvm.sdk:nativeimage")
    implementation(enforcedPlatform(libs.quarkus.bom))
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
    implementation("com.github.luben:zstd-jni:$zstdVersion")
    implementation("gg.grounds:scene-format:0.4.4")
    // 2.23.0 supplies Scene queue actions in revision 3 while retaining revisions 1 and 2.
    // Ship this dependency in the API and derive-worker images together.
    implementation("gg.grounds:plugin-lobby-scene-catalog:2.23.0")
    implementation("gg.grounds:resourcepacks-client:1.0.0")
    implementation("tools.jackson.core:jackson-databind:3.2.4")
    implementation("tools.jackson.module:jackson-module-kotlin:3.2.4")
    // Keycloak bearer tokens, not projected ServiceAccount tokens: the build
    // server lives on the grounds-dev spoke while this runs on core, and a k8s
    // SA token from one cluster means nothing to the other cluster's JWKS.
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
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
    implementation(platform("software.amazon.awssdk:bom:2.57.7"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation("io.nats:jnats:2.26.4")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-security")
    testImplementation("io.rest-assured:rest-assured")
    // Version comes from the Quarkus BOM. Pinning one here is how you end up asking
    // Maven Central for a testcontainers release that does not exist while the BOM
    // quietly resolves a different one.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

configurations.configureEach {
    // Keep API and lit worker on the same Zstd patch despite the Quarkus BOM's older pin.
    resolutionStrategy.force("com.github.luben:zstd-jni:$zstdVersion")
    // Jackson 3.2 requires the 2.22 annotations API, including JsonApplyView.
    resolutionStrategy.force("com.fasterxml.jackson.core:jackson-annotations:2.22")
}
