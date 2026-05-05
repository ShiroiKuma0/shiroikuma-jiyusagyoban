# Visual Flow Baseline

Last updated: 2026-05-05

OpenTasker v0.2.24 adds a read-only Flow tab backed by a pure graph model. This is the first L1 slice: it makes profiles easier to inspect without replacing the existing list/form editor.

## Scope

- Profiles become graph roots.
- Contexts feed profile activation as context nodes.
- Enter tasks and optional exit tasks are separate lanes.
- Task actions are rendered as ordered step nodes.
- Missing task references and empty lanes are surfaced as warnings.

## Non-Goals

- No drag/drop editing.
- No branching editor.
- No parallel/subflow authoring UI.
- No replacement for the existing profile and task editors.

## Model

`AutomationFlowGraphBuilder` converts active `Profile` and `Task` domain models into:

- `AutomationFlowNode` entries for profile, context, task, action, and missing-reference nodes.
- `AutomationFlowEdge` entries for context requirements, enter/exit task links, and action order.
- Warning strings for incomplete graph inputs.

The model is kept outside Compose so later graph editing, export previews, and tests can reuse it.

## Next Flow Work

1. Add selectable graph nodes that deep-link to the relevant profile, context, task, or action editor.
2. Add branch/condition visualization for action conditions and future flow-control actions.
3. Add zoom/pan or a canvas view only after the read-only graph proves useful on real profile data.
4. Add accessibility labels and text summaries for complex graphs before exposing drag/drop editing.
