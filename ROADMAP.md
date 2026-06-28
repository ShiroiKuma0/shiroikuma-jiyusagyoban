# OpenTasker Roadmap

**Current app version:** 0.2.75
**Last updated:** 2026-06-27

Only open work belongs here; git history and `CHANGELOG.md` are the release record.

## Research-Driven Additions

### P1 -- Maintainability, accessibility, and localization

- [ ] P1 — Add repeatable accessibility checks for critical flows
  Why: Source-level content-description coverage exists, but setup, profile/task editors, scenes, destructive actions, and run-log flows do not have a repeatable accessibility gate.
  Evidence: `app/src/test/java/com/opentasker/ui/AccessibilitySourceTest.kt`; `app/src/androidTest/java/com/opentasker/ui/screens/RunLogScreenContentTest.kt`; https://support.google.com/googleplay/android-developer/answer/10964491
  Touches: Compose semantics/test tags, accessibility source tests, androidTest accessibility checks for setup/editor/scene/run-log paths.
  Acceptance: Automated tests cover missing labels, disabled-state clarity, large-text clipping, and keyboard/TalkBack semantics on onboarding, profile creation, task/action/context editors, scene creation, and run logs.
  Complexity: M

- [ ] P1 — Expand Compose UI tests beyond Run Log
  Why: Run Log has instrumentation coverage, but editor and setup paths carry most user-facing breakage risk.
  Evidence: `app/src/androidTest/java/com/opentasker/ui/screens/RunLogScreenContentTest.kt`; `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt`; `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt`; `app/src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt`
  Touches: `app/src/androidTest`, Compose test tags/semantics, editor validation hooks.
  Acceptance: Instrumented UI tests cover first-launch onboarding, profile creation, task/action editor validation, context editor validation, scene creation, and import/export error states.
  Complexity: M

### P2 -- Ecosystem integrations and observability

- [ ] P2 — Add a repeatable Locale plugin compatibility fixture
  Why: The current Locale host/target validation depends on whichever plugin is installed, so compatibility evidence is not repeatable.
  Evidence: `tools/validate-locale-plugin.ps1`; `docs/LOCALE_PLUGIN_HOST.md`; https://github.com/twofortyfouram/android-plugin-api-for-locale
  Touches: test fixture app/module or JVM contract tests, Locale discovery/config/fire/query code, validation harness summary output.
  Acceptance: A synthetic setting/condition plugin validates discovery, config parsing, fire/query/request-query, redaction, and result codes in a repeatable local or CI/device flow.
  Complexity: M

- [ ] P2 — Spike the first Home Assistant bridge protocol
  Why: Home Assistant, Node-RED, and n8n dominate local automation integrations, while OpenTasker has HTTP/LAN primitives but no native bridge.
  Evidence: https://github.com/home-assistant/android; https://companion.home-assistant.io/docs/core/sensors/; https://nodered.org/docs/; `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`
  Touches: integration package, action metadata, setup permissions, HTTP/local-network policy, tests.
  Acceptance: A tested proof of concept chooses outbound webhook, MQTT, or `mobile_app` registration as the first supported bridge and documents auth, payload limits, retry behavior, and privacy boundaries.
  Complexity: M

- [ ] P2 — Add an outbound MQTT publish action with an F-Droid-safe client decision
  Why: MQTT is the common local automation bus for Home Assistant and Node-RED, and existing HTTP actions do not support retained/QoS topic workflows.
  Evidence: https://github.com/hannesa2/paho.mqtt.android; `gradle/libs.versions.toml`; `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`
  Touches: Gradle dependency catalog, action metadata, network/local-network permission checks, run-log redaction, unit tests with a fake client.
  Acceptance: `mqtt.publish` supports host, port, TLS option, topic, payload, QoS, and retain; private hosts respect `ACCESS_LOCAL_NETWORK`; tests cover success, denial, timeout, and redaction.
  Complexity: M

- [ ] P2 — Design a UnifiedPush or ntfy event-trigger path
  Why: FOSS remote triggers need push without Google services or polling, and ntfy/UnifiedPush already serve the degoogled Android ecosystem.
  Evidence: https://github.com/binwiederhier/ntfy; https://github.com/UnifiedPush/android-connector; existing event contexts under `app/src/main/java/com/opentasker/automation` and `app/src/main/java/com/opentasker/core/contexts`
  Touches: event context model, setup screen, notification permission handling, payload validation, integration tests.
  Acceptance: A bounded `event=push` design or spike defines distributor/ntfy choice, auth model, payload size limits, retry behavior, redaction, and one tested trigger path.
  Complexity: M

### P3 -- Evaluations

- [ ] P3 — Evaluate Glance widget migration
  Why: Widgets use RemoteViews XML today; Glance could reduce widget/UI divergence but may add dependency and capability tradeoffs.
  Evidence: `app/src/main/java/com/opentasker/widget/TaskWidgetProvider.kt`; `app/src/main/res/layout/widget_task.xml`; https://developer.android.com/jetpack/androidx/releases/glance
  Touches: widget package, Gradle dependencies, widget tests.
  Acceptance: Recommendation records APK-size impact, missing Glance features, testability gains, and migration steps; no production rewrite happens before the decision is accepted.
  Complexity: S

- [ ] P3 — Evaluate Navigation3 timing
  Why: The app uses Navigation Compose 2.9.8, while Navigation3 is still an alpha-track migration target and the UI split is a higher-priority risk.
  Evidence: `gradle/libs.versions.toml`; https://developer.android.com/jetpack/androidx/releases/navigation3
  Touches: navigation setup, route/state ownership, deep-link/editor-state handling.
  Acceptance: Recommendation states wait/migrate criteria, minimum stable version, expected code movement, and risks to editor state and deep links.
  Complexity: S

- [ ] P3 — Evaluate bounded accessibility automation as an opt-in advanced module
  Why: Tasker/AutoInput, MacroDroid, and AutoJs6 show demand for tap/read automation, but unbounded accessibility scripting is a high policy and trust risk.
  Evidence: https://github.com/SuperMonster003/AutoJs6; https://support.google.com/googleplay/android-developer/answer/10964491; no `AccessibilityService` entry in `app/src/main/AndroidManifest.xml`
  Touches: manifest/service design, action metadata, setup disclosure, permission UX, policy documentation.
  Acceptance: Threat model and policy review define whether bounded tap/swipe/read actions can ship, what data is never collected, and why arbitrary scripting is excluded.
  Complexity: M
