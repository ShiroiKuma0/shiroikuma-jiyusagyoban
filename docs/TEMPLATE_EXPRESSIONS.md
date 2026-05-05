# Template Expressions

OpenTasker v0.2.33 includes bounded template expression support for action arguments and per-expression run-log trace reporting.

The engine is implemented in `TemplateExpressionEngine` and evaluates `{{ ... }}` expressions against caller-provided task, event, global, and array scopes. `TaskRunner` applies legacy `%var` expansion first, then applies template expansion to action arguments, and stores sanitized summaries, warnings, and per-argument expression traces in `ActionExecutionTrace`. Run-log diagnostics parse those summaries into structured fields so the UI can show expanded arguments, source scopes, expression values, and template warning counts separately from the action result message.

## Scope Model

Unqualified names resolve in this order:

1. Task scope
2. Event scope
3. Global scope
4. Array scope

Explicit prefixes pin lookup to a scope:

```text
{{ task.name }}
{{ event.packageName }}
{{ global.owner }}
{{ array.items[0] }}
```

The `%name` spelling is accepted as a convenience for simple variable names, but new template docs should prefer `{{ name }}`.

## Supported Expressions

Variable expansion:

```text
Hello {{ name }}
```

Default fallback:

```text
Hello {{ name | default:"there" }}
```

String functions:

```text
{{ title | trim | upper }}
{{ packageName | lower }}
```

Math functions:

```text
{{ count | add:1 }}
{{ battery | div:2 | round }}
{{ seconds | floor }}
```

Arrays:

```text
{{ items[0] }}
{{ items[#] }}
{{ items | join:", " }}
```

JSON paths from a scoped JSON string:

```text
{{ payload.user.name }}
{{ payload.items[0].label }}
{{ payload.items[#] }}
```

## Safety Boundaries

The baseline is intentionally small and fail-closed:

- No user code execution.
- No shell commands.
- No regex functions in this template engine.
- Unknown functions preserve the original `{{ ... }}` token and emit a warning.
- Missing values expand to an empty string unless a `default` pipe is present.
- Template length, expression length, expression count, function chain length, JSON depth, resolved value length, and final output length are bounded.
- Each expansion emits a trace with the raw token, normalized expression, source scope, functions, value, and first warning.

## Current Runtime Status

Action arguments support template expansion at runtime. Run-log trace rows show sanitized expanded values only for arguments that used template expressions, redacting sensitive argument names such as `token`, `key`, `secret`, `cookie`, and `password`. Individual expression lines are persisted under each action trace and include the argument name, source scope, expression, resolved value, and optional warning.

Conditions still use the legacy `%var` condition evaluator. Condition-template adoption and regex-specific template policy remain future L7 work.
