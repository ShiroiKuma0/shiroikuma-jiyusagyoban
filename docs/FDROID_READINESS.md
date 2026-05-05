# F-Droid Readiness

OpenTasker keeps F-Droid work in a property-based distribution profile so existing Gradle variant names remain stable.

## Local Verification

Use the Gradle gate for normal development and CI:

```bash
./gradlew -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata
```

Use the release harness before cutting or submitting a version:

```powershell
.\tools\verify-fdroid-release.ps1 -BuildRelease -RunFdroidLint -SkipTagCheck
```

After the release tag exists, run the same harness without `-SkipTagCheck` so `v<versionName>` must point at the full commit SHA recorded in `fdroid/metadata/com.opentasker.app.yml`.

To compare a signed upstream APK with the local unsigned release payload, build first and pass the upstream artifact:

```powershell
.\tools\verify-fdroid-release.ps1 -BuildRelease -UpstreamApk .\release\OpenTasker-v0.2.58.apk
```

The APK comparison hashes ZIP entry names and bytes while ignoring `META-INF/` signatures. This does not replace F-Droid's reproducible build review, but it gives maintainers a repeatable local check that signing is the only expected APK difference.

## F-Droid Profile Contract

The `fdroid` profile currently:

- sets `BuildConfig.DISTRIBUTION` to `fdroid`
- keeps the package name unchanged as `com.opentasker.app`
- uses the same fully open dependency graph as the standard build
- pins Android build tools to `35.0.0`
- runs `verifyFdroidReadiness` to block common proprietary dependency families such as Google Play Services, Firebase, Play Billing, Crashlytics, Adjust, and Facebook SDKs
- runs `verifyFdroidMetadata` to keep draft metadata version fields, commit pinning, Gradle properties, preassemble hooks, changelog URL, and unsigned APK output synchronized with the app build

Release signing remains environment-driven. Without `OPEN_TASKER_RELEASE_KEYSTORE` and related variables, Gradle produces unsigned release artifacts suitable for F-Droid-style source builds.

## Metadata Draft

The draft metadata lives at `fdroid/metadata/com.opentasker.app.yml`. Before submitting to fdroiddata:

- confirm `versionName`, `versionCode`, `CurrentVersion`, and `CurrentVersionCode` match `app/build.gradle.kts`
- set `commit` to the immutable release source commit or tag target
- tag the same source commit as `v<versionName>` and push the tag
- confirm `gradleprops: openTaskerDistribution=fdroid`
- keep `preassemble: ":app:verifyFdroidReadiness"`
- keep `output: app/build/outputs/apk/release/app-release-unsigned.apk`
- run `fdroid lint` and a local `fdroid build --no-tarball com.opentasker.app:<versionCode>` from the `fdroid` directory

The repository also includes `fdroid/config/categories.yml` so standalone local `fdroid lint` can validate the draft categories without a full fdroiddata checkout.

## Local fdroidserver Evidence

The current metadata for v0.2.58 was validated on 2026-05-05 with:

```bash
cd fdroid
fdroid lint com.opentasker.app
```

and in WSL with fdroidserver 2.4.4, Java 17, and Android SDK 35:

```bash
cd /mnt/c/Users/--/repos/OpenTasker/fdroid
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
ANDROID_SDK_ROOT=/opt/android-sdk \
../build/fdroid-venv-linux/bin/fdroid build --test --no-tarball com.opentasker.app:60
```

That run successfully built `com.opentasker.app` version `0.2.58` (`versionCode 60`) from commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`.

Notes from this workstation:

- Ubuntu 22.04's packaged fdroidserver 2.1 can lint the metadata but does not include a Gradle 8.9 helper hash.
- The local WSL build used fdroidserver 2.4.4 in an ignored venv and Android SDK packages installed under `/opt/android-sdk`.
- fdroidserver returns process exit code 0 for some failed local build attempts, so `tools/verify-fdroid-release.ps1 -RunFdroidBuild` treats `Could not build app`, `Build for app ... failed`, and `N build failed` output as hard failures.

## Policy Notes

F-Droid review checks license, source availability, build-system compatibility, prebuilt binaries, non-free dependencies/resources, tracking, ads, and AntiFeatures. Reproducible builds are not mandatory, but F-Droid documents them as best practice for new apps because they preserve upstream signatures and strengthen supply-chain trust.

Useful F-Droid docs:

- Inclusion How-To: https://f-droid.org/en/docs/Inclusion_How-To/
- Reproducible Builds: https://f-droid.org/en/docs/Reproducible_Builds/
- Build Metadata Reference: https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/
