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
    }
}

rootProject.name = "iroh-kmp"

include(":iroh-runtime")
include(":iroh-runtime:api")
include(":iroh-bluetooth")
include(":sample:android")
