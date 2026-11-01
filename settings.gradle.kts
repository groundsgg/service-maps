rootProject.name = "service-maps"

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").get()
                password = providers.gradleProperty("github.token").get()
            }
        }
        gradlePluginPortal()
    }
}

// The lit worker (see lit-worker/build.gradle.kts): built into the same image, run as a Job.
include("lit-worker")
