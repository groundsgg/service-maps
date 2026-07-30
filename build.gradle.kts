plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.30.6"
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
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.37.4"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    // HTTP + JSON rather than gRPC, unlike the gameplay services: the callers are
    // the portal's BFF proxy, the build server's Paper plugin and staff tooling.
    // Game servers never call this service at all — they read static objects off
    // the CDN — so there is no in-cluster RPC client to serve.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    // Keycloak bearer tokens, not projected ServiceAccount tokens: the build
    // server lives on the grounds-dev spoke while this runs on core, and a k8s
    // SA token from one cluster means nothing to the other cluster's JWKS.
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-opentelemetry")
    // Real probes rather than a TCP check: the readiness probe then also fails when the
    // database is unreachable, which is the failure a tcpSocket probe reports as healthy.
    implementation("io.quarkus:quarkus-smallrye-health")
    // Plain AWS SDK v2 against R2, the same way grounds-lod's generator talks to it.
    // UrlConnectionHttpClient rather than the Netty async client: every call here is a
    // presign or a small metadata write, so an event loop buys nothing.
    implementation(platform("software.amazon.awssdk:bom:2.31.6"))
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
