// Offline LOD encoding is isolated from the API and has no cloud credentials or listeners.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

repositories {
    mavenCentral()
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
    implementation("net.minestom:minestom:2026.10.25b-26.3-grounds")
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-25")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("gg.grounds.lod.worker.LodWorkerMainKt") }

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
