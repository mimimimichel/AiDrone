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
        mavenCentral()
        // Seulement pour la bibliothèque USB-série (Roomba)
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}

rootProject.name = "BebopIA"
include(":app")
