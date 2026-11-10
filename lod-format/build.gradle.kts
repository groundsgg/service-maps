plugins { id("org.jetbrains.kotlin.jvm") }

repositories { mavenCentral() }

kotlin { jvmToolchain(25) }

dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.github.luben:zstd-jni:1.5.7-25")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
