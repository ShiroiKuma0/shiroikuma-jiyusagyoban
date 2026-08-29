# Design QA

## Reference set

The selected visual direction is Quiet Workshop. The ten page references live in `design/mockups/`:

- `profiles-quiet-workshop.png`
- `tasks-quiet-workshop.png`
- `run-log-quiet-workshop.png`
- `variables-quiet-workshop.png`
- `flow-quiet-workshop.png`
- `scenes-quiet-workshop.png`
- `inspector-quiet-workshop.png`
- `setup-quiet-workshop.png`
- `diagnostics-quiet-workshop.png`
- `settings-quiet-workshop.png`

The supplied logo reference is preserved at `design/logo/source-user-logo-2026-08-29.png`. Production artwork is under `app/src/main/res/`, `design/logo/`, and `fastlane/metadata/android/en-US/images/`.

## Test viewport

- Device profile: Pixel 7 API 35 emulator
- Resolution: 1080 x 2400
- Density: 420 dpi
- Theme: AMOLED black
- State: realistic seeded profiles, tasks, variables, scenes, run history, and setup gaps

## Comparison passes

The first pass captured all ten implemented destinations. It exposed repeated summaries, oversized controls, clipped Run Log actions, a misplaced Scenes create action, and an Inspector that buried live context data.

The second pass paired every reference and implementation screenshot in one side-by-side image at the same 1080 x 2400 viewport. The shared header was tightened, Profiles moved its workspace tools into the search row, Run Log actions were shortened, Scenes gained a bottom create action, and Inspector gained a single-profile selector with collapsible details.

A final paired review covered the changed Profiles, Run Log, Scenes, and Inspector screens. The remaining differences preserve real app functions or Android system chrome. No control is cropped, launcher-safe content stays inside the adaptive icon safe zone, and all core actions remain reachable.

## Final captures

Release screenshots are stored in `fastlane/metadata/android/en-US/images/phoneScreenshots/`. The unchanged in-app views were revalidated against version code 94 after the launcher artwork update.
