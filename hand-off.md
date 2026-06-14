# Hand-off — add a generic "Send Intent" action to 白い熊 自由作業盤

Run this from `~/git/shiroikuma-jiyusagyoban` in a fresh chat. Read `CLAUDE.md` and
`.claude/skills/build-apk/SKILL.md` first.

## Why this fork exists

`白い熊 自由作業盤` (`shiroikuma.jiyusagyoban`) is our fork of
[OpenTasker](https://github.com/SysAdminDoc/OpenTasker), a FOSS Tasker-style automation app. Upstream
can launch an app or open a URL, but it has **no action that fires an arbitrary Android intent with a
custom action, explicit component, and typed extras**. That gap is the whole reason for the fork: we
add one **generic "Send Intent" action** so an OpenTasker task can drive any app's intents — first and
foremost the token-gated automation intents exposed by our sister app **`白い熊 GNU Jami`
(`shiroikuma.jami`)**: send a message, place a call, open a conversation.

State at hand-off: the fork is fully set up (rename, version tail, signing, skills, black-yellow icon)
and the first build (`0.2.60+1`) is done. This task is the first **feature**.

## The task

Add a `SendIntentAction` to OpenTasker's action framework, wire it into the runtime registry and the
editor-metadata registry, and add package visibility so explicit intents to other apps resolve on
Android 11+. Then build and test it by firing the Jami `SEND_MESSAGE` intent end-to-end.

## OpenTasker's action framework (already mapped)

- `app/src/main/java/com/opentasker/core/engine/Action.kt` — the interface is tiny:
  ```kotlin
  interface Action {
      val id: String
      val category: ActionCategory          // enum: SETTINGS, NOTIFICATION, FILE, NET, MEDIA, APP, VARIABLE, FLOW, SYSTEM, PLUGIN
      suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult   // Success / Failure(msg) / Skip
  }
  ```
  `ActionContext` gives `ctx.app` (Context), `ctx.variables`, `ctx.logger`. `ActionRegistry.register(action)`
  registers an impl by id.
- `app/src/main/java/com/opentasker/core/actions/AppActions.kt` — existing APP-category actions
  (`LaunchAppAction` = `app.launch`, `OpenUrlAction` = `url.open`, etc.). Model the new action here or in a
  new `IntentActions.kt`.
- `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt` — `ActionField` / `FieldType`
  (TEXT, NUMBER, DROPDOWN, CHECKBOX, MULTILINE) + `ActionMetadataRegistry` + `registerActionMetadata()`
  builds the editor form. Add a metadata entry so the new action gets editable fields.
- **Find the built-in registration bootstrap** — grep for where `ActionRegistry.register(` and
  `registerActionMetadata()` are called at app startup (e.g. an `Application.onCreate` or a
  `registerBuiltInActions()` function) and add ours alongside the existing ones. Don't assume; locate it.

## Suggested implementation

`SendIntentAction` (id e.g. `intent.send`, category `APP`), args map:
- `action` — intent action string (e.g. `shiroikuma.jami.action.SEND_MESSAGE`, or
  `android.intent.action.VIEW`).
- `package` — target package (e.g. `shiroikuma.jami`).
- `class` — optional explicit component class (e.g. `cx.ring.automation.AutomationActivity`).
- `data` — optional data URI (e.g. a `jami-cmd://…` deep link).
- `mime` — optional MIME type.
- `target` — DROPDOWN: `activity` (default) / `foreground-service` / `service` / `broadcast`.
- N string extras — fixed slots `extra1_key`/`extra1_value` … `extra6_key`/`extra6_value`
  (mirror the notification action's button slots), all sent as string extras.
- Optional `flags` (e.g. always add `FLAG_ACTIVITY_NEW_TASK` for the activity target).

Build the `Intent`, set action/component/data/type/extras, dispatch per `target`
(`startActivity` / `startForegroundService` / `startService` / `sendBroadcast`), return
`ActionResult.Success` or `Failure(message)`. Note Android's boolean-extra caveat: extras here are all
strings; the Jami video flag works via the deep link (`?video=1`) or by sending the
`PLACE_VIDEO_CALL` action, so string-only extras are fine for v1.

Then register: `ActionRegistry.register(SendIntentAction())` and an `ActionMetadata(...)` entry in
`registerActionMetadata()`.

## Manifest — package visibility (Android 11+)

`AndroidManifest.xml` has a `<queries>` block. Add our sister app so explicit intents resolve:
```xml
<queries>
    <package android:name="shiroikuma.jami" />
    <!-- …existing entries… -->
</queries>
```
(For a truly generic action you may also want a broad `<intent>` query, but pin the Jami package at minimum.)

## The Jami target contract (what to fire)

The Jami automation surface is token-gated (enable + copy the token in Jami's
**Settings → Appearance → Automation**). Full reference: `~/tmp/jami-automation-intents.org`.

- Component: `shiroikuma.jami` / `cx.ring.automation.AutomationActivity` (note: applicationId
  `shiroikuma.jami`, but the class namespace is `cx.ring` — use the fully-qualified class).
- Actions: `shiroikuma.jami.action.SEND_MESSAGE | PLACE_CALL | PLACE_VIDEO_CALL | OPEN_CONVERSATION`.
- Extras: `account` (or `default`), `peer` (`jami:<hex>` / `swarm:<id>` / `sip:`), `text`, `token`.
- Or deep link (Send Intent VIEW + data):
  `jami-cmd://send/<account>/<peer>?text=<urlenc>&token=<token>`.

## Done = 

A "Send Intent" action selectable in the OpenTasker editor with the fields above, that can fire the Jami
`SEND_MESSAGE` intent and actually deliver a message on-device. Build with the **build-apk** skill;
build-only until 白い熊 says "Push".
