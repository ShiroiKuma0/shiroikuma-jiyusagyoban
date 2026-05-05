# Termux Script Readiness

OpenTasker v0.2.27 adds a Termux scripting readiness baseline for future "Run Script" support. This is not a script runner yet.

## Active Scope

- Declares package visibility for Termux (`com.termux`) and Termux:Tasker (`com.termux.tasker`).
- Detects whether both packages are installed through `TermuxScriptBackend.inspect(context)`.
- Shows an optional "Termux script bridge" row in Setup. Optional rows do not count against required setup progress.
- Adds `script.termux.run` to the action metadata registry so the future action has a stable ID and form shape.
- Registers a runtime `TermuxScriptAction` that fails explicitly if invoked through imported or legacy data.
- Blocks `script.termux.run` in `ActionCapabilityRegistry`, so the normal UI cannot add it yet.

## Non-Goals

- No `com.termux.permission.RUN_COMMAND` permission request is declared or requested.
- No Termux `RUN_COMMAND` service intent is dispatched.
- No Locale/Termux:Tasker plugin fire request is built for scripts.
- No arbitrary command, shell, or embedded JavaScript execution is provided.
- No stdout, stderr, exit code, or output-variable parsing is implemented.

## Contract Notes

The Termux `RUN_COMMAND` integration requires the sender to hold `com.termux.permission.RUN_COMMAND`. Termux:Tasker also requires the host app to have that permission before plugin commands can run. Termux:Tasker stores normal plugin scripts under `~/.termux/tasker`; execution outside that directory depends on Termux properties and has higher risk.

Primary references:

- Termux RUN_COMMAND intent: <https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent>
- Termux:Tasker setup and script directory: <https://github.com/termux/termux-tasker/blob/master/README.md#setup-instructions>

## Next Work

Before OpenTasker can enable script execution, the implementation needs:

1. Explicit user opt-in separate from normal setup readiness.
2. Manifest and onboarding support for `com.termux.permission.RUN_COMMAND`.
3. A strict script allowlist, initially limited to `~/.termux/tasker` names.
4. A dispatch backend isolated from the generic action registry.
5. Bounded stdout, stderr, and exit-code capture with redaction limits.
6. Output variable mapping that cannot overwrite protected variables silently.
7. Run-log audit entries for every script request, result, timeout, and failure.
8. F-Droid and Play policy review before making public script-execution claims.
