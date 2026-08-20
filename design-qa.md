# Design QA

## Source of truth

The ten visual references live in `docs/design/mockups/`. They define the page hierarchy, AMOLED palette, sage accent, coral warning color, compact row density, five-item navigation, full-width primary actions, and a maximum 12 dp corner radius.

## Comparison setup

- Reference canvas: 853 x 1844 pixels, generated for a 390 x 844 dp mobile layout.
- Implementation capture: 1080 x 2400 pixels from a Pixel 7 API 35 emulator at 420 dpi.
- Review normalization: both sides were normalized to 853 x 1844 pixels so hierarchy, spacing, density, and component proportions could be judged together.
- Combined evidence: `docs/design/qa/compare-profiles.png`, `compare-tasks.png`, `compare-flow.png`, `compare-setup.png`, and `compare-settings.png`.
- Contact sheet: `docs/design/qa/comparison-contact-sheet.png`.

Each comparison places the visual reference on the left and the emulator implementation on the right.

## Comparison history

### Round 1

The app shell matched the direction, but task and profile cards exposed too many secondary actions. Setup and Settings also retained large nested cards that pushed important controls below the first viewport. Flow opened with an invariant editor instead of the automation path.

Changes made:

- Moved profile and task secondary actions into overflow menus.
- Converted task actions to numbered divider rows with direct edit affordances.
- Added the readiness progress treatment and compact metadata.
- Replaced Setup permission cards with status rows and inline actions.
- Reworked the first Settings controls into compact rows and collapsed backup and script details.
- Removed the invariant panel from the Flow page and kept the generated automation path first.
- Applied the 12 dp radius ceiling across the Material shape scale.

### Round 2

The combined comparisons show the same dominant hierarchy, palette, navigation model, control density, and page-level action placement. All primary controls remain usable with real application data.

## Findings

- P0: none.
- P1: none.
- P2: Profiles keeps a search field because real workspaces can contain many profiles.
- P2: Setup counts and row labels reflect the emulator's actual permission state instead of the sample values in the reference.
- P2: Settings retains OpenTasker's advanced recovery and integration controls below the compact appearance rows. Those controls are collapsed where practical so the first viewport remains close to the reference.

## Interaction checks

- Primary navigation switches between Profiles, Tasks, Run Log, and Setup.
- More opens Variables, Flow, Scenes, Inspector, Diagnostics, and Settings.
- Task run and overflow actions remain available.
- Profile toggles and overflow actions remain available.
- Theme selection, backup expansion, and permission actions expose working controls.
- The page-level create actions remain full width and reachable above navigation.

## Final result

Passed. No critical or major visual mismatch remains in the reviewed states.
