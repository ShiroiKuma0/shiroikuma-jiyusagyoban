# 白い熊 自由作業盤 — bundle schema & concepts

This is the authoritative reference for the **OpenTaskerBundle** JSON format used by every Import/Export
in the app, plus the core concepts. The in-app **Help** tab mirrors this document; the **action
reference** (every action's `type` id and `args`) is generated live in-app from the action registry, so
it always matches the installed build — it is not duplicated here.

> Source of truth for the data shapes: `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt`
> and `app/src/main/java/com/opentasker/core/model/*.kt`. Keep this file in sync when those change.

## Concepts

- **Project** — an optional folder grouping profiles, tasks and scenes. Items with no project are *Unfiled*.
- **Task** — an ordered list of actions. Run on a trigger, a tile, a widget, or by hand.
- **Action** — one step in a task (show a notification, set a variable, send an intent, …).
- **Profile** — binds one or more contexts to an *enter-task* (and optional *exit-task*); active while
  **all** its contexts match.
- **Context** — a condition: app in foreground, time window, day, location, device state, or a one-shot event.
- **Scene** — a floating overlay built from elements (text, button, slider, image …) that can run tasks on tap.
- **Widget template** — a named home-screen widget layout, referenced by the *Set Widget* action and
  refreshed from variables.
- **Variable** — a named value (`%name`) read/written by actions; scope follows its case (below).

## Variables & scoping

Variables are written `%name`; scope is decided by the case of the leading letters:

- `%ALLCAPS` — **super-global**: one value shared everywhere (`projectId` 0).
- `%MixedCase` — **project-global**: one value per project (the task's project; *Unfiled* maps to super-global).
- `%lowercase` — **task-local**: ephemeral, lives only for that task run.

Persisted variables (super-global and project-global) survive restarts and appear on the **Vars** tab.
Indexing like `%array(2)` is **not** supported — the parenthetical is literal text.

## The bundle file (`OpenTaskerBundle`)

One JSON format is used by every Import/Export. Any tab's **+** menu imports it; each tab exports its own
slice. Top-level fields:

| field | type | notes |
| --- | --- | --- |
| `schemaVersion` | int | Format version this build understands (currently **4**). A newer file warns; older files always import (missing fields default). |
| `appVersion` | string | App version that wrote the file (informational). |
| `exportedAtEpochMs` | long | Export timestamp. |
| `metadata` | object | `name`, `description`, `capabilityRequirements[]`, `warnings[]`. |
| `projects` | array | Project folders referenced by items below. |
| `tasks` | array | Tasks with their actions. |
| `profiles` | array | Profiles with their contexts (imported **disabled** for review). |
| `variables` | array | Persisted variables. |
| `scenes` | array | Scenes with their elements. |
| `templates` | array | Widget-layout templates. |
| `sort` | object | Per-tab Alphabetical/Manual choice (`profiles`, `tasks`, `scenes`). |

On import, a project whose name already exists prompts **"Import into"** (MERGE — file items under the
existing project) or **"New project"** (RENAME — make a separate copy, name uniquified). Profiles always
import **disabled** so contexts and permissions can be reviewed first.

### Landing items in an existing project

To make an imported task/profile/scene land in an existing project (e.g. `時間と日付`), include that
project in `projects[]` and reference its bundle-local `id` from the item's `projectId`, then choose
**Import into** (MERGE) on the conflict prompt — names match case-insensitively.

```json
{
  "projects": [{ "id": 1, "name": "時間と日付" }],
  "scenes":   [{ "id": 300, "name": "場面テスト", "projectId": 1, "widthDp": 280, "heightDp": 200, "elements": [] }]
}
```

## Tasks & actions

A task is an ordered `actions[]` list.

| field | type | notes |
| --- | --- | --- |
| `id` | long | Bundle-local id (referenced by profiles / scene elements). |
| `name` | string | Display name; also how scene/profile lookups resolve by name. |
| `priority` | int | Higher runs first on contention. |
| `collisionMode` | enum | `ABORT_NEW` · `ABORT_EXISTING` · `RUN_BOTH` · `WAIT` — behaviour if already running. |
| `actions` | array | The steps (below). |
| `projectId` | long? | Owning project, or `null` = Unfiled. |
| `position` | int | Manual sort order within the tab. |

Each action (`ActionSpec`):

| field | type | notes |
| --- | --- | --- |
| `type` | string | Action id, e.g. `notify.show` — see the in-app **Action reference**. |
| `args` | map | String key→value arguments for that action type. |
| `label` | string? | Optional custom label. |
| `continueOnError` | bool | If true, a failure doesn't stop the task. |
| `condition` | string? | Optional `%var` condition guarding the step. |

## Profiles & contexts

A profile is active while **all** of its contexts match; entering runs `enterTaskId`, leaving runs
`exitTaskId`.

| field | type | notes |
| --- | --- | --- |
| `name` | string | Display name. |
| `enabled` | bool | Imported profiles arrive **disabled**. |
| `contexts` | array | Conditions (below). |
| `enterTaskId` | long | Task run when the profile becomes active. |
| `exitTaskId` | long? | Task run when it deactivates (optional). |
| `cooldownSec` | int | Minimum seconds between activations. |
| `automationMode` | enum | `SINGLE` · `RESTART` · `QUEUED` · `PARALLEL`. |

Each context (`ContextSpec`):

| field | type | notes |
| --- | --- | --- |
| `type` | enum | `APPLICATION` · `TIME` · `DAY` · `LOCATION` · `STATE` · `EVENT`. |
| `config` | map | Type-specific settings (time window, app list, …). |
| `invert` | bool | Match when the condition is false. |
| `orGroup` | string? | Contexts sharing an `orGroup` match as **OR** instead of AND. |

`EVENT` covers one-shot triggers (boot, notification, NFC, calendar, and the internal `event=minute`
tick used by clock widgets).

## Scenes

A scene is a floating overlay (`widthDp` × `heightDp`) of positioned elements.

| field | type | notes |
| --- | --- | --- |
| `widthDp` / `heightDp` | int | Panel size. |
| `elements` | array | Positioned UI elements (below). |
| `projectId` | long? | Owning project, or `null` = Unfiled. |

Each element (`SceneElement`):

| field | type | notes |
| --- | --- | --- |
| `type` | enum | `BUTTON` · `TEXT` · `EDIT_TEXT` · `CHECKBOX` · `TOGGLE` · `SLIDER` · `IMAGE` · … |
| `xDp` / `yDp` / `widthDp` / `heightDp` | int | Position and size within the panel. |
| `config` | map | Element settings (label, text, min/max, …); `%vars` expand at show time. |
| `tapTaskId` / `longPressTaskId` | long? | Task to run on tap / long-press. |

## Variables, templates & projects

**Variable**

| field | type | notes |
| --- | --- | --- |
| `name` | string | Without the leading `%` (scope follows its case — see above). |
| `value` | string | Current value. |
| `projectId` | long | `0` = super-global, `>0` = that project's project-global. |

**Widget template**

| field | type | notes |
| --- | --- | --- |
| `name` | string | Referenced by a *Set Widget* action and the launcher. |
| `layout` | string | Widget-layout JSON with `%vars` left raw (expanded at render). |

**Project**

| field | type | notes |
| --- | --- | --- |
| `id` | long | Bundle-local id referenced by items' `projectId`. |
| `name` | string | Folder name (matched case-insensitively on import). |
| `color` / `sortOrder` / `description` | — | Optional presentation. |
