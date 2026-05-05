# Scenes Baseline

Last updated: 2026-05-05

OpenTasker v0.2.25 exposes the existing scene persistence model through a safe active UI surface. The baseline is a scene library and validator, not an overlay runtime.

## Active Scope

- Scenes are loaded from Room through `SceneDao.getAllAsFlow()`.
- The Scenes tab can create and delete scene shells.
- Scene cards show dimensions, element counts, element bounds, and task bindings.
- `SceneValidator` reports invalid scene dimensions, empty scenes, invalid element geometry, out-of-bounds elements, and missing tap/long-press task references.
- The UI shows whether Android overlay access is currently granted.

## Not Active Yet

- Element add/edit forms.
- Drag/drop layout editing.
- Overlay window launch.
- Background foreground-service overlay behavior.
- Android 15 overlay runtime policy handling beyond setup visibility.

## Next Scene Work

1. Add element creation/editing for text, button, slider, image, and task-bound controls.
2. Add task binding pickers for tap and long-press actions.
3. Add a scaled canvas preview with element positions.
4. Add explicit overlay launch only after permission, disclosure, and Android 15 behavior checks are implemented.
