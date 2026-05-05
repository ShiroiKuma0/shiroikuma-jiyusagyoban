# Locale Plugin Host

OpenTasker includes a conservative Locale/Tasker-compatible plugin host baseline for setting and condition plugins.

## Supported in v0.2.52

- Discovers packages that expose Locale edit-setting or edit-condition activities.
- Discovers explicit setting and condition broadcast receiver permission metadata for disclosure.
- Resolves explicit edit-setting and edit-condition configuration activities.
- Parses successful configuration results into deterministic string-only JSON and bounded blurb text.
- Adds the `plugin.locale.fire` action for explicit setting-plugin dispatch.
- Adds the `plugin.locale.query` action for explicit condition-plugin ordered broadcasts.
- Sends `com.twofortyfouram.locale.intent.action.FIRE_SETTING` only to the resolved receiver component in the configured package.
- Sends `com.twofortyfouram.locale.intent.action.QUERY_CONDITION` only to the resolved receiver component in the configured package.
- Parses Locale result codes `16` satisfied, `17` unsatisfied, and `18` unknown.
- Resolves `unknown` with a bounded best-effort last-known-state cache for the same plugin package and bundle.
- Treats `unknown` without matching history as `unsatisfied`.
- Can store the condition result as `satisfied`, `unsatisfied`, or `unknown` in a task variable.
- Accepts a string-only JSON object and converts it into the Locale bundle extra.
- Adds a short blurb extra for plugin/user visibility.
- Enforces package-name validation, a 16 KB bundle JSON limit, and a 1-30 second timeout wrapper.

## Not yet supported

- Interactive plugin configuration activity results.
- Host handling for plugin `REQUEST_QUERY` broadcasts.
- Third-party plugin allowlists/denylists.
- Plugin-provided variable expansion or progress callbacks.

## Safety rules

- Never dispatch an implicit plugin execution broadcast.
- Fail closed if a package does not expose exactly one execution receiver for the requested Locale action.
- Fail closed if a package does not expose exactly one configuration activity for the requested Locale edit action.
- Never accept nested JSON, parcelables, serialized objects, or arbitrary extras from user input.
- Never preserve nested, parcelable, serialized, null, or arbitrary object values from plugin configuration results.
- Surface plugin package and requested permissions before users enable plugin-backed profiles.
- Treat plugin execution as third-party code and keep it behind explicit user configuration.
