# Template Expressions

OpenTasker v0.2.30 adds a pure template expression baseline for future action argument expansion and debugger UI work.

The engine is implemented in `TemplateExpressionEngine` and evaluates `{{ ... }}` expressions against caller-provided task, event, global, and array scopes. It is intentionally separate from the active `%var` runtime path until every action/run-log surface can consume expansion traces safely.

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

The active task runner still uses the existing `%var` `VariableStore` expansion path. The v0.2.30 baseline provides the parser/evaluator, trace model, and regression coverage needed before wiring template expressions into action arguments, variable debugging, and per-step expanded-argument run logs.
