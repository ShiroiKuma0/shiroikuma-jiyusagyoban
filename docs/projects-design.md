# Projects — design

Status: design agreed, not yet implemented. Fork feature for `shiroikuma-jiyusagyoban`.

OpenTasker organizes everything in flat global lists (`profiles`, `tasks`, `scenes`, `variables`)
with no grouping. This adds Tasker-style **Projects** — top-level baskets for one area of activity —
plus the export/import improvements that go with them.

## Goals / non-goals

- **Goal:** group profiles, tasks, and scenes into named projects; filter the UI by project;
  export/import per-item, per-project, or the whole workspace.
- **Non-goal (v1):** project-scoped variables (variables stay global), project-level
  enable/disable, any engine/behavior change. Projects are purely organizational in v1.

## Resolved decisions

| Question | Decision |
| --- | --- |
| Variables scope | **Global** in v1 (engine uses one `VariableStore`; scoping is Phase 4). |
| Delete a project | **Reassign its items to Unfiled** (never cascade-delete); offer explicit "delete project + items" as a second action. |
| Project as execution unit | **No** — organizational only; the engine ignores `projectId`. |
| Export scope | **Only the selected items themselves** — no automatic dependency closure. Dangling references become warnings. |
| Export granularity | **Per-item** (any single profile/task/scene/variable), **per-project** (a project's own items), and **whole workspace** — all the same primitive: export an explicit selection. |

## 1. Data model

A `Project` entity, and a nullable `projectId` on the three groupable entities. `null` = a virtual
**Unfiled** bucket (no row needed), so existing data needs no backfill. One project per item.

```kotlin
// core/model/Project.kt
@Serializable
data class Project(
    val id: Long = 0,
    val name: String,
    val color: Int? = null,     // optional ARGB swatch
    val sortOrder: Int = 0,     // manual tab ordering
    val description: String = "",
)

// Profile.kt / Task.kt / Scene.kt — one new field each:
val projectId: Long? = null     // null = Unfiled
```

Variables get no `projectId` (global). Cross-project references are allowed (a profile in project A
may call a task in project B), surfaced with a subtle badge because export treats them literally
(see §4).

## 2. Storage & migration

- New `@Entity("projects")` + `ProjectDao`, mirroring `ProfileDao`/`TaskDao`.
- Add nullable `project_id` to `profiles`, `tasks`, `scenes`; extend their `toEntity`/`toDomain`.
- One additive `Migration(N, N+1)` in `DatabaseMigrations.getAllMigrations()`:

```sql
CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                       color INTEGER, sortOrder INTEGER NOT NULL DEFAULT 0,
                       description TEXT NOT NULL DEFAULT '');
ALTER TABLE profiles ADD COLUMN project_id INTEGER;   -- nullable -> existing rows = Unfiled
ALTER TABLE tasks    ADD COLUMN project_id INTEGER;
ALTER TABLE scenes   ADD COLUMN project_id INTEGER;
```

Non-destructive; the SQLite DB-backup path then carries projects with no extra work.

## 3. UI

The bottom nav stays type-based (Profiles / Tasks / Scenes …); the project is an orthogonal filter.

- **Project switcher** in the top bar: `All` · `Unfiled` · each project (by `sortOrder`). Filters the
  Profiles, Tasks, and Scenes lists (Flow optional; Inspect/Setup/Log stay global). Persist the
  selected project id in a small prefs store (same shape as `ThemeStore` / `RunLogRetentionSettings`).
- **Projects management** (new screen, or a Setup section): create / rename / recolor / reorder /
  delete. Delete → reassign items to Unfiled by default.
- **"Move to project…"** action on each profile/task/scene card. A task created from inside a profile
  editor defaults to that profile's project.

## 4. Export / Import (JSON bundle, schema v2)

### Single primitive: export an explicit selection

Per-item, per-project, and whole-workspace are all the same operation — build a bundle from an
explicit list of ids, **pulling in nothing automatically**:

- **Export item** (per card): the one selected profile / task / scene / variable.
- **Export project**: every item whose `projectId == P` — that project's *own* items only.
- **Export all**: the whole workspace (today's behavior).

`OpenTaskerBundleCodec.build(...)` already accepts explicit lists, so this is mostly a selection UI +
a thin `exportSelection(selection)` entry that funnels into `build`. Each export is a normal
`OpenTaskerBundle`, so import reuses the existing preview → validate → confirm flow unchanged.

**No dependency closure.** If an exported profile references a task that isn't in the selection, we do
not pull that task in; we record a warning instead (reusing the bundle's `warnings` channel), and the
existing import-time `validate()` already warns about and skips profiles whose `enterTaskId` is
missing (and drops scene links whose `tapTaskId`/`longPressTaskId` is missing). So partial bundles are
already safe — this just makes them first-class.

**Variables** are global and not owned by a project, so per-item / per-project exports exclude them by
default, with an optional "include referenced variables" toggle (a name-scan of action args /
conditions). Whole-workspace export includes them, as today. A missing `%var` at runtime resolves to
empty, never a crash — consistent with current behavior.

**Project rows** referenced by exported items are included in the bundle so `projectId` resolves on
import; exporting from Unfiled adds no project rows. `metadata.name` reflects the selection
(e.g. "Project: Home", "Profile: Morning routine") for a readable filename/preview.

### Schema v2

```kotlin
const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 2

@Serializable
data class OpenTaskerBundle(
    val schemaVersion: Int = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadata = BundleMetadata(),
    val projects: List<Project> = emptyList(),   // NEW
    val tasks: List<Task> = emptyList(),          // Task/Profile/Scene now carry projectId
    val profiles: List<Profile> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val scenes: List<Scene> = emptyList(),
)
```

With `explicitNulls = false`, a `null` `projectId` is omitted, so a v2 export with no projects stays
shape-compatible with what v1 expected for those fields.

### Compatibility

- **Flip the decoder to `ignoreUnknownKeys = true`** (currently `false`). It doesn't retro-fix shipped
  v1 builds, but it makes every future schema addition (v3+) non-breaking for v2 readers.
- Keep the `schemaVersion` gate in `validate()` as the explicit signal: a v1 build shows the clean
  "unsupported schema version" warning rather than a parse crash (once on a tolerant decoder).

### Import remap

`importBundle` already remaps `taskId` (via `taskIdMap`) and rewrites profile `enterTaskId` /
`exitTaskId`. Add a parallel **`projectIdMap`**: insert imported projects first
(collision policy: merge-by-name), then rewrite each imported entity's `projectId` through the map.

## 5. Phasing

| Phase | Scope | Touches |
| --- | --- | --- |
| **1 — Data** | `Project` model + entity/DAO, `projectId` columns, Room migration, mappers. Everything Unfiled. | `core/model`, `core/storage`, `DatabaseMigrations` |
| **2 — UI** | Project switcher, management screen, "Move to project", filtered lists, persisted selection. | `ActiveAutomationUi`, new screen, prefs store |
| **3 — JSON v2** | `projects` + `projectId` in bundle, `ignoreUnknownKeys = true`, `projectIdMap` import, selection-based export (item / project / all). | `OpenTaskerBundle`, codec, repository, export UI |
| **4 — Optional** | project-scoped variables, `Project.enabled` (engine), colors/icons in the theme. | `VariableStore`, engine, `ThemeStore` |

First buildable slice: Phase 1 + the switcher from Phase 2 (low-risk, everything Unfiled), then JSON v2.
