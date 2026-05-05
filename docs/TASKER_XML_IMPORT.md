# Tasker XML Import Baseline

OpenTasker imports Tasker XML through a two-step migration path:

1. Parse Tasker XML into an `OpenTaskerBundle`.
2. Review the migration report before writing the bundle to Room.

The baseline parser supports common Tasker `<Task>`, `<Profile>`, and `<Variable>` structures. It maps a small safe action set (`notify.show`, `flow.wait`, `var.set`, and `log`) and preserves all other Tasker action codes as `tasker.unsupported` placeholders so imported tasks fail explicitly instead of pretending to run.

The report includes:

- source task/profile/variable/scene counts
- mapped Tasker action codes
- unsupported Tasker action placeholders
- skipped profiles with missing entry tasks
- unsupported or lossy contexts
- scene exclusion warnings
- capability warnings from the OpenTasker bundle validator

Current limitations:

- No file picker or preview UI yet.
- No direct Room write from Tasker XML yet; import should go through the bundle preview path.
- Scenes are reported and skipped.
- Most Tasker action codes remain unmapped until each action can be translated safely.
