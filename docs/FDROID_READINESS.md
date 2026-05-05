# F-Droid Readiness

OpenTasker keeps F-Droid work in a property-based distribution profile so existing Gradle variant names remain stable.

## Local Verification

```bash
./gradlew -PopenTaskerDistribution=fdroid :app:clean :app:assembleRelease :app:verifyFdroidReadiness
```

The `fdroid` profile currently:

- sets `BuildConfig.DISTRIBUTION` to `fdroid`
- keeps the package name unchanged as `com.opentasker.app`
- uses the same fully open dependency graph as the standard build
- pins Android build tools to `35.0.0`
- runs `verifyFdroidReadiness` to block common proprietary dependency families such as Google Play Services, Firebase, Play Billing, Crashlytics, Adjust, and Facebook SDKs

Release signing remains environment-driven. Without `OPEN_TASKER_RELEASE_KEYSTORE` and related variables, Gradle produces unsigned release artifacts suitable for F-Droid-style source builds.

## Metadata Draft

The draft metadata lives at `fdroid/metadata/com.opentasker.app.yml`. Before submitting to fdroiddata:

- replace `TODO_FULL_COMMIT_HASH_FOR_RELEASE` with the immutable release commit
- verify `versionName` and `versionCode` match `app/build.gradle.kts`
- confirm `gradleprops: openTaskerDistribution=fdroid`
- run `fdroid lint` and a local `fdroid build` from an fdroiddata checkout

## Policy Notes

F-Droid review checks license, source availability, build-system compatibility, prebuilt binaries, non-free dependencies/resources, tracking, ads, and AntiFeatures. Reproducible builds are not mandatory, but F-Droid documents them as best practice for new apps because they preserve upstream signatures and strengthen supply-chain trust.

Useful F-Droid docs:

- Inclusion How-To: https://f-droid.org/en/docs/Inclusion_How-To/
- Reproducible Builds: https://f-droid.org/en/docs/Reproducible_Builds/
- Build Metadata Reference: https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/
