---
name: task-spec
description: Format an OpenTasker task for 白い熊 to build by hand, as a clear tabular spec — each action given by its picker fold-path (Category => Action Name) followed by its fields in the editor's display order. Use whenever handing 白い熊 a task / sub-task to enter in 白い熊 自由作業盤 (e.g. test tasks, examples, repro steps), or when 白い熊 asks for a task "in the spec format".
---

# Giving 白い熊 a task in tabular spec format

When you hand 白い熊 a task to build in 白い熊 自由作業盤, write it so it maps 1:1 onto the app's
screens — no prose, no guessing. Two rules:

1. **Address each action by its fold-path** exactly as it appears in the **Add action** picker:
   `Category => Action Name` (e.g. `Notification => Show Notification`, `Flow => Run Task`). The
   category is the picker's group header; the name is the row.
2. **List the action's fields in the order the editor shows them.** That order is always:
   **Action label** first, then the metadata fields top-to-bottom, then the dynamic
   **Parameters** / **Return values** rows (Run Task / Return Values only), then **Continue on
   error** and **Condition** — and you only write a line for a field you're setting.

## Template

```
Task: <name>            (Project: <project>, Priority: <0-10>, Collision: <abort new|abort existing|run both|wait>)

1. <Category> => <Action Name>
   Action label: <label>
   <Field label>: <value>
   <Field label>: <value>

2. <Category> => <Action Name>
   ...
```

- Number the actions in execution order. Indentation under a header = that action's fields.
- For the dynamic editors, use a `Parameters:` / `Return values:` block of `name = value` rows.
- Only include `Continue on error: yes` or `Condition: <expr>` when set (default off / none).
- Values may use `%VAR` (globals UPPERCASE, locals lowercase), `{{ param.name }}` (a called task's
  parameter), `%@name` (terse param), and `{{ global.x }}` / `{{ task.x }}` / `{{ event.x }}`.
- Conditions support `==  !=  <  >  <=  >=` (operands are expanded first), else plain truthiness.

## Action field reference (display order)

Only the actions used most often / in examples. The rule above covers any action: label, then the
fields as the editor lists them.

| Fold-path | Fields, in order |
| --- | --- |
| `Flow => Run Task` | Action label · Task id or name · Store results into (prefix) · **Parameters** (name = value…) · Continue on error · Condition |
| `Flow => Return Values` | Action label · **Return values** (name = value…) |
| `Flow => Fail` | Action label · Error message |
| `Flow => If` | Action label · Condition |
| `Flow => Else` / `Flow => End If` | Action label |
| `Flow => For Each` | Action label · Array variable name · Item variable name |
| `Flow => Wait` | Action label · Milliseconds |
| `Notification => Show Notification` | Action label · Title · Message · Channel · Persistent · Tag · ID · Button 1 label · Button 1 task · Button 2 label · Button 2 task · Button 3 label · Button 3 task |
| `System => Log Message` | Action label · Message |
| `Variable => Set Variable` | Action label · Variable name · Value |
| `App => Send Intent` | Action label · Intent action · Target package · Component class · Data URI · MIME type · Dispatch target · Extra 1 key/value … · Intent flags |

### Run Task results

A `Flow => Run Task` with `Store results into (prefix) = g_` writes the called task's named
**Return Values** to `%g_<name>`, plus always `%g_ok` (`true`/`false`) and `%g_error`. Use an
UPPERCASE prefix (e.g. `G_`) if you want those to be global / visible in the Vars tab.
