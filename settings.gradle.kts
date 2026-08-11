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

rootProject.name = "OpenTasker"
include(":app")
include(":baselineprofile")

// Upstream's core/* and feature/* module split is NOT taken by this fork (白い熊, 2026-08-11).
//
// The split is transitional: each library module points its source set back at
// app/src/main/java/com/opentasker/..., and :app is meant to drop those same files through
// `kotlin { sourceSets { configureEach { kotlin.exclude(...) } } }`. Under AGP 9's built-in Kotlin
// that exclusion is silently ignored — :app compiles every "excluded" file anyway — so each type
// exists twice and R8 fails the release build with "is defined multiple times". Upstream has not
// hit it because the split landed after 0.2.84 and only a minified release build shows it.
//
// Adopting it properly would mean widening this fork's `internal` engine, storage and model
// declarations to public so :app can still see them across a module boundary. Revisit when
// upstream finishes the split; until then the app module compiles its own sources, as before.
