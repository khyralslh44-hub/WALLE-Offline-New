pluginManagement {
    repositories {
        google()
        // Google-hosted mirror is used before Maven Central to avoid intermittent HTTP 403s
        // from the canonical host on clean GitHub-hosted runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Keep builds reproducible when repo.maven.apache.org is temporarily blocked.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
    }
}
rootProject.name = "EVE-Robot"
include(":app")
