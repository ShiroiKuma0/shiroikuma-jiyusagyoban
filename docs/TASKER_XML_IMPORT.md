# Tasker XML Import

OpenTasker imports Tasker XML through a two-step migration path:

1. Parse Tasker XML into an `OpenTaskerBundle`.
2. Review the migration report before writing the bundle to Room as disabled profiles.

The baseline parser supports common Tasker `<Task>`, `<Profile>`, and `<Variable>` structures. It maps a small safe action set (`notify.show`, `flow.wait`, `var.set`, and `log`) and preserves all other Tasker action codes as `tasker.unsupported` placeholders so imported tasks fail explicitly instead of pretending to run.

The Profiles screen exposes this through Android's document picker. Selected XML is read with a 4 MB limit, parsed in the background, and shown in a confirmation dialog before any Room write happens. Confirmed imports reuse `OpenTaskerBundleRepository`, remap IDs, upsert variables, and create imported profiles disabled by default so actions, contexts, and capabilities can be reviewed before enabling them.

The preview includes:

- source task/profile/variable/scene counts
- import task/profile/variable counts
- mapped Tasker action codes
- unsupported Tasker action placeholders
- skipped profiles with missing entry tasks
- unsupported or lossy contexts
- scene exclusion warnings
- capability warnings from the OpenTasker bundle validator

Current limitations:

- Scenes are reported and skipped.
- Most Tasker action codes remain unmapped until each action can be translated safely.
- Import does not request new permissions automatically; setup remains explicit through the normal checklist and capability review.
