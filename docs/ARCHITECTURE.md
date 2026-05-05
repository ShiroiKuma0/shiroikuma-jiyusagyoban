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
│  - Profiles / Tasks / Inspector / Setup / Run Log     │
├───────────────────────────────────────────────────────┤
│  Engine                                               │
│  - ProfileMatcher  (evaluates active set)             │
│  - TaskRunner      (executes actions, flow control)   │
│  - VariableStore   (global + per-task locals)         │
│  - ContextSources  (Flow<ContextEvent> per type)      │
│  - NotificationListenerService -> event source bridge │
│  - MainActivity NFC intents -> event source bridge    │
│  - CalendarProvider / sun ticks -> event source bridge│
├───────────────────────────────────────────────────────┤
│  Action Library                                       │
│  - Action interface + registry                        │
│  - Built-ins grouped by category                      │
│  - Capability gating for restricted actions           │
├───────────────────────────────────────────────────────┤
│  Storage (Room + DataStore)                           │
│  - Profiles, Tasks, Actions, Scenes, Variables        │
│  - Run log                                            │
│  - OpenTasker JSON bundles / Tasker XML import plans  │
├───────────────────────────────────────────────────────┤
│  Platform                                             │
│  - AutomationService (foreground)                     │
│  - AlarmManager exact/inexact time ticks              │
│  - NetworkCallback / BroadcastReceivers / UsageStats  │
└───────────────────────────────────────────────────────┘
```

### Engine flow

1. `AutomationService` starts on boot, loads all enabled `Profile`s.
2. Each `Profile` subscribes to its `Context` sources (cold `Flow`s exposed by `ContextSources`).
3. `ProfileMatcher` keeps a per-profile boolean state. When all contexts in a profile transition `false → true`, it submits the entry task; on `true → false`, the exit task.
4. `TaskRunner` walks the action list, expanding `%vars` against `VariableStore`, executing actions sequentially, and writing action-level trace summaries to the run log.
5. Actions return a `Result` (Success / Failure / Skip) — `TaskRunner` decides whether to halt based on the action's "Continue Task After Error" flag.

The Context Inspector UI separately observes registered `ContextSource` flows and applies the same `ContextMatchEvaluator` rules to persisted profiles. It does not dispatch tasks; it explains current source health, latest values, and why each profile would or would not match.

Notification listener triggers are represented as Event contexts with `event=notification`. The listener pushes in-memory events containing package, title, and body previews; Android logs include package and character counts only, not notification text.

NFC tag triggers are represented as Event contexts with `event=nfc`. `MainActivity` accepts tag, tech, and NDEF discovery intents, normalizes the scanned tag ID, and pushes an in-memory event that profiles can filter with `tagId`.

Calendar and sun triggers are represented as Event contexts. Calendar polling uses `CalendarContract.Instances` only when `READ_CALENDAR` is granted and emits redacted metadata such as calendar name, busy/upcoming state, all-day flag, and minutes until start/end, without event titles or descriptions. Sunrise/sunset filters evaluate minute ticks against user-provided latitude/longitude, offset minutes, and trigger windows.

Tasker XML import is intentionally staged through the OpenTasker JSON bundle model. `TaskerXmlImporter` parses common Tasker task/profile/variable structures into a bundle plus migration report, preserves unmapped Tasker actions as explicit unsupported placeholders, and reports skipped contexts, profiles, and scenes before any Room write path is invoked.

The F-Droid distribution path is property-based rather than a product flavor. `-PopenTaskerDistribution=fdroid` sets `BuildConfig.DISTRIBUTION`, keeps existing Gradle variant names stable, and is paired with `verifyFdroidReadiness` to block common proprietary dependency families before release-profile builds.

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

`ActionContext` exposes the application context, variable store, current task scope, and a logger. The current runtime is the built-in action registry, capability metadata for unsupported or setup-required Android operations, and a conservative Locale setting-plugin dispatch action.

### Plugin host

Locale-compatible setting-plugin dispatch is active through `plugin.locale.fire`. It accepts only explicit package names and string-only JSON bundle values before dispatching `com.twofortyfouram.locale.intent.action.FIRE_SETTING`. Interactive plugin configuration, condition query execution, and richer plugin callbacks are still roadmap items.

## Why these choices

- **Kotlin + Compose + Material 3** — modern, smaller, type-safe, and matches your stack rules.
- **Room over raw XML** — the live store is queryable; Tasker/XML import-export is planned, not shipped.
- **JSON in blobs for action/context config** — additive schema, no migration churn per new action.
- **Locale-compat plugin API** — planned to unlock the existing plugin ecosystem after the local execution/runtime model is stable.
- **Foreground `AutomationService`** — required for reliable trigger eval on modern Android (Doze, App Standby).
