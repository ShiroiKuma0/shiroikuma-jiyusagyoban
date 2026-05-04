# OpenTasker JSON Bundle v1

OpenTasker JSON bundles are the portable interchange format for profiles, tasks, actions, contexts, variables, scenes, and template sharing.

## Format

Top-level fields:

| Field | Required | Notes |
|---|---:|---|
| `schemaVersion` | Yes | Current value: `1`. Imports reject unsupported schema versions. |
| `appVersion` | Yes | OpenTasker version that produced the export. |
| `exportedAtEpochMs` | Yes | Export timestamp supplied by the caller. |
| `metadata` | Yes | Human-readable name/description, capability requirements, and warnings. |
| `tasks` | Yes | Sorted by name, then source ID, for stable diffs. |
| `profiles` | Yes | Sorted by name, then source ID. Task references are remapped during import. |
| `variables` | Yes | Sorted by variable name. |
| `scenes` | Yes | Sorted by name, then source ID. Element task links are remapped when possible. |

## Import behavior

- Tasks import first and receive new local Room IDs.
- Profiles import after tasks; missing enter-task references skip that profile with a lossy warning.
- Profile automation modes are preserved as `SINGLE`, `RESTART`, `QUEUED`, or `PARALLEL`.
- Missing exit-task references are dropped with a lossy warning.
- Variables insert or update by variable name.
- Scenes import after tasks; missing element task links are dropped with a lossy warning.
- Unsupported schema versions are rejected before writing.
- Unsupported or setup-required actions are preserved, but capability requirements are surfaced in metadata/warnings.

## Stability rules

- Exporters sort top-level collections deterministically.
- Exporters include capability requirements for non-ready actions.
- Importers never silently repair missing references; every skipped or dropped link is reported.
- Future schema versions must add migration code before changing field meaning.
