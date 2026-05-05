# Shizuku Readiness

OpenTasker v0.2.26 adds a Shizuku readiness baseline for future elevated actions. This is a status and planning surface only; it does not execute privileged work.

## Active Scope

- Declares package visibility for the Shizuku manager package, `moe.shizuku.privileged.api`.
- Detects whether the Shizuku manager is installed through `ShizukuPowerBackend.inspect(context)`.
- Shows an optional "Shizuku power mode" row in Setup. Optional rows do not count against required setup progress.
- Adds capability hints to elevated-action candidates such as airplane mode, mobile data, screenshots, reboot, screen off, and wake.
- Keeps all elevated candidates blocked by `ActionCapabilityRegistry`.

## Non-Goals

- No Shizuku API dependency is linked.
- No Shizuku permission request is made.
- No shell command or elevated binder call is executed.
- No restricted action is enabled because Shizuku is installed.
- No F-Droid or Play distribution claim is made for privileged execution.

## Next Work

Before OpenTasker can ship actual Shizuku-backed actions, the implementation needs:

1. A reviewed Shizuku API dependency and distribution-policy check.
2. Explicit user opt-in separate from normal setup readiness.
3. Permission request and revocation handling.
4. A small isolated backend interface for elevated operations.
5. Per-action allowlisting with clear user-facing risk copy.
6. Run-log audit entries for every elevated request, result, and failure.
7. F-Droid reproducibility and Play policy review for any privileged capability claims.

Core automations must remain functional without Shizuku.
