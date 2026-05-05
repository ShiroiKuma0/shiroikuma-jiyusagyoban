# OpenTasker Architecture

## Research summary — how Tasker works

Tasker (closed source, package `net.dinglisch.android.taskerm`) is a Java/Kotlin Android app that began life around 2007 in the Android Developer Challenge 2. Its mental model — confirmed by the official user guide at <https://tasker.joaoapps.com/userguide/en/index.html> — is:

```
Profile  := { Context+ } -> { Task+ }
Context  := Application | Time | Day | Location | State | Event
Task     := Action[]   (with flow control: If/For/Goto/Wait/Stop)
Action   := one of ~350 built-in primitives, or a plugin-supplied action
Scene    := UI overlay built from Elements (Button, Slider, Web, Map, ...)
Variable := named slot, %global or %local, expanded at action runtime
```

Profiles are *active* whenever **all** their contexts match. Activation runs the entry task; deactivation runs the (optional) exit task. Tasks are ordered Action lists with imperative flow control. Persistence is XML (`*.prj.xml`, `*.tsk.xml`, `*.prf.xml`, `*.scn.xml`). Plugins expose actions/conditions/events via the Locale-compatible AIDL contract (`com.twofortyfouram.locale.intent.action.EDIT_SETTING` etc.), which is also the de-facto Android automation plugin standard.

Tasker's source is **not public**. This architecture is reconstructed from the user guide, public plugin SDK, intent surface, and observable behavior — not decompilation.

## OpenTasker layered architecture

```
┌───────────────────────────────────────────────────────┐
│  UI (Jetpack Compose, Material 3, AMOLED default)     │
│  - Profiles / Tasks / Flow / Scenes / Setup / Logs    │
├───────────────────────────────────────────────────────┤
│  Engine                                               │
│  - ProfileMatcher  (evaluates active set)             │
│  - TaskRunner      (executes actions, flow control)   │
│  - VariableStore   (global + per-task locals)         │
│  - TemplateExpressionEngine (bounded {{ ... }} eval)  │
│  - ContextSources  (Flow<ContextEvent> per type)      │
│  - NotificationListenerService -> event source bridge │
│  - MainActivity NFC intents -> event source bridge    │
│  - CalendarProvider / sun ticks -> event source bridge│
├───────────────────────────────────────────────────────┤
│  Action Library                                       │
│  - Action interface + registry                        │
│  - Built-ins grouped by category                      │
│  - Capability gating for restricted/elevated actions  │
├───────────────────────────────────────────────────────┤
│  Storage (Room + DataStore)                           │
│  - Profiles, Tasks, Actions, Scenes, Variables        │
│  - Run log                                            │
│  - OpenTasker JSON bundles / share manifests / import │
├───────────────────────────────────────────────────────┤
│  Platform                                             │
│  - AutomationService (foreground)                     │
│  - AlarmManager exact/inexact time ticks              │
│  - NetworkCallback / BroadcastReceivers / UsageStats  │
│  - FOSS location source + geofence evaluator          │
│  - Optional Shizuku manager readiness detection       │
│  - Optional Termux script bridge readiness detection  │
└───────────────────────────────────────────────────────┘
```

### Engine flow

1. `AutomationService` starts from app launch or boot, then loads all enabled `Profile`s.
2. Each `Profile` subscribes to its `Context` sources (cold `Flow`s exposed by `ContextSources`).
3. `ProfileMatcher` keeps a per-profile boolean state. When all contexts in a profile transition `false → true`, it submits the entry task; on `true → false`, the exit task.
4. `TaskRunner` walks the action list, expanding legacy `%vars` and bounded `{{ ... }}` templates against `VariableStore`/`TemplateExpressionEngine`, executing actions sequentially, and writing action-level trace summaries to the run log.
5. Actions return a `Result` (Success / Failure / Skip) — `TaskRunner` decides whether to halt based on the action's "Continue Task After Error" flag.

The Context Inspector UI separately observes registered `ContextSource` flows and applies the same `ContextMatchEvaluator` rules to persisted profiles. It does not dispatch tasks; it explains current source health, latest values, and why each profile would or would not match.

Notification listener triggers are represented as Event contexts with `event=notification`. The listener pushes in-memory events containing package, title, and body previews; Android logs include package and character counts only, not notification text.

NFC tag triggers are represented as Event contexts with `event=nfc`. `MainActivity` starts the foreground automation service, accepts tag, tech, and NDEF discovery intents, normalizes the scanned tag ID, and pushes an in-memory event that profiles can filter with `tagId`.

Calendar and sun triggers are represented as Event contexts. Calendar polling uses `CalendarContract.Instances` only when `READ_CALENDAR` is granted and emits redacted metadata such as calendar name, busy/upcoming state, all-day flag, and minutes until start/end, without event titles or descriptions. Sunrise/sunset filters evaluate minute ticks against user-provided latitude/longitude, offset minutes, and trigger windows.

Location contexts use `LocationContextSourceImpl`, `LocationDwellStateStore`, and `FossGeofenceEvaluator` for a Play-services-free geofence path. The source emits Android framework GPS/network fixes through provider-specific balanced cadence defaults and fail-closed setup events for missing permission, disabled providers, unavailable services, or source errors. Shared `LocationPolicyDisclosures` copy keeps Setup and Context Inspector aligned on Android 11+ background-location settings, approximate precision carryover, and Android 14+ location foreground-service gating. `ProfileMatcher` enriches Location events with profile/context/config-scoped persisted `insideSinceEpochMs` metadata before matching, so dwell timers can survive process restarts without reusing state after a geofence config edit. Profile deletion and context-list edits clear profile-scoped persisted dwell keys so removed or reindexed geofences do not keep stale timers. The Context Inspector applies the same dwell enrichment per profile row and shows inside/outside/accuracy-blocked dwell state. The active matcher supports Haversine distance, radius, optional max accuracy, and dwell-time checks when a location event supplies latitude, longitude, accuracy, observed time, and inside-since metadata. API 36 device smoke confirmed app launch starts the foreground service with `specialUse|location` after location prerequisites are granted/enabled; device-backed battery measurement and background reliability remain future work.

Tasker XML import is intentionally staged through the OpenTasker JSON bundle model. `TaskerXmlImporter` parses common Tasker task/profile/variable structures into a bundle plus migration report, preserves unmapped Tasker actions as explicit unsupported placeholders, and reports skipped contexts, profiles, and scenes. The Profiles import UI reads selected XML with a bounded document-picker flow, shows the migration/capability report, then invokes the Room bundle repository only after confirmation; imported profiles are created disabled by default.

Template expressions are staged through `TaskRunner` action arguments and conditions. `TemplateExpressionEngine` evaluates bounded `{{ ... }}` expressions with task, event, and global variable scopes, explicit scope prefixes, arrays, JSON path reads, safe string/math functions, traces, and warnings. It does not execute user code, shell commands, or regexes, and unknown functions preserve the original token so unsupported expressions fail closed. Regex-like template functions are explicitly unsupported; existing bounded `%var(regex:...)` operators remain part of the legacy `VariableStore` path. `TaskRunner` applies legacy `%var` expansion first, then template expansion for arguments and conditions. Action arguments record sanitized expanded-argument summaries, warning counts, and per-expression trace lines; conditions skip fail-closed when template expansion emits warnings. Run-log diagnostics parse argument summaries into structured trace fields so the UI can render expanded arguments, source scopes, expression values, and template warnings separately from the action result message.

Profile sharing is staged through the same bundle model. `ProfileShareLibrary` creates offline share manifests with stable slugs, counts, unverified trust state, capability/import safety findings, and GitHub Discussions submission markdown. It does not publish to a network, claim template verification, or bypass bundle validation.

The read-only visual flow baseline is generated from active domain models. `AutomationFlowGraphBuilder` maps each profile into context, profile, enter-task, exit-task, action, edge, and missing-reference nodes so the Compose Flow tab can show graph structure without mutating profile/task data.

Scenes are persisted in Room and exposed through a conservative Scenes tab. The active baseline creates and deletes scene shells, previews persisted elements, validates geometry and task bindings through `SceneValidator`, and shows overlay permission readiness without launching overlay windows.

The Shizuku readiness baseline is intentionally non-executing. `ShizukuPowerBackend` only checks whether the Shizuku manager package is visible and installed, exposes optional setup status in the Setup screen, and annotates elevated-action candidates in capability messages. OpenTasker does not link the Shizuku API, request Shizuku permission, execute shell commands, or enable privileged actions in this baseline.

The Termux scripting baseline is also non-executing. `TermuxScriptBackend` checks package visibility for Termux and Termux:Tasker, the Setup screen reports optional script-bridge readiness, and `script.termux.run` exists as a gated action with an explicit runtime failure path. OpenTasker does not request `com.termux.permission.RUN_COMMAND`, dispatch Termux intents, capture stdout/stderr, or map script output variables in this baseline.

The F-Droid distribution path is property-based rather than a product flavor. `-PopenTaskerDistribution=fdroid` sets `BuildConfig.DISTRIBUTION`, keeps existing Gradle variant names stable, and is paired with `verifyFdroidReadiness` to block common proprietary dependency families before release-profile builds.

Gradle plugin and library versions are centralized in `gradle/libs.versions.toml`. Dependency modernization is handled as staged upgrade batches so Kotlin/KSP/Compose compiler alignment, Room schema risk, WorkManager runtime behavior, Hilt startup drift, AGP release output changes, and the F-Droid profile can be verified independently.

### Persistence schema (Room)

| Table | Key fields |
|---|---|
| `profiles` | id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson |
| `tasks` | id, name, collisionMode, priority, actionsJson |
| `scenes` | id, name, widthDp, heightDp, elementsJson |
| `variables` | name (PK), value, isGlobal |
| `run_logs` | id, taskId, taskName, timestamp, durationMs, success, message |
| `automation_rules` | id, name, enabled, profileId, ruleJson, executionMode, createdAt, updatedAt |
| `execution_logs` | id, ruleId, ruleName, triggerId, triggerType, timestamp, status, message, executionTimeMs, actionResultsJson |

Action and context configurations are stored as JSON blobs (kotlinx.serialization) so adding new types doesn't require a Room migration.

### Action interface

```kotlin
interface Action {
    val id: String                   // stable identifier, e.g. "wifi.toggle"
    val category: ActionCategory
    suspend fun run(ctx: ActionContext, args: ActionArgs): ActionResult
}
```

`ActionContext` exposes the application context, variable store, current task scope, and a logger. The current runtime is the built-in action registry, capability metadata for unsupported or setup-required Android operations, and conservative Locale setting-plugin dispatch plus condition-plugin query/configuration/request-query helpers.

### Plugin host

Locale-compatible setting-plugin dispatch is active through `plugin.locale.fire`. It accepts only explicit package names and string-only JSON bundle values before dispatching `com.twofortyfouram.locale.intent.action.FIRE_SETTING`. Interactive plugin configuration, condition query execution, and richer plugin callbacks are still roadmap items.

## Why these choices

- **Kotlin + Compose + Material 3** — modern, smaller, type-safe, and matches your stack rules.
- **Room over raw XML** — the live store is queryable; Tasker/XML import-export is planned, not shipped.
- **JSON in blobs for action/context config** — additive schema, no migration churn per new action.
- **Locale-compat plugin API** — planned to unlock the existing plugin ecosystem after the local execution/runtime model is stable.
- **Foreground `AutomationService`** — required for reliable trigger eval on modern Android (Doze, App Standby).
