# Dependency Modernization

Last updated: 2026-05-05

OpenTasker centralizes Gradle plugin and library versions in `gradle/libs.versions.toml`. The v0.2.23 baseline intentionally does not upgrade dependency versions; it creates one controlled surface for staged, reversible modernization.

## Current Version Matrix

| Area | Version |
|---|---:|
| Gradle wrapper | 9.4.1 |
| Android Gradle Plugin | 9.2.1 |
| Compile SDK / Build Tools | 36 / 36.0.0 |
| Target SDK | 35 |
| Kotlin / Compose compiler plugin | 2.3.21 |
| KSP | 2.3.7 |
| Compose BOM | 2026.04.01 |
| AndroidX Core KTX | 1.18.0 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.10.0 |
| Navigation Compose | 2.9.8 |
| Room | 2.8.4 |
| WorkManager | 2.11.2 |
| DataStore | 1.2.1 |
| Coroutines | 1.10.2 |
| Hilt / Dagger | 2.59.2 |
| AndroidX Hilt Navigation Compose | 1.3.0 |
| Kotlinx Serialization JSON | 1.11.0 |
| Gson | 2.14.0 |
| AndroidX Test Runner | 1.7.0 |
| AndroidX Test JUnit | 1.3.0 |
| JUnit | 4.13.2 |

## Upgrade Order

1. Hilt/Dagger through an intermediate compatible version while the runtime still starts through `OpenTaskerApp_NoHilt`. Completed first with `2.52`, then with `2.59.2` after the AGP 9 batch satisfied the newer Hilt plugin requirement.
2. Room with exported-schema review and migration-test execution. Completed on the existing `androidx.room` 2.x line with `2.8.4` after Kotlin/KSP alignment; Room 3.0 uses a new artifact group that remains a separate migration decision.
3. WorkManager with service/scheduler smoke testing. Completed with `2.11.2`; no active WorkManager workers are currently registered in source, so verification focuses on build/lint/release profile stability.
4. Compose BOM, Activity Compose, Lifecycle, Navigation Compose, and Hilt Navigation Compose together with UI smoke testing. Completed the API 36-unblocked stable set with Compose BOM `2026.04.01`, Activity Compose `1.13.0`, Lifecycle `2.10.0`, Navigation Compose `2.9.8`, and Hilt Navigation Compose `1.3.0` after the AGP `8.13.2` batch resolved the earlier Lifecycle lint incompatibility.
5. AndroidX Core KTX, Coroutines, DataStore, Kotlinx Serialization, and Gson in small runtime-focused batches. Completed the Kotlin/API-compatible stable subset with Core KTX `1.18.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.11.0`, and Gson `2.14.0`.
6. Kotlin, KSP, and the Compose compiler plugin as one aligned compiler batch. Completed first with Kotlin/Compose plugin `2.2.21`, KSP `2.2.21-2.0.5`, and Gradle `compilerOptions` DSL, then advanced to Kotlin/Compose plugin `2.3.21` and KSP `2.3.7` once AGP 9 allowed Hilt `2.59.2`.
7. Android Gradle Plugin only after debug, release, lint, and F-Droid profile builds are stable under the previous batches. Completed first with Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, then advanced to Gradle wrapper `9.4.1` and AGP `9.2.1` while keeping compile SDK `36`, Build Tools `36.0.0`, and target SDK `35`. The AGP 9 batch keeps `android.builtInKotlin=false` and `android.newDsl=false` as temporary compatibility flags so the explicit Kotlin plugin setup can remain intact.
8. API 36-unblocked AndroidX dependency follow-ups. Completed with the latest stable Core KTX, Activity, Navigation, Lifecycle, Compose BOM, and Hilt Navigation Compose lines available from official Maven metadata on 2026-05-05.
9. Built-in Kotlin and AGP new DSL migration. Remaining before AGP 10: remove the explicit `org.jetbrains.kotlin.android` compatibility path, drop `android.builtInKotlin=false` and `android.newDsl=false`, and move any legacy variant API users to Android Components APIs. Room 3 remains a separate artifact-group migration decision.

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
.\gradlew.bat -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata
git diff --check
```

When a device or emulator is available, add startup/navigation smoke coverage before committing UI, compiler, Room, WorkManager, or AGP upgrade batches.

## Batch Log

- 2026-05-05: Upgraded Hilt/Dagger from `2.46` to `2.52` as the first intermediate generated-code batch. Verified `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest`, then reran `:app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug` serially after a parallel Gradle run raced on merged-manifest outputs, and verified `-PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata`.
- 2026-05-05: Upgraded Room from `2.6.1` to `2.7.2` on the existing `androidx.room` artifact line. A trial upgrade to `2.8.4` failed during KSP schema export with a `kotlinx.serialization` generated-serializer ABI mismatch before Kotlin/KSP alignment, so `2.8.4` was completed in the compiler batch below. Room 3.0 stays out of this batch because it uses the new `androidx.room3` group. Verified debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile, and `:app:connectedDebugAndroidTest` on `SM-S938B - 16` with the two migration tests passing.
- 2026-05-05: Upgraded WorkManager from `2.9.1` to `2.11.2`. No active WorkManager workers are registered in source, so this batch is limited to dependency compatibility and release/build verification. Verified debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and F-Droid release profile.
- 2026-05-05: Upgraded the stable Compose/AndroidX UI set within API 35 / AGP 8.7 constraints: Compose BOM `2024.10.01` to `2025.07.00` and Activity Compose `1.9.3` to `1.10.1`; Lifecycle remains `2.8.7`, Navigation Compose remains `2.8.4`, and Hilt Navigation Compose remains `1.2.0`. Trials with Activity `1.11.0`/`1.12.4`/`1.13.0` and Navigation `2.8.9`/`2.9.8` failed AAR metadata checks because newer transitive dependencies require compile SDK 36 and AGP 8.9.1. Compose BOM `2025.08.01`, Compose BOM `2026.04.01`, Hilt Navigation Compose `1.3.0`, Lifecycle `2.9.4`, and Lifecycle `2.10.0` resolve Lifecycle `2.9.x+`; those variants compiled far enough to expose a lint crash in `NonNullableMutableLiveDataDetector` with the current AGP/Kotlin analysis API. Verified debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile, and an install/start smoke on `SM-S938B - 16` with `AutomationService` foreground.
- 2026-05-05: Upgraded the compatible runtime-support subset: Core KTX `1.15.0` to `1.16.0`, DataStore `1.1.1` to `1.2.1`, and Gson `2.11.0` to `2.14.0`. Core KTX `1.17.0`/`1.18.0` failed AAR metadata because they require compile SDK 36 and AGP 8.9.1. Coroutines `1.10.2` and Kotlinx Serialization JSON `1.11.0` failed against Kotlin `2.0.21` because their published artifacts use newer Kotlin metadata; Coroutines was later completed at `1.10.2`, and Serialization was completed at `1.9.0`, in the Kotlin/KSP/compiler batch. Verified debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and F-Droid release profile.
- 2026-05-05: Upgraded the compiler-aligned dependency set to Kotlin/Compose plugin `2.2.21`, KSP `2.2.21-2.0.5`, Room `2.8.4`, Coroutines `1.10.2`, and Kotlinx Serialization JSON `1.9.0`; migrated the Android module from deprecated `kotlinOptions` to `compilerOptions`. Kotlin `2.3.21` plus KSP `2.3.7` was deferred because Hilt `2.52` cannot load the new KSP task class, and Hilt `2.59.2` requires AGP 9.0+. Verified debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and F-Droid release profile. Release minification passed but emitted R8 Kotlin metadata warnings under AGP `8.7.2`, so the AGP/API 36 batch is next.
- 2026-05-05: Upgraded the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, keeping target SDK `35`. AGP `8.13.2` was chosen over AGP 9.x to satisfy Kotlin `2.2` R8 support and API 36 requirements without adopting AGP 9's new DSL/built-in Kotlin behavior in the same batch. Verified AAR metadata, debug Kotlin/unit tests, debug androidTest compile, debug APK, lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B - 16` with `AutomationService` foreground. The previous AGP `8.7.2` release R8 Kotlin metadata warnings are gone.
- 2026-05-05: Upgraded the API 36-unblocked AndroidX stable set: Core KTX `1.16.0` to `1.18.0`, Compose BOM `2025.07.00` to `2026.04.01`, Activity Compose `1.10.1` to `1.13.0`, Lifecycle `2.8.7` to `2.10.0`, Navigation Compose `2.8.4` to `2.9.8`, and Hilt Navigation Compose `1.2.0` to `1.3.0`. Verified AAR metadata, debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B - 16` with `AutomationService` foreground.
- 2026-05-05: Upgraded the AGP 9 compatibility stack to Gradle wrapper `9.4.1`, AGP `9.2.1`, Hilt/Dagger `2.59.2`, Kotlin/Compose plugin `2.3.21`, KSP `2.3.7`, and Kotlinx Serialization JSON `1.11.0`. Added temporary AGP 9 compatibility flags `android.builtInKotlin=false` and `android.newDsl=false` because the explicit Kotlin plugin is not compatible with AGP 9's new DSL; these flags emit deprecation warnings and must be removed before AGP 10 by migrating to built-in Kotlin and Android Components/new DSL APIs. Verified AAR metadata, debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B - 16` with `AutomationService` foreground.
