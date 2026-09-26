# Contributing to OpenTasker

Thanks for taking a look. This file covers what you need to get a build running, where things live, and how to avoid duplicating someone else's work.

## Before you start

Open an issue, or comment on an existing one, before writing anything substantial. Small fixes can go straight to a pull request. Anything that adds a feature, a dependency, or a new action is worth a short conversation first, because some of it has already been decided against and the reasoning isn't always obvious from the code.

## Getting a build

You need JDK 17 or 21 and the Android SDK. Use the checked-in Gradle wrapper rather than a system Gradle install. The wrapper pins the distribution and its checksum on purpose.

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running the tests

The JVM suite is what you'll run most:

```bash
./gradlew :app:testDebugUnitTest
```

Lint is part of the build contract, not advisory. `abortOnError` is on, so a new lint error fails the build:

```bash
./gradlew :app:lintDebug
```

Instrumented tests need a device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Without one, still compile them. Nothing else in the everyday lane touches `src/androidTest`, so a break there otherwise goes unnoticed:

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

If instrumented tests fail with `NoSuchMethodError` on a method name ending in `$app()`, the app APK on the device is older than the test APK. Uninstall both packages and run again:

```bash
adb uninstall com.opentasker.app
adb uninstall com.opentasker.app.test
```

UI changes need their Compose screenshot references regenerated, or `validateDebugScreenshotTest` will fail on a diff you meant to make:

```bash
./gradlew :app:updateDebugScreenshotTest
./gradlew :app:validateDebugScreenshotTest
```

The JVM suite above only covers `:app`. The `core/*` modules carry their own tests and nothing in the everyday lane runs them, so run those too:

```bash
./gradlew :core:storage:testDebugUnitTest :core:engine:testDebugUnitTest :core:common:testDebugUnitTest
```

There's also an aggregate gate, `./gradlew localQualityGate`, which runs lint, coverage floors, dependency policy, schema checks and the connected tests together. **You cannot run it on a clone.** It reaches `packageRelease` through `verifyReleaseAssetName` and `verifyPackagedTypeCompleteness`, and release packaging fails closed unless the four `OPEN_TASKER_RELEASE_*` signing variables are set, which only the maintainer has. It also pulls in `connectedDebugAndroidTest`, which needs a device. A pull request needs neither. The JVM suites, `lintDebug`, `compileDebugAndroidTestKotlin` and `assembleDebug` are the lane to run.

Note that this repository does not use GitHub Actions. Builds, tests and releases all happen locally, so nothing will run automatically against your branch. Please say which commands you ran in the pull request description.

## Where things live

Most of the code is in `:app`, but the `core/*` modules now own their own sources rather than pointing back into it. Storage in particular moved out entirely, so look for a file by package under the module that owns it.

| Path | What's in it |
| --- | --- |
| `app/src/main/java/com/opentasker/core/engine/` | Task execution, profile matching, the variable store, the foreground service |
| `app/src/main/java/com/opentasker/core/actions/` | The action catalog and every built-in action implementation |
| `app/src/main/java/com/opentasker/core/contexts/` | Trigger sources: time, location, app, network, NFC, notifications, calendar |
| `app/src/main/java/com/opentasker/core/transfer/` | Tasker XML import, OpenTasker bundle import and export |
| `app/src/main/java/com/opentasker/ui/screens/` | All Compose UI |
| `app/src/main/res/values/strings.xml` | User-visible copy. Everything on a screen resolves through here |
| `core/storage/src/main/kotlin/` | Room entities, DAOs, migrations, the encrypted database setup, backup and restore |
| `core/model/src/main/kotlin/` | The profile, task, action and context data model |
| `core/engine/src/main/kotlin/` | Engine pieces already split out of `:app` |
| `core/common/`, `feature/automation/` | Small modules. Most of what they'll own still lives in `:app` |
| `app/src/test/` | JVM tests, including the source-guard tests described below |
| `core/*/src/test/` | Each module's own JVM tests. `:app:testDebugUnitTest` does not run these |
| `app/src/androidTest/` | Instrumented tests |
| `docs/EXTERNAL_INTENTS.md` | The external broadcast protocol other apps use to trigger tasks |

## Things the build enforces

A few guard tests exist because the same class of bug shipped more than once. They'll fail your build, and the failure message is usually the explanation:

`LocalizationSourceTest` rejects hardcoded user-visible strings in presentation code. Copy on a screen resolves through `R.string`. Presentation code also isn't allowed to render exception text, so a validation failure carries a resource id via `UiRejection` in `UiMessages.kt` instead of a message string.

There are line ceilings on the UI files. `ActiveAutomationUi.kt` stays under 1500 lines and everything in `ui/screens/` stays under 2400. If you're over, extract something rather than asking for the ceiling to move.

Regex literals get scrutiny. Android's regex engine is ICU and it rejects patterns that `java.util.regex` accepts on a desktop JVM, which has broken a shipped release here before. A regex in a `companion object` that ICU won't compile takes the whole enclosing class down. The JVM test suite cannot see this, so a regex change wants an instrumented test.

## Claiming an issue

Comment on the issue saying what you're taking. For anything file-scoped, name the file. Issues tagged `good first issue` are often several independent pieces, and one file is a perfectly reasonable pull request. Several small ones are easier to review than one large one.

If an issue has been sitting with a claim on it for a couple of weeks and nothing's landed, it's fine to ask whether it's still being worked on.

## Pull requests

Keep a pull request to one logical change. Match the surrounding code style rather than reformatting. If you spot unrelated problems while you're in there, mention them in the description or open an issue, but leave them alone in the diff.

Commit messages should say why, not what. The diff already covers what.

New dependencies are a bigger ask than they look. Dependency verification is checksum-pinned and signature-checked in `gradle/verification-metadata.xml`, so adding one means updating that file with reviewed evidence. Raise it in an issue first.

## License

Contributions are made under the MIT License, the same as the rest of the project. See [LICENSE](LICENSE).
