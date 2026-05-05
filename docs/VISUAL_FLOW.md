# Visual Flow Baseline

Last updated: 2026-05-05

OpenTasker v0.2.24 added a read-only Flow tab backed by a pure graph model. The current unreleased L1 work keeps the graph layout read-only while adding node deep links, conditional action visualization, a compact scrollable lane overview, screen-reader summaries, and mutation shortcuts that reuse existing pickers.

## Scope

- Profiles become graph roots.
- Contexts feed profile activation as context nodes.
- Enter tasks and optional exit tasks are separate lanes.
- Task actions are rendered as ordered step nodes.
- Missing task references and empty lanes are surfaced as warnings.
- Profile, context, task, action, and missing-reference nodes carry typed targets.
- Selectable nodes deep-link to the existing profile, context, task, or action dialogs; missing task references open the owning profile for repair.
- Conditional actions carry first-class condition text and render `if ...` incoming edge labels plus compact node markers.
- Each profile graph includes a horizontally scrollable lane overview for context, profile, enter-task, and exit-task lanes.
- Graph cards and node surfaces expose deterministic accessibility labels through Compose semantics.
- Add Context and Add Step commands open the existing context/action pickers for the graph profile or task lane.

## Non-Goals

- No drag/drop editing.
- No branching editor.
- No parallel/subflow authoring UI.
- No replacement for the existing profile and task editors.

## Model

`AutomationFlowGraphBuilder` converts active `Profile` and `Task` domain models into:

- `AutomationFlowNode` entries for profile, context, task, action, and missing-reference nodes.
- `AutomationFlowEdge` entries for context requirements, enter/exit task links, and action order.
- `AutomationFlowTarget` entries for nodes that can open an existing editor.
- Conditional action metadata used by incoming edge labels and node badges.
- Accessibility summaries for whole graphs plus profile/context/task/action node labels.
- Warning strings for incomplete graph inputs.

The model is kept outside Compose so later graph editing, export previews, and tests can reuse it.

## Next Flow Work

1. Add future flow-control branch/subflow visualization once those action types exist.
2. Add zoom gestures only if the scrollable lane overview proves useful on real profile data.
3. Validate the summaries against real complex graphs before exposing drag/drop editing.
4. Add drag/drop mutation only after picker-backed graph edits are stable against real profile data.
