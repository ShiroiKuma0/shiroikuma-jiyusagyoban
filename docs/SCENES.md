# Scenes Baseline

Last updated: 2026-05-05

OpenTasker v0.2.25 exposed the existing scene persistence model through a safe active UI surface. Current unreleased L2 work keeps scenes non-runtime while adding element authoring and task bindings.

## Active Scope

- Scenes are loaded from Room through `SceneDao.getAllAsFlow()`.
- The Scenes tab can create and delete scene shells.
- Scene cards can add, edit, and remove button, text, slider, and image elements.
- Element dialogs edit dp bounds, type-specific config, tap task binding, and long-press task binding.
- New elements use `SceneElementDrafts` defaults for stable JSON IDs, safe initial bounds, and type-specific starter config.
- Scene cards show dimensions, element counts, element bounds, and task bindings.
- `SceneValidator` reports invalid scene dimensions, empty scenes, invalid element geometry, out-of-bounds elements, and missing tap/long-press task references.
- The UI shows whether Android overlay access is currently granted.

## Not Active Yet

- Drag/drop layout editing.
- Scaled canvas layout preview.
- Overlay window launch.
- Background foreground-service overlay behavior.
- Android 15 overlay runtime policy handling beyond setup visibility.

## Next Scene Work

1. Add a scaled canvas preview with element positions.
2. Add drag/drop layout editing only after the form-backed element editor is stable.
3. Add explicit overlay launch only after permission, disclosure, and Android 15 behavior checks are implemented.
