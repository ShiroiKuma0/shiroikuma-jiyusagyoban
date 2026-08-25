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
include(":core:common")

// Upstream's core/* module split, adopted in stages (白い熊, 2026-08-25).
//
// It was rejected in 4aceaabd because the split was TRANSITIONAL: each library module pointed its
// source set back at app/src/main/java/com/opentasker/..., :app was meant to drop the same files
// through `kotlin { sourceSets { configureEach { kotlin.exclude(...) } } }`, and under AGP 9's
// built-in Kotlin that exclusion is silently ignored — so every type existed twice and R8 failed the
// release build with "is defined multiple times".
//
// Upstream finished the split in 0.2.88: the modules own their sources and app/ no longer holds a
// copy, so there is nothing left to duplicate. Adopting it removes the per-sync cost of moving
// upstream's files back into :app, which had to be paid by hand in the middle of conflict
// resolution and grew with every release.
//
// The cost it does carry is visibility: `internal` stops at a module boundary, so a declaration
// :app still reaches has to be public. Modules are added one at a time, each with its own signed
// build, rather than in one step.
//
// feature:automation is NOT taken: its only file, AutomationBlueprintInputField, is upstream's
// blueprint input presentation, which this fork does not have.
