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

There's also an aggregate gate, `./gradlew localQualityGate`, which runs lint, coverage floors, dependency policy, schema checks and the connected tests together. It's slow, and it pulls in `connectedDebugAndroidTest`, so it needs a device attached or an explicit `-x connectedDebugAndroidTest`. You don't need to run it for a normal pull request.

Note that this repository does not use GitHub Actions. Builds, tests and releases all happen locally, so nothing will run automatically against your branch. Please say which commands you ran in the pull request description.

## Where things live

Almost all the code is in `:app`. The `core/*` and `feature/*` Gradle modules exist, but with one exception their source sets still point back at files under `app/src/main/java`, so treat the package layout below as the real map rather than the module list.

| Path | What's in it |
| --- | --- |
| `app/src/main/java/com/opentasker/core/engine/` | Task execution, profile matching, the variable store, the foreground service |
| `app/src/main/java/com/opentasker/core/actions/` | The action catalog and every built-in action implementation |
| `app/src/main/java/com/opentasker/core/contexts/` | Trigger sources: time, location, app, network, NFC, notifications, calendar |
| `app/src/main/java/com/opentasker/core/storage/` | Room entities, DAOs, migrations, the encrypted database setup |
| `app/src/main/java/com/opentasker/core/transfer/` | Tasker XML import, OpenTasker bundle import and export |
| `app/src/main/java/com/opentasker/ui/screens/` | All Compose UI |
| `app/src/main/res/values/strings.xml` | User-visible copy. Everything on a screen resolves through here |
| `app/src/test/` | JVM tests, including the source-guard tests described below |
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
