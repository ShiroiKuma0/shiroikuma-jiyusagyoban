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

## Plain-language pass, 2026-09-03

Forty-four user-facing strings were rewritten to name the outcome first instead of the engine's own vocabulary. The words that went: admission, burst window, time pulse, fail closed, transport, invariant, bind, tile slot, blueprint, semantic changes, and three worker names.

What changed, by area:

- **Run limits.** "Burst start limit" is now "Limit rapid repeats", "Admission overflow" is "When the limit is reached", and the Diagnostics panel headed "Execution admission" is headed "Run limits". Its rows say "Running now, app-wide" and "Rapid starts, app-wide" rather than "Global active executions" and "Global burst starts". A held run in the log says "Held because:" instead of "Admission policy:".
- **Protected settings.** The feature called "device-state invariants" is now "Settings your automations must not change". Adding one is "Protect a setting"; the lint finding is "A protected setting could be changed" and its fix text says what to do rather than naming the violated invariant.
- **Fail closed.** Three places said an action would "fail closed". They now say it refuses to run, and the Shizuku one explains that OpenTasker cannot reach its helper service instead of naming a "privileged user-service transport".
- **Direct boot.** The pre-unlock disclosure kept every fact and lost "arms", "app-owned minute time trigger", "time pulse", "device-protected storage", and "trigger family". `DirectBootContractTest` pins that disclosure, so its assertions moved to the new spelling rather than being loosened.
- **Templates.** "Blueprints" is "Templates" everywhere a user sees it, and "no semantic changes" is "nothing that affects how this runs has changed".
- **Workers.** "Run log prune", "Temporary state revert", and "Configuration snapshot" became "Run log cleanup", "Undo temporary changes", and "Scheduled backup".

One inline English string also moved into resources: the snackbar shown when a task holds an action this build has no editor for. It was the only user-visible literal left outside `strings.xml`.

Resource names were left alone. Only values changed, so nothing in code or in the localisation gate had to move.

### Corrections, same day

An adversarial review of the pass found eight things wrong with it, all fixed in a follow-up:

- **Two claims were false.** "Leave blank to use the app-wide setting" describes a setting that does not exist: `ExecutionAdmissionLimits` is only ever built with its compile-time defaults and nothing in the app changes them. It now says "the built-in default", and the untouched sibling helper was reworded to match instead of contradicting it.
- **The invariant copy promised enforcement.** "Settings your automations must not change" and "are not allowed to touch" read as blocking. Nothing blocks: `AutomationInvariantStore` is read by the lint and the flow graph, never by the engine. The feature is now "Settings to warn about", you "watch" a setting rather than protect it, and the body says outright that nothing is blocked.
- **A hedge was dropped.** The lint finding said a profile "changes" a watched setting. The lint only knows a profile *may* satisfy the guard, and its subject is a comma-joined list that can end in "+2 more", which made the singular verb wrong as well. Back to "may change".
- **"Scheduled backup" named the wrong feature.** That worker takes the off-device configuration snapshot. The app has a separate manual database backup with its own vocabulary, so the name is now "Automatic snapshot".
- **The de-jargoned label interpolated jargon.** The run log says "Held because: %1$s", and every value it can carry still said "Burst limit exceeded (the per-profile window)". All six admission reasons were rewritten, in the resources and in the hardcoded English fallback that shadows them.
- **Two parallel English copies had drifted.** `EnglishAutomationLintStrings` and `EnglishExecutionAdmissionStrings` duplicate user copy as Kotlin defaults. The pass rewrote the resources and left both serving the old wording to any caller that takes the default. Both are now in step, with a comment saying so.
- **Six strings the pass walked past** still said binding, transport, slots, guard, or forbidden write, several of them on screens whose neighbours had just been rewritten. One of them also carried an em dash, which the writing rule forbids outright.
- **Two rewrites were rule-of-three lists**, which is itself one of the tells the rule names.

Four test assertions pinned the old wording. Each moved to the new spelling rather than being loosened to a substring both versions would satisfy.
