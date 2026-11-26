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

rootProject.name = "RuleDeck"
include(":app")
include(":baselineprofile")
include(":core:model")
include(":core:common")
include(":core:storage")
include(":core:engine")
include(":feature:automation")
