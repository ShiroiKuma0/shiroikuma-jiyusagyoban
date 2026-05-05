# Dependency Modernization

Last updated: 2026-05-05

OpenTasker centralizes Gradle plugin and library versions in `gradle/libs.versions.toml`. The v0.2.23 baseline intentionally does not upgrade dependency versions; it creates one controlled surface for staged, reversible modernization.

## Current Version Matrix

| Area | Version |
|---|---:|
| Android Gradle Plugin | 8.7.2 |
| Kotlin / Compose compiler plugin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Compose BOM | 2024.10.01 |
| AndroidX Core KTX | 1.15.0 |
| Activity Compose | 1.9.3 |
| Lifecycle | 2.8.7 |
| Navigation Compose | 2.8.4 |
| Room | 2.6.1 |
| WorkManager | 2.9.1 |
| DataStore | 1.1.1 |
| Coroutines | 1.9.0 |
| Hilt / Dagger | 2.52 |
| AndroidX Hilt Navigation Compose | 1.2.0 |
| Kotlinx Serialization JSON | 1.7.3 |
| Gson | 2.11.0 |
| AndroidX Test Runner | 1.7.0 |
| AndroidX Test JUnit | 1.3.0 |
| JUnit | 4.13.2 |

## Upgrade Order

1. Hilt/Dagger through an intermediate compatible version while the runtime still starts through `OpenTaskerApp_NoHilt`. Completed with `2.52` after Maven Central showed the current line extends through `2.59.2`.
2. Room with exported-schema review and migration-test execution.
3. WorkManager with service/scheduler smoke testing.
4. Compose BOM, Activity Compose, Lifecycle, and Navigation Compose together with UI smoke testing.
5. Coroutines, DataStore, Kotlinx Serialization, and Gson in small runtime-focused batches.
6. Kotlin, KSP, and the Compose compiler plugin as one aligned compiler batch.
7. Android Gradle Plugin only after debug, release, lint, and F-Droid profile builds are stable under the previous batches.

## Risk Rules

- Do not mix dependency version bumps with feature work.
- Keep each upgrade batch reversible and scoped to related libraries.
- Keep Kotlin, KSP, and Compose compiler plugin versions aligned.
- Treat Hilt as an architecture change until startup is migrated or unused Hilt surface is removed.
- Treat Room as data-risk work; every Room bump needs schema and migration coverage.
- Treat WorkManager and AGP as release-risk work; every bump needs release and F-Droid profile builds.
- Keep the `openTaskerDistribution=fdroid` profile green after dependency changes.

## Verification Gate

Run this gate for each dependency batch:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug
.\gradlew.bat -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness
git diff --check
```

When a device or emulator is available, add startup/navigation smoke coverage before committing UI, compiler, Room, WorkManager, or AGP upgrade batches.

## Batch Log

- 2026-05-05: Upgraded Hilt/Dagger from `2.46` to `2.52` as the first intermediate generated-code batch. Verified `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest`, then reran `:app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug` serially after a parallel Gradle run raced on merged-manifest outputs, and verified `-PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata`.
