plugins { id("org.jetbrains.kotlin.jvm") }

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
    api(project(":lod-format"))
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.github.luben:zstd-jni:1.5.7-25")
    compileOnly("net.minestom:minestom:2026.10.25b-26.3-grounds")
    testImplementation("net.minestom:minestom:2026.10.25b-26.3-grounds")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
