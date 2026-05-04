# Locale Plugin Host

OpenTasker includes a conservative Locale/Tasker-compatible plugin host baseline for setting plugins.

## Supported in v0.2.14

- Discovers packages that expose Locale edit-setting or edit-condition activities.
- Adds the `plugin.locale.fire` action for explicit setting-plugin dispatch.
- Sends `com.twofortyfouram.locale.intent.action.FIRE_SETTING` only to the configured package.
- Accepts a string-only JSON object and converts it into the Locale bundle extra.
- Adds a short blurb extra for plugin/user visibility.
- Enforces package-name validation, a 16 KB bundle JSON limit, and a 1-30 second timeout wrapper.

## Not yet supported

- Interactive plugin configuration activity results.
- Condition query execution and result parsing.
- Third-party plugin allowlists/denylists.
- Plugin-provided variable expansion or progress callbacks.

## Safety rules

- Never dispatch an implicit plugin broadcast.
- Never accept nested JSON, parcelables, serialized objects, or arbitrary extras from user input.
- Surface plugin package and requested permissions before users enable plugin-backed profiles.
- Treat plugin execution as third-party code and keep it behind explicit user configuration.
