# OpenTasker Research

Updated: 2026-06-12
Project: OpenTasker

## Top Finding

OpenTasker's highest-value next work is action trustfulness: close visible but unsupported TTS/sound playback and strengthen notification lifecycle controls before adding more trigger breadth. The app already has strong local-first execution, Tasker XML import, profile sharing, Locale plugin hosting, and JSON bundle transfer, but several user-facing action surfaces still advertise capability without complete runtime behavior.

## Scope

This pass reviewed the current OpenTasker repository, local project notes, Android platform changes, competitor automation surfaces, FOSS automation alternatives, and adjacent integration expectations. It intentionally proposes only incomplete work for ROADMAP.md and does not mark existing work complete.

## Current Repository Signals

- The app is a Kotlin/Jetpack Compose Android automation tool using Room, WorkManager, Material 3, Coroutines, and flavor-specific Play/F-Droid builds.
- Current dependency posture is modern: AGP 9.2.1, Kotlin 2.3.21, Compose BOM 2026.04.01, Room 2.8.4, WorkManager 2.11.2, Gson 2.14.0, compile SDK 36, target SDK 35.
- The latest local commit is `0c01cfc fix: gate sms permissions for play`, ahead of origin by one commit before this research pass.
- Runtime source still exposes visible incomplete actions:
  - `tts.speak` maps to `SayAction`, which logs text and returns "Text-to-speech action is not implemented yet."
  - `sound.play` maps to `PlaySoundAction`, which logs a path and returns "Direct media playback is not implemented yet."
- Notification posting currently creates one default "OpenTasker actions" channel and auto-cancel notifications, without persistent/update/cancel, category/channel controls, tap routing, or muted-channel visibility.
- OpenTasker can host Locale-compatible plugins, but it does not appear to expose itself as a Locale/Tasker plugin target that Tasker or MacroDroid can invoke.
- Event profile hints include boot, notification, NFC, calendar, sunrise, and sunset, but no package-added, package-removed, or package-replaced trigger family.

## Market And Competitor Signals

- Tasker treats text-to-speech, local media playback, notifications, package lifecycle events, and plugin actions as mature automation primitives.
- MacroDroid emphasizes broad built-in action coverage, including speaking text, media control, device control, messaging, screenshots, and Tasker/Locale plugin interoperability.
- Automate positions flowchart execution and hundreds of blocks as its core differentiator.
- FOSS automation competitors remain fragmented. Automation2 is active on F-Droid and covers common triggers/actions, while Easer is historically important but older. OpenTasker can still differentiate through local-first UX, import/export, transparent run logs, F-Droid readiness, and modern Android policy handling.
- Adjacent tools such as Home Assistant task bridges and ntfy/UnifiedPush integrations reinforce that Android automation users expect bridges more than isolated feature islands.

## Platform Signals

- Android Auto Backup is automatic unless configured; sensitive automation databases, secrets, plugin payloads, and shared-profile state need explicit backup rules.
- Android 17 hardens background audio behavior. Background playback and volume control can fail silently unless the app uses valid lifecycle, media session, foreground-service, or visible/user-initiated state. This makes TTS and sound playback a correctness and truthfulness issue, not just a feature gap.
- Android notification channels are immutable after creation. Users control channel importance and interruptions, so OpenTasker should surface channel state and open channel settings rather than pretending an action can change importance later.
- Android 11+ package visibility policy treats installed-app inventory as sensitive. Package lifecycle events should avoid broad inventory scans and should not request `QUERY_ALL_PACKAGES` for a simple event trigger.
- Android 8+ implicit broadcast restrictions require package lifecycle handling to be implemented with clear runtime/service boundaries.

## Dependency And Security Snapshot

- No urgent dependency modernization item was found in the current version catalog. The repo is already on current stable Android build tooling and modern AndroidX releases.
- Gson is already newer than the historical unsafe-deserialization vulnerability range.
- The stronger near-term security gaps are policy/configuration issues already reflected in the roadmap: Auto Backup exclusions, unbounded network/wait behavior, external execution boundaries, and notification/plugin trust surfaces.

## Recommended Roadmap Additions

The roadmap additions from this pass are:

- RD35: TTS and sound playback action truthfulness.
- RD36: Notification channel/category and lifecycle controls.
- RD37: OpenTasker Locale/Tasker plugin target bridge.
- RD38: Package lifecycle event trigger family.

## Source Trace

Repository evidence:

- `app/src/main/java/com/matthewglover/opentasker/automation/actions/BuiltInActions.kt`
- `app/src/main/java/com/matthewglover/opentasker/automation/actions/MediaActions.kt`
- `app/src/main/java/com/matthewglover/opentasker/automation/actions/ActionCapabilities.kt`
- `app/src/main/java/com/matthewglover/opentasker/ui/ActiveAutomationUi.kt`
- `app/src/main/java/com/matthewglover/opentasker/plugins/LocalePluginHost.kt`
- `app/src/main/AndroidManifest.xml`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `CLAUDE.md`
- `AGENTS.md`
- `ROADMAP.md`

External sources:

- Tasker Say action: https://tasker.joaoapps.com/userguide/en/help/ah_say.html
- Tasker Music Play action: https://tasker.joaoapps.com/userguide/en/help/ah_music_play.html
- Tasker Notify action: https://tasker.joaoapps.com/userguide/en/help/ah_notification.html
- Tasker Event A-Z: https://tasker.joaoapps.com/userguide/en/help/eh_index.html
- Tasker plugin introduction: https://tasker.joaoapps.com/plugins-intro.html
- Tasker plugin service migration: https://tasker.joaoapps.com/pluginsservicesmigration.html
- MacroDroid website: https://www.macrodroid.com/
- MacroDroid Play listing: https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid
- MacroDroid Locale/Tasker plugin action: https://macrodroidforum.com/wiki/index.php/Action%3A_Locale/Tasker_Plugin
- Automate website: https://llamalab.com/automate/
- Automate flow documentation: https://llamalab.com/automate/doc/flow.html
- Automation2 F-Droid listing: https://f-droid.org/packages/com.jens.automation2/
- TaskerHA F-Droid listing: https://f-droid.org/en/packages/com.github.db1996.taskerha/
- ntfy Tasker/MacroDroid/Automate integration issue: https://github.com/binwiederhier/ntfy/issues/31
- UnifiedPush ntfy distributor: https://unifiedpush.org/users/distributors/ntfy/
- F-Droid UnifiedPush overview: https://f-droid.org/en/2022/12/18/unifiedpush.html
- Android Auto Backup: https://developer.android.com/identity/data/autobackup
- Android backup best practices: https://developer.android.com/privacy-and-security/risks/backup-best-practices
- Android 17 background audio hardening: https://developer.android.com/about/versions/17/changes/bg-audio
- Android notification channels: https://developer.android.com/develop/ui/compose/notifications/channels
- Android notification creation: https://developer.android.com/develop/ui/compose/notifications/create-notification
- Android package visibility: https://developer.android.com/training/package-visibility
- Android broadcast guidance: https://developer.android.com/develop/background-work/background-tasks/broadcasts
- Android Glance widget guidance: https://developer.android.com/develop/ui/compose/glance/create-app-widget
- AGP 9.2 release notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Jetpack Compose April 2026 updates: https://android-developers.googleblog.com/2026/04/jetpack-compose-april-2026-updates.html
- Room release notes: https://developer.android.com/jetpack/androidx/releases/room
- Gson OSV advisory: https://osv.dev/vulnerability/GHSA-4jrv-ppp4-jm57

## Self Audit

- Updated exactly `RESEARCH.md` and `ROADMAP.md`.
- Replaced `RESEARCH.md` instead of creating dated research files.
- Appended only new incomplete roadmap items.
- Did not add implementation code.
- Did not claim completion for existing roadmap work.
