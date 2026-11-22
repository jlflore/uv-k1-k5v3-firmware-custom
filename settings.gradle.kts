pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Miroir de Maven Central hébergé par Google : Maven Central limite (erreur 429) les
        // téléchargements depuis les serveurs de GitHub Actions (Robolectric, pour les tests).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "K1Remote"
include(":app")
