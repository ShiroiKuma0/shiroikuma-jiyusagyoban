# Visual Flow Baseline

Last updated: 2026-05-05

OpenTasker v0.2.24 added a read-only Flow tab backed by a pure graph model. The current unreleased L1 work keeps that view read-only while adding node deep links and conditional action visualization.

## Scope

- Profiles become graph roots.
- Contexts feed profile activation as context nodes.
- Enter tasks and optional exit tasks are separate lanes.
- Task actions are rendered as ordered step nodes.
- Missing task references and empty lanes are surfaced as warnings.
- Profile, context, task, action, and missing-reference nodes carry typed targets.
- Selectable nodes deep-link to the existing profile, context, task, or action dialogs; missing task references open the owning profile for repair.
- Conditional actions carry first-class condition text and render `if ...` incoming edge labels plus compact node markers.

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
- Warning strings for incomplete graph inputs.

The model is kept outside Compose so later graph editing, export previews, and tests can reuse it.

## Next Flow Work

1. Add future flow-control branch/subflow visualization once those action types exist.
2. Add zoom/pan or a canvas view only after the read-only graph proves useful on real profile data.
3. Add accessibility labels and text summaries for complex graphs before exposing drag/drop editing.
4. Add mutation commands only after graph targets and summaries are stable against real profile data.
