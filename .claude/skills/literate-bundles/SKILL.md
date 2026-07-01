---
name: literate-bundles
description: HARD RULE for every OpenTasker JSON bundle (or any JSON) produced for 白い熊 — it must arrive already documented as a "literate programming" artifact: useful per-action labels, a per-task note, and full variable docs at the point of definition. Apply whenever generating/editing an importable bundle (tasks/profiles/variables) or any JSON 白い熊 will read. NOT an opt-in formatting pass — bundles are wrong if shipped uncommented.
---

# Literate bundles — comment everything useful, nothing pointless

白い熊's rule (2026-06-29): **anything you produce in JSON must arrive already commented, descriptively
and usefully** — so a task reads like a literate-programming document. This is not a later polish step;
an uncommented bundle is incomplete.

## The two anti-goals (hold both at once)
1. **Document what isn't self-evident** — purpose, units, defaults, ranges/floors, side effects, “needs X
   permission”, why-this-value. The reader should understand the task *from reading it*, without asking.
2. **Never comment for the sake of it.** No labels that just restate the action name/args. If a comment
   wouldn't tell the reader anything they can't see, **omit it** — visual flooding is a failure too.
   Repetitive boilerplate (a run of identical `profile.toggle` / `flow.if` / `flow.endif`) gets **one**
   summarizing label on the first, the rest bare.

## Where comments live (OpenTasker schema)
- **`ActionSpec.label`** (the editor's "Action label", shown first) — the per-action comment. Set it only
  where it aids legibility (the substantive action, a non-obvious value, a permission requirement).
- **`itemMeta` `note` (+ `noteExpanded: true`)** — the per-task note. **Every task gets one**: a one-line
  literate summary of what the task does / its trigger.
- **`Project.description`** — one line on the project's purpose.
- **Variables have no description field** → document each variable **in full on its `var.set` action label**
  at the point it's defined (usually the `…の設定`/`[01]` task): name — meaning, default, range/floor (with
  the *source* of any bound, e.g. “floor 40 = ViewConfiguration.getDoubleTapMinTime”), effect. The task's
  `note` then **points to those action labels** as the single source of truth — don't duplicate the prose.

## Bilingual (Japanese + English) — required
白い熊 (2026-06-29): every comment is **Japanese first, then its English translation on the line(s)
below**, separated by a blank line. Yes, this doubles the length — that's intentional (白い熊 uses it as
a language-learning exercise). Applies to every `label`, `note`, and `description`. Keep the two halves a
faithful parallel (same content, not a paraphrase). Format:

```
<日本語の説明>

<English translation>
```

(In a JSON string that's `"…日本語…\n\nEnglish…"`.) Skip the English only when the Japanese is itself a
bare identifier/value with nothing to translate.

## Style
- Comment in Japanese (白い熊's tasks are Japanese), each with the English translation beneath (above rule).
- Keep labels to a tight sentence or two. Put the authoritative detail in one place; reference it elsewhere.
- When folding into an **existing** task whose style is bare (e.g. `起動完了`'s unlabeled blocks), stay
  consistent: update the **task note** instead of labeling only your inserted lines (which would look odd).
- In the generator, make `label`/`note` first-class (e.g. `act(type, label=None, **args)`,
  `im(id, gid, pos, note=None)`) so commenting is the default path, not an afterthought.

## Ship every item in its group (never orphan it)
白い熊 (2026-07-01): when a bundle carries a task/profile/scene that lives in a **group**, the bundle MUST
carry that group so import files the item into its group — not into "Unfiled". (e.g. the 71/01/37 trio
belongs in 起動無効; a bundle shipping task 71 alone dropped it to Unfiled.)

Two parts, **both required**:
- **`groups[]`** — an `ItemGroupSpec` for each group an included item belongs to (`id` [bundle-local],
  `tab`, `projectId`, `name`, `position`, `expanded`, `parentGroupId`). Import MERGES groups by
  (tab, project, name), so shipping an existing group re-uses it in place (no duplicate, fold state kept).
- **`itemMeta[]`** — for each item, its `groupId` set to that group's bundle-local id (alongside the note).
  **Critical trap:** import UPSERTS itemMeta, so it CLOBBERS `groupId`. Every item already needs an
  itemMeta row for its note (above) — and an itemMeta emitted **without** a `groupId` sets the item's group
  to null → it jumps to Unfiled on import. So whenever you emit a note, set that item's `groupId` too.

Source the group + membership from the workspace mirror (`_globals/groups.json` + `_globals/item-meta.json`);
if the mirror is stale for the item, infer the group by name within its project (match the on-device group).
See [[trio-group-convention]].

See [[literate-bundles-rule]], the `task-spec` skill (action field order incl. Action label), and
[[clear-import-instructions]].
