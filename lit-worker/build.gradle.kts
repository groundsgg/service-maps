// The lit worker: lights one published map bundle and uploads the lit copy (see
// gg.grounds.lit.LitReconciler for the service side). A plain JVM program rather than part of the
// service, because it needs Minestom — the same lighting code and Minestom build the game servers
// load maps with (grounds-minestom-runtime's runtime-world), so the light is byte-identical — and
// the service's Quarkus platform pins several of Minestom's libraries to older versions.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
    // Minestom for Minecraft 26.3 is our fork, the build the game servers run.
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/minestom")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
        content { includeGroup("net.minestom") }
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation("gg.grounds:grounds-minestom-runtime-runtime-world:0.13.0")
    implementation("net.minestom:minestom:2026.10.10-26.3-grounds")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("gg.grounds.lit.worker.LitWorkerMainKt") }

tasks.test { useJUnitPlatform() }
