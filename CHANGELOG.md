# Changelog — 白い熊 自由作業盤

This file carries **both** histories. The fork's own releases come first, newest first, each naming
the upstream commit it is built on; [OpenTasker](https://github.com/SysAdminDoc/OpenTasker)'s own
changelog follows below, verbatim and untouched.

Keeping our block strictly above upstream's own heading is not cosmetic: upstream inserts each new
release directly under that heading, so their insertions and ours never touch and this file merges
cleanly on a rebase instead of conflicting on every sync.

## 0.2.86.2026-08-11.g9b9d18dd+003 — 2026-08-12

**A lighter 4, and the Health Index bars now mean something.**

The 4 is `#F4511E`, an orange-red with black ink, up from the pure red it shipped as this morning.
It puts ΔE 21.3 between 4 and 5 where there were 14.5. The constraint on a lighter 4 turned out to be
the emerald at 2 rather than the dark red at 5 — a light red and a light green are the same colour to
a red-green reader, `#FF8080` measuring 1.5 against the 2 — and an orange-red survives being light
precisely because it carries yellow, the axis colour deficiency leaves alone. It also only became
available once the dark red moved to 5: against a pure red at 5 every orange in this range measured
under 3.

**The index's component bars are banded instead of tinted.** They were one blue running light at 0 to
dark at 100, so the shade said the same thing as the bar's length and the number at the end of the
row, a third time, in the one form that cannot be named — 白い熊 asked why one bar was darker and one
bluer, and there was no answer worth giving. Each bar now takes its colour from the index's OWN cut
points (85 / 70 / 55 / 40), the ones the headline has always printed "Good" from, and prints that
band word beside itself in the same colour. Reading down the card now says which component is holding
the index down — on the morning it was built, four bands were fine and Steps alone was Low.

Applying a total's bands to one component is an extension rather than a published rule; both are
0–100 on the same construction, which is what makes it defensible, and it is why the word carries the
claim and the colour only agrees with it. The headline word and the bands are one function now, so
they cannot drift apart.

## 0.2.86.2026-08-11.g9b9d18dd+002 — 2026-08-12

**The 1–5 scale runs best-first, and is a new set of colours.** 1 is now the best night and 5 the
worst, throughout: 白い熊's own 体感 rating, the published 実睡眠 and 夜間心拍 bands, and the
within-person step every other graded value uses. The cut points did not move — 7 h is still 7 h,
50 bpm still 50 bpm — only the numbers printed on them. **The ratings already on file are re-numbered
once** by `6 − n`, behind a run-once flag: the store holds bare integers with no scale marker in
them, so a rating written before the flip would otherwise have gone on meaning its own opposite, and
feeding the baseline and the adverse count that way.

The colours were settled over five rounds of rendered strips, with 白い熊 choosing the anchors and
every candidate measured before it was drawn: **yellow `#FFFF00`, emerald `#00D084`, blue `#1E5AFF`,
red `#FF0000`, dark red `#A00000`**. What the measurements ruled out is worth recording — pure green
`#00FF00` cannot sit beside pure yellow (ΔE 3.5 under deuteranopia), the whole middle of the orange
family is indistinguishable from a pure red, and violet cannot sit beside blue. 4 and 5 are
deliberately one family getting worse rather than two hues, so the bad end is read by lightness,
which no colour deficiency touches. Fills are solid with a paired ink instead of a 30 %-alpha wash
inside a ring, and nothing on the row is outlined any more.

**The scale legend is the same pill the table draws**, five of them stretched with the ⓘ to fill the
line, weighted by what each has to hold rather than into equal fifths.

**The reference-band panel is structured instead of prose.** Each rung of 実睡眠 and 夜間心拍 is now
its own coloured box, the exact cut points that land a night on it, and why that cut point is where
it is — inside a rounded yellow frame, at the info typography the rest of 「健康」 uses. 皮膚温 and
体感 keep their explanations of why they have no reference band at all.

## 0.2.86.2026-08-11.g9b9d18dd+001 — 2026-08-12

**Rebased onto upstream 0.2.86**, an audit-driven release: a deep multi-pass audit filed sixty
findings and upstream shipped all of them, plus one critical defect the audit itself missed. That
defect never reached this fork — upstream's `TaskRunner` compiled its array-reference regex with bare
closing braces, which desktop `java.util.regex` accepts and Android's ICU engine rejects, and because
it sits in a companion initializer the result was an `ExceptionInInitializerError` that stopped
*every* task from running on a real device. This fork keeps that helper at file level and had already
escaped the braces, so builds from 2026-08-10 on ran tasks normally throughout.

**Automations no longer fire on unrelated events.** A pulse advanced whenever any event reached an
EVENT context, whether or not that context was watching for it. Every EVENT context is subscribed to
every bridge, so a profile whose expression was already true for another reason activated on
unrelated traffic — `EVENT(nfc) OR STATE(wifi=Home)` ran on every notification while on that network
— and two OR'd EVENT leaves turned one physical event into two runs. Only an event a context
actually matches advances its sequence now. The per-invocation event snapshot the fork threads
through `ContextMatchUpdate.vars` is unchanged.

**A cooldown survives disabling its profile.** The persisted deadline was pruned against the
*enabled* profiles, so switching a profile off mid-cooldown deleted it while the in-memory
reservation stood; whether the cooldown still applied then depended on whether the engine happened
to restart before the profile came back on. Pruning is against the profiles that still exist.

**Widgets.** A widget or shortcut run is admitted by the engine's live admission controller instead
of a private in-memory one, so it respects the saturated-profile and circuit-breaker limits the
in-app Run button already documents as mandatory; a second tap while the same task is still running
is refused rather than starting a concurrent run with real side effects; and renaming or deleting a
task now asks the widgets to re-read it, instead of leaving a stale label and a tap that can only
answer "Task not found".

Upstream also finishes `TaskRunActivity` before the run so a long widget task cannot leave the
launcher under an invisible, touch-consuming window. **That one is deliberately not taken here.**
This fork passes `visibleActivity = true` precisely because the tap opened a real window, and that
is what lets a widget task play audio, dispatch a media key or change the volume on Android 17+,
which restrict those to a visible app. 話す時計 and the volume tasks depend on it; finishing first
would make the claim false. The reasoning is recorded at the call site.

**Smaller fixes taken:** `ping` resolves its target before demanding local-network access, so
pinging a public host no longer fails closed on Android 17 for a permission it never needed;
Wake-on-LAN now enforces the private-address premise its permanent capability gate rests on;
`sound.play` accepts only file and content URIs, so a remote sound can no longer bypass the
private-network policy the HTTP action enforces; the Tasker XML importer accepts `<v>` as well as
`val`/`value`, so a file this app exported and re-imported no longer drops every variable; the
foreground-service notification uses the app's own monochrome mark and sage accent rather than a
platform holo drawable; the action and context editors ignore a stray tap on the scrim, which had no
undo because nothing was saved yet; and the engine scope carries an exception handler, so a database
that misses its readiness deadline on a cold boot fails that launch instead of killing the process
into a watchdog crash loop. The fork's `SupervisorJob` and `Dispatchers.Main` are unchanged — the
engine drives scene overlays and the edge bar through `WindowManager`.

**Not applicable to this fork**, and recorded here so the gap is not mistaken for an oversight:
upstream's project-delete secret re-encryption (this fork never moves variables between projects),
its variable-mutation-lock/transaction ordering fix and the `LockedMutations` refactor around it
(the fork owns a different storage layer with a different DAO), the run-log SQL paging and
failed-load state, the resource-backed `UiMessage` architecture, and the JSON-paste import dialog.
The files upstream added for those — `UiMessages.kt`, `InlineNotice.kt`, `UiErrorMessageTest.kt` —
are removed; the fork defines its own `InlineNotice`.

`CHANGELOG.md`'s embedded copy of upstream's changelog is refreshed to upstream's current file. It
had gone stale at 0.2.82, which is why this file conflicted at all: the anchor the arrangement above
depends on no longer matched.

## 0.2.85.2026-08-11.g6c6f1aab+003 — 2026-08-11

**A volume key pressed while the phone is ringing now reaches the phone.** The grabber consumed a
single tap whenever the screen was on and re-injected it only when the screen was off — and a
ringing call lights the screen, so the one moment the key most needs to reach the system was exactly
the moment it was swallowed. Neither the framework nor the dialer ever learned it had been pressed,
and the volume panel opened over the call screen instead. A ringing flag now sits beside the
screen-state flag in the grabber, pushed over the same privileged path, and a short tap is
re-injected whenever either says so. The panel is suppressed for that same press at the source, so
`物理鍵 音量下単` never matches and nothing opens over the call. Long, double and triple presses are
untouched and stay ours.

The ringing state is read from the phone-state broadcast, seeded whenever the grabber binds so a
rebind mid-call cannot miss it, and falls back to the audio mode where the Phone permission is not
granted. One cost is unchanged: the re-injected press is synthesised through `input keyevent`, which
is asynchronous, so it lands a beat later than a real press.

**Rebased onto upstream 0.2.85**, whose own release fixed the build broken by its staged module
split, added a true-black AMOLED theme and an opt-in Material You, bounded the exported broadcast
target's database work, and put profile execution slots under one lock for every automation mode.
Four of its additions are deliberately not taken here — the API-36 AppFunctions prototype, the
release-update check, the automation-invariant screen, and the module split itself — each because it
contradicts a bridge, a version scheme or a build arrangement this fork already owns. The reasons are
recorded in the commits and in `settings.gradle.kts`.

## 0.2.84.2026-08-11.g9b75aac5+018 — 2026-08-11

**Only an Activity may now claim to have come to the front.** `TYPE_WINDOW_STATE_CHANGED` is fired by
anything that adds or replaces a window inside somebody else's screen — an app widget on the
launcher's desktop, a popup, a toast, and this app's own scene and bubble overlays — and every one of
them carries **its own** package, not the package of whatever is actually on screen. The
accessibility service took that at face value, so on a widget-dense desktop `%APP_PACKAGE` flipped to
a random widget's package within seconds of arriving at the home screen. Sampled every five seconds
against a launcher that held the only application window on the display, it read a calculator, then
the launcher, then this app itself, then the system settings.

**Everything keyed on the foreground app was therefore reading the wrong app, intermittently.** The
kanji clock's blacklist — the list of apps it must stay off — compared the launcher's entry against
whichever widget had spoken last, missed, and drew the clock strip across the one desktop it was
configured to avoid. It looked like a blacklist being ignored; it was a blacklist being asked the
wrong question, which is why it held for other apps and failed only where the widgets live. 回転's
per-app rotation modes and 通知明滅's foreground kill read the same signal and were wrong in the same
moments.

**The test is the event's class, not its package.** A real switch names an Activity that the package
itself declares; a widget or an overlay names a plain View class, which no manifest declares as an
activity. That answer is a manifest fact for as long as the app is installed, so it is cached per
class, and a rejection is logged once per class — the desktop's widgets would otherwise flood the log
with the same line every minute.

Measured on the device rather than argued: four minutes on the launcher with `%APP_PACKAGE` steady,
where it had previously flipped inside five seconds, and eight of eight samples agreeing with the
system's own resumed activity across a file manager, a notepad, a dictionary, the settings app and
home.

## 0.2.84.2026-08-11.g9b75aac5+017 — 2026-08-11

**The app can now find a band whose address it does not already know.** A sync is addressed by MAC
and needs no scan, so there was no way to *discover* a band at all: `健康の設定 -- [727][01]` simply
held the address, typed in once. That is fine until the band is replaced or factory-reset — the
address changes, every sync afterwards fails silently, and nothing on the phone can say what the new
one is. The new `band.scan` action listens for eight seconds with **no scan filter**, because
filtering on the band's own `fff0` service is the obvious shortcut and a trap: a band that keeps its
service UUID out of the advertisement would look exactly like an empty room. It then connects to the
strongest candidates and checks for `fff0`/`fff6`/`fff7` — the same gate `BandGattClient.open`
already puts a sync through, reused rather than re-implemented, and the only conclusive test there
is. It stops at the first device that answers.

**The verdict is never presented as more than it is.** `confirmed` means something answered on the
band's own characteristics. `likely` means it advertises `fff0`, or this model's name. `possible`
means the name merely looked plausible — and hands back no address at all. Every line of evidence is
printed beside the verdict, because that address is about to be typed into a setting every later sync
trusts. The scan writes no setting itself: the address comes back in a variable and 白い熊 decides
whether it goes into `01`.

**The band's advertised name was unknown to this repo until it was measured.** Nothing in `core/band`
ever needed it, since a sync connects by MAC. It is `Hume Band V2 A13A`, and those last four
characters are the last two octets of `D5:A7:06:DC:A1:3A` — so the prefix is stable across units and
the suffix identifies the unit. A replacement band can therefore be recognised by name before
anything connects to it, which is exactly the case the feature exists for.

**One window does the searching and the reporting.** It began as the batch progress panel handing
over to a summary dialog, which was wrong three ways at once: the panel blacked out the whole screen,
it never listed the devices it had already heard, and the dialog replaced the list at the moment it
became worth reading. The window is now a scene — mid-screen over a scrim, 560×640, filling up device
by device as they are heard and re-ranked on every tick, because a device's evidence changes as more
of its packets arrive (the name usually turns up in the scan *response*, a packet after the one that
first announced it). When the scan ends, that same window becomes the report in place. **中止** stops
the radio without closing the window; **閉じる** does both, so dismissing it can never leave the radio
listening behind it.

**The progress panel's running row turns.** A static `▶` beside a step that takes twenty seconds is
indistinguishable from a wedged one. The spinner runs on its own clock rather than on panel updates,
because the case that needs it is a task blocked inside a single long action — where by definition
nothing is arriving to redraw the row. Every long task that raises the panel gets it, 保存復元's
backup runs included.

**A build-time guard for a failure that only ever appeared on the phone.** Registering an action in
`ActionCatalog` is not enough to make it usable: it also has to be classified in
`AutomationSensitivityRegistry`, or every bundle containing it is refused at import as an "unknown
unclassified action", and it needs a capability entry or it is refused as "unsupported". `band.scan`
shipped, installed, ran, and passed the whole JVM suite before the transfer bridge rejected the first
bundle that used it. Three assertions now fail the build instead.

## 0.2.84.2026-08-11.g9b75aac5+013 — 2026-08-11

**Upstream sync: 2 commits, 18 files. Upstream did not move its own version literals**, so the pin
alone advances: `.g5c01f064` → `.g9b75aac5`, same base date. The build counter therefore keeps
running — `+012` → `+013`, `versionCode 860013` — because `versionCode` is the only thing an installer
compares.

**What landed from upstream.** The Home Assistant webhook action takes the Companion apps'
`message`/`data` notification envelope directly instead of only a hand-written JSON payload: fill the
two new fields and the action assembles `{"message": …, "data": {…}}` itself, refusing the pair if a
raw payload is also set. A `message` beginning `command_` is checked against the ~35 documented
Companion commands (`command_broadcast_intent`, `command_flashlight`, `command_dnd`, the `kiosk_*`
family, …), so a typo fails at parse time rather than posting a webhook that quietly does nothing.
`AutomationTargetContract` gained Home Assistant's field names (`message`, `data`,
`intent_package_name`, `intent_action`, `intent_extras`) as aliases beside the namespaced extras, so
an HA `command_broadcast_intent` can reach the automation receiver in HA's own vocabulary.

**The push bridge now speaks ntfy natively.** `PushContextEvents` accepts ntfy's unprefixed extra
names (`topic`, `id`, `title`, `message`, `message_bytes`) alongside the `com.opentasker.extra.*`
ones and listens on `io.heckel.ntfy.USER_ACTION` and `…MESSAGE_RECEIVED`, so an ntfy notification's
own `broadcast` action can drive an `event=push` context with no relay app in between. The
per-install token stays mandatory, the unauthenticated ntfy action is deliberately not a manifest
entry point, and the forwarded `base_url` / `time` / `tags` / `priority` / `attachment_*` metadata is
sanitized and length-capped before it reaches the event's variables.

**Upstream's new webhook fields arrived as resource ids the fork does not have.** Both new
`ActionField`s were declared with `R.string.catalog_action_home_assistant_webhook_field_*`; the fork
keeps action labels and hints as inline strings, so the metadata block conflicted. The fields are
re-added in the fork's own style with upstream's wording rather than dropped — `HomeAssistantWebhookAction`
merged cleanly on its own, so taking the conflict as a deletion would have left the parser able to
read `message`/`data` and the editor unable to offer them: a feature present in the engine and
unreachable from the UI.

**Release-truth provenance, adopted as schema only.** Upstream's gate now requires an annotated tag
per changelog release from v0.2.58 onward, matching `releaseTagCommit` and an ancestor of HEAD.
`tools/release-truth.json` takes the two new keys so the manifest matches the schema, but keeps the
fork's capability counts (172 actions, bundle schema 5, Room 27); the gate itself hangs only off
`verifyFdroidMetadata` and `localQualityGate`, neither of which `buildFork` runs, and the fork ships
no F-Droid metadata to verify. The upstream test asserting those keys stays retired here.

## 0.2.84.2026-08-11.g5c01f064+012 — 2026-08-11

**Upstream sync: 11 commits, 71 files. Upstream did not move its own version literals**, so the pin
alone advances: `.2026-08-11.g08847560` → `.2026-08-11.g5c01f064`. The build counter therefore keeps
running — `+011` → `+012`, `versionCode 860012` — because `versionCode` is the only thing an installer
compares, and resetting `N` while `appVersionCode` stands still would make the sync a downgrade.

**What landed from upstream.** Elevated actions now run through a versioned Shizuku AIDL user service
that rechecks exact argv inside the privileged process and unbinds on teardown; the fork takes the
service and its `reboot` path, which previously just refused. UnifiedPush registration became a real
distributor-neutral connector — discovery, SDK-versioned identity registration, RFC 8291 decryption,
endpoint persistence and delivery acknowledgement — with ntfy's standard JSON reaching `event=push`
and the legacy token broadcast still accepted. Scene-canvas elements announce their type, label,
position, size and selection state with custom select/nudge/resize actions, user-facing enum and error
copy moved behind resources, and the locale gate now rejects an empty locale directory by name instead
of skipping it.

**The action and context pickers gained search — on the fork's own labels.** Upstream's search filters
a localized triple it builds by resolving three `@StringRes` ids per item. The fork keeps names,
descriptions and categories as inline strings on `ActionMetadata` itself — some of them Japanese, and
far more actions than upstream ships — so the filter runs straight over the metadata instead, matching
display name, description or stable id (`file.read` finds the action as readily as "Read file" does).
Both catalogues are built once inside `remember` rather than rebuilt on every keystroke.

**Upstream deleted two sealed members the fork still uses, and git applied it cleanly.** Making
action and context removal immediate-and-undoable let upstream drop `DeleteTarget.ActionTarget` and
`ContextTarget`. The fork never touched those lines, so the deletion was not a conflict — it simply
landed, in a tree whose `ActiveAutomationUi` still routes both removals through the confirmation
dialog. Both members are restored with their exhaustive `when` branches. A clean merge is not evidence
that nothing was lost.

**The fork's own privileged actions stand.** Upstream rerouted airplane mode, mobile data, screen-off,
wake and screenshot through its new user service. The fork's implementations are the tested ones and
each is deliberately different: screen-off prefers the accessibility global action so it sleeps the
screen without locking the device, the airplane toggle keeps its best-effort `AIRPLANE_MODE` broadcast
because that broadcast is system-only and its failure used to fail an action whose setting had already
applied, mobile data uses `svc data` and needs no phone-state permission to read its own state, and
the screenshot stores the path it wrote. Four upstream UX changes are **declined** for now, each
entangled with a screen the fork has rewritten: undoable deletions, the new *Settings* destination
split out of Setup, the back-to-Profiles handler, and the IO-backed setup ViewModel.

**The suite says what the fork actually is.** Upstream swapped its "genuinely unsupported" exemplar
from `reboot` to `app.kill`, its polarity being the opposite of ours — the fork drives `app.kill`
through Shizuku, and keeps `reboot` unsupported because shell access is not enough for it and it wants
device-owner privilege. Three share/bundle capability tests are reverted to `reboot` with that reason
recorded inline. `UiEnumLabels` learned the fork's `PROGRESS` and `METEOR` scene element types and
reads action names from the metadata; the picker-search test was rewritten for the fork's shape, and
the two tests covering declined features were dropped. 1391 tests pass.

## 0.2.84.2026-08-11.g08847560+011 — 2026-08-11

**The 体感 rating was a night out of step, and 運動と回復 could not show it either way.**

**Upstream sync: 2 commits, both security.** MQTT TLS built its socket without a hostname, which
silently disables certificate hostname verification — any broker with a valid chain for *any* host
was accepted, credentials and payload included. The TLS layer now keeps the hostname as the logical
peer while the TCP connection stays pinned to the SSRF-vetted address, with `HTTPS` endpoint
identification and SNI set explicitly. And the exported Locale condition receiver checked its grant
only on the variable branch, so any app could ask "is profile 17 active" and read the answer off the
ordered-broadcast result code; the grant is now keyed per binding and checked before Room is touched.
The fork's own `deleteVariable` had lost that revocation at an earlier sync — `revokeAllForVariable`
had zero callers — so a grant outlived the variable it named.

**Ratings now attach to the night they describe, and the old ones were re-keyed.** The fix of
`+029` changed what a `yyyyMMdd` key *means* — from the day the answer was typed on to the start date
of the night it describes — and moved nothing already on file, so every stored rating read a night
late. On the morning of the 11th the answer typed on the 10th appeared as last night's, with no empty
slot for the new one. The one-time shift is calendar arithmetic and injective, flag-guarded because
the store cannot tell the two schemes apart by inspection and a second run would walk the history
backwards in silence. The read-back had drifted from the write too: `?.let {} ?: rating(today)` yields
null for "no recovery" and "no rating" alike, so an unrated night fell through to a key nothing writes
any more. Both sides go through one function now, and the row names the night instead of claiming
"Today" — which was wrong every single morning.

**運動と回復 shows what is stored.** It showed nothing: every banded value was reachable only through
the per-session cards, which render one per *marked* session, so with none marked the page was an
empty note, a bar grid and three anonymous dots — and a dot triple says the same thing for a night
rated 3 and a night never rated. The table is now driven by the union of the band's nights and the
stored ratings, because a rating whose date has no sleep session was counted in the baseline and
displayed nowhere at all.

**Heart rate and sleep are graded against published ranges, not 白い熊's own median.** A within-person
scale cannot call a six-hour habit short — six hours *is* that person's usual. Sleep uses the NSF
consensus with the AASM line breaking the tie inside "may be appropriate", so short and long are
deliberately asymmetric; heart rate uses Jensen's resting decades, with the caveat printed on screen
that those are daytime rates and a sleeping heart rate runs below them. Skin temperature gets no
absolute band and will not: the wrist sensor tracks the room at r = 0.961, so a threshold would be
grading the bedroom.

**One 1–5 colour vocabulary**, chosen by search against `PaletteCheck` rather than by eye — worst
adjacent pair ΔE 9.9 for a red-green reader against a target of 8, 16.3 in full colour against a floor
of 15. The band roles are not reused: `BAND_WARN` sits within a few ΔE of this theme's yellow ink,
which is why an amber value beside a yellow one read as one state twice. Step 1 is dark blood with a
pale ring rather than more red, because red and orange separate only by hue at chip size.

Column headings appear once instead of four labels a night, dates carry the year and weekday, week
rules run at Sun/Mon and Fri/Sat, and the way in from the 回復 card is a full-width pill with its own
counts — as a caption it was the smallest thing on a long card, and the whole record sat behind it.

**Tests: 1383**, 26 new — the migration's calendar arithmetic across month, year and leap boundaries;
every published band boundary; the deliberate short-versus-long asymmetry; monotonicity across the
heart-rate range; the palette clearing every hard gate; and a rating with no night still reaching the
table, which is the hole the whole session started in.

## 0.2.82.2026-08-10.ga481b77a+035 — 2026-08-10

**Recovery tasks and held-run replay — the last two things the 0.2.82 sync left stored but inert.**

**A failed task can now hand off to a recovery task.** The missing link started further back than
expected: the fork's `TaskRunner` produced no structured error at all, so there was nothing to hand
anyone. It now records the *first* unhandled failure — task, action id, index, type, message —
because a later failure from a `continueOnError` action further down would misname the cause.

On a failed run carrying such an error, the engine tries the profile's own recovery task, then the
global one. It runs **once**, cannot trigger its own recovery, and never falls back to the task that
just failed — that would re-run the failure with its own error as input. The failure arrives as
ordinary task-local variables, so the recovery task can branch on what broke instead of parsing a log
line. The admission lease is released first: a profile capped at one active run would otherwise
refuse the very task meant to diagnose it. What the recovery did is folded into the failed run's own
log entry. The profile editor gains a **Recovery task** picker now that it is enforced.

**A refused run is now a held run you can replay.** Under `overflowPolicy = LOG`, an admission
refusal writes a HELD row carrying a redacted, size-capped snapshot of what would have run, and the
Run Log offers **Replay** beside it. Replaying consumes the row first, and only a consume that
actually changed a row proceeds — the update is `WHERE id = :id AND held = 1`, so two taps cannot
both win and run the work twice. A row whose payload failed to encode is never marked held: a Replay
button that cannot work is worse than none.

**Tests: 1332.** The fallback selection order is extracted into a pure `fallbackCandidateIds` so the
decision is testable without a database — profile beats global, the same id is not tried twice under
two names, a task is never its own recovery. Three more pin the structured error itself: an unhandled
failure names the action that ended the run and the action after it does not execute; a failure
caught by `flow.catch` leaves nothing to recover from; a succeeding run carries no error.

Nothing here activates until a recovery task or a concurrency cap is set — with neither, the fallback
branch is skipped and no row is ever marked held.

## 0.2.82.2026-08-10.ga481b77a+034 — 2026-08-10

**Profile arbitration is enforced, and settable.** The 0.2.82 sync brought upstream's profile policy
into the database and left the engine ignoring it. This wires it in — and adds the editor it turned
out to be missing entirely.

**The editor first, because there wasn't one.** The fork had no profile-policy UI at all: the
`priority` field in the profile dialog is the *task* priority, a different, older thing. So none of
these values could be set, which would have made enforcing them dead code. The profile dialog now
carries an **Arbitration** section — priority, grace period, lifetime (Always / Until a date / Once,
with a date field), optional max-concurrent and burst caps, and whether a refused run is logged or
dropped quietly — each validated against the same bounds the engine normalises to.

**The engine.** `AutomationService` keeps the matched set and arbitrates on it. While a profile
matches it suppresses any matching profile of *strictly* lower priority — their runs are skipped and
logged naming the suppressor — and when it stops matching, exactly those profiles that nothing else
still outranks are released and activated. One-shot profiles are consumed inside a transaction, so
two contexts matching in the same instant cannot both win the single run; an expired profile is
disabled rather than re-evaluated for ever. A profile's own `maxActiveExecutions` and `burstLimit`
now reach the admission controller — they were being dropped, so only the global limits ever
applied — and `overflowPolicy` decides whether a refused run leaves a Run Log entry.

Grace periods and lifetime suppression needed no work here: they were already live in
`ProfileMatcherImpl`, which merged cleanly during the sync.

**The exit task is owed on admission, not on dispatch.** The first cut of this gated the exit task on
whether the enter side actually dispatched — which silently breaks a profile carrying *only* an exit
task, an ordinary way to write one. (Upstream gates it that way too.) The rule is now: a profile that
passed the lifetime and priority checks owes its exit task whether or not it had an enter task to
run, while one that was outranked never acted and has nothing to undo.

The release decision moved into `ProfileLifecyclePolicy.released(before, remaining)` so it can be
tested at all — the engine calling it is an Android Service. Four tests cover the release chain
(High → Middle → Low frees them one at a time, not all at once), that equal priorities never suppress
each other, that a profile which merely stopped matching is not "released", and that a disabled
profile outranks nothing.

**Nothing changes until a priority is set.** Every existing profile sits at 0, equal priorities never
suppress, and the caps are blank. Arbitrating equal priorities by database id would make every
profile mutually exclusive with every other one by default, which is why it does not.

**Still not wired:** `fallbackTaskId` remains stored and unenforced, and is deliberately absent from
the editor rather than offered as a control that does nothing. It and held-run replay both need
upstream's reworked execution helper (structured errors, ledger states, the journal), which is its
own job.

## 0.2.82.2026-08-10.ga481b77a+033 — 2026-08-10

**Upstream sync: 50 commits, 247 files.** The largest batch this fork has taken. Upstream did not
move its own version literals, so the pin alone advances: `.2026-08-08.gbd01eebb` →
`.2026-08-10.ga481b77a`. The build counter keeps running — see below.

**What landed from upstream.** Profiles gain priority, activation/deactivation grace periods, and
never/date/once lifetimes, plus per-profile admission limits and a fallback task — all stored, all
editable. Run logs gain the held/replay columns and an execution journal that survives process
death. `flow.try` now classifies every action for retry safety and requires the whole try body to
be safe before it retries, so a body of send-message-then-fetch-URL no longer re-sends the message.
`%FLOW_ERROR_CAUGHT` is finally `true` inside a `flow.catch`. `%count-1` expands `%count` again
instead of collapsing the whole token to empty. Cleartext MQTT must resolve every address to a
private one and connects to the address it vetted; jsoup moves to 1.23.1 for GHSA-pmhh-3w7g-xqp8.
Toolchain: Gradle 9.6.1, AGP 9.3.1, Kotlin 2.4.10.

**Fixed: the app died before its first frame on a regex desktop Java accepts and Android does not.**
The array-reference pattern ported for the `foreach` fix ended in a bare `}}`. Android's regex engine
is ICU, which reads `{` and `}` as interval-quantifier syntax and rejects a stray brace —
`Pattern.compile` threw at class-init and took the process down with *"Syntax error in regexp pattern
near index 42"*. Desktop Java is lenient and compiles the same pattern without complaint, which is
why it passed every test here and failed on every launch there. Upstream's own copy of the literal
leaves the braces bare too.

`RegexIcuCompatibilityTest` now reads every `Regex("…")` literal in the main sources, strips escaped
braces and legitimate `{m,n}` quantifiers, and fails on whatever brace survives. It reads the source
rather than executing it, because the JVM these tests run on is the very engine that cannot see the
problem. Verified against the real defect: restoring the bare `}}` turns the suite red.

**Fixed: the first build of this sync crashed on start for anyone who already had the app.** Three
of the migrated profile columns — `maxActiveExecutions`, `burstLimit`, `fallbackTaskId` — were added
by `ALTER TABLE` without a `DEFAULT NULL`, while their entities declare
`@ColumnInfo(defaultValue = "NULL")`. Room compares the two when it OPENS an existing database and
refuses the whole upgrade over the difference, so the app died before its first frame. Upstream never
meets this because it declares those additions as generated `AutoMigration`s, which emit the defaults
for it; the fork registers every migration by hand and has to write them out.

Nothing catches this on a clean machine: a fresh install creates schema 27 outright and never runs a
migration, so the build, the install and all 1319 tests passed. `scripts/check-room-migration.py` now
builds the previous schema in SQLite, applies our migrations, and diffs the result against what Room
expects — the same comparison Room makes at open time, without needing a device. It reports this exact
fault when the `DEFAULT NULL` is removed again.

**The build counter no longer resets on a sync, and the build refuses to regress.** The tail was
being reset to 1 on every sync, including one where upstream never moved its own `appVersionCode` —
which is the normal case for a fork that rebases onto every upstream commit. An installer compares
`versionCode` and nothing else, and `versionCode = appVersionCode * 10000 + N`, so the reset sent it
*backwards*: 840030 installed, 840002 offered, refused as a downgrade. The fresh date and sha in the
version name are cosmetic; nothing compares them. `BUILD_NUMBER` now runs monotonically and resets
only when `appVersionCode` itself moves, and `buildFork` records `LAST_BUILT_VERSION_CODE` and fails
rather than produce a code at or below it — so the next time this is got wrong it surfaces at build
time instead of on the phone. (白い熊, 2026-08-10.)

**A `foreach` over an array reference works again.** The editor writes an array slot as
`{{ array.xs }}`, and the fork's runner was reading that text as if it were the array's name — so
the loop found nothing and ran zero times. It resolves the reference to the name now, as
`text.join` does.

**Every fork action is now a declared action.** Upstream replaced its hand-maintained registration
list with one catalogue that owns each action's identity, category, retry contract and factory, and
rejects any action that has no declaration. All 96 of the fork's own actions are declared there —
so 「文字認識」, the band, the scenes, the share relays and the rest are first-class rather than
tolerated, and the release action count (172) is derived from the catalogue instead of asserted.

**The database chain absorbs upstream's five new versions at its own end.** Upstream's v11–v15 mean
entirely different things from the fork's, so they are renumbered 23–27 rather than merged by
number, exactly as its v6–v10 were before them. Schema 27 on this build; a 22 upgrades in place.

**What this build deliberately does not take.** Upstream's profile priority/lifetime/admission
policy is stored and editable but **not yet enforced by the engine** — the fork's `AutomationService`
is its own (wakelock, alarm-resurrect, per-invocation event variables, context-source gating), and
wiring upstream's arbitration into it is a separate job rather than a merge. Direct Boot ships
without its setup toggle, so it stays off. The action editor keeps the fork's inline labels rather
than upstream's string resources, so typed output chips are absent. Bundle export still writes
argument values verbatim — the workspace mirror has to round-trip exactly, and upstream's new
redaction would replace them with `REDACTED`.

## 0.2.82.2026-08-08.gbd01eebb+030 — 2026-08-10

**Two inks, and the split carries meaning.** The secondary text was grey, which read as *disabled*
rather than as *secondary*, and left headings distinguished from prose by weight alone (白い熊).

So: **yellow is the data** — headings, labels, values, anything the band measured. **Light blue is the
explanation** — the usual ranges, the provenance, the caveats, the arithmetic behind a number. A
glance now sorts the screen into what the band said and what we are saying about it, which is the
distinction this whole window is built around.

Blue against yellow is also the one pair that survives every common colour-vision deficiency, so the
split stays legible to anyone. And headings keep two further cues besides colour: they are larger and
bolder, and sub-headings now carry a leading bar — so the hierarchy still reads in greyscale. Colour
reinforces the structure here; it never carries it alone.

## 0.2.82.2026-08-08.gbd01eebb+029 — 2026-08-10

**Four reported faults, and a visual pass over the whole 「健康」 page.**

**The morning rating attached to the wrong night.** 白い熊 rated 2 on the 10th and the card kept
showing Normal. The rating was filed under the calendar day it was typed on, while the marker looks a
night's rating up by the night's own START date — and a night begun at 23:xx on the 9th is never the
10th. So every morning's answer was filed against a night that had not happened yet and the card
showed the previous day's for ever. Ratings now attach to the night they describe, and are read back
by the same key.

**Peak 30-minute cadence read 8 steps/min at 07:57.** True of the four minutes on record and absurd
as a statement about anybody. It is a daily figure and it is meaningless before the day has happened,
so it now uses the most recent day carrying at least 30 minutes of walking, and prints which day that
is. 白い熊's now reads 133 against a population norm of 71.

**7h 40m against 8h 07m was not stale data.** Both are last night: 135 + 254 + 71 deep/light/REM is
7h 40m asleep, plus 27 minutes awake makes the 8h 07m session the 睡眠 card headlines. The 回復 marker
is now labelled **Time asleep** and says the difference out loud, rather than leaving the reader to
subtract two numbers on different cards. Nocturnal heart rate was never stale — it comes from the
same night.

**The health index wrapped at 100.** The score column was a 28 dp guess and three digits did not fit;
it now measures itself, like the label column beside it.

**The look.** Every section is a bordered group with a big, bold, underlined header and an accent dot;
body text is larger and yellow; sub-headings are visibly heavier than prose; value pills are outlined
rather than flat. The sleep stages become a proper table — stage, minutes right-aligned, share, and a
total rule under it. It all lives in one file, `SectionChrome.kt`, because the failure mode of "make
it nicer" is eight cards that are each nice and collectively inconsistent.

## 0.2.82.2026-08-08.gbd01eebb+027 — 2026-08-09

**`運動と回復` — the session register: a five-week grid and a paired list.** Reached from
`See every session ▸` under the load block.

The **grid** is five weeks ending today, Monday first. Each day carries a bar whose height is that
day's session load and a row of three dots showing how many markers were outside your usual range on
the night that *started* that day. Reading down a column answers the question the 回復 card cannot:
whether the bad nights follow the training.

The **list** pairs every marked session with the night after it — duration, MET-minutes, peak heart
rate inside the window, then that night's nocturnal heart rate with its delta, sleep, and how you
felt. The pairing runs **forward** because that is the direction the measured effect runs: training
raises the *following* night's heart rate, by up to 15 % when it ends near bedtime and by nothing once
four hours separate them.

Each night is banded against **only the nights before it**, never a baseline that contains it or the
months that came after — so the register shows what a night looked like at the time rather than
against today's numbers.

**One aggregate, and no more.** Median nocturnal heart rate on nights after a session against nights
after none, with both sample sizes printed, and it stays hidden until there are four of each. No
correlation coefficient, no trend line, no verdict about whether the training is working: with a
handful of sessions those are noise wearing the clothes of insight, and that is the failure mode of
every training app there is.

It will look almost empty for a fortnight. That is the honest state of a register with one session in
it.

## 0.2.82.2026-08-08.gbd01eebb+026 — 2026-08-09

**`運動記録（後から） -- [727]` — mark a session you forgot to bookend, by drawing it on the chart.**

A new screen reached by `band.session mode=pick`: the heart-rate chart, a long-press-and-drag to mark
when you trained, ±5-minute arrows on each end, and a **Record session** button. **Nothing reaches the
store until that button is pressed** (白い熊's instruction) — the drag proposes and the arrows correct.
A finger on a six-hour chart resolves to about a minute per pixel at best, and a workout's edges are
exactly where that matters: the score is `(METs − 1) × minutes`, so ten minutes of slop is a fifth of
a fifty-minute session.

The chart is the same one the detail screen draws — the curve over the SpO₂-coincident spot readings,
the periodic series as hollow dots — which is deliberate here: a lift shows in the curve and is absent
from the dots, so the shape you are hunting for is the gap between them. On 2026-08-09 that gap sits
at 16:10.

The 5-to-240-minute rule is enforced twice: the button is disabled outside it, and the model refuses
it anyway, because the store is the thing that must never take a one-minute "workout".

`SpanSelectionState` gains `set(start, end)` for the arrows, and `DetailHeader`/`SpanChips` stop being
file-private now that two screens share them.

## 0.2.82.2026-08-08.gbd01eebb+025 — 2026-08-09

**A sleep score with published weights, peak cadence, regime detection, and a self-rating you can
withdraw.**

**Sleep score on Apple's 50/30/20.** Duration 50 points, bedtime consistency over the last 13 nights
30, interruptions 20 — the only sleep-score composition any manufacturer actually publishes. Stages
are excluded, which is Apple's own choice despite having the best-validated staging in the
independent literature (κ 0.68 against 0.20–0.53 for everyone else). What Apple does *not* publish is
the curve inside each term; ours are stated on the card rather than hidden. 白い熊's first reading is
**71 · OK — duration 50/50, consistency 7/30, interruptions 14/20, 140 min from the usual bedtime**,
which corroborates the regularity index of 57 exactly: the same finding at a different time scale.

The clock is treated as **circular**, so 23:50 and 00:10 are twenty minutes apart rather than
twenty-three hours and forty. On a linear clock every night that straddles midnight would have scored
zero for consistency.

**Peak 30-minute cadence.** The mean of the day's thirty highest step-count minutes — not the best
consecutive half hour, which is a different and unvalidated quantity. It is one of very few intensity
measures that survived adjustment for total volume (mortality HR 0.67, 0.56–0.83 in 47 471 adults),
where "minutes above 100 steps/min" did not (HR 0.86, ns). 白い熊 reads **113 against a population
norm of 71**.

**Travel and altitude are detected and said out loud, never silently corrected.** A rolling baseline
has one characteristic way of lying: it absorbs a step change and then reports the return to normal as
the anomaly. After long-haul travel, sleep duration re-converges in about two days while timing has
not returned within fifteen (1.5 M nights, 64 847 trips) — so the algorithm declares recovery while
the person is still displaced. At altitude, resting heart rate rises and oxygen falls, which reads as
overtraining indefinitely although it is adaptation. Both now produce an amber note qualifying the
card. Freezing or re-basing the baseline would be inventing an adjustment nobody has published.

**The self-rating can now be withdrawn.** Tapping the value already selected removes it. Previously a
stray tap became permanent data 白い熊 had not authored — found exactly that way, by a stray tap
during testing.

## 0.2.82.2026-08-08.gbd01eebb+024 — 2026-08-09

**Sleep regularity, and a way to record training the band cannot see.**

**回復 gains the Sleep Regularity Index.** In 60 977 UK Biobank participants, sleep regularity
predicted mortality *more strongly than sleep duration did* — 20–48 % lower all-cause risk across the
four more regular quintiles, surviving adjustment for duration. It is computed the published way
(compare every minute's sleep/wake state with the same minute 24 h later; `SRI = 200 × agreement −
100`), not by the cheaper bedtime-variance approximation most apps use, and it needs nothing but the
sleep/wake distinction — which consumer wearables get right at 91–96 % sensitivity, unlike the stage
labels. 白い熊's first reading is **57, irregular**: onsets ranging 20:51 to 23:42 across the recorded
nights. It sits beside the markers rather than in the counting rule, because it is a property of the
fortnight, not of last night.

**New `Mark Training Session` action, and a `運動記録 -- [727]` task to run it.** Strength work is
close to invisible to this hardware, and the evidence is 白い熊's own 20-minute lifting session on
2026-08-09: spot heart rate read **82, 93, 90 bpm** — the 71st, 95th and 91st percentile of an
ordinary waking day — while the periodic series read **62 and 70**, at or below resting. Steps ran
17–36/min, so "no steps" is the wrong test, and skin temperature did not move at all (36.0 °C either
side). Automatic detection was attempted and abandoned: a threshold tight enough to exclude ordinary
life catches one of those three readings, and a loose one found **76 "sessions" in ten days**, one of
them three hours long. Three samples is not enough information.

So the window is marked by hand — one tap to start, one to end, toggling — and inside it the
heart-rate channel becomes legitimate. The over-crediting that disqualified it as an all-day metric
was purely an artefact of integrating across 144 buckets of ordinary life; across a bounded workout
there are no transients to mistake. Marked-session load is reported separately from the walking
figure so the part that needed a tap is visible, and the card now states that marked strength work
still reads about **18 % low**, because heart rate falls between sets and the ten-minute grid samples
that trough as often as the effort.

A session under 5 minutes or over 4 hours is discarded rather than recorded — the first found the
honest way, by a test double-tap logging a one-second "workout".

## 0.2.82.2026-08-08.gbd01eebb+022 — 2026-08-09

**New 回復 section, second on the dashboard, under 健康指数.** Four literature-research agents were
dispatched at 白い熊's instruction and came back converging hard, including on things that cut against
the feature as first framed. What shipped is what the evidence supports, with 白い熊 choosing the
framing knowing what it cost.

**Three markers, counted — not scored.** Nocturnal heart rate over the Sleep4h window, time asleep,
and a one-tap daily self-rating; the headline says how many are outside your usual range and names
them. That shape is deliberate: **no commercial readiness score has ever been validated against an
outcome** — not Garmin Body Battery, not Polar Nightly Recharge, not Fitbit Daily Readiness, and the
one positive study of WHOOP Recovery was written by six WHOOP employees. The only composite shape
with published support is a **count** (≥2 of 3 elevated → 92 % PPV for overreaching, against ≥85 %
for each criterion alone). A weighted score would need coefficients no study has produced and would
hide which marker fired.

**Every threshold is a conjunction**: a marker fires only when the change is both unusual for you
(beyond 1.5 robust SD of your last 28 nights) *and* large enough that the literature calls it
meaningful — 5 bpm, 30 minutes, one step of five. Either test alone misbehaves, and 白い熊's own
nights prove it: eight nights at a 3.4 % coefficient of variation, which is the published
same-condition *noise floor*, so a z-score alone would fire on the sensor.

**The self-rating is in because it beats the sensors.** Across 56 studies subjective measures tracked
training load with "superior sensitivity and consistency" to objective ones; in a 3-arm RCT a daily
questionnaire beat HRV-guided training on both outcomes; through a two-week overload block perceived
strain rose sharply while every nightly sensor metric stayed flat. One tap, and it is the third leg
of the published counting rule.

**Deliberately excluded, each on its own evidence:** deep/REM percentages (consumer staging agrees
with sleep labs at κ 0.20–0.53, and no study links stage proportions to next-day readiness — Apple,
with the best staging in the field, excludes stages from its own sleep score); SpO₂ (measurement
error about twice the whole day–night swing); nadir timing; ACWR (its own field has called for its
dismissal, and for a recreational wearer it is a flat line at 0.99). Skin temperature is shown but
never counted, only ever upward, and only when sustained two nights — a wrist sensor at night
correlates with the bedroom at r = 0.961.

**Load is measured from walking cadence, not heart rate.** The recommended metric made a heart-rate
channel primary; run against 白い熊's ten days it produced 13 240 MET-min/week against a 500–1 000
public-health band, because holding one 10-minute spot reading across the bucket turns every
transient into ten minutes of exercise, 144 times a day. The cadence channel lands at 567 with no
tuning. The card states the cost on screen: cycling, carrying and strength work leave no step
signature, so the figure is a floor, not a total.

**And it says what it cannot see**, on the detail screen: no HRV, no way to tell alcohol from a hard
late session from illness (all three raise nocturnal heart rate by the same 3–9 bpm), and a
short-history ladder that shows absolute differences until 14 nights rather than pretending to a
dispersion estimate it does not have.

## 0.2.82.2026-08-08.gbd01eebb+021 — 2026-08-09

**The heart-rate curve swaps to the spot readings.** `+019`/`+020` gave the bold curve to the
periodic series and the hollow dots to the ten-minute readings taken with blood oxygen. That was the
wrong way round: the curve should carry the series that can be believed, and during any activity
that is the spot reading. Now the **spot readings carry the curve** and the periodic series is drawn
as hollow dots around and under it. At rest the dots sit on the line, because there both are right;
when they fall away below it, that is the periodic series losing the pulse to wrist motion.

The cost is a sparser curve — six samples an hour rather than twenty-four, breaking wherever the spot
series pauses for more than half an hour. The threshold needs no new constant: it is already the
larger of the nominal cadence and the observed median, so a ten-minutely series gets a ten-minutely
threshold on its own. Every reading is still drawn; the info sheet, the caveat and the legend swap
with it.

**The health-index labels stop wrapping.** The label column was a per-language constant — 116 dp for
English — and two of the five labels did not fit, so "Resting heart rate" and "Heart-rate stability"
broke to two lines, those rows stood taller than the other three, and the bars were unevenly spaced
down the card. The column now measures the actual labels at the actual type style and takes the
widest, so it is exactly wide enough in any language at any font scale, and the rows refuse to wrap
at all.

## 0.2.82.2026-08-08.gbd01eebb+020 — 2026-08-09

**Corrects what the `i` sheet says about the two heart-rate series.** `+019` described the periodic
series as "a slow baseline", which was still the wrong picture. Measured against each reading's own
quiet baseline — the median of the still periodic readings in the previous half hour — for readings
taken with 60 or more steps in the surrounding minute:

| | n | median | vs own quiet baseline |
| --- | --- | --- | --- |
| periodic, walking | 41 | 64 bpm | **−4.0 bpm, 59 % below resting** |
| spot, walking | 29 | 86 bpm | **+18.0 bpm, 97 % above** |

It does not hold a baseline while you walk — it drifts slightly *down*, and a heart rate cannot fall
during brisk walking. Nor is it frozen or derived: consecutive periodic samples repeat exactly only
6 % of the time while walking (13 % at rest), so it is live and varying. It is a genuine low-power
measurement that loses the pulse under wrist motion, and its failure mode is to read a little low
rather than to report nothing.

Both series are real measurements, and at rest they agree to 1 bpm — so the line is your heart rate
while you are still, and under-reads once you move. The info sheet and the caveat now say exactly
that, in both languages: **at rest, believe the line; once moving, believe the dots.** No behaviour
change.

## 0.2.82.2026-08-08.gbd01eebb+019 — 2026-08-09

**The two heart-rate populations are not one measurement with an offset.** They were written up as
the same thing read with a "+7.46 bpm bias". Comparing each SpO₂-coincident reading against the
periodic readings either side of it, across ten days, says otherwise:

| when | median gap | over +15 bpm |
| --- | --- | --- |
| asleep and still | **+1.0 bpm** | **0 %** |
| awake, not moving | +3.5 bpm | 7.8 % |
| 21–100 steps nearby | +10.5 bpm | 27.5 % |
| over 100 steps nearby | **+22.0 bpm** | 72.5 % |

A calibration offset would survive sleep; this one vanishes. What is left is exertion — and the
periodic series is the one that fails to show it. Walking at 130 steps/min it reads a median of
**58 bpm**, below its own resting median of 66, while the spot reading reads 89 (and is not a
cadence-lock artefact: it sits 42 bpm *below* the step rate, where an artefact locks on to it). The
spot readings own the day's maximum on 9 days in 10.

**So heart rate is now a curve over the periodic series with the spot readings hollow on top.** The
line is drawn exactly as body temperature is — PCHIP with a gradient fill, in the heart-rate blue —
and every spot reading sits on it as a hollow dot at the value and moment it was taken. Both marks on
the dashboard card and the full-screen detail; nothing aggregated, averaged or decimated. **The peaks
are on the dots, not on the line**, and the `i` sheet and the legend now say so.

Two chunks behind it, because a tint and a break mean different things: the pooled series still owns
the footer counts, the rejections and the gap shading — a shaded stretch means the band recorded
*nothing* — while the curve gets its own series, built from the pooled chunk's retained points and
re-segmented at the periodic cadence. So the line breaks where the periodic series really stops, a
stretch carrying only spot readings gets a break and no shading, and a rejected reading can return as
neither a dot nor a knot in the curve.

This replaces the scatter shipped in `+018` earlier the same day, which replaced the hourly capsule:
an hour of heart rate is 12 to 30 readings, and a capsule showed two of them and hid the rest, so the
chart read as though the band measures once an hour. The `53–105 bpm` headline, the slew gate, the ✕
marks, the footer counts and the day table are unchanged throughout.

## 0.2.82.2026-08-08.gbd01eebb+018 — 2026-08-09

**The band archive was silently losing rows; now it cannot.** Rows are committed to the database as
each stream lands, but the JSONL flush sat on the success path alone — so a sync that landed rows and
then threw took its lines with it, and the banked lines were dropped and leaked. On 白い熊's archive,
syncs 28 and 41 were missing entirely, taking **27 heart-rate rows** of 2026-08-08: the day table read
452 where the file held 425, with no warning anywhere.

Two fixes. The flush is now **unconditional** — success, timeout, exception, failed connect — with a
`finally` that clears the bank, and a failed sync writes a census with `ok:false` instead of leaving
nothing but a hole in the id sequence. And every sync now runs `BandArchiveRepair`: any sync id in the
database with no census line in the monthly files gets its rows re-emitted, closed by a `repair`
marker written last. It is bounded by the oldest archive file present, costs a `startsWith` scan, and
finds nothing to do in the normal case.

## 0.2.82.2026-08-08.gbd01eebb+001 — 2026-08-09

**Rebased onto upstream `bd01eebb`.** Upstream did not bump its own version — still 0.2.82 — so this
sync moves the base pin and nothing else. Two upstream commits, neither of which changes what the app
does here.

Upstream gave the Tasker XML importer the instrumentation coverage it never had: six tests running the
real importer on Android's own parser. The point is sharper than the count. The JVM suite could not
observe the doctype defect at all — desktop Xerces accepts the Apache secure-parsing feature URI that
Android's Expat-backed factories throw for — so the fix in 0.2.82 shipped with every unit test green
and every on-device import still broken. Reverting the fix fails four of the six, including the plain
export carrying no doctype at all.

Upstream also moved release signing off the machine-global `~/.android/debug.keystore`, which the SDK
regenerated on 2026-08-01 and destroyed the key that signed v0.2.79 in the process. A repo-owned
`app/dev_keystore.jks` takes over, reached through a new `selfhost` signing config, and upstream's
users have to uninstall once. **This fork is unaffected.** Our releases are signed from the gitignored
`keystore.properties`, so the `release` config still wins and `selfhost` is dead code in our tree — no
signature break, no uninstall, a normal in-place update.

## 0.2.82.2026-08-07.g37770efc+008 — 2026-08-08

**Rebased onto upstream 0.2.82.** Two upstream fixes, both for things that could never have worked:
`ACCESS_NOTIFICATION_POLICY` was never declared, so the app never appeared on the Do Not Disturb access
page and `dnd.set` / `zen.rule.set` / `zen.rule.clear` were dead on every device; and Tasker XML import
failed on-device for everyone, because Android's Expat parser rejects the Apache secure-parsing feature
URI the importer treated as mandatory. Doctypes carrying entities or external DTD references are still
refused, so the XXE protection survives.

**「文字認識」 no longer ships its models, and no longer needs setting up.**

The ~100 MB of ONNX weights left the APK, taking it from 129.3 MB to 13.8 MB — they never change, and no
build is ever deleted from the phone, so twenty builds was 2.5 GB of identical weights. The floor now is
ONNX Runtime's own native library, not the models. The character dictionaries still ship, all 93 KB:
each has to match its model exactly, so bundling them removes a whole class of mismatch.

That left a setup step, and the first attempt at it failed in the most ordinary way — the window opened,
recognised nothing, and explained itself in a status line under an empty box, which reads as "nothing
was found" rather than "you have something to do". So: the weights are now **discovered**, by expected
filename under the conventional folder and then /sdcard/tmp, with a hand-picked path always winning; and
a missing model gets a panel that names the file, the folder and a button to the settings. A picked
document is normally read in place rather than copied, since the app can resolve the URI back to a real
path.

The window gains 画像を選ぶ and can open with no image at all, which is what lets a task put 文字認識 on
a launcher shortcut — new project **文字認識 [362]**, with `文字認識 -- [362]` to open it and
`文字認識の設定 -- [362][01]` declaring where the weights live, so that decision rides the workspace
export and survives a re-flash while the value still lands app-side for the native loader.

**Per-line confidence, measured rather than assumed.** The recognition graphs already end in a softmax
and the decoder was applying a second one, pinning every confidence at about 1/18385 — invisible in the
text, because argmax survives any monotonic transform, and only exposed once the number was displayed.
With that fixed, the honest finding is that a line's MEAN confidence barely discriminates (worst correct
0.918 against worst wrong 0.932); the marker reads the least sure single character instead, one band, at
a threshold picked for recall.

**Detection knobs** — long side, binarisation, box score, unclip — are settings now, each with a note
saying which way it goes wrong, reachable from the review window: 検出設定 lands on those rows, Back
returns to the screenshot, 再認識 re-detects.

**Two wallpaper actions**, ported from Tasker. `wallpaper.set` gains `where` (the lock screen is
unreachable otherwise — `setBitmap` with no flags touches only the home screen) and `shared`.
`wallpaper.live` is new and turns 26 AutoInput actions into one: shell holds SET_WALLPAPER_COMPONENT, so
a Shizuku UserService sets the live wallpaper outright in under half a second, with the system preview
as the fallback when Shizuku is absent.

**Fixes.** A pass-through overlay no longer swallows every tap — since Android 12 an untrusted overlay
above 0.8 opacity blocks touches to the app underneath regardless of FLAG_NOT_TOUCHABLE, which killed
every tap on screen while 通知明滅 was lit. CJK section headings no longer clip to a single glyph
(`IntrinsicSize.Min` treats every CJK character as a break point). The 「健康」 day tables are legible,
and a chart span can be long-pressed and totalled. The release-truth gates were unsatisfiable — two of
them demanded contradictory README text — and now pass.

## 0.2.81.2026-08-02.g97059d7b+039 — 2026-08-08

**「文字認識」 — share a screenshot, get its text back, entirely on the device.**

PP-OCRv5 detection and recognition on ONNX Runtime, no network at any point. Japanese and English
come from one model, so the everyday case never picks a language; German, Czech and Polish ride a
Latin recogniser and Russian an East Slavic one, each a chip that re-reads the cached crops without
re-detecting. The review window pairs the screenshot — every detected line boxed — with a three-line
text field and one button that copies and closes; tapping a box on the image moves the caret to that
line. Vertical Japanese is handled as vertical: tall crops are rotated upright and columns emitted
right-to-left. Both recognition tiers ship, the accurate 81 MB model by default and a ~2.5× faster
16 MB one behind a switch in the appearance settings, with a per-action `model` override. Tasks reach
the same engine through the new `ocr.recognize` action.

Measured on a corpus with hand-written ground truth: 0.00 % character error on Japanese, English,
German and Russian, 5.6 % Czech, 6.9 % Polish. Detection runs at a 1600 px long side rather than
PP-OCR's 960 default, which halves the error on a full-width phone screenshot; the errors that
disappear are exactly the small text. On the Mate XT a small cut-out takes ~2 s and a full-width
screenshot ~5 s on the accurate model.

Three things had to be got right and were wrong first, each of which fails silently rather than
loudly. Box dilation must expand along the box's own axes — a text line is extremely elongated, so
pushing corners out from the centre barely raises the height and crops the line to its x-height band,
which decapitates every ascender and diacritic ('ä' reads as 'a', thin Latin lines as nothing at
all). Recognition must not cap crop width at the 320 px in the training-time shape, which compresses
a full-width line four- to six-fold; CJK survives that and Latin and Cyrillic do not. And vertical
columns read right-to-left — the vertical sample recognised perfectly and still scored 67 % error on
order alone. Together those took the corpus from 32.11 % to 1.32 %.

The ~100 MB of ONNX weights are not in git: a Gradle task fetches them once per machine, pinned to
immutable revisions and verified by SHA-256, into a gitignored assets directory. ONNX Runtime needs
R8 keep rules — its native layer resolves Java classes by name, so minification does not fail at
build or link time, it aborts the process on the first inference.

**A pass-through overlay no longer swallows every tap.**

`FLAG_NOT_TOUCHABLE` stops a window receiving touches and says nothing about it obscuring them. Since
Android 12 the system drops a touch to the app underneath when an untrusted overlay covers it above
`maximum_obscuring_opacity_for_touch` — 0.8 by default. The 通知明滅 frame is fullscreen at window
alpha 1.0, so while any notification was lit, every tap anywhere on the screen was discarded before
it reached the app. Pass-through overlays now borrow the accessibility service's trusted overlay type,
which is exempt, so they keep full opacity and still let taps through; without the service they fall
back to sitting exactly at the opacity limit. This also fixes the 音楽 fullscreen edge-light, and the
kanji clock strip and 電池線, which silently killed taps within their own band across the top.

**Mark a stretch of a 健康 chart and total it.**

Long-press and drag on a full-screen chart shades a span and reports it underneath. Steps are summed;
every other metric reports mean and range, since summing a heart rate is not a quantity. Totals come
from the samples, never the drawn curve — the curve is an interpolation and summing it would invent
steps nobody took — and a span that catches nothing says so rather than showing a confident zero. The
span persists after the finger lifts so it survives a pinch and a pan; a long-press that never moves
clears it.

**The 健康 day tables are legible.**

Both day-by-day tables were sized as if space were scarce, on a screen where they occupy about a
third of the width. The per-metric table broke `2026-08-08` across two lines at a 104 dp date column;
the dashboard table was set in the smallest type on the page and its 52 dp column rendered `Rest HR`
and `Sleep` as `Rest HRSleep`. Columns are now wide enough for their own headings, dashboard rows are
19 sp, and every cell is a single line by construction.

Also: the release-truth contract records the capability counts it actually checks (it claimed 74
registered actions against an actual 168, and bundle schema 2 against 5).

## 0.2.81.2026-08-02.g97059d7b+024 — 2026-08-08

**The band's own charge, at the top of 「健康」.**

It was already being read — `readBattery` fires on every sync and `stampDevice` writes it to the
`band_syncs` row — and then nothing ever showed it. It now sits beside the 同期 button: the
percentage, a short bar coloured by the reserved status roles (good / warning / critical), and the
figure always beside the colour so the state never rests on hue alone.

**The age is printed with it, and that is the point.** The band only answers a battery query while a
sync is connected, so this is a snapshot and never a live reading. "76 %" with no timestamp would let
a figure from yesterday read as current, which is worse than showing nothing — so it says
`just now` / `3 h ago` / `2 d ago` underneath. Before the first sync it says the charge is unknown
rather than rendering an absent value as 0 %.

`BandStatus.batteryPct` takes the freshest reading from **any** recent attempt, successful or not:
the band is asked immediately after connecting, so a sync that later failed part-way through still
read a perfectly good level, and discarding it would age the number for nothing.

Verified against the on-device archive — the last five syncs recorded 52, 52, 51, 50, 50 %.

## 0.2.81.2026-08-02.g97059d7b+023 — 2026-08-08

**The permission block told the truth about the wrong thing.**

白い熊 kept being prompted that tasks need Accessibility while Accessibility was plainly enabled.
Both halves were true. `dumpsys accessibility` on the Mate XT:

```
Bound services:   {Service[label=白い熊 考直, …]}
Enabled services: {…kojiki…, shiroikuma.jiyusagyoban/…ShiroiKumaAccessibilityService}
Crashed services: {shiroikuma.jiyusagyoban/…ShiroiKumaAccessibilityService}
```

Enabled, crashed, not bound. The toggle is the user's wish; the binding is whether Android is
actually running it — and once a service lands in `Crashed services` the framework never re-binds it,
so the toggle sits there reading "on" over a dead service. The app was right to block. Three things
around that were wrong, and all three are fixed.

### It said "enable it in System Settings" to somebody who already had

`CapabilityState.blockedDetail` now recognises enabled-but-not-running and says what is actually
happening, with what to do: turn it off and on again. The app already knew how to tell the two apart
(`isEnabledInSettings` vs the live binding); the dialog simply never asked.

### Tapping OK did not stop it coming back

The quiet window only started if the user tapped "Open … settings". Now **showing** the dialog quiets
its requirements, and going to settings *shortens* that window rather than lengthening it — somebody
actively fixing a permission deserves an honest answer when they come back, somebody who tapped OK
does not want to hear about it again for ten minutes. The direction is counter-intuitive enough to be
worth a test.

### Every blocked task hung for two minutes

The pre-flight awaited the dialog with a 120 s timeout, on a task it had *already decided could not
run*. Measured in the log: `相撲字時計⇨描画 -- [672][036][17]` failed at `120030ms` and `起床舞判定`
at `120022ms`, both entirely spent sitting in that modal. The dialog is now fire-and-forget; the task
fails immediately.

### Also: no more paying 3 s per task to wait for a rebind that is not coming

`awaitConnected` polls for the transient EMUI unbind→rebind gap, which is right — but a *crashed*
service looks identical from inside the app and never comes back, so every blocked task paid the full
timeout. It now pays that wait once and backs off for a minute; a real rebind clears the backoff
through `onServiceConnected`, so the transient case still recovers within a second.

## 0.2.81.2026-08-02.g97059d7b+022 — 2026-08-07

**Steps join 健康指数 at 20 %.** Five components now, not four.

The dose–response evidence for daily step count is among the better literatures in wearable health —
all-cause mortality falls steeply from roughly 2 500 to 7 000 steps a day and flattens after — so it
earns its place. The 20 % came out of the other four in proportion (each × 0.8), rounded to whole
percent so the printed weights sum to exactly 100 rather than 99: resting heart rate 26, stability
11, blood oxygen 17, sleep 26, steps 20.

The ramp is **3 000 steps = 0, 7 500 = 100, no bonus above** — the steps card's own band-ladder
edges, not the 2 500 / 8 000 the literature suggests in isolation. The invariant that index
breakpoints ARE card breakpoints is what stops a card reading "Standard" beside a component scoring
15, and it is worth more than 500 steps of precision.

Two things this forced, both worth stating:

- **Zero steps is a measurement.** Everywhere else in the index an absent value means the band did
  not measure and is reported missing; a day of not walking is a real and rather informative
  reading. An empty series is missing, a series of zeroes scores zero, and a test holds the two
  apart.
- **A day now needs half the index's weight before the table gives it a number.** Steps score on
  their own, so a day where the band recorded nothing but a short walk would have landed a
  renormalised 0 in a column beside days scored from all five — arithmetically correct and
  unreadable. Below the line the row shows a dash and keeps its raw figures.

Steps are also the one **behaviour** among five physiological measures, so a long walk lifts the
index while nothing in the body's own numbers has moved. That is deliberate, it is now said plainly
in the info panel, and a test asserts it so nobody later files it as a bug.

## 0.2.81.2026-08-02.g97059d7b+021 — 2026-08-07

**Four changes 白い熊 asked for after living with the charts for a day.**

### The info panels are readable now

The `i` sheets and the 健康指数 page are two screens of prose that were being drawn with the same
recessive grey 12 sp the app uses for chart captions. Body text is now 16 sp in the theme accent, set
solid — line height equal to font size — with headings at 22 sp, bold and underlined. It lives in one
place (`InfoType`), used by the index page and all eight metric sheets, because typography spread
across ten call sites reliably stops matching.

### Card order

歩数 → 睡眠 → 体温 → 心拍 → 血圧 → 血中酸素 → バンド状態指数, as one explicit list. It is not the
order of `MetricSpecs.ALL` — that is a decoding table whose order is about record layouts, and sleep
and blood pressure are not entries in it at all, so nothing else could interleave the three kinds of
card.

### One date format

`2026-08-07`, everywhere: the day table, the crosshair readout, and the time axis, which used to say
`8/7`. Three formats for one kind of thing is three chances to misread which day you are looking at,
and the year stops being optional the moment the archive crosses one. A full ISO date is wide for an
axis, so the tick ladder now offers at most five labels and the renderer still drops any that would
collide — fewer date ticks, none ambiguous.

### Day-by-day history under every chart

The sleep screen printed one undated breakdown — the most recent night — and every other metric
printed none, so "how did I sleep on Tuesday" could only be answered by zooming a hypnogram and
reading an axis. Now every full-screen chart carries a table underneath.

Sleep gets a row per night: the date, `22:41 → 08:33`, the duration, and all four stages with minutes
and percentage. **Every recorded session**, not just the latest and not just the longest of each day
— that rule belongs to the summary table, where one row shows one number; a history that hid a real
nap would be the screen pretending it did not happen.

The others get date, median, low–high, and the sample count. The count is not decoration: a median
built from four readings and one from four hundred are different claims. Blood pressure gets no table
— a dumbbell day is two series, and those numbers live inside a ±10 mmHg clamp, so tabulating them
would dress up a non-measurement.

Histories are built from the **raw** series, before the outlier filter. The table reports what the
band recorded; the chart's ✕ marks are where the two disagree.

## 0.2.81.2026-08-02.g97059d7b+020 — 2026-08-06

**The crosshair only existed on the dashboard.** 白い熊 opened 歩数 full-screen, tapped a bar, and
nothing happened — correctly, because `MetricDetailScreen` never wired it up. The full-screen views
got the pinch/pan modifier and no crosshair at all.

They have one now, and it takes the gesture the screen actually had spare: **tap to plant it, tap it
again to clear**. It stays put, so you can put it on a spike and then pinch and pan around it — which
is what that screen is for. The dashboard keeps long-press-and-drag, because a tap there already
means "open this metric".

With the line planted, the full-screen headline reads **that instant** instead of the 24-hour
summary, and a line under the plot says when — or `no reading there` when the crosshair is parked in
a stretch the band did not measure. 睡眠 reads out the stage. Before the first tap that line is the
hint that the gesture exists at all; an undiscoverable gesture is the same as no gesture, which is
exactly how this shipped.

`readoutPoints` now lives on `MetricChart`, so the preview card, the plot and the full-screen readout
cannot disagree about what the crosshair is pointing at — including for capsule and bar metrics,
which have no line to sample.

Five new instrumented tests, run on the Mate XT in the same modifier order the screen uses: a tap
plants it, a second tap in the same place clears it, a tap elsewhere moves it, and neither pan nor
pinch is lost to it. All 11 gesture tests green.

## 0.2.81.2026-08-02.g97059d7b+018 — 2026-08-06

**The last three 「健康」 backlog items, built together.**

### A crosshair through every chart at once

Long-press any plot and drag: one vertical line runs through **every** card, and each one reads out
its own value at that instant instead of its window summary. Heart rate shows the reading at 03:12,
睡眠 shows the stage you were in, 血中酸素 shows the dip — the cross-reading that was previously only
possible by opening each card separately and lining the time axes up by eye.

It is long-press rather than touch because the cards live in a scrolling list and the detail charts
already claim pinch and horizontal drag; `detectDragGesturesAfterLongPress` takes the pointer only
after the finger has been still, so a scroll flick and a pinch both still reach their usual handlers.

Two consequences worth stating. Sleep gave up its own night-spanning viewport and joined the shared
clock — cross-reading is impossible if each chart is on a different one. And the readout **refuses
across a gap**: park the line in the middle of a four-hour hole and it says nothing rather than
printing the value from either edge, which would state a reading for a time the band was not
measuring.

### A day-by-day table

Deliberately a table, not a chart. The plots answer "what happened today"; this answers "**was
Tuesday better than Monday**", and a reader comparing two days wants two numbers side by side rather
than two points to measure against an axis. Columns: 健康指数, resting heart rate, sleep with its
deep/REM split, steps, and the SpO₂ low.

A night belongs to the day it **started** — Tuesday's sleep is the night you went to bed on Tuesday
— matching the band's own noon-to-noon chunking. The longest session of a day is the one shown, so a
nap never displaces the actual night, and a day that would be all dashes gets no row at all.

### Thirty-five chart knobs, with a live preview and a colour check

Everything the charts used to hard-code is now on the UI page beside the rest of the appearance
settings: preview and full-screen heights, card spacing, axis and headline text, line width, dot
size, capsule / bar / dumbbell widths, sleep block height, corner radius, grid / fill / glow / gap
opacities, four "what gets drawn" switches, the opening time span, the line shape (smooth, straight,
or held-until-the-next-reading), and eleven series colours.

The preview runs **the app's real renderers** over made-up data, so it cannot drift from what it
claims to preview, and it works on a device that has never synced the band.

Two things came out of doing it. The mark weights are now **dp rather than raw pixels**, which fixes
a quiet density bug — the old `STROKE = 2f` was two device pixels, so on a 3× screen the line was a
third of its intended weight and got thinner the better the display. And the colour validation
that had only ever run on a laptop now **runs on the phone**: `PaletteCheck` is a faithful Kotlin
port of the data-viz validator — same OKLab conversion, same Machado–Oliveira–Fernandes CVD
transforms, same thresholds — reporting live on whatever has been picked. It is advisory, never a
block, with a one-tap restore beside it. Hume's violet REM beside a blue stage measures ΔE 1.9 under
protanopia and is now a **failing unit test** rather than a paragraph of prose.

## 0.2.81.2026-08-02.g97059d7b+017 — 2026-08-06

**Three of the band's six HRV-record fields turned out not to be measurements.**

白い熊 noticed the Health Index scoring HRV 15/100 while the HRV card beside it read "Standard", and
asked whether that was inconsistent. It was — and chasing it down, with three research agents and a
forensic pass over 2 131 records, found something larger.

### `hrv` is not HRV

It is a device state index. Two binary firmware flags — whether the band thinks you are asleep, and
whether the optical read succeeded — explain **74 % of its variance**; adding real step data adds 0.2
points. It correlates **positively** with heart rate (+0.383 pooled, +0.179 asleep) where every real
variability metric is negative. It carries **no sleep-stage information at all** (deep 21.0, light
21.0, REM 20.5). It switches at the sleep boundary within one or two records where heart rate ramps
over forty minutes. And it is near-random within a state where the same record's heart rate holds an
autocorrelation of +0.59.

It is not motion artefact either — the hypothesis both the literature and I favoured. Awake-but-
stationary (no steps for 15 minutes, median 48) looks like awake-and-moving (54), not asleep (21).

The records come in **two kinds**: 1 644 carrying a heart rate and blood pressure (values 15–94) and
487 where that whole triple failed (50–99), never partially. The apparent 15–99 range was those two
populations pooled.

So: **removed from 健康指数**, its 25 % redistributed across the four survivors, with nothing
substituted — there is no other field here that measures autonomic tone. The chart survives as
**Band State Index**, split by record type, with no band ladder and no unit.

### Blood pressure is generated inside a ±10 mmHg clamp

Across 1 644 records systolic occupies **every integer from 110 to 129 and never leaves it**;
diastolic every integer from 60 to 79. That is 120 ± 10 and 70 ± 10 — the calibration window this
class of SDK clamps to. Six days of ordinary life does not keep real systolic pressure inside a
twenty-point box. They carry no memory either: lag-1 autocorrelation −0.015 and −0.020 against 1.13
for independent draws.

Kept at 白い熊's request, chipped **"not a measurement"**, with the evidence and the FDA's September
2025 communication in its info sheet.

### "Vascular age" / "stress" is a lookup on the same byte

Reconstructed exactly: type B is `⌊hrv/2⌋` plus 20 or 45, **487 of 487**; type A is 43…47 when
`hrv ≤ 45` and 10…14 when `hrv ≥ 46`, 1 632 of 1 644. Residual entropy 1.94 bits ≈ log₂5 — the spread
is a uniform dither. Zero independent information, so the chart is gone. The decoder and archive are
untouched so the finding stays checkable.

The firmware maps *high* `hrv` → *low* stress, treating it as HRV in the conventional
"higher = more relaxed" sense — while the data show it is highest when awake and active.

### Also

The Health Index gains an `i` — "How to read it" and "What to aim for" — and a ringed accent `i` now
marks every screen that has an explanation behind it. Line-metric headlines are the 24-hour median
rather than the latest reading, and the index reads the same window on the same band ladders, with a
test asserting the two can never drift apart again.

## 0.2.81.2026-08-02.g97059d7b+009 — 2026-08-06

**「健康」 is a real dashboard now, and the scaffolding is gone.**

One page opened from `グラフ -- [727]`: the last sync time and a 同期 button with live feedback, then
eight full-width cards. Tap any card for a full-screen view that opens on the last 24 hours and
pinch-zooms and pans across the whole history. The numbers sit **above** each plot rather than beside
it, which is a third of the plot width Hume spends on two lines of text.

`MetricLineChart.kt`, `BandChartsSection.kt` and `BandScreen.kt` are deleted. The pipeline they sat on
— filters, segmentation, LTTB, PCHIP, viewport, ticks — was always the asset and is untouched.

### Four new renderers

Hourly **capsules** (heart rate, SpO₂) spanning each hour's real min–max; blood-pressure **dumbbells**
with systolic and diastolic on **one** mmHg axis; a sleep **hypnogram** stepped between the raw stage
codes with no interpolation, because there is no value between "deep" and "REM"; and step **bars**,
where a zero-height bar is a real measurement rather than a gap.

### 健康指数 — a score that can be wrong

Hume's Health Score draws on resting heart rate, HRV, heart-rate stability, SpO₂ and sleep quality —
all of which we measure — but its weights are withheld and part of it needs a Body Pod. Its
neighbours are worse: the vendor's own consumer report calls "Life Added: 1.9 days" a *model-based
estimate, not the result of a controlled clinical study*, and notes there is *no universal clinical
standard for wearable biological age estimation*. Metabolic Momentum, Capacity, Strain, Recovery and
Pace of Aging are all dropped.

In their place, ours: 0–100 from five components, every breakpoint and weight a constant in
`HealthIndex.kt` and printed in the info panel beside each component's contribution today. **It
refuses rather than guesses** — a component with no data is named as missing and the index says it is
partial, never scored as zero. That distinction is what stops a night the band did not record from
reading as a bad night.

### `vascular` and `stress` are the same byte

Found while researching stress: HRV-record offsets `[10]` and `[12]` are **identical in 2038 of 2038
samples**, and in both golden frames. One number, two names. It also does not behave like stress —
asleep it is pinned at ~45 (850 of 872 samples in 40–49) and awake it scatters 10–99, the opposite of
what a stress measure does.

So one series is drawn, called **バンド指標** rather than ストレス, and its info panel says exactly
that. Real HRV stress indices are validated science (Baevsky's Stress Index: normal 80–150, rising
1.5–2× under mild stress) but they need beat-to-beat RR intervals **this band never sends** — the
panel says that too, rather than implying our number is one of them.

### Two chart bugs the dashboard exposed

The heart-rate card first drew **52 gaps** where there are 4: the gap analysis ran on the split
periodic series while the capsules were drawn from the pooled one. And running Hampel over the pooled
series flagged **102 real readings** as outliers, because the +7.46 bpm second population looks
exactly like a sawtooth of them. Gap analysis now follows the series it is drawn over, and capsules
skip Hampel entirely — an envelope's ends are two real readings, not a curve fitted through them.

### Colour is computed, not chosen

Every palette was run through the data-viz validator against this app's own near-black surface. The
obvious hypnogram colours fail outright: **violet REM against blue light-sleep measures ΔE 1.9 under
protanopia and 9.8 with normal colour vision**. Two sleep stages nobody can tell apart is not a
stylistic quibble, so the stages use the one ordering of the documented slots that clears every gate.
Hume's rainbow ramps are not reproduced either — a rainbow has no perceptual order, so a reader cannot
tell which end means "more".

1 009 unit tests and 6 instrumented gesture tests, all green.

## 0.2.81.2026-08-02.g97059d7b+004 — 2026-08-06

**The band was quietly throwing data away, and now it cannot do so unnoticed.**

Four questions had been carried as unknown since the 「健康」 work began. All four are now settled by
measurement against a week of real data — ten syncs, 16 242 archived records, 2026-07-31 → 08-06 —
and two of the answers contradict what was written down.

### The band ignores the date you ask for

It returns its **entire ring buffer** on every stream of every sync. Sync 8 asked for records from
2026-08-05 07:41 and was handed heart rate from 2026-08-01 18:59. That makes the oldest record it
returns a direct reading of its buffer floor, free, on every sync — which is the whole detector.

Watching that floor gives three numbers per stream: **headroom** (how long a sync may be missed),
**floor advance** (the band evicted something) and **lost window** (it evicted something *we had
never read*). The last is the only honest loss figure, and it is exact —
`oldest(now) − newest(previous read)`. On the existing archive it reproduces the two real HRV holes,
13.4 h and 2.1 h, and reads zero everywhere else. Both are now assertions in `BandCensusTest`, taken
from the archive's own timestamps.

Measured capacities: heart rate **2048 records** (saturated — a power of two) ≈ 4.6 days; **HRV about
21 hours** and rolling since day one; SpO₂, temperature, steps and sleep have not overflowed once in
six days. HRV is the binding constraint, and 15.5 h of HRV, stress and blood pressure had already
been lost to it before this was understood.

### Every "lost records" number the app ever printed was fake

Loss used to be `expected − inserted`, with `expected` from a nominal cadence. Heart rate is
documented at 120 s and really runs at a 240 s median, so it ran 2× high; `detail` was listed at 60 s
when one detail record is a **ten-minute** bucket, so it ran 10× high. One sync reported "detail lost
1913" having lost precisely nothing.

Fixing the constants would not have saved it: the band skips slots constantly — the periodic
heart-rate series fills only 51–77 % of its own nominal slots overnight while demonstrably on the
wrist — so any cadence-based expectation manufactures loss out of a band that simply did not measure.
It is gone, replaced by the floor reading. Old census rows still decode; the retired fields are just
unknown keys now.

The claim that the buffer was "about three days deep" was never a measurement either. The first sync
asked for three days, because the fallback is three days, and got three days.

### 自動同期 — sync every four hours, and speak only when something is wrong

A new profile and task in 「健康」 fire at 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00 — six times a
day against HRV's ~21 h, a 5.4× margin that still holds if the band quadruples its HRV rate. The cost
is a few seconds of BLE each time.

It is silent on success. It warns in exactly two cases: a sync that **could not go through** while
enough of the shallowest buffer has been consumed (threshold `Band_WarnAtPct`, default 60 %, editable
in 健康の設定), or a lost window above zero — which should never happen, and if it does means the
four-hour cadence itself is too slow. There is deliberately no routine nudge; a warning that shows
every day is not a warning.

`band.sync` now publishes `BAND_Ok`, `HeadroomHours`, `HeadroomStream`, `AgeHours`, `LastSuccess`,
`LostHours`, `LostStreams` and `PressurePct` on **every** path including failure, sourced from the
database — because the run that most needs to explain itself is the one that could not connect.

「健康」 also gains the standard 71/01/37 trio in an `起動無効` group — `健康 ⇨ 起動 -- [727][71]` loads
the settings and enables the profile, `⇨ 無効 -- [727][37]` disables it — wired into
`起動完了`'s two chains as `r7_` so it starts and stops with every other project. This matters more
than housekeeping: **a profile always imports disabled no matter what the bundle says**, so
`自動同期` was inert from import until the 71 task first ran it.

The 「健康」 window's staleness banner is fixed too. It used to warn whenever the oldest held record
was under 24 h old, which HRV satisfies permanently, so it was on every single time and therefore
said nothing.

### One notification is one frame — measured, not assumed

Records-per-notification lands exactly on `floor(244 / stride)` for four independent strides at once:
24 for heart rate and SpO₂, 22 for temperature, 16 for HRV, 9 for detail. Sleep, whose 130-byte frames
are the largest and the only ones that could plausibly fragment, came back at **exactly 255 records in
255 frames**. Every stream in all nine working syncs ended on its terminator, never on an idle
timeout, which frame-counted paging could not manage if our notification count diverged from the
band's. The census now records the longest and shortest notification per stream, so the rule stays
self-monitoring rather than merely once-checked.

### The heart-rate gaps are the band, not us

The ~47 gaps across six days were suspected of being a pipeline defect. They are not. Every one has
other sensor streams alive inside it — SpO₂ in 47 of 47, plus temperature, HRV and steps — so the band
was worn and recording and simply did not write a heart-rate sample. Hume's own hourly capsules for
2026-08-04 agree with our decode to the bpm (its headline 58–91, our pooled range 58–91) and show no
data we lack. The overnight stretch nobody had checked is checked: 51–77 % slot fill on every one of
six nights, none anomalous.

Sleep stage `4` is likewise settled: 2 970 stage-minutes over six nights, codes `{1, 2, 3, 5}` only,
zero occurrences — and Hume's own sleep screen shows exactly four stages.

## 0.2.81.2026-08-02.g97059d7b+003 — 2026-08-06

**Launching an app by intent action works again.**

Upstream 0.2.81 rebuilt `intent.launch` behind a bounded dispatch policy. Its final rule refused any
activity whose class name the task had not typed out by hand — even after the policy had already
resolved the action against the named package, confirmed the result lived in that package, and
confirmed it was exported. Naming an app and an action, which is how Android has always been asked to
take a photo, became an error: *"target component was not found or is not exported."*

That took out the 物理鍵 project's double-press camera the moment `+002` landed. `カメラ起動` asks
`com.huawei.camera` for `android.media.action.STILL_IMAGE_CAMERA`; both the screen-on camera and the
screen-off secure camera failed at the same line.

The fork drops that last hurdle and nothing else. A dispatch now fails exactly when nothing in the
named package handles the action — which is what the error message claimed all along. Everything the
rule was standing in front of still stands: the resolved component must live in the package the task
named, it must be exported, broadcasts and services still demand an explicit class, and every bound
argument upstream added — allowlisted flags, allowlisted URI schemes, no `file://`, capped extras —
is untouched.

Pinning the class in the task would have worked too, and was rejected: on this firmware
`STILL_IMAGE_CAMERA` resolves to `com.huawei.camera/.controller.VoiceAssistantActivity`, a
vendor-internal name that a firmware update can rename out from under a saved task. Resolving at
dispatch time follows the phone instead. The fork's own **Send Intent** (`intent.send`) never went
through this policy and was never affected.

## 0.2.81.2026-08-02.g97059d7b+002 — 2026-08-03

**Rebased onto upstream `97059d7b` (0.2.81)** — 62 upstream commits, the largest sync so far:
231 files, +18 455 / −1 585. What the fork took, kept, and refused:

### Taken from upstream

Eleven new actions are registered and given editor entries, ported from upstream's `@StringRes`
catalog into the fork's inline-label format: progress notifications (`notify.progress`), contact
lookup, Android 15+ Zen rules (`zen.rule.set` / `zen.rule.clear`), app archive/unarchive, shortcut
publishing, `state.temporary`, `ime.info`, a Home Assistant webhook, and `mqtt.publish`.

**Try / Catch / End Try** now appear in the editor. `TaskRunner` already implemented the frames and
the bounded exponential retry; the blocks were unreachable only because they had no metadata entry.

Upstream's **matcher pulse continuity** is adopted inside the fork's matcher: the pulse sequence now
comes from the observer, so it survives matcher rebuilds and suppresses replayed deliveries — while
the fork's per-invocation `%NOTIF_*` variable snapshot is preserved alongside it.

The **four-slot configurable Quick Settings tiles** replace the fork's single tile, with the fork's
engine-stopped gate re-applied so a stopped engine shows the tile as unavailable rather than
dispatching a run that would be dropped.

Also taken: the new triggers (SMS/MMS, Advanced Protection, Bluetooth security, screen recording,
USB, companion presence, all-Bluetooth-disconnected, received-share, UnifiedPush), wider Tasker XML
mappings, HTML/CSS-selector `data.read`, offline bundle text import, and the Kotlin 2.4.10 /
Compose BOM 2026.06 / Room 2.8 toolchain bump.

### Refused, deliberately

**SQLCipher whole-database encryption is not adopted** (白い熊, 2026-08-03). The app sandbox and
Android's file-based encryption already protect the live file; encryption would have added a native
library to an app that has none, slowed a DB-chatty engine, made backups restorable only to the
install that wrote them, and introduced a per-install Keystore key that fails closed. The read-only
opener upstream's backup review depends on survives in `DatabaseSecurity`; `DatabaseKeyStore` is gone.

Also refused: upstream's `@StringRes` action catalog (the fork's 177 entries stay inline-string),
the demand-counted calendar/sun bus (the fork's per-second tick drives the kanji clock, the 電池線
battery line, 話す時計 and the blink port), the adaptive navigation shell, and the nested ALL/ANY/NOT
context authoring UI — `Profile.contextExpression` exists so upstream's read paths compile, but it is
never set, so contexts keep combining with implicit AND exactly as before.

### Collisions resolved

`clipboard.get`, `clipboard.set` and `ime.set` exist on both sides. The fork's actions keep those ids
because saved tasks reference them; upstream's duplicates are not registered.

Projects were invented independently on both sides. The fork's model wins — the workspace mirror,
every bundle and the 71/01/37 trio convention rest on it — so upstream's 8→9 projects migration is
not replayed.

### Schema 21 → 22

`edit_history` gains `nextJson` and `isUndone` (upstream v10, renumbered onto the end of the fork
chain) for durable undo/redo. Upstream's v9 has no counterpart here: the fork had projects first.

## 0.2.79.2026-08-02.g915979d9+036 — 2026-08-03

**「健康」 — the Hume Band's history, pulled over BLE, archived, and drawn honestly.**
A new project reads the band's stored health data — heart rate, HRV, SpO₂, temperature, sleep and
steps — into the workspace, keeps an append-only archive of every record it has ever seen, measures
how much of the band's own memory it can still reach, and draws it all in its own window.

### Talking to the band

`band.sync` connects, drains every stream and disconnects; the link is never held open. The band
requires no pairing and no authentication, which is its design and not ours. Two details are the
difference between working and silently wrong: the MTU must be raised to 247, because sleep frames
are 130 bytes and the default 23-byte ATT MTU truncates them without an error, and CONTINUE paging
must carry a **zero** date rather than the original start date, which otherwise restarts the stream
forever.

The dedupe key is **the band's own wall clock**, never epoch millis. At a daylight-saving fall-back
the same wall-clock hour happens twice, and an epoch key would store that hour's records a second
time.

The firmware defines a mode that erases the band's stored history. It is not merely unused here — a
source-guard test fails the build if the opcode, the words "erase"/"factoryReset"/"clearBand", or an
`Int`-taking frame builder ever appear in the band package. There is no way to name a raw mode byte,
so the destructive command cannot be expressed.

A sync that finds nothing is a **success**, not a failure; a sync that starts while one is already
running **skips**. A stream that times out is recorded and the sync moves on, because the band's ring
buffer is the real risk and banking six streams beats abandoning the run over one.

### The census — the part that measures what cannot be looked up

The band's buffers are small and overwrite their oldest records silently, and their true depth is
documented nowhere. So it is measured: a gap that came back with no loss proves the buffer is **at
least** that deep, and a gap that did lose records proves it is **at most** that deep. The two bounds
converge from opposite sides over days of ordinary use. A stream that errored is excluded entirely —
counting it as "no loss" would inflate the lower bound with a sync that never read anything.

### The archive

Every new record is appended to a monthly JSONL file, one line each, never rewritten. The database is
what the app queries and is pruned on a schedule; the archive is the record and is not.

### 「健康」, in its own window

Not a tab. `band.charts` opens a fullscreen window, so a task — and therefore a launcher shortcut —
leads straight to the data without going through an app about automation.

The charts follow one rule, in 白い熊's words: *as close to the actual measurements as possible.*
Nothing drawn is a value that was not measured.

- **No averaging.** Decimation is Largest-Triangle-Three-Buckets, which *selects real samples* and
  preserves the visual envelope. Its buckets are anchored to absolute time, so the selection does not
  crawl while panning.
- **Filters flag; they never replace or delete.** The textbook outlier filter substitutes a window
  median, which would draw a number that never occurred. A flagged sample stays, is shown as a ✕ at
  its real value, and is counted in the footer.
- **Smoothing is monotone cubic (PCHIP)**, chosen for a guarantee rather than for looks: the minimum
  and maximum of the drawn curve equal those of the retained samples. An overshooting spline would
  draw an SpO₂ of 101 %. That guarantee is a unit test.
- **Every chart carries its own accounting** — samples, rejected, gaps, no-reading — tappable to
  reveal exactly what was dropped. That is a feature, not debug output: it is what makes filtering
  trustworthy at all.

### Two things the band does that no document mentions

The `hr` stream carries **two different measurement populations** — a periodic series and an extra
reading taken at each SpO₂ measurement, running **+7.46 bpm** higher. Merged, they make a sawtooth
that consumes the outlier filter's entire rejection budget. They are told apart by an exact timestamp
join with the SpO₂ stream; the obvious heuristic, reading the seconds field, misclassifies a quarter
of them.

And the **gap threshold is measured, not assumed.** The periodic heart-rate series is nominally
sampled every 120 seconds; it really runs at 240. Believing the nominal figure declared 231 of 848
intervals to be gaps and shredded the chart into 235 fragments.

The full protocol, every record layout, and the reason behind each filtering decision are written up
in `docs/hume-band-protocol.md`.

### Also

`band.sync` publishes live progress into variables as it runs, so a scene bound to those names
animates with no polling — and the window shows a spinner and a seconds counter from the instant the
button is pressed, because connecting takes seconds and a still progress bar is indistinguishable
from nothing happening.

## 0.2.79.2026-08-02.g915979d9+031 — 2026-08-03

**接続 keeps a history — and the engine grew four capabilities to let a workspace build one.**
Every speed run now records where it happened and what it measured; the history is browsable,
each row opens its spot on the map, and a place can be named once and read back on every run
there. None of that needed 接続-specific code in the app: what it needed was four general
primitives that any project can use.

### A position, without Play Services

`location.get` reads a fix through the framework `LocationManager`, matching the FOSS geofence
source this app already ships. It accepts a cached fix when one is fresh enough (`max_age_ms`),
because a phone that has not moved has nothing new to say and waking GPS for it costs time and
battery; otherwise it waits `timeout_ms` for an update and, failing that, **falls back to the
newest stale fix rather than failing the task**. It publishes the fix's **age** alongside it,
which matters more than it sounds: on this device, indoors, GPS does not lock and the network
provider does not answer, so the position is routinely hours old. A coordinate worth keeping is
not automatically a coordinate worth trusting, and the age is what lets the caller tell.

It carries a new **`Location` capability requirement**, so it gets the same red status pill,
Settings deep-link and pre-flight block as every other permission-bearing action.

### Scene pages can act

The `WEB` scene element renders arbitrary HTML and scrolls by touch, but it had no
`WebViewClient` — so a link could be shown and never followed. It now has a link bridge:

- `task://run?task=<name>&<var>=<value>…` sets each extra parameter as a global, then runs that
  task — the same contract a scene button has, plus arguments.
- Any other scheme (`geo:`, `tel:`, `https:`) is handed to Android as a VIEW intent.

That is what makes a **scrollable list of unknown length** possible in a scene at all: elements
are laid out at fixed coordinates, so a list has no natural home among them, but an HTML page
does — and now its rows can do something when tapped.

### Files where the user can see them

File actions resolve every path inside the app's private `user_files`, and an absolute path is
not rejected but **folded into the sandbox**, so writing to `/sdcard/…` quietly succeeded and
produced a file nothing else could reach. `shared=true` on read/write/append/mkdir resolves
against the user's own storage instead, keeping every protection the sandbox has — lexical
containment, the symlink component scan, no-follow open — and granting nothing the app did not
already hold for its backups.

Two sharp edges were filed down in passing: `/sdcard` is a symlink to `/storage/emulated/0`, so
matching only the canonical root produced a very real doubled `/sdcard/sdcard/…` tree; and
`file.read` reported a missing file with the same message as a sandbox escape, which sends you
hunting the wrong bug.

### A dialog can tell "clear this" from "never mind"

`dialog.input` stored an empty string for both a cancelled dialog and one confirmed with the
field cleared, so a task could not implement deletion: the two outcomes were indistinguishable.
It now also writes **`<store>_ok`** — true when confirmed, false when cancelled. An answer
deliberately left empty is a decision, and the engine now says so.

### 接続's history

Built entirely in the workspace on those four: each run appends one JSON object to
`接続履歴.jsonl` in 白い熊's backup tree, with its position, the reverse-geocoded place name
(OpenStreetMap Nominatim, after the run — a geocoder request during a measurement would be
measured), the fix's age, and all three legs. The list is an HTML page in a scene; a row's
coordinates open it in 白い熊 地図, and the pill beside the place name writes `接続地点名.jsonl`,
keyed by coordinates rounded to ~11 m, so naming a place names every run there — past and
future. Both files are append-only and newest-wins, so renaming costs one line and clearing the
name writes an empty value that reads back as no name.

## 0.2.79.2026-08-02.g915979d9+025 — 2026-08-02

**接続 — measuring real throughput on each SIM.** 白い熊's speed varies by location, and the phone
carries two SIMs (slot 0 T-Mobile, slot 1 O₂). This release is the engine behind a workspace project
that measures download, upload and latency per SIM and over WiFi, shows it live, and reports it. It
spans builds `+005`–`+025`; the measurement was wrong for most of them, and the corrections are the
most interesting part of the story.

### Speed testing, shaped like Ookla's

`net.speedtest` pins one transport with `ConnectivityManager.requestNetwork` and runs the transfer on
that network. Each leg is bounded by the **clock**, not by bytes: `seconds` (default 10) decides, and
`max_mb` is only a runaway guard. A byte cap that binds first ends the leg inside TCP slow-start —
a 5 MB cap finished in 0.28 s and produced a single sample. Eight parallel streams by default, because
one TCP stream is bounded by `window / RTT` and under-reports a fast link. The first `ramp_ms` (2 s) is
excluded from the headline average and kept separately in `%SPD_*Raw`.

Endpoints are discovered at run time from Ookla's server list, ordered by distance from the client IP,
so "pick a nearby server" is answered by the source of truth rather than guessed. Those servers
307-redirect `http`→`https` and `HttpURLConnection` silently refuses cross-protocol redirects, so the
download follows hops by hand and the upload pre-resolves its final URL with a zero-length probe — a
body already streaming cannot be replayed at a new location. `%SPD_*Fallback` is non-empty whenever a
figure came from a fallback endpoint and is therefore not comparable with the others; a throttled
mirror silently carried three early runs and made them look like 白い熊's link.

Cloudflare's `/__down` is deliberately absent: it answers 403 to non-browser clients. It backs their
web speed test and was never a public API, and sending browser headers to get past that would be
impersonating one against an endpoint free to start refusing again.

### Switching the data SIM without root

`sim.data.set` and `sim.list` address SIMs **by slot, never by subscription id** — this phone carries
five subIds for two physical SIMs, stale entries from earlier insertions, so a hardcoded subId breaks
on re-insertion. The switch goes through a Shizuku UserService calling `ISub.setDefaultDataSubId`;
shell holds `MODIFY_PHONE_STATE`, and Shizuku runs as shell. `cmd phone` has no data-sub subcommand,
and `settings put global multi_sim_data_call` only mirrors the choice rather than driving it.

Shizuku UserServices are instantiated **by name**, so they need R8 keep rules. Without one,
`bindUserService` returns a null binder and the only symptom is a message about the privileged
telephony bridge failing to start.

### Cancelling a run now actually stops it

The cancel button was reached through a scene tap, and it did nothing useful. Two faults, either of
which was enough on its own:

- The transfer's stream loop checks the cancel flag on every buffer — but the fallback beneath it,
  `if (!served) runStream(…)`, exists to guarantee one stream when the deadline has already passed and
  it fired on **cancel** too. All eight workers therefore opened a connection and ran a full stream
  *after* the tap. A cancel during download also rolled straight on into the upload leg's URL
  resolution and connection setup. Measured tap-to-stop fell from ~12 s to **194 ms**.
- `接続 -- 中止` restored the SIM before WiFi, unguarded. Cancelling with no run in flight handed
  `sim.data.set` an empty slot; the action failed, the task aborted on it, and the WiFi restore —
  sitting after it — never ran. The cancel button could leave the phone with WiFi off, which is the
  one state that costs a chat its route back to the device. WiFi is restored first now, and every
  step between "WiFi off" and that restore is non-fatal.

### What the upload was really measuring

白い熊 noticed uploads starting near the download's closing figure and falling away, then continuing to
fall for several seconds after the progress bar reached 100 %. That instinct was right, and it turned
out to be three separate faults:

- **The measured window ran until the workers returned.** Past the deadline an upload worker still
  flushes its socket buffer, closes the stream and waits for the server's HTTP response — seconds in
  which no bytes are counted while the clock advances. On a 10 s leg a 5 s tail understates the result
  by a third, which was the whole of the upload's long-standing gap against Ookla. The window now ends
  at the last counted byte.
- **The leg did not stop at the deadline either** — 12 s of draining after a 10 s leg, sitting at zero.
  Nothing measurable happens there, so the connection is dropped instead.
- **Bytes counted as sent when `write()` returned**, i.e. on entering the kernel's socket buffer rather
  than on reaching the wire, so whatever was still buffered counted as delivered. Throughput now comes
  from the kernel's own per-UID counters, which advance only when bytes leave the interface.

`%SPD_Cur` was also being set to the same cumulative mean as `%SPD_Avg`, so the live headline could only
ever decay — exactly the behaviour that looked like bleed from the previous leg. It is a true
instantaneous rate now.

Against a same-hour Ookla reference on T-Mobile (192 / 58.4 Mb/s) the fork reads 163.82 / 66.39: −15 %
and +14 %, in opposite directions, which is variance between two runs rather than bias. Upload had been
−64 %. Part of the residual is honest accounting — the kernel counters include TCP/IP headers, which
Ookla's payload figure does not.

### Latency is a round trip again

`%SPD_*Ms` is time-to-first-byte: DNS, TCP, TLS and the server composing a response. It read 250–320 ms
against Ookla's ~27 ms, and the two were never comparable — hence its 初byte label. `net.speedtest` now
also measures the **TCP handshake** to the server the leg is about to use, five samples, before the
transfer while the link is idle; measuring under load would report bufferbloat instead. SYN to SYN-ACK
is one round trip, which is the quantity Ookla reports. Published as `%SPD_Ping` with min, jitter and
loss beside it. Measured: Wi-Fi 5.92 ms, T-Mobile 22.72 ms, O₂ 30.89 ms — against 83 / 284 / 273 of
初byte on the same run.

It is deliberately **not** `/system/bin/ping`: a subprocess does not inherit `bindProcessToNetwork`, so
on a cellular leg it would time whatever the system default route is rather than the SIM under test.

### Scenes: shape colours, and a direction that reads at a glance

`RECTANGLE` and `OVAL` read their fill straight from the raw config map while every other element type
goes through the expander, so a colour given as a `%variable` stayed a literal string, `sceneColor()`
returned null, and the shape drew **nothing** — while a literal-coloured sibling in the same icon
rendered fine, which made it read as a layout bug. Fixed, and it repairs variable-coloured shapes
across the whole workspace, not only in 接続.

`net.speedtest` also publishes `%SPD_Arrow` — `↓` while downloading, `↑` while uploading, and empty the
moment a run ends or is cancelled, so an overlay can never show a direction with no transfer behind it.
A scene cannot branch on a variable and the phase only exists while the action runs, so this had to come
from the engine.

### Per-network binding, and why WiFi has to be switched off

`requestNetwork(TRANSPORT_CELLULAR)` succeeds on this device and a cellular network does come up while
WiFi stays connected — but using it fails with `EPERM` when binding the socket, confirmed both
per-connection and process-wide. Both code paths are kept, process-binding preferred, so this works
unchanged on a device that permits either; but on 白い熊's phone a SIM can only be measured while it is
the default route. The workspace therefore saves WiFi state, switches it off, and restores it.

## 0.2.79.2026-08-02.g915979d9+004 — 2026-08-02

**Rebased onto upstream `915979d9`** — ten upstream commits that never bumped upstream's own version,
which is exactly why the version string changed shape this release (see below).

### The version now names the upstream commit we sit on

`<upstream version>.<base commit date>.g<8-char base sha>+<NNN>`. Upstream sat on `0.2.79` for this
whole ten-commit stretch, so the bare literal said nothing about how current the fork was. The pin is
`git merge-base HEAD master` — the upstream commit our patches sit on, not our own HEAD and not
`master`'s tip — plus that commit's own committer date so builds sort chronologically. `+N` is
zero-padded to three digits in the name only; `versionCode` keeps the plain integer
(`appVersionCode * 10000 + N`) and upstream's literals are read back, never edited.

`BUILD_NUMBER` resets to `1` on **every** sync, including one where only the sha moves. When upstream
ships commits without bumping `appVersionCode` that makes the new `versionCode` lower than the
installed one, so deliveries now use `adb install -r -d`.

Implementation note: the pin is computed with `providers.exec`, not a raw `ProcessBuilder` — Gradle's
configuration cache refuses an external process started at configuration time.

### Skipped and cancelled runs are no longer reported as failures

Upstream's new admission controller applies each task's collision policy at the shared execution
boundary, so a scene slider that fires repeatedly during a drag now records genuine *Skipped* runs.
Those are stored with `success = false` — correct for the record, but three UI surfaces read that raw
flag and called them failures: the Run Log's red "Last failure" banner, the workspace card's
`recentFailure`, and the Monitor tab's per-task failure count (whose own hint reads "red = at least
one run failed"). All three now classify through `RunLogEntry.outcome()`, which already distinguished
`Failed` / `Skipped` / `Cancelled`. Skipped runs stay fully visible as their own state.

### The top bar shows the installed version

The full version string sits to the right of 白い熊 自由作業盤, bottom-aligned and at 66% of the name's
size (taken from the ambient text style, not hardcoded), so a glance says which build is running. The
name yields width first, so the version is never the part that gets ellipsized.

### Upstream designs deliberately not adopted

Four collisions, each with the corresponding upstream contract test retired and commented:

- **Bundle format** — upstream added a schema-1→2 migration and task-id remapping; the fork's format
  is id-free and name-based, so `OpenTaskerBundle.kt` stayed ours.
- **Typed action fields** — upstream reworked `ActionField` into `@StringRes` labels plus
  `FieldType.TASK/APP/FILE` with validation rules. The fork's inline-string catalog cannot be
  expressed in it; the orphaned `ActionFieldPolicy.kt` was removed.
- **The visual redesign** — upstream's sage/graphite palette against our black-and-yellow. `Theme.kt`,
  the screens and the launcher icons stayed ours. Upstream's retuned `DesignSystem` spacing and radii
  *were* taken, so the UI is slightly denser than before.
- **Run-log pagination and export UI** — needs upstream's ViewModel, which we kept fork-side.

### Worth recording: our scene gestures never used upstream's broken path

Upstream fixed scene tap/long-press bindings that built raw `RUN_TASK` intents without
`PROTOCOL_VERSION=2` and were rejected before enqueue. That fix is inert here: `scene.show` routes to
the fork's `SceneOverlayManager`, whose gesture handler calls `executeAndLogTask(…, source = "Scene")`
directly in-process — no intent, no receiver. Upstream's `SceneOverlayService.fireRunTask` is not
reached. Verified on-device: a scene button tap ran its task and logged `Source: Other: Scene`.

Also taken from upstream: the Android Keystore GCM-IV fix in the secret codec, feedback-loop risk
analysis, and a corrupt-task guard on widget/shortcut taps.

## 0.2.79+1 — 2026-07-31

**Rebased onto upstream OpenTasker 0.2.79.** The first build of the new line. Two upstream designs
collided with fork designs and the fork's were kept; everything else upstream shipped came across.

- **Setup clarity**: the checklist now separates engine baseline, enabled-automation requirements, optional integrations, and reliability guidance. Permission rows are derived from enabled profiles and reachable task actions, so an empty workspace no longer reports unrelated automation blockers.

- **Execution identity**: profile, manual, widget, shortcut, notification, Locale, Scene, and external runs now share a bounded structured envelope and idempotent command ledger. Run Log entries persist one execution ID, producer, causal parent, and redaction-safe terminal reason; active execution state and external dispatch preserve the same identity across admission, cancellation, and terminal projection.

- **Diagnostics**: engine health now aggregates timestamped Loading/Ready/Stale/Error evidence instead of treating one old observation as current. Heartbeat freshness, matcher failures, standby throttling, exact-alarm fallback, watchdog failures, scheduler constraints, advanced-protection warnings, and active/pending executions expose concrete reasons in Diagnostics and the redacted report.

- **Context observation**: calendar and sun event pulses now use one demand-counted hot bus shared by engine matchers and the visible Context Inspector. Calendar queries start only when a calendar event is requested, and the Inspector releases its collectors when it leaves the screen while showing Loading/Ready/Stale/Error observation health and age.

- **Localization coverage**: secondary navigation, workspace notices, ViewModel messages, Context Inspector, Run Log, Diagnostics, and Flow graph copy now resolve through Android resources, with source-contract tests preventing new hardcoded visible English.

- **Locale compatibility**: host component discovery and broadcast transport are injectable for a deterministic synthetic setting/condition plugin fixture. Instrumentation coverage now exercises configuration, fire/query dispatch, request-query events, result codes, and bundle-argument redaction without depending on a third-party plugin installation.

- **Home Assistant bridge**: added a bounded outbound webhook action using the existing HTTP/LAN policy. HTTPS is the default, webhook URLs and JSON payloads are redacted, payloads are capped at 16 KB, and only transient failures receive capped exponential retry.

- **MQTT bridge**: added `mqtt.publish` with an in-app MQTT 3.1.1 QoS 0/1 client, platform TLS, retained messages, bounded payloads/timeouts, optional credentials, and private-LAN gating for cleartext. No new client dependency is required for F-Droid.

- **Push trigger spike**: added an authenticated `event=push` bridge for a de-googled UnifiedPush distributor, with a per-install Setup token, bounded payloads, duplicate suppression, and redacted event metadata.

- **Tasker migration**: expanded XML import/export mappings for safe notification, variable, speech/vibration, volume/brightness/timeout, torch/media, app/URL, screenshot, and structured flow actions. Unsupported actions remain explicit, and lossy notification/volume fields are listed in the migration report.

- **Profile sharing**: added an editable local community-share preview for workspace and imported bundles. The preview accepts bounded screenshot attachments with local thumbnails, renders trust and safety findings, exposes the computed bundle import plan, and hands off to the existing variable-conflict review before any Room write.

- **Preflight runner**: added side-effect-free task/profile previews with bounded synthetic event variables, flow branch decisions, expanded arguments, setup gaps, intended effects, and explicit blockers for unsupported actions. The review surface never invokes runtime actions or persists variables.

- **Intent dispatch**: generalized `intent.launch` into bounded activity, explicit broadcast, and explicit service delivery with URI/MIME support, allowlisted flags, capped primitive extras, ordered-broadcast result capture, and exported-target checks. Unsafe URI schemes, parcelable-style extras, ambiguous or non-exported external targets, and unapproved implicit broadcast/service dispatch fail closed.

- **Local projects**: added a first-class Room-backed workspace boundary with atomic Default-project migration, project-scoped runtime variables, shared filtering across automation surfaces, project-preserving bundle import/export, cross-project reference warnings, and explicit variable-safe reassignment before deletion.

- **Structured data**: `data.read` now supports bounded HTML parsing with CSS selectors and normalized element text. The jsoup dependency is pinned, checksum-verified, MIT-licensed, and performs no network I/O in the action.

- **Received Share trigger**: OpenTasker now appears in Android's Sharesheet for bounded text, URLs, MIME-typed content, and single or multiple file/content URIs. Share filters can match MIME, text, URI, and multiplicity; sanitized `share_*` variables reach the selected task, while oversized and arbitrary Parcelable extras fail closed.

- **Nested context logic**: profiles can now persist and edit recursive ALL/ANY/NOT expressions over their existing context leaves. Legacy implicit-AND profiles remain unchanged, the Inspector explains the evaluated tree, OpenTasker bundles preserve grouping, and Tasker XML export reports its unavoidable flattening.

- **Flow validation**: added a complex graph fixture covering multiple contexts, conditional branch labels, subflow markers, missing-task repair targets, continuation semantics, and screen-reader summaries. The Flow surface keeps zoom/pan and picker-backed add commands, while direct drag/drop persistence remains deferred until those editor paths have broader UI coverage.

- **Release trust**: a generated `tools/release-truth.json` manifest now owns version/code, Android SDK, dependency, capability-count, bundle/Room schema, and immutable artifact-commit claims. The local quality gate validates the manifest against shipped source, README, and F-Droid metadata with configuration-cache-safe verification.

- **Execution authoring**: profile editing can now select or clear an exit task; task editing exposes the previously stored collision policy; action editing exposes conditions and continue-after-failure; and task cards provide accessible move-up/down controls backed by a transactional history snapshot. Task collision admission now runs at the shared execution boundary for profile, manual, nested, widget/shortcut, notification, and external requests: Abort new logs a skipped run, Abort existing cancels the active coroutine tree, Wait serializes requests, and Run both permits overlap. Profile re-trigger mode remains the earlier profile-specific decision, while the referenced task's collision policy is the global last-mile rule.
### What the fork kept
The **action catalog stays inline-string** — our 158 entries with the `COLOR`, `WIDGET_LAYOUT` and
`APP_PACKAGE` field types. Upstream converted its 69 entries to `@StringRes`; adopting that would have
meant rewriting every fork action into string resources for a translation that will never exist.

The **bundle format stays schema 5, id-free and name-based**. Upstream is still on id-bearing schema 2,
and 0.2.79 added a rewriter that remaps task-to-task ids on import — a bug name-based linking does not
have. Keeping ours also keeps `scene.show`, notification buttons and sub-task steps resolving by name
across re-imports.

### What came from upstream
**External intents are protocol v2 and asynchronous.** `RUN_TASK` no longer holds the broadcast open
for a task that may run for half an hour: the receiver validates, hands the run to the foreground
service, and replies `ACCEPTED` with an execution id the caller polls via `QUERY_EXECUTION`. Callers
must declare `PROTOCOL_VERSION=2`. The fork's shutdown gate sits ahead of that handshake, so a stopped
app answers 「is stopped」 rather than a version complaint the caller cannot act on; `QUERY_STATUS`
stays ungated and still reports `STOPPED`.

**Stored action arguments are redacted wherever they are displayed** — the task list and flow graph
were joining raw arguments into their subtitles, so an `authorization` header or request body typed
into an action appeared on screen and in screenshots. Nine credential-bearing fields now declare their
sensitivity explicitly (`http.request` authorization/headers/query/body, `http.post` data, `sms.send`
message, `script.termux.run` stdin), with `headers_var` and `body_file` marked structural so the
name heuristic stops over-masking them. One canonical `<redacted>` placeholder replaces the copy the
task runner kept.

**A run can be cancelled**, and a cancelled run is its own outcome in the run log — distinct from
Skipped, which means the run never started. Upstream's execution registry replaces the fork's
equivalent, which was written the same week and did the same job.

Also inherited: reference-safe task deletion and rename, per-step variable-write records, the
"scheduled jobs blocked by" diagnostic, `WRITE_SETTINGS` finally declared (so Set brightness and screen
timeout can actually be granted), and `app.kill` marked Unsupported rather than advertising a
force-stop it never had the privilege to perform.

### Known gaps from the merge
Upstream's **restore review is unwired** — its manager API is present, but the fork's ViewModel still
stages a selected database immediately instead of showing the review-and-confirm step. And nothing now
forces an explicit capability entry per action; unknown ids still fail closed, but the fork's 96
unclassified actions rely on the shipped-action default.

## 0.2.78+54 — 2026-07-31

**The app can be shut down, and it stays shut down.**

### Exit app fully
The top-bar ⋮ menu gains 「Exit app fully」 and 「Restart engine」. Exit runs the tasks listed under
Monitor ⇨ Run on exit — the mirror of Run on start, so the app never has to know a project name — then
raises a report of everything **still** live, and only tears down once that is confirmed. The report
comes first on purpose: a dialog shown after the app is gone cannot be read, and its whole value is
naming what should already have stopped and had not. It is written to the run log too, so it survives
the dialog.

Stopping the engine is not enough on its own to keep this app down. The per-minute exact alarm
resurrects it, and the accessibility and notification-listener services are bound by the system, so the
process returns within seconds however it is killed. Exit therefore sets a **persisted flag**, and every
way back in — the resurrect alarm, boot, the quick-settings tile, widget and shortcut taps, notification
buttons, sister-app token intents, the adb bridge's `RUN_TASK` — declines while it is set, writing a
`停止中 — refused …` row to the run log rather than failing invisibly. Export, import and status queries
are deliberately **not** gated: they start nothing.

The process itself does not die, and cannot be made to. The accessibility service goes dormant instead —
never `disableSelf()`, which would drop the grant and cost a trip through system settings — and the
notification listener `requestUnbind()`s, which keeps its grant and rebinds silently when the app is
opened again. Opening the app lifts the stop; so does a reboot, unless the new 「Start engine on boot」
switch is off.

### Live now
Monitor gains an honest list of everything the app is holding open — in-flight tasks, scenes, bubbles,
the progress panel, the engine — each row stoppable on the spot. It is the same inventory the shutdown
report draws from, deliberately: the moment you want to look at something that should not be running is
usually not the moment you want to exit.

### Bubbles outlived the engine that owned them
Freeze and flash bubbles were never torn down when the engine stopped. Their collectors died with its
scope, but the windows belong to the WindowManager and stayed on screen — with `started` still true, so
a restart never re-subscribed them. Both now stop properly, which is also why a restart re-establishes
the whole overlay layer.

### One privileged process leaked per install
Shizuku keys a UserService by (component, **version**), and the 物理鍵 key grabber passes the app's
versionCode so each build gets fresh code instead of a stale process holding the previous APK's
`libevgrab.so`. The cost was that after an update the old version could no longer be named, so it was
never unbound: five `shell`-owned `:keygrab` processes were alive after a single morning of builds.
Every version ever bound is now recorded and swept, with a one-off window of recent build numbers to
clear an existing backlog.

### The permission dialog's settings buttons did nothing
Four separate faults, all of them old. `DialogActivity` is declared with an empty `taskAffinity` and
`excludeFromRecents`, so launching a settings page backgrounded its own task, and the destroy that
followed reported a `Cancelled` the user never chose; a per-minute profile then re-raised the modal on
top of Settings within the minute, which is what made the button look broken. The Shizuku button pointed
at upstream's `moe.shizuku.privileged.api`, which is not the fork installed here, so it silently fell
through to a web page — and the missing `<queries>` pin would have hidden the fork anyway on Android 11+.
Every failure was swallowed by a bare `runCatching {}`.

Now: the pill settles deliberately after a successful launch, a three-minute quiet window per requirement
keeps the modal from stacking on the page it just opened (the task stays blocked and still logs), Shizuku
resolves fork-first with both ids pinned, and anything that will not open says so. The setup guide URL is
our own `shiroikuma-shizuku` page rather than upstream's, which describes a different app and a different
APK. The manifest contract test now asserts the whole manager list, since it was only ever checking the
id that was not the problem.

### Also
- `SET_STARTUP_TASKS` on the adb bridge sets run-on-start / run-on-exit / start-on-boot **by task name**,
  so the dev loop configures them instead of handing 白い熊 device steps.
- `QUERY_STATUS` answers a new `STOPPED` boolean, ungated, so a caller can ask before firing something
  that would be refused.
- The stop flag is cached in memory: it is read from `onAccessibilityEvent`, which the framework calls on
  the main thread for every window-state change on the device, and disk I/O does not belong there.
- `hand-off-backup-automation.md` is now `sister-app-contract-backup-automation-hand-off.md`, with a header saying
  plainly that it is implemented **by a sister app** and is not outstanding work here.

## 0.2.78+47 — 2026-07-28

**The progress highlight lands on the category the app is actually writing.**

### The number was read one row too low
A backup of 白い熊 Handy RSS drew 「UI fonts」 as the running item while the counter beneath it read
「区分 4/9 — Downloaded images 883/3680」. The contract said `current` **counted what was finished**, so
the relay activated `current + 1` — but no app in the family was ever written that way: Handy RSS,
白い熊 音楽, 空中線 and 応用管理 all send the **position of the category they are writing**, paired with
that category's own name in `text`. The contract line was the error, and it has been corrected: a number
that survives the `index_total` guard is the 1-based position of the item being written now. Every app
that sends numbers only — around fifteen of them — was drawn one row low; all of them are fixed by the
same change. (An app that sends `item` was never affected: the id addresses the row directly.)

### A row the marker has left keeps a ✓, not a ▶
Activating an item ticked off the **pending** rows above it and left previously-active ones alone, so
each row the marker passed through kept its ▶ and a nine-row pane could claim four categories were
running at once. Rows above the marker are now finished, whatever they were — except a failure or a skip,
which is a verdict rather than a leftover.

## 0.2.78+46 — 2026-07-28

**The backup window becomes an ordinary window, gains a destination, and learns to stop.**

### The panel is an Activity now, not a system overlay
It floated over every app and answered to none of Android's gestures: Home did not put it away, it never
appeared in recents, and during a backup that can run for an hour there was no way to set it aside and
use the phone. It is now `ProgressPanelActivity` — **Home backgrounds it and the run carries on, recents
lists it, tapping it there resumes exactly where it was**, and Back backgrounds rather than abandons.
`singleTask` with its own task affinity, so it is a separate entry from the workspace UI and can never
stack a second copy. Nothing about a run depended on the window: it is driven by the task and the app's
broadcasts, and the panel only renders the state flow — so being backgrounded, folded or rotated changes
nothing. The panel no longer needs "Display over other apps" (bubbles and scenes still do).

### A destination you can change for one run (個別保存)
A tappable pill above the list shows where this run will write. Tapping it turns the panel into a
**folder browser** — sub-folders, 「‥ 上へ」, 「ここに保存」 — and the choice lands in `%BR_RunDir`:
**the configured export directory and 01 are never touched**, and the override is cleared every time the
plan opens, so it cannot leak into a later run. The pill tracks the folder being walked and says which
of the three states it is in (`保存先` / `選択中` / `保存先（今回のみ）`). 保存中核 writes to
`%BR_Dir` unless the pill overrode it; the repair sweep follows the same resolution.

### 中止 actually stops the app
It used to stop 自由作業盤 *listening* — the app carried on to the end, renamed its part-file into place
and delivered a backup that had been cancelled. 保存中核 now fires **`CANCEL_EXPORT`** at the app, which
deletes its partial and answers `ERROR:cancelled`. The wire contract gained the action, including the
requirement that it be reachable from the **exported receiver**: a stop path on a non-exported service
cannot be triggered by the app that started the export, which is exactly why Jami's three working stop
buttons were useless to the batch.

### Progress-panel fixes
- **The highlight only moves down.** An app may revisit a category — Jami loops over accounts writing
  each one's chat texts then its files — and following that faithfully made the marker jump between rows
  during one apparent phase, which reads as a fault. Counters still update; the marker does not go back.
- **The item pane lists what is actually being exported.** It was built from the saved selection while
  the export ran the plan's per-run choice, so a category ticked in the plan was written but never shown.
- **Folder-browser rows take taps.** The shared row renderer only attached a click handler to rows that
  could unfold, which a folder entry cannot.

## 0.2.78+41 — 2026-07-28

**Two counters, and a highlight that points at the right thing.** The batch's progress pane was
reading one number as two different things, and an app with a corpus made that visible.

### The bug
An app reports real counts as it works — but *what* it counts changes with the step: categories while
it walks them, files or messages while it writes one of them. The relay read that number as a row
index, so when 白い熊 GNU Jami exported its chat corpus and reported 「ファイル 1234/8942」, the panel
tried to tick row 1234 and highlight row 1235 of a **nine-row** list. Nothing ticked, and a four-digit
count sat under a row that was never the thing being written.

### The contract gains three optional extras
- **`item`** — the category id being written *right now*. The panel highlights that row and ticks off
  everything above it, with no arithmetic. This is the fix; everything else is fallback.
- **`bytes` / `bytes_total`** — the second counter.

All additive: an app that sends none behaves exactly as before.

### This side
- `progress.item` gains **`key`** (address a row by category id) and **`index_total`** — a number is
  honoured as a position only when the app's own total equals the number of rows on the pane, which is
  precisely the case where the count really is a walk through the categories. Any other shape moves no
  highlight, so a corpus count can no longer point at nothing.
- Activating an item marks the still-pending items above it done — an export walks its items in order.
- **Both counters on one line**: 「ファイル 1234/8942 · 512 MB / 4.2 GB」, byte sizes formatted as
  everywhere else in the panel.
- **Sub-options are no longer dropped from the item pane.** `progress.row` used to keep top-level rows
  only; selected sub-options now appear indented under their group, so an app's parts (Jami's
  `chat_texts` / `chat_files`) are visible as the rows they are.

## 0.2.78+40 — 2026-07-28

**Picking a couple of apps, and deciding once what each app backs up.** Two entries either side of
「保存」: one that opens the same plan with nothing chosen, and one that edits every app's saved item
selection in a single window.

### 個別保存 — the plan, opened empty (`backup.plan` gains `preselect`)
- The same window and the same behaviour as 「保存」, but **no app is ticked**. Tick one or two, press
  保存開始. Backing up a single app no longer means opening the roster and deselecting thirty-two.
- The items inside are unchanged: ticking an app brings its saved selection with it, already ticked,
  and the run still writes only `%BR_RunApps` / `%BR_Run_<App>`, so saved defaults survive untouched.
- `preselect` = `saved` (the default, what 「保存」 uses) or `none`.
- Pressing the button with nothing ticked used to do nothing, silently; it now says so.

### 保存項目一括選択 — the whole roster's items in one window (`backup.edititems`)
- **A sweep, then an editor, in the same window.** Every app is asked for its current
  `LIST_CATEGORIES` — one progress row each, 中止 available, frozen apps thawed and re-frozen — which
  refreshes `%BR_Cat_<App>` so newly added items and changed labels appear. The window then becomes
  the editor: each app unfolds to its items, ticked as its saved selection has them.
- **保存 writes both places**: `%BR_Items_<App>` for every ticked app, and the matching line in
  「保存復元の設定 -- [979][01]」, so the choice outlives a restart. It becomes the default every later
  backup starts from.
- **An app's own tick means "save this app"** — unticking one leaves its saved selection exactly as it
  was, so editing a single app never disturbs the rest.
- 保存中核 gains `%BR_Mode=list`: fetch the list, re-freeze, return — no dialog, no export.

### The progress panel
- `progress.show` gains **No item pane** (`single`): a run whose steps have nothing to list under them
  drops the lower pane entirely and gives the step list the whole window, instead of drawing an empty
  frame under a stray header.

### The export contract, in this app too
- This app's own `LIST_CATEGORIES` now sends the fourth field — `id⇥label⇥parent⇥on|off` — like the
  sister apps it collects. Nothing here is large-derived-and-re-creatable, so every category is `on`.
- An absent `items` extra now means **this app's default set** rather than "everything", which is what
  the contract has always specified; the two coincide here only because nothing is opt-out.

## 0.2.78+38 — 2026-07-28

**The backup batch becomes one window.** 保存復元 used to be two lines of text on a floating panel
and a wall-of-text summary dialog; a frozen app cost ten silent minutes; a failure could only be read,
not acted on. The run now opens as a plan, becomes a live two-pane progress view, and ends as a report
you can repair from — all in the same window.

### The progress panel (new)
- **Two auto-following panes** — the run's steps on top, the current step's items below. The active
  row is parked `(lines-1)/2` down, so finished work stays visible above it and what is coming stays
  visible below; a hand-scroll suspends the follow for 5 s so you can look ahead without being yanked
  back. Finished rows are ticked and dimmed, failures washed red, the running row highlighted.
- **Real counters, never a percentage** — 「アプリ 7/33」 in the header and the app's own live line
  (「書籍 1234/8942」) indented under the item it belongs to.
- **中止 stops within a second**, not at the end of the current wait: pressing it aborts the pending
  `intent.send` reply as well as skipping the rest, then goes straight to the report.
- **Fold-out rows** — a finished app opens to the items it exported and the path it wrote.
- Actions: `progress.show` / `progress.row` / `progress.item` / `progress.finish` / `progress.hide`.
  Addressing a panel that is not showing is a silent no-op, never a task failure.

### The plan (new — `backup.plan`)
- 「保存」 no longer starts anything. It opens the roster as a tick-list: every app selected, each
  unfolding to its own items **with the app's labels and sub-option indentation**, ticked as that
  app's saved selection has them. Select/deselect-all for the apps, and for the items inside each app;
  a group item carries its sub-options with it.
- The app's line folds it open; only its checkbox selects it. An item's line selects the item.
- The choice is published to `%BR_RunApps` / `%BR_Run_<App>` and 「保存実行」 runs it — the saved
  `%BR_Items_<App>` are never overwritten, so narrowing one run (or adding an item the saved selection
  leaves out) is free.
- `backup.runitems` resolves what an app should export now: the per-run choice, else the saved
  selection, else empty — which the contract reads as the app's own defaults.

### The report + repairs
- At the end the item pane folds away, the button becomes OK, and the list stays browsable with live
  ✓/✗ counts. A failed row opens to the **full** error text plus the repair that fits it:
  **「全ファイルアクセスを許可」** (opens that app's All-files-access page), **「停止して起動管理へ」**
  (force-stops a wedged/starved app and opens Huawei's アプリ起動管理 — it does *not* re-run, because
  nothing changes until that setting does), and **「保存し直す」** for the app alone, plus
  「失敗をすべて保存し直す」 for the lot.
- Every retry first **sweeps that app's unreadable archives** — a killed export leaves a ZIP with no
  end-of-central-directory record that is indistinguishable from a real backup until you try to
  restore it.

### 保存整理 — pruning (new — `backup.prune`)
- The backup directory as a tick-list: one row per app, archives newest-first, everything but the
  newest pre-ticked, per-app 「(11/12) · 554.8/605.2 MB」 and grand totals of both count and size.
  Single files toggle by hand; nothing is deleted until 「削除」; the same window then reports what went.

### Frozen apps, and not waiting on the dead
- **`app.frozen`** (a plain PackageManager read) plus thaw → export → **re-freeze exactly what was
  frozen**, on every exit path including failure and 中止. A `pm disable-user` app cannot receive
  broadcasts at all, so a frozen target previously sat out the whole 600 s timeout in silence.
- A **`LIST_CATEGORIES` pre-flight** doubles as a liveness probe: a dead or unimplemented app fails in
  20 s instead of 600.
- **`intent.send` gained a watchdog** that judges **progress, not noise**: each progress broadcast is
  fingerprinted, and an app whose reports stop *changing* is given up on — which also catches an app
  that heartbeats while hung. Reported apart as 応答途絶 (nothing arrived) vs 作業停止 (arrived,
  unchanged).

### Item picker
- `backup.categories` parses a `LIST_CATEGORIES` reply natively (the optional fourth `on`/`off` field
  is positional, which a chain of regexes cannot take apart safely), replacing eleven `var.replace`
  steps in the core task.
- The picker is now drawn from **the app's own defaults every time** — opening it is not an existing
  choice, the choice is what you make in it — and it stores the whole catalogue (ids, labels,
  parent/child structure, defaults) in `%BR_Cat_<App>`, which is what lets the plan window show proper
  labels and indentation.
- `param:mode` is gone from all 33 wrapper tasks (it made a hand-run wrapper warn about a missing
  template value); mode travels in `%BR_Mode`.

### Contract (`sister-app-contract-backup-automation-hand-off.md`)
Generalised — no app-specific incidents — and hardened with what this run taught, with the self-test
checklist replaced by "verification happens in 自由作業盤":
- **Never export from a receiver.** `goAsync()` does not extend the broadcast window (~10 s foreground,
  ~60 s background); overrun it and the system ANRs and kills the app mid-export. Foreground service,
  `startForeground()` within 5 s, wakelock.
- **A heartbeat is a promise, not a shield** — bound every blocking step (per-file timeout,
  skip-and-continue with the skips counted in the reply, an overall ceiling that replies `ERROR:`).
- **Write archives atomically** (`<name>.part` → rename), and delete the partial on every failure path.
- **Never persist an in-progress flag**, and never let one outlive its export.
- **Check `isExternalStorageManager()`** and reply exactly `ERROR:no-storage-access` — that string is
  what arms the grant button; a raw `EACCES` can only be read.
- **A fourth `LIST_CATEGORIES` field** declares each item's default (`on`/`off`, absent = `on`), so an
  app can mark large, derived, re-creatable data opt-out; `items` absent now means the app's defaults.
- An export that runs for minutes needs the OEM to allow it — a foreground service is not enough on
  EMUI, which force-releases the wakelock and starves the process.

### Fixes
- `DELETE_ITEMS` can target Unfiled items (it required a real project).
- The retired 保存進捗盤 scene is gone; the panel replaces it.

## 0.2.78+11 — 2026-07-25

**Self-registering backup roster.** Adding a sister app to the one-tap backup used to mean three
manual steps behind one tap: pick the app, then hand-add its two settings rows, then discover the
per-app wrapper task was missing when the item picker died on `sub-task not found`. Picking the app
is now the whole job — three new task-editing actions let a task build out its own configuration.

### Actions
- **Add Action** (`task.addaction`, Tasks) — insert an action into ANOTHER task, but only if it isn't
  there yet. Identity is *(action type + `name` arg)* — the pair `task.editaction` matches on — so
  re-running never duplicates a row. Placement (`at`): `end`, `start`, a 0-based index, or **`sorted`**
  — with `sortPattern`, a regex over the `name` arg whose first capture group is the sort key. A
  sorted insert appends after the last matching action and then stable-sorts that whole region, so a
  new entry lands in its alphabetical slot, equal keys keep the order they were added in, and actions
  outside the region never move. Args are written **verbatim** — deliberately not expanded a second
  time — so a label built with the literal-`%` trick lands in the target task as a literal
  `%BR_Token_<App>` rather than that variable's value. Optional `store` reports `added` / `exists`.
- **Task Exists** (`task.exists`, Tasks) — store `true`/`false` for whether a task of this name exists,
  optionally scoped to one project, so a task can generate a missing sub-task instead of failing on it.
- **Sort Group Tasks** (`tasks.sort`, Tasks) — put one Tasks-tab group back in alphabetical order,
  positioned below the project's ungrouped tasks, for groups that grow a generated task per app.

### Engine
- The group-ordering rule that `tasks.launchers` applied to its generated group moves into a shared
  `sortGroupTasksAlphabetically()`, so the launcher generator and `tasks.sort` order groups identically.

### Workspace (保存復元 project)
- **『保存対象選択』** now finishes the job for every app it picks: each one's `%BR_Token_<App>` /
  `%BR_Items_<App>` pair is added to the `[979][01]` settings task if absent (whole Token/Items block
  re-sorted by app name), and any app whose `保存 ⇨ <pkg>` wrapper is missing is handed to
  『保存作成』 to be generated on the spot. A closing dialog names the apps still awaiting a pasted
  token. The existence check defaults to *exists*, so a failed lookup can never trigger a regeneration
  that would overwrite a wrapper's hand-written label.
- **『保存作成』** ends by sorting the 保存タスク group, so a freshly generated wrapper sits with its
  neighbours instead of at the bottom.

## 0.2.78+8 — 2026-07-25

One-tap backup of **every** 白い熊 app: this build is the app-side half of a cross-app export
contract, plus the pickers, timeouts, and file naming the new **保存復元** workspace project needs.

### The sister-app state-export contract
- **`StateExportReceiver`** (exported, token-gated) answers two new actions:
  - `shiroikuma.jiyusagyoban.action.EXPORT_STATE` — runs the full category-ZIP export headlessly
    (no UI). String extras: `token`, `path` (absolute directory; **overrides** the configured SAF
    directory), `items` (comma list of category ids; absent = everything), `progress_action`, plus
    the reply trio `reply_action` / `reply_package` / `reply_id`. Replies
    `OK:<path>|<bytes>|<human size>|<n> categories` or `ERROR:<reason>`.
  - `shiroikuma.jiyusagyoban.action.LIST_CATEGORIES` — replies `OK:` + one `id<TAB>label` line per
    exportable category, so a caller can offer a live item picker.
- **Replies ride a fresh broadcast** back to the caller (`FLAG_INCLUDE_STOPPED_PACKAGES`), single-fire
  guarded by an `AtomicBoolean`, sent from `goAsync()` + an IO dispatcher. EMUI severs both live
  binders and the ordered-broadcast result between third-party apps, so this is the only channel that
  works — the same shape renrakusaki's contacts backup proved.
- **Progress broadcasts** while exporting: `app`, `text` (numbers-first display line), and structured
  `current` / `total` (long) + `unit`. `SettingsBackup.export` takes an optional per-category
  `onProgress` callback that feeds them.
- **`AutomationAuth`** — `automation_enabled` (default off) + `automation_token` (24 random bytes,
  hex, generated lazily, compared constant-time with `MessageDigest.isEqual`). Device-local: its
  prefs file is deliberately **not** in the export map, so the token never travels in a backup.
- **UI**: a master switch and a tap-to-copy token row (with Regenerate) appended to the **Export /
  Import** section of the 白い熊 自由作業盤 UI page — same placement in every sister app.

### Export file naming — the family convention
- Backups are now named **`shiroikuma-jiyusagyoban_<yyyy-MM-dd_HH-mm-ss>.zip`** — English app name
  plus datetime, **no version, no `-export` infix** — from both the automation path and the UI page,
  so every sister app's backups sort and read alike in one shared directory.
- The "Last export" query accepts the legacy `白い熊 自由作業盤-…` prefix too, so older backups stay
  recognised.

### Actions
- **Pick From List → Variable** (`dialog.pickmulti`, Alert) — checkbox multi-select over arbitrary
  items, with a bold **全選択** master toggle above a divider, optional display labels, and optional
  parent ids: sub-options indent under their parent and follow its toggle. Current values pre-ticked;
  cancel leaves the variable untouched.
- **Pick One App → Variable** (`app.pick`, App) — the icon-tile app grid in one-tap mode, optionally
  restricted to a package list (e.g. only the apps in a backup target list).
- **Pick Apps → Variable** (`app.pickmulti`) gains **`include_self`**, so a backup-target list can
  include 自由作業盤 itself; an own package already in the selection is now always shown.
- **Send Intent** (`intent.send`) — receiver-mode `result_timeout` raised from 120 s to **600 s**: a
  sister-app export legitimately runs for minutes.

### Engine
- Per-action budgets: `intent.send` gets 660 s (its 600 s ceiling plus slack) and `dialog.*` /
  `app.pickmulti` get 600 s, so a blocking reply or a picker left open is no longer killed at 60 s.

### Fixes
- **`var.split` with `delete_base` destroyed the array it had just created** — `unset()` clears the
  array store as well, so `flow.foreach` over a freshly split list saw zero items and silently
  skipped the whole loop. The scalar is now removed *before* the array is stored.

## 0.2.78+3 — 2026-07-25

The UI page grows a full app-state Export/Import (Kōjiki-style category ZIP) and takes on the kxkb
look; the Presets section moves to the bottom.

### Export / Import — everything settable, as one ZIP
- **First section at the top of the 白い熊 自由作業盤 UI page**: a settable export directory (SAF
  tree picker, persisted device-locally, never itself exported), shown **red while unset, yellow once
  set**, with a latest-export line beneath it (newest `白い熊 自由作業盤-*.zip` in the directory,
  re-queried on page open and after every pick/export).
- **The Export/Import panel** (black box, 2 dp yellow border): directory box + last-export line,
  "Select all" + seven category checkboxes, and an ArcaneChat-style pill button row — Cancel alone on
  the left, Import / Export grouped on the right.
- **Categories** — "Workspace programming" (projects · tasks · profiles · scenes · variables — the
  standard full JSON export as `workspace.json`) first, then UI theme (colours + font files),
  Widgets, Bubbles, App settings (sort · projects · logs · picker), Share tiles, Task icons.
- **Format** (`SettingsBackup`): a ZIP of plain per-category JSON files (type-tagged
  SharedPreferences dumps that merge on import — never clear), real font/icon files under `fonts/` /
  `icons/`, and a `manifest.json`. Runtime state and security grants (Locale tokens, Termux
  allowlist) are deliberately excluded.
- **The "+" → Import JSON… flow accepts the whole ZIP**: it detects the archive and imports its
  `workspace.json` exactly as if the plain full-export JSON had been picked.
- **Dialogs and the auto-close chain**: success dialogs are black-yellow with a yellow border. After
  export, OK closes the info dialog, the panel, and the UI page; after import, "Later" closes the
  whole chain and "Restart now" relaunches the app. Failures ("Export failed…", "No categories
  selected.") leave the panel open.

### UI page restyle (kxkb look) + Presets to the bottom
- All section headings are 20 sp medium accent, **underlined only as wide as the heading text**,
  sections separated by thin hairline spacers; rows follow the kxkb 16→32 dp indent cascade.
- The Presets section (Reset to black & yellow) now sits at the very bottom, like Kōjiki's trailing
  Reset row.
- Long-pressing into the UI page (top-bar ⋮ or the Setup tab) now confirms with a short vibration.

## 0.2.78+1 — 2026-07-25

Upstream resync: the fork rebased onto OpenTasker **0.2.78** (from 0.2.76; 31 upstream commits across
v0.2.77 "roadmap drain" + v0.2.78 + unreleased work). All fork features carry over unchanged;
`BUILD_NUMBER` restarts at 1 on the new base (versionCode 800001).

### Adopted from upstream
- **Geofence-exit contexts** — Location conditions gain a "Match when outside" toggle (fire when NOT
  at a place, dwell measured outside the radius); exposed in the fork's context editor as a checkbox.
- **QUEUED-mode fix** — retriggers arriving while a task runs now queue instead of being dropped as
  "cooldown active"; the cooldown is reserved only when a fresh run actually starts (merged with the
  fork's per-run event-variable snapshots, which are preserved).
- **Notification-button tasks run in the foreground service** — not the receiver's ~10 s window, so
  long tasks (e.g. `flow.wait`) complete reliably; collision-free PendingIntent request codes.
- **`download` via the shared `http.request` transport** — same-origin redirects, atomic fsync'd
  writes, 50 MB cap, LAN-permission gate; no parallel OkHttp path.
- **Tasker import `Wait` fix** — the five time fields are read by argument index, fixing imported
  waits mis-scaled by up to 1000×.
- **UI/security polish** — opaque selected-chip fill in both themes, intent-filter enforcement on the
  exported trigger receivers, `FileActions` symlink/TOCTOU hardening, app-standby-bucket and
  Advanced-Protection-Mode diagnostics readers, density-correct flow connectors.

### Kept fork-side (upstream's counterparts intentionally not taken)
- The fork's rewritten variable engine (`VariableStore`: project-scoped globals, `childScope`,
  persistent-global cache), name-first `OpenTaskerBundle` format, and `task.run` sub-task machinery
  supersede upstream's versions — upstream's sub-task-scoping fix addresses a leak the fork's
  isolated child scopes never had.
- Upstream's new import-time field-limit enforcement stays out: it would only endanger the fork's
  hand-authored bundles.
- The scene editor stays the fork's monolithic `SceneLibraryScreen`; upstream's split scene modules
  and secondary Diagnostics screen remain dropped.

## 0.2.76+31 — 2026-07-23

A 通知明滅 alert-quality pair: the notification tone and the vibration now fire **together** (like a
normal phone message alert), and Jami's internal auto-recovery notifications are silenced by their
notification **channel** — display only, no tone, no vibration, no edge light.

### `sound.play` — background playback (`wait=false`)
- New `wait` arg on `sound.play`: `true` (default) keeps the old behavior — the task blocks until
  playback finishes; **`false` starts playback and returns immediately**, the player releasing itself
  on completion/error. Exposed in the action editor as a dropdown.
- Why: 通知明滅点灯 played the Jami substitute tone to the **end** before reaching its vibrate
  action — tone-then-buzz instead of the phone's simultaneous tone+buzz. With `wait=false` the tone,
  the `%Tsuchi_Vib_Pattern` vibration, and the edge light all start together.

### `%NOTIF_CHANNEL` — the notification event exposes the channel id
- The notification trigger now threads the posting app's **notification-channel id** to the enter
  task as `%NOTIF_CHANNEL` (per-invocation, alongside `%NOTIF_PACKAGE`/`%NOTIF_TITLE`/…, and as
  `channel` in the event metadata) — the locale-proof way to tell an app's housekeeping channels
  from its real message/call channels.

### 通知明滅 workspace wiring (bundle `通知明滅点灯-v2`)
- 通知明滅点灯 plays the Jami tone with `wait=false` (simultaneous tone + vibration + edge light).
- The Jami watchdog filter now stops on `%NOTIF_PACKAGE == shiroikuma.jami &&
  %NOTIF_CHANNEL == shiroikuma_watchdog` — covering **all** the connection watchdog's notifications
  (auto-recoveries, error storms, network warnings) in every locale — with the old exact-title match
  (「白い熊 Jami 自動回復」) kept as fallback. These notifications keep their shade entry but get no
  tone, vibration, or edge light. (The watchdog channel's own system-default sound is silenced
  device-side: 通知明滅 ⇨ Jami 消音 opens Jami's notification settings to mute the 自動回復 channel.)

## 0.2.76+30 — 2026-07-23

Two headline features since +5 — per-app share-sheet tiles (each its own on-device-generated relay
APK), and a backup-guarded system language switch built on a new EMUI-proof cross-app reply channel —
plus a batch of system-wide editor fixes.

### 共有アプリ工房 — per-app share targets via on-device relay-APK generation
- EMUI's share sheet renders **one tile per package**, so the fork's earlier ACTION_SEND
  entry points (sharing shortcuts, activity aliases) all collapsed under the single 自由作業盤 tile.
  The only way to a separate tile is a separate installed package — so each target now gets its own
  tiny **relay APK, generated + signed + installed entirely on the phone** (no PC rebuild).
- On-device APK engine (`core/share/relay/`): a fixed relay stub (`classes.dex` + `resources.arsc` +
  binary-manifest template in `assets/relay/`) is specialised per target by rebuilding only the
  manifest **string pool** (package/label/target — `RelayManifestTemplate`), swapping the icon PNG,
  and assembling a hand-rolled **STORED + 4-byte-aligned zip** (`RelayApkBuilder`). Signed via
  **apksig** with an on-device software RSA key + self-signed X.509 cert (`RelayKeystore` /
  `RelayCertBuilder` — no BouncyCastle, nothing secret ships), installed over a **Shizuku streaming
  `pm install` session** (`RelayInstaller`). The relay (`RelayActivity`) forwards to the exported
  `ShareForwardActivity`, which **unfreezes the target** (Shizuku `pm enable`), forwards the content,
  and drops a re-freeze bubble.
- **共有アプリ工房** screen (`share.relays` action → `ShareAppsActivity`): add a frozen-inclusive app,
  edit its tile name (prefilled) + icon, Generate / Regenerate / Reinstall / Remove, Shizuku-gated
  with a copy-streams fallback; `ShareRelayStore` tracks each target (relay package, state, signing
  fingerprint). New icon sources: the target's **other activity icons**, installed **icon packs**
  (`appfilter`/`drawable.xml` parsing), and curated **framework drawables**.

### System language switch (白い熊 雷起動盤) — backup-guarded
- **`system.set_locale`** — persistent, root-less ja⇄en switch (`CHANGE_CONFIGURATION` +
  `WRITE_SETTINGS`, hidden `updatePersistentConfiguration`). **Reorders** the existing `LocaleList`
  (target first, keeps every other installed language) rather than replacing it — a naive
  `LocaleList(target)` replace dropped English from the system.
- **`system.get_locale`** — current locale tag (+ language-only) into variables, for the toggle and
  the already-set guard.
- Registered in the runtime + editor-metadata registries; `system.set_locale` mapped in the
  capability registry (blocking, Write Settings) and surfaced on the **Setup tab** (Modify system
  settings + adb-only Change configuration checks).
- Guard: a locale change on EMUI once recreated `contacts2.db` empty (all contacts lost), so the
  switch first backs up contacts via `白い熊 連絡先` (`shiroikuma.renrakusaki` `BACKUP_CONTACTS`) and
  proceeds **only** on an `OK:` reply — otherwise vibrate + modal + refuse. Immediate on-tap flash +
  persistent "backing up…" notification cover the wait.

### Send Intent — `reply_via=receiver`, a binder-free cross-app reply channel
- EMUI severs the ordered-broadcast result channel between third-party apps **and** drops a broadcast
  carrying a live Binder (`ResultReceiver` *or* `PendingIntent`) into another app's manifest receiver.
  New mode: pass only plain string extras (`reply_action` / `reply_package` / random `reply_id`),
  sent **ordered** with `FLAG_INCLUDE_STOPPED_PACKAGES`; the target broadcasts the result back to our
  **exported `IntentReplyReceiver`**, correlated by id (`IntentReplyBridge`). `shiroikuma.renrakusaki`
  added to `<queries>`.

### System-wide editor & reliability fixes
- **Run Task** action editor now has a **Parameters** section (add / edit / delete `param:*`) that
  merges the named parameters back on Save — previously Save rebuilt args from the visible fields and
  **silently wiped** every parameter.
- **Set Variable** value (and any field opting into `pathPicker`) gains a **folder icon** that opens
  the system directory/file picker and fills a real filesystem path — no more typing paths by hand.
- **Edit Task** dialog gets the yellow border used by the other editors; `dialog.text` can now drop
  its Cancel button (OK-only acknowledgment).
- Accessibility pre-flight tolerates the EMUI **unbind→rebind transient** (`awaitConnected`): a task
  firing during a configuration change (e.g. the locale switch itself) is no longer spuriously blocked
  with a "permission required" dialog.

## 0.2.76+5 — 2026-07-22

The flash-bubble release: 通知明滅 gets a semi-凍結融解 icon layer — while an app's notification
is edge-flashing, its icon shows down the Desktop's LEFT edge (the mirror of the freeze bubbles on
the right), with a single kill-all 全消灯 icon pinned below the stack. Tap and long-tap behaviors
are UI-settable.

### Flash bubbles — left-edge Desktop icons for flashing apps
- `FlashBubbleStore` + `FlashBubbleOverlayManager` (`core/bubbles/`): one draggable
  `TYPE_APPLICATION_OVERLAY` window per flashing app — its launcher icon + ⚡ badge + label —
  anchored **top+left** and shown **only while the default home launcher (雷起動盤) is
  foreground**, exactly like the freeze bubbles (which stay on the right; the stacks never mix).
  Styling (icon size / corner / label size / weight / font) is shared with the existing Freeze
  bubbles settings. Positions persist across restarts, rotation, and fold changes.
- **Kill-all icon**: while flashing is ongoing, a single 全消灯 bubble (the app's own icon + ✕
  badge) sits below the lowest app bubble; each newly flashing app inserts above it and pushes it
  back to the bottom. Tap = run the kill-all task (same function as tapping the flash-ongoing
  notification) and hide itself — **the per-app icons deliberately stay**; long-tap = hide it
  without killing anything; drag to move.
- **UI-settable gestures** (Settings → UI customization → *Flash bubbles (通知明滅)*): tap and
  long-tap each pick from *Open app + kill flash* / *Kill flash only* / *Open app only* /
  *Dismiss icon only* (defaults: tap = open + kill, long-tap = kill without opening). The
  per-app kill task (default `通知明滅消灯`) and kill-all task (default `通知明滅全消灯`) are
  also settable, so the layer stays generic. "Kill flash" runs the kill task with the bubble's
  package injected as the **per-invocation `%APP_PACKAGE`** (the event-locals mechanism), so the
  same workspace task serves the foreground profile and a bubble gesture — including dismissing
  the app's notification and dropping the ongoing notification when it was the last flasher.
- Five new bridge actions (System category) — the workspace's handle on the layer, since the
  flashing state lives in workspace variables the app can't see: `bubble.flash_add` (package +
  optional label; dedup, stacks below, pushes the kill icon down), `bubble.flash_remove`,
  `bubble.flash_clear` (bubbles + kill icon — the 無効 path), `bubble.flashkill_show`,
  `bubble.flashkill_hide`. Registered in the sensitivity catalog (local-only) and capability
  map (overlay-permission notes on the two showing actions).

### 通知明滅 workspace wiring (bundle)
- `通知明滅点灯` adds the app's bubble + shows the kill-all icon right before the ongoing
  notification; `通知明滅消灯` removes that app's bubble and hides the kill-all icon when the
  last flash dies; `通知明滅全消灯` hides only the kill-all icon (app icons stay, per spec);
  `通知明滅 ⇨ 無効 [37]` clears the whole layer. Task notes document the icon behavior.

## 0.2.76+4 — 2026-07-20

The hands-off-reload release: the adb bridge can now RUN a task, so the dev loop fires a
project's 71 reload itself right after every settings import (白い熊: "You should be running 71
yourself in update-situations like this - always"). Plus a 通知明滅 naming normalization and the
companion-side half of protected-contact picture masking.

### `RUN_TASK` bridge action
- `WorkspaceTransferReceiver` gains `shiroikuma.jiyusagyoban.action.RUN_TASK` — run a task by
  name over adb: `--es …extra.TASK '<task name>'` plus optional `--es …extra.PROJECT '<project>'`
  to disambiguate (name-first, case-insensitive; an ambiguous name errors out instead of picking
  one). Executes through `executeAndLogTask` exactly like a manual run and answers success +
  duration in the ordered-broadcast result. Same explicit-component + protocol-extra gate as the
  other bridge actions.

### 通知明滅 variable normalization (workspace)
- Every ALLCAPS variable renamed to the project's proper form: `TSUCHI_C_/F_/T_/B_<pkg>` →
  `Tsuchi_C_/F_/T_/B_<pkg>`, `TSUCHI_HIDE_*` → `Tsuchi_Hide_*`, `TSUCHI_TONE_FILE_*` →
  `Tsuchi_Tone_File_*` — across 71/01/37, 点灯, 再描画, 消灯, 全消灯, and both 保護試験 tasks
  (including the literal probe string in the unset-detection). Scope is unchanged: these names
  all contain lowercase package parts, so they were already project-globals. New-name rows were
  seeded with current values in the same bundle; the 35 old ALLCAPS rows were swept via
  `DELETE_ITEMS`; 71 was re-run headlessly over the new `RUN_TASK` action.

### Protected-contact pictures — companion side (workspace)
- Jami's file-transfer notifications ("Picture from <name>" + avatar + photo preview) bypass the
  protected-contact masking that text messages already get. The fix lands in shiroikuma-jami
  (handed off as `hand-off-protected-picture-notifications.md` in that repo); this side is ready:
  `通知明滅の設定 [01]` defines `%Tsuchi_Hide_Body_Pic` (新着写真。) and `通知明滅 ⇨ 起動 [71]`
  now pushes it as the `protected_body_picture` extra on the existing `SET_PROTECTED_CONTACTS`
  broadcast (current Jami builds ignore the unknown extra harmlessly).

## 0.2.76+3 — 2026-07-20

The quiet-mode release: 通知明滅 now behaves like a real notification channel — the system-bar
vibrate/silent tile mutes its tone, and every lighting notification also buzzes with a
message-style vibration pattern (settable in the 01 設定 task), which itself respects silent mode.

### `sound.play` volume streams (+2)
- New optional `stream` arg: `media` (default — nothing else changes), `notification`, `ring`,
  `alarm`, `system`, implemented via `AudioAttributes` (sonification content type), with a dropdown
  in the action editor. Sounds on notification/ring/system follow the ringer mode, so the
  vibrate/silent tile mutes them at the OS level — no ringer checks needed in tasks; media and
  alarm keep playing regardless. The `volume` arg composes with any stream.
- The 通知明滅 per-app tone (`通知明滅点灯`'s `sound.play`) now rides `stream: notification` — it
  previously played on the media stream and sailed straight through quiet mode. Its loudness now
  follows the notification volume slider. (Workspace bundle; 話す時計 and all other sounds stay on
  media by design.)

### Incoming-message vibration for 通知明滅 (+3)
- `vibrate` gains a `pattern` arg — comma-separated ms alternating OFF,ON starting with an initial
  delay (the Android waveform convention), e.g. `0,150,100,150` = buzz–pause–buzz. Segments and the
  total are bounded to the existing 10 s cap; `millis` stays for one-shots.
- `state.get` gains a `ringer` key → `normal` / `vibrate` / `silent` — the raw vibrator ignores the
  ringer, so tasks gate themselves.
- Workspace: `通知明滅点灯` vibrates `%Tsuchi_Vib_Pattern` right after the app-foreground stop (no
  buzz while that app is open in front), gated on `ringer != silent`. The pattern is defined and
  documented in `通知明滅の設定 [01]` (白い熊's tuned value: a triple buzz
  `0,150,100,150,100,150`). Net behavior: normal = tone + buzz, vibrate = buzz only,
  silent = light only.

### 通知明滅: 白い熊 Jami 自動回復 exclusion (workspace, same day)
- The `白い熊 Jami 自動回復` notification (the Jami connection watchdog's auto-recovery report)
  no longer fires the tone, edge light, or wakedance — an early-stop guard in `通知明滅点灯`
  matches its exact package + title before the tone plays or the unread flags are set.
- `通知明滅試験` gained stage 0: a simulated 自動回復 notification that must produce no reaction,
  so the exclusion is testable on demand.

## 0.2.76+1 — 2026-07-20

The upstream 0.2.76 resync release: the fork rebases onto upstream's v0.2.76 "deep audit" release
(10 commits, 56 files), the new upstream schema bump is renumbered onto the fork chain, and
upstream's enter/exit-task engine split is fused with the fork's per-event snapshot queue. No
workspace (task/profile/scene) changes ride along — this is a pure engine/app release.

### Upstream v0.2.76 — deep audit fixes (2026-07-17, merged 2026-07-20)

- **Engine**: exit tasks now run on their own job slot and never consume the profile cooldown, so a cooldown, SINGLE-mode in-flight enter task, or RESTART can no longer silently drop the exit task. Closed a QUEUED lost-task race where a retrigger could be enqueued into a queue whose consumer had already decided to exit.
- **Engine**: plugin conditions no longer flap: the shared Locale plugin poll source multiplexes every subscription, so the matcher now holds state for results addressed to a different plugin/bundle instead of driving every plugin context true→false→true each 30 s cycle. The internal `sun_tick` minute pulse can no longer satisfy a generic/blank-filter EVENT context (previously firing imported event profiles every minute); blank-event/blank-filter specs fail closed.
- **Contexts**: all-day calendar events match on the local day instead of the raw midnight-UTC bounds (they were shifted by the zone offset). The Context Inspector is now read-only and no longer resets the engine's persisted location dwell timers, and its match explanations honor OR groups like the engine. Serialized the two-thread state-source merge and synchronized camera/mic AppOps start/stop against a watcher leak.
- **Data**: added indexes on `run_logs(timestamp)` and `edit_history(entityType, entityId)` (schema v8, migrated + instrumented).
- **Actions**: `download` runs on OkHttp with a policy-DNS hook so the cleartext private-LAN rule and the API 37 `ACCESS_LOCAL_NETWORK` gate are enforced against the addresses actually connected to (closing a DNS-rebinding TOCTOU), fails on non-2xx instead of saving a redirect stub over a good file, and fsyncs before the atomic rename. `tile.set` fails honestly and is capability-gated Unsupported instead of reporting a no-op Success. `screen.timeout` rejects the "0 = never" value that actually turns the screen off immediately. `flow.wait` at its 30-minute maximum no longer always times out; `sound.play`/`tts.speak` get a 10-minute budget and TTS queue failures fail fast. `datetime.*` zone typos fail closed; `data.read` CSV supports RFC 4180 quoted fields. Added missing editor fields for ping, download, sound.play, and media.mute.
- **UI**: fixed a Diagnostics crash from duplicate log keys (now keyed on a monotonic sequence) and stopped its polling while backgrounded. The one-time NFC write is disarmed on dialog close, expires after 60 s, and runs its tag I/O off the main thread. `deleteVariable` reports real success/failure instead of an optimistic toast; undo no longer reports false success; `updateTask`/`updateProfile` are transactional. Editing an unknown action type shows a message instead of a dead tap. The context editor blocks saving garbled TIME windows and out-of-range coordinates. New profiles default to disabled, the enter-task selection no longer resets mid-edit, and backup state loads off the main thread.
- **Theming**: scene warning text follows the applied theme's luminance (was near-invisible in Light-app-on-dark-system), the Locale plugin edit activity honors the persisted theme, run-log detail lines no longer render twice, and the scene overlay is clamped on screen so it can't be dragged fully offscreen. Removed dead duplicate helpers.

### Fork-side resync work (+1)

- **Database renumbering**: upstream's v8 (the `run_logs(timestamp)` and `edit_history(entityType,
  entityId)` indexes) is renumbered onto the fork chain as **migration 19→20** — same scheme as the
  previous resync (the fork chain occupies 5..17; upstream's 6/7/8 land as 18/19/20). Schema
  `20.json` exported; an installed device migrates in place by just adding the two indexes.
- **Engine fusion**: upstream's enter/exit job-slot split is merged INTO the fork's per-invocation
  event-snapshot dispatch (+106) rather than replacing it — `dispatchTask` now carries both
  `eventVars` and `isExit`, exit tasks run on the collision-free `-profileId` slot without consuming
  the cooldown, and the QUEUED enqueue/drain decision sits under one lock while each queued run still
  carries its own `%NOTIF_*` snapshot (notification bursts keep their per-event values).
- **Adopted from upstream into fork-owned files**: transactional `updateTask`/`updateProfile`
  (the fork's task data-loss guard, write tracing, and icon-file cleanup are retained; upstream's
  corrupt-record-overwrite throw is deliberately NOT adopted — corrupt rows keep surfacing via the
  decode-issues banner instead of hard-blocking every save), honest `tile.set` failure + an
  Unsupported capability pill (nothing in the workspace uses it), real success/failure feedback on
  variable delete, the profile editor refusing to save a dangling enter-task binding, and the new
  editor fields — ping (timeout, result variable), download (timeout, size limit), sound.play
  (volume), media.mute (stream).
- **Kept fork-side where upstream diverged**: the ThemePrefs-driven `OpenTaskerTheme` (upstream's new
  Locale-plugin theme code re-pointed at it), the rewritten scene library and the removed Diagnostics
  destination, plain-string action metadata/capabilities (upstream moved to string resources), and
  name-first task resolution in profile dispatch.
- **Repairs**: restored the `plural` helper in `ActiveAutomationLists` that upstream's dead-code
  sweep deleted out from under the fork's still-live call sites.
- **Verified no-ops for the fork**: the new scene-overlay clamp applies only to drag gestures —
  programmatically edge-placed fork scenes (電池線, 通知明滅枠, the 音楽 buttons) are unaffected.
  `BUILD_NUMBER` reset for the 0.2.76 line (versionCode 780001).

## 0.2.75+203 — 2026-07-19

The workspace-health release: the app now tells you — truthfully, live, and from the top level — which
tasks cannot run and why, instead of hiding a permanently-wrong "Unsupported" badge inside a task card
while the task silently failed in 0 ms. Plus overlay quality-of-life for the 音楽端灯 buttons and a
%variable cache-coherence fix.

### Workspace health marks & truthful capability badges (+201..+203)

- **`wake` un-broken.** Upstream capabilities-mapped `wake` through its "elevated power backend" —
  which upstream never ships (`hasPrivilegedTransport()` is hardcoded `false`), so the action was
  *permanently* Unsupported, and the task pre-flight **hard-failed any task containing a wake at
  0 ms** (the セキュアカメラ / Freeze-bubble wakes). The fork's real `WakeAction` runs
  `input keyevent 224` through ShizukuShell — so the capability is now a plain Shizuku-gated one,
  exactly like `shell.run`. (`reboot`/`lock` stay Unsupported: their upstream implementations are
  stubs that always fail.)
- **Live action pill.** The capability pill on an action row now reflects reality: **hidden** when
  the requirement is actually met (a standing "Needs setup" on a working action was a lie), **red +
  what's missing** ("Needs setup — Shizuku not installed") when it isn't — and **tapping it
  deep-links** to the settings screen / app that grants the requirement. Shizuku is verified
  **binder-up + access-granted** (new `CapabilityState.isMetLive`), not upstream's
  "manager app is installed". Everything re-evaluates on every app resume.
- **Red ❗ propagation.** A task that cannot run right now — an Unsupported action, or a blocking
  permission that is live-unmet (the exact pre-flight/0 ms condition) — gets a red ❗ on its **task
  row**, on its **project filter chip** (All/Unfiled included), and on the **Tasks icon in the bottom
  nav bar**. **Profiles inherit** the mark from the task they run (enter *and* exit task, resolved
  name-first with id fallback exactly like the engine) → profile rows, Profiles-tab chips, and the
  **Profiles nav icon**. All startup breakage is visible from the top level.
- **Setup tab: Task health card.** Setup now opens with a health card driven by the *same* checks as
  the marks — red, listing each blocked task and exactly what it's missing, with a "Show in Tasks"
  jump; a green "all tasks can run" one-liner otherwise. Marks and Setup can no longer disagree.
- **Setup contradictions fixed.** The "Shizuku power mode" card used to show a **green check** while
  its body said *"disabled by the persisted kill switch … blocked until the backend is implemented"*.
  Replaced by a real **Shizuku** card: green only when Shizuku is running *and* granted; distinct
  states for not-installed / installed-but-not-running / running-but-not-granted — the last with a
  **one-tap Grant** that pops Shizuku's own permission dialog. The **Termux script bridge** card had
  the same disease (green on mere installation, body claiming the feature is unimplemented — it is
  implemented in this fork): it now green-checks only when dispatch is really ready (installed +
  ≥ 0.109 + RUN_COMMAND granted) and offers a **direct RUN_COMMAND runtime-permission request**.

### Overlay QoL trio (+198..+200)

- **`scene.show` numeric `vAlign`** (0..1): positions the scene's vertical **center** at that
  fraction of the screen height, proportionally correct across fold states — `%Ongaku_Btny` now
  drives the 音楽 良/削除 buttons' height as one knob.
- **`keepScreenOn` arg** on `scene.show`: the overlay holds `FLAG_KEEP_SCREEN_ON` while shown —
  EMUI let the screen time out over the player's own keep-awake flag whenever an overlay sat on top.
- **Gradle configuration cache** enabled — `buildFork` made CC-safe (project values captured at
  configuration time); warm re-runs configure in ~0.6 s.

### %variable cache coherence (+196..+197)

- **`PersistentGlobalScope.refreshFromDb()`** re-warms the %var cache after a bundle import and
  after `DELETE_ITEMS` variable deletions — direct DAO writes had made imported variables invisible
  to scene expansion until a process restart (the 音楽端灯 fade knobs rendered as tiny black
  fallbacks).
- **BUTTON scene-element `textColor`** is now `v()`-expanded live like every other element, so
  `%Ongaku_Btncolor` drives the 良/削除 fade continuously.


## 0.2.75+195 — 2026-07-16

The 白い熊 音楽 migration release: the `音楽端灯` project moves from PowerAmp to the 白い熊 音楽 sister
app, the audio-reactive meteors move natively INTO that app, and the fork rebases onto 38 new upstream
commits (see the next section for everything that merge brought in).

### 音楽端灯 → 白い熊 音楽 (shiroikuma.ongaku)
- **Player contract**: the workspace now listens to `shiroikuma.ongaku.STATUS_CHANGED` /
  `TRACK_CHANGED` broadcasts (extras: `paused`, `path`, `title`, `artist`, `favorite` → `%INTENT_*`
  variables) and drives the player through its token-gated `AutomationActivity`
  (`op` + `token`: `TOGGLE_FAVORITE`, `DELETE_CURRENT`, `PLAY_PLAYLIST` with `playlist` + `track`,
  transport ops). PowerAmp support was removed entirely — tasks, profiles, scenes and 28 tuning
  variables — not kept as legacy.
- **良 (favorite) button**: toggles the current track's favorite in the player; flashes the coming
  state; self-corrects from the re-broadcast.
- **削除 (delete) button**: now shows a **confirmation dialog** (song title + artist + human path;
  削除 / やめる) before firing — and the overlay buttons **hide instantly** when the dialog opens
  (they float above dialogs; previously they lingered 2–3 s over it). Deletion happens inside the
  player (skip → SAF delete → library row) instead of the old `NEXT` + raw `shell rm`.
- **Button scenes tightened to 220×220 dp** — the old 300 dp-tall windows had an invisible tappable
  band below the glyph that sat exactly over the player's pause button on the folded panel.
- **Play Tenet / Play Lifting**: rewired to open the player and start the named playlist —
  `Play Tenet` starts at the track "Freeport" via the `track` extra.

### Meteors moved natively into 白い熊 音楽 — stack removed here (+193)
- The audio-reactive edge meteors now render **inside the player**, beat-locked from its own decoder
  tap — sample-accurate beats, working under Android Auto / Bluetooth offload, alive exactly while
  music plays. The full renderer + beat-grid spec (all tuned values) was handed off to that repo.
- Removed from this app: `MusicPulseSource` (the output-mix `Visualizer` beat source — **no
  Visualizer remains, so audio offload is never blocked by this app**), the `EdgeMeteors` GPU
  renderer, the `OngakuPulse` WebView JS bridge (+ its R8 keep rules), and the `music.viz.test`
  diagnostic action. `SceneElementType.METEOR` survives as a decode tombstone so archived exports
  still restore (such an element renders nothing).
- The short-lived **Android Auto silence fallback** (+191: `Bridge.silentMs()`, meteors falling back
  to the non-reactive animation when A2DP-offload kept the output mix silent) shipped and was then
  superseded by the native move within the same release window.

### DELETE_ITEMS bridge action (+192)
- `WorkspaceTransferReceiver` gains `shiroikuma.jiyusagyoban.action.DELETE_ITEMS`: headless deletion
  of named workspace items over adb — a JSON manifest (`projectName` + `tasks` / `profiles` /
  `scenes` / `variables` name lists), resolved by (project, name). A shown scene is hidden first,
  item notes are cleaned up, and variables are swept from both the project and super buckets.
  Bundle import can only add/overwrite; this completes the headless dev cycle with removal.

### App picker overhaul (+194, +195)
- The multi-select app picker (Make Launcher Tasks, app-multiselect fields) is **near-fullscreen**
  (97% × 94% — still a dialog, not a page).
- Each tile shows the **package id under the label** — and since search always matched ids as well
  as names, id-only hits are finally self-explanatory. The search hint says so.
- **Icons 72 dp** (50% bigger), re-rasterized at true pixel size; the grid's cell width tracks the
  icon size.
- A ⚙ panel exposes **persistent sizing knobs**: icon dp (32–160), label sp (7–28, default 14,
  **bold** by default with a Bold toggle), id sp (6–20), and **Pad↕ / Pad↔** grid + tile padding
  (0–32 dp; vertical default tightened 12 → 4 dp).

### Upstream resync (0.2.75/77, d4a99f5 — 38 commits)
- `custom` was flattened and rebased onto the new upstream tip; every fork customization survived.
  Fork-critical calls made during the merge: upstream's two new DB migrations renumbered onto the
  fork chain (17→18 `variables.isSecret`, 18→19 `profiles.requiresRiskAcknowledgement`, final
  version 19); upstream's ASCII-only variable-name policy NOT adopted in the store (it would have
  silently killed Japanese-named variables); upstream's secret-variable name-pattern backfill
  dropped (it would have irreversibly encrypted working `Pkey_*` globals); all ~60 fork action ids
  registered in upstream's new sensitivity catalog so its fail-closed "unknown action" gate never
  blocks fork tasks; the fork's name-first bundle format, scene pipeline, engine doze-hardening and
  battery `EXTRA_PLUGGED` semantics kept over upstream's variants.
- Everything upstream added in that span — the text/regex, date-time and structured-data action
  packs, the full `http.request` action, off-main-thread task execution, live profile
  reconciliation, the token-gated Locale receiver, encrypted secret variables, engine diagnostics,
  fail-closed corrupt-payload storage and the rest — is itemized in the next section.

## Upstream unreleased work merged on the 0.2.75/77 resync (2026-07-15)

- **Diagnostics**: added a secondary Diagnostics destination with live engine heartbeat, active foreground-service types, app-standby bucket, exact-alarm delivery, last matcher failure, WorkManager watchdog stop reason, bounded process logs, and redacted crash previews. Shared diagnostic reports now include that health snapshot, up to 100 ring-buffer entries, and bounded crash excerpts; Authorization/Bearer credentials are redacted in addition to existing secret patterns.
- **Background reliability**: time/day contexts now consume AlarmManager wake pulses in addition to an aligned in-process minute clock, so a Doze wake reaches the matcher instead of only producing a log line. Inexact fallback alarms use `setAndAllowWhileIdle`; a persisted service heartbeat and 15-minute WorkManager watchdog re-arm dropped ticks, and foreground-service timeout leaves a recovery alarm armed before shutdown.
- **Variable reliability**: global write-back now compares each run against its hydrated snapshot under a process-wide mutation coordinator and publishes accepted rows as one Room batch. Concurrent runs merge disjoint globals without loss; stale same-global writers preserve the first committed value and add an explicit conflict note to the run log instead of silently clobbering it.
- **Variables**: unified variable-name normalization across runtime writes, `var.persist`, the Variable vault, durable storage, and Tasker XML imports. Any uppercase letter now consistently identifies a global; all-lowercase names stay task-local, explicit lowercase persistence targets are promoted instead of silently disappearing, invalid targets fail visibly, and root-local event values cannot leak into durable snapshots.
- **Networking**: replaced the split HTTP GET/POST editor actions with one cancellable `http.request` transport on OkHttp 5.4.0. It supports GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, bounded structured query/header/auth input, inline or file bodies, status/header/body variables, atomic response files, per-stage timeouts, and explicit no-redirect/same-origin redirect policy. TLS bypasses and cross-origin redirects fail closed, cleartext remains private-LAN-only, header traces redact credentials, and stored GET/POST IDs remain hidden compatibility aliases.
- **Release/docs**: expanded the release-truth contract from README-only checks to source-derived capability and version checks across architecture, dependency, F-Droid, scenes, visual flow, Shizuku, Termux, and Locale documentation. The gate excludes explicitly historical dependency logs and includes a deterministic stale-document failure example.
- **Backup reliability**: encrypted `.otbackup` exports now use chunked format v2, authenticating each bounded 64 KiB frame plus an explicit terminal frame before validated restore staging is atomically published; v1 backups remain restorable. Wrong passphrases, corruption, truncation, cancellation, write failures, and interrupted staging clean temporary plaintext without replacing an existing pending restore, while startup restore keeps its pending journal through same-directory atomic database replacement.
- **Battery/reliability**: production Wi-Fi, connectivity, app-usage, shake, camera/mic, package, and Bluetooth context monitors now start only while an enabled profile depends on them and stop after the final dependent profile is disabled or deleted. Profile edits reconcile reference counts without duplicate registrations, and an explicit subscription barrier prevents a newly activated pulse source from firing before its matcher is listening. Camera/mic AppOps pulses are now also wired into event-context matching.
- **Security**: every built-in action now has an explicit data-access, external-transmission, device-control, destructive, or local-only classification. Bundle schema v2 carries a computed task/profile power manifest and flags potential data-to-external chains; unknown actions fail before import or task side effects. Imported profiles persist a review-required state, stay outside the engine registry, and require an in-app acknowledgement before their first enable.
- **Security**: global variables can now be explicitly stored as Android Keystore-backed AES-256-GCM secrets. Secret provenance survives legacy/template expansion and derived writes, redacts nonsensitive argument fields, action logs, traces, and failures, and keeps values out of ordinary OpenTasker/Tasker exports. Cross-device or key-loss restores fail closed with a deliberate re-entry flow.
- **Maintainability**: split the scene library's list/cards, interactive canvas, element dialogs, and overlay controls into focused Compose modules; the public screen is now a 160-line coordinator protected by source-boundary, localization, accessibility, and scene behavior tests.
- **Security**: Termux scripts now require a Setup-managed SHA-256 allowlist and a matching Termux-side preflight hash before every run. The app declares and requests `RUN_COMMAND`, requires Termux 0.109+ result support, receives results through a non-exported one-shot callback, bounds arguments/stdin/timeouts/stdout/stderr/pending commands/rate-limit state, redacts captured content from logs, and can map stdout, stderr, exit code, and original lengths to variables.
- **Data safety**: completed the fail-closed stored-payload boundary across manual, widget, notification, external-intent, export, inspector, and widget-configuration paths; corrupt rows stay untouched for database recovery and skipped runs now record the reason. Edit-history pruning is also entity-scoped, so trimming one task can no longer delete another task, profile, or scene history.
- **Scenes**: overlay rendering now uses the editor's authored scene projection and exact element bounds, supports overlapping elements and bounded local-image decoding from persistable content URIs, and reads both legacy slider `progress` and current `value` deterministically.
- **Reliability**: notification action buttons now bind to immutable task IDs through a task picker. Renames preserve bindings, deleted tasks fail visibly, and legacy name bindings migrate only when the name is unique; duplicates never select an arbitrary task.
- **Scenes**: multi-selected elements now move as one rigid, edge-clamped mutation with one transactional undo snapshot; resize gestures use independent horizontal and vertical canvas scales and stay within scene bounds.
- **Onboarding**: first-run template onboarding now completes only after an explicit skip or successful install and resumes after dismissal or recreation. Runtime permission results update Setup immediately, repeated denial routes to app settings, and grant/revocation resets recovery state.
- **Accessibility**: scene overlays now use a 48dp close target, expose screen-reader move actions, and retain a proper touch click path. Profile switches, task/profile actions, nested action/context controls, run-log filters, and expression details now expose specific names and authored state without duplicate decorative icon announcements.
- **Platform**: Android 17 audio hardening is now eligibility-aware instead of disabling every audio action. Visible task launches and while-in-use-eligible automation services attempt sound, TTS, volume, ringer, mute, and media-key operations; boot/background runs fail before side effects with recovery guidance, while exact-alarm access is honored only for alarm-stream changes.
- **Security**: Shizuku permission can no longer promote elevated capabilities or route commands through an ordinary app-UID `ProcessBuilder`. The kill switch is persisted and defaults on, Setup distinguishes stopped/permission/unavailable/disabled states, and every elevated action remains `Unsupported` until a privileged user-service transport exists.
- **Security**: OpenTasker JSON and Tasker XML imports now enforce shared entity/action/context/scene/string budgets, plus streaming token/node and nesting preflights before model or DOM allocation. Named violations fail before the Room transaction.
- **Release**: added one local quality/release gate covering blocking lint, the JVM test floor, Room schema drift, Android-test compilation, resolved dependency/repository/checksum policy, a CycloneDX SBOM with OSV advisory results, configuration-cache reuse, and Play/F-Droid release assemblies. Enabling permission lint also caught and fixed the missing manifest permission for the shipped vibrate action.
- **i18n**: moved action/context catalogs, setup and backup copy, capability diagnostics, widget plurals, and scene-overlay labels to Android resources; seeded Spanish setup translations, enabled `en-XA`/`ar-XB` debug pseudolocales, and expanded localization guards.
- **Reliability**: Wake-on-LAN now rejects MAC addresses with mixed `:`/`-` separators (e.g. `AA:BB-CC:...`); a consistent separator is required.
- **Actions**: added a text/regex action pack: **Match Text** (`text.match`, captures become an array), **Replace Text** (`text.replace`, `$1` group refs), **Split Text** (`text.split`, literal or regex), **Join Text** (`text.join`), and **Substring** (`text.substring`). Regex uses the linear-time RE2 engine with bounded pattern/input sizes, so patterns can't hang the runner.
- **Actions**: added date-time actions: **Format Date/Time** (`datetime.format`), **Parse Date/Time** (`datetime.parse`), and **Add to Date/Time** (`datetime.add`). Convert between epoch milliseconds and formatted strings with optional time zones, and do calendar-aware date arithmetic (seconds through years), all deterministic and offline. Fixed units are exact zone-independent deltas; months/years honor calendar length.
- **Actions**: added a **Read Data** action (`data.read`) that parses JSON, CSV, or XML into variables entirely on-device: ideal for turning HTTP responses and file contents into usable automation data. Supports JSON path selectors (`items[0].name`), CSV column/cell selection, and XML element paths (`root/item/name`), sets an array plus a `%var_count`, is size-bounded, hardened against XML external entities, and fails closed on malformed input or an unresolved selector.
- **Security**: the external-automation broadcast target now bounds the number of supplied variable extras (64) in addition to the existing per-value length cap, name validation, and signature permission.
- **Interoperability**: OpenTasker bundle import now tolerates hand-edited JSON: `//` comments, trailing commas, and case-insensitive enum values decode cleanly, while unknown keys and oversized bundles are still rejected. Export output is unchanged.
- **Reliability**: task execution now runs off the main thread. Every run path (manual, profile trigger, widget/shortcut, notification action, Locale/external) executes actions on `Dispatchers.IO`, and the automation service's matching/dispatch runs on `Dispatchers.Default`. Previously blocking actions (HTTP GET/POST, download, ping, Wake-on-LAN, file I/O) launched from the main thread threw `NetworkOnMainThreadException` and failed silently. Debug builds now install StrictMode to flag any accidental main-thread disk/network I/O.
- **Privacy**: SMS recipient numbers are now masked in run logs (e.g. `***6789`) instead of stored in full: run-log redaction does not otherwise scrub phone numbers.
- **Reliability**: hardened smaller action/import edge cases: the Termux script action no longer passes a spurious empty argument when `arguments` is blank or double-spaced; `file.list` reports a clean "invalid file name pattern" failure instead of leaking a raw Java exception for a bad glob; and OpenTasker bundle import no longer counts updated variables as newly inserted.
- **Reliability**: hardened the variable engine. A `var.set` targeting a huge array index (e.g. `%X[2000000000]`, reachable from an imported/shared profile) no longer tries to grow a multi-billion-entry list: out-of-range writes fail closed. Array storage now evicts the genuinely least-recently-used array at its cap instead of an arbitrary one, and is synchronized for concurrent tasks. Ternary conditions whose test contains parentheses (e.g. `(%A(+1) > 5) ? a : b`) are now parsed correctly instead of silently falling through.
- **Reliability**: event/notification text matching with `regex=true` now uses the linear-time RE2 engine (as variable regex already does) instead of the JDK backtracking engine, so a pathological user pattern can no longer hang the matcher on an incoming event.
- **Correctness**: battery-level triggers now normalize `EXTRA_LEVEL` against `EXTRA_SCALE`. On devices that report a non-100 scale (some report 255), `battery_level` thresholds previously never/always matched.
- **Security**: the exported Locale fire receiver now requires a revocable execution grant. Any app could previously broadcast a chosen task id to the receiver and have OpenTasker run it. Configuring the plugin now issues a high-entropy token bound to the selected task; the receiver dispatches only when the incoming bundle carries a token that is still stored and bound to that exact task, so forged, missing, mutated, revoked, and deleted-task grants are rejected without dispatch. Grants are revoked automatically when their task is deleted.
- **Networking**: cleartext HTTP to LAN/private hosts now actually works. The network-security config previously listed private ranges as `<domain>` hostnames (Android has no CIDR support there), which silently blocked every literal LAN IP. Cleartext is now gated solely by the runtime policy: HTTPS stays the default, `allow_http` is an explicit opt-in, and any host not resolving to a loopback/link-local/site-local/IPv6-ULA address is rejected before a connection opens. IPv6 Unique Local Addresses (`fc00::/7`), previously misclassified as public, are now recognized.
- **Variables**: global (`%UPPERCASE`) variables and `var.persist` values are now genuinely durable. Every execution path (manual run, profile trigger, widget/shortcut, notification action, Locale/external intent) hydrates persisted globals before running and commits any globals changed during the run to the database before reporting success, so they survive across runs and process restarts. Local (lowercase) variables still never escape their invocation, and the Variables vault now reflects real global state.
- **Reliability**: the running automation engine now reconciles itself from the profiles table. Creating, editing, enabling, disabling, or deleting a profile rebuilds matchers and plugin subscriptions live, without needing a service restart, while leaving any in-flight task run untouched. Purely cosmetic edits (name, group) no longer thrash the engine.
- **Data safety**: corrupt stored automation payloads now fail closed. Task, profile, and scene rows whose JSON no longer decodes are surfaced with the exact record and field, cannot be executed (profiles skip them with a run-log note and `task.run` refuses corrupt sub-tasks), and cannot be overwritten by the normal editors (the raw bytes are preserved for undo/backup recovery). Scene edits now also snapshot to edit history, and stored payloads decode through a shared codec that tolerates unknown additive fields.
- **Release**: refreshed the draft F-Droid metadata pin and local fdroidserver lint/build evidence for `0.2.75`/`77`.
- **Release**: added the Kotlin/Gradle dependency verification hashes needed by clean fdroidserver source checkouts.
- **Reliability**: hardened database backup creation so local backups wait for a complete WAL checkpoint, publish only schema-validated copies, clean up failed temporary files, and keep backup UI state from getting stuck after failures.
- **Testing**: added Compose instrumentation coverage for setup onboarding, task/profile editor validation, action/context required-field validation, scene creation, and incompatible import review states.
- **Accessibility**: added repeatable source gates for setup, profile/task editors, action/context editors, scenes, destructive dialogs, and run-log states; converted remaining app-shell and setup semantic labels to string resources.
- **i18n**: completed the core active automation, editor, flow, scene, and premium-state string-resource extraction pass; added a JVM source guard for hardcoded Compose strings and valid Weblate locale targets.
- **Reliability**: routed remaining direct platform log calls through `AppLogger` and added a source-level regression guard so `android.util.Log` stays isolated to the logging wrapper.
- **Maintainability**: finished the active-automation shell split into owned view-model, list, editor, action, and context modules while keeping `ActiveAutomationUi.kt` under 1,500 lines.
- **Release**: synced draft F-Droid metadata and the PowerShell release verifier with the current `0.2.75`/`77` Gradle release contract.
- **Docs**: added a release-truth contract test so README release values and shipped-feature claims stay aligned with Gradle metadata and current backend docs.

## v0.2.75 - 2026-06-19

Scene editor finishing pass and visual flow editor authoring.

- **Feature**: scene overlay launch via `SYSTEM_ALERT_WINDOW`: each scene card shows a "Show" button (when overlay permission is granted) that displays the scene as a draggable floating window with dark-themed element views and tap-to-run-task bindings.
- **Feature**: scene element multi-select: drag-starting an element selects it (highlighted border); when multiple elements are selected, dragging one applies the delta to all selected elements as a group.
- **Feature**: alignment guides on scene canvas: elements snap to canvas edges, center lines, and other element edges/centers during drag. Dashed guide lines render during the gesture with a 6dp threshold.
- **Feature**: flow canvas pinch-zoom (0.5x-2.5x) and pan gestures for the lane overview.
- **Feature**: flow edge routing: vertical connectors between lanes and horizontal connectors between nodes drawn as Canvas lines with endpoint dots.
- **Feature**: branch and subflow markers: action nodes with sub-task references show a Subflow pill; conditional actions show a Branch pill with the if-condition text.

## v0.2.74 - 2026-06-19

i18n bootstrap, engine v3, dependency upgrade, encrypted backup, Shizuku/Termux backends, and Locale interop.

- **i18n**: expanded `strings.xml` from 49 to 170+ string resources covering all major UI surfaces. Converted ImportReviewDialogs, VariablesScreen, RunLogScreenContent, ContextInspectorScreen, and SceneLibraryScreen to use `stringResource()`. Created locale skeleton directories for 13 languages. Added contributor translation workflow docs to README.
- **Feature**: `var.set` now supports dotted and bracketed path syntax (`config.theme`, `items[0]`, `Data.user.profile.name`) for nested JSON writes via `VariableStore.setAtPath()`. Array indices auto-grow with empty-string padding.
- **Feature**: Run-Log expression traces now render in an expandable debugger surface with per-expression arg name, scope source, monospace expression→value mapping, and warning highlights.
- **Feature**: encrypted database backup/restore using AES-256-GCM with PBKDF2-derived keys (600k iterations). `.otbackup` file format with 4-byte magic, salt, IV, and authenticated ciphertext.
- **Feature**: Shizuku elevated backend with real API 13.1.5 integration. Checks Shizuku service state (ping, permission), exposes Ready/PermissionNeeded/Disabled/ManagerInstalled states. ShizukuShellRunner validates commands against a strict allowlist. Kill-switch toggle. ActionCapabilities dynamically promotes elevated actions when Shizuku is active.
- **Feature**: Termux RUN_COMMAND dispatch with executable path, arguments, working directory, and background execution. SHA-256 script hash pinning for allowlist verification. 1-second per-script frequency cap. Output-to-variable mapping via capture prefix.
- **Feature**: Tasker XML export for the mappable action subset (notify, wait, log, var.set). Exports Time, Day, Application, State, and Event contexts. Reports skipped actions and unmappable contexts.
- **Feature**: Locale plugin target bridge: OpenTasker now appears as a Locale-compatible setting plugin for Tasker/MacroDroid. Edit activity shows task picker; fire receiver dispatches tasks through the existing automation pipeline.
- **Feature**: scene element resize handles on the canvas preview. Drag the bottom-right handle to resize elements within scene bounds.
- **Dependencies**: upgraded Compose BOM from 2026.04.01 to 2026.05.00 with updated dependency verification checksums.
- **Style**: adopted DesignSystem spacing and radius tokens across 5 major UI screen files.

## v0.2.73 - 2026-06-17

Hardening, test coverage, and expression engine improvements.

- **Security**: applied Android 17+ `ACCESS_LOCAL_NETWORK` permission guard to Ping and Wake-on-LAN actions; all LAN socket actions now enforce the same gate.
- **Security**: extended the Android 17+ local-network guard to HTTPS requests targeting private, loopback, or link-local hosts so URL-backed LAN actions cannot bypass Setup permission state.
- **Reliability**: added Room schema v5 drift gate: CI now fails if any schema version file is missing; added migration tests for 2→3, 4→5, and full 1→5 path.
- **Supply chain**: enabled Gradle dependency verification with SHA-256 checksums for all resolved artifacts.
- **Feature**: added `var.persist` action to promote local variables to global scope across task invocations.
- **Testing**: broadened action guard coverage for file, settings, app, and notification-channel operations; expanded retention policy boundary tests.
- **i18n**: centralized common UI strings (navigation, dialogs, setup, empty states) in `strings.xml`.
- **Docs**: updated Setup permission copy to list all guarded network actions.
- **Safety**: `AutomationService.onDestroy()` now snapshots job collections before cancelling to prevent `ConcurrentModificationException` during service teardown.
- **Safety**: `reloadProfiles()` cleans up stale queued tasks for deleted/disabled profiles, preventing memory accumulation.
- **Safety**: `ArrayStore` now caps at 500 entries to prevent unbounded growth from `%var(split:...)` operations within a single task run.
- **Safety**: `OpenTaskerBundleCodec.decode()` now rejects JSON payloads larger than 16 MB to prevent OOM from malicious imports.
- **Safety**: capped `file.write` and `file.append` payloads at the existing 1 MB file-action boundary and fail before creating or expanding files beyond that limit.
- **Safety**: bounded imported database restore staging to 100 MB and remove temporary files if the import stream fails or exceeds the limit.
- **Safety**: `WiFiNetworkMonitor` and `ConnectivityMonitor` handle null `ConnectivityManager` gracefully instead of crashing on devices where the system service is unavailable.
- **Reliability**: serialized persisted Location dwell-state read/modify/write operations so concurrent matchers cannot lose inside-since updates.
- **Reliability**: cleaned up LocationManager listener registration on provider-set changes and partial registration failures to avoid duplicate callback chains.
- **Safety**: surfaced corrupted profile/task JSON as visible UI warnings while keeping safe fallback domain objects.
- **Safety**: hardened database backup validation with current schema-shape checks, required-table row-count reads, and a consumed WAL checkpoint before copy.
- **Maintainability**: split run-log rendering and import-review dialogs out of `ActiveAutomationUi.kt`, with source tests guarding the screen ownership boundary.
- **Reliability**: made `flow.wait`, `vibrate`, and `screen.timeout` fail clearly on missing, malformed, or out-of-range durations instead of silently defaulting or clamping.
- **Fix**: implemented deterministic `file.list` filename glob filtering and added the missing action editor field for `pattern`.
- **Security**: tightened import validation by rejecting oversized/DOCTYPE Tasker XML before parsing and blocking OpenTasker bundles with duplicate task IDs or variable names.
- **UX**: bounded long Tasker/OpenTasker import review dialogs so warnings and mapped-action lists stay scrollable on compact screens.
- **Reliability**: replaced API 33-only service receiver registration with AndroidX compatibility calls and gated camera/mic active watchers to Android 11+ APIs.
- **Safety**: made downloads write to a temporary file and replace the destination only after a complete bounded copy succeeds.
- **Performance**: reduced scene-canvas drag recomposition overhead by using primitive float state and lambda offsets.
- **UX**: polished first-run onboarding, labeled create actions, the widget task picker, and the home-screen widget treatment so setup and secondary flows feel more guided and intentional.
- **Reliability**: made widget and launcher-shortcut task runs close with clear feedback even when task execution throws, avoiding stranded translucent runner activities.
- **Reliability**: guaranteed external automation broadcast pending-results finish even if ordered-result publication fails.
- **Cleanup**: removed duplicate `ArrayStore.joinWith` method (identical to `join`).

## v0.2.72 - 2026-06-16

Setup and secondary-state polish pass.

- **Setup**: replaced the theme button grid with an accessible radio-style selector that exposes selected/not-selected state and avoids no-op selected buttons.
- **Backup**: tightened backup/restore copy, added a calm state banner, and changed secondary backup actions into compact side-by-side controls so the card scans better on compact screens.
- **Permissions**: normalized setup action button shape for a more consistent control language.
- **Flow/Scenes/Inspector**: upgraded sparse empty states into framed, explanatory surfaces with status cues and clearer next-step copy.
- **Docs**: bumped app metadata and README/roadmap state for v0.2.72.

## v0.2.71 - 2026-06-16

Premium UX polish pass.

- **Navigation**: promoted Run Log into the primary bottom navigation, clarified destination labels, and tightened selected-state geometry for more stable compact-screen behavior.
- **Theme**: synced the navigation bar color with the AMOLED/light/high-contrast theme selection so edge-to-edge chrome feels intentional.
- **Profiles/Tasks**: made status and secondary action rows horizontally safe on compact screens, added filtered no-match notices, and kept long mode/group/collision labels from crowding primary content.
- **Run Log**: moved outcome and duration chips below the run header so diagnostics keep readable width with long task names and trace detail.
- **Variables**: upgraded the Variables tab into a summary-driven variable vault with metrics, clear search, polished empty states, consistent cards, and explicit sensitive-value masking labels.
- **Design system**: added reusable screen spacing and opacity tokens to reduce hardcoded visual decisions across Compose surfaces.

## v0.2.69 - 2026-06-16

Locale condition plugin context UX (N7).

- **Feature**: added `ContextType.PLUGIN` for Locale/Tasker condition plugins as first-class profile context predicates; users can pick a condition plugin, configure it, and have profiles activate/deactivate based on the plugin's satisfied/unsatisfied state.
- **Feature**: added `LocalePluginConditionContextSource` that polls subscribed condition plugins every 30 seconds with last-known-state caching through the existing `LocalePluginConditionStateCache`.
- **Feature**: added Plugin context row in the context picker with package, config JSON, description, and timeout fields.
- **Feature**: Context Inspector shows plugin condition source health, config summary (package + blurb), and match state.
- **Engine**: `AutomationService` registers plugin subscriptions when enabled profiles are loaded and clears them on destroy.
- **Tests**: added evaluator tests for plugin matching, package/bundle validation, inversion, and inspector config summary.

## v0.2.68 - 2026-06-16

Safety and correctness patch.

- **Safety**: replaced legacy Java/Kotlin regex worker threads in variable `%regex` and `%replace` operators with RE2/J linear-time matching, eliminating leaked `regex-eval` threads from pathological user-authored patterns.
- **Safety**: unsupported advanced regex syntax now fails closed for variable regex operations instead of attempting cancellable backtracking.
- **Correctness**: fixed `torch.set` toggle semantics by reading the current torch state through `CameraManager.TorchCallback`; if Android cannot report the state, toggle now fails honestly and tells users to use explicit `on`/`off`.
- **Correctness**: torch actions now select a camera that actually reports flash availability instead of using the first camera id.

## v0.2.67 - 2026-06-15

Deep engineering, security, and UX audit pass.

- **Thread safety**: made `ArrayStore` concurrent-safe with `ConcurrentHashMap` to prevent `ConcurrentModificationException` when tasks run in parallel automation mode.
- **Thread safety**: upgraded `VariableStore` local scope maps to `ConcurrentHashMap` to prevent race conditions between concurrent coroutines reading/writing the same scope.
- **Thread safety**: marked `WiFiNetworkMonitor.lastState` and `ConnectivityMonitor.lastState` as `@Volatile` since `NetworkCallback` methods fire on binder threads.
- **Thread safety**: marked `CameraMicContextEvents` camera/mic callback fields as `@Volatile` to prevent races between `start()` and `stop()` on different threads.
- **Resource leak**: added `CameraMicContextEvents.stop()` call in `AutomationService.onDestroy()` to unregister `AppOpsManager` watchers that were previously leaked.
- **Data corruption**: fixed HTTP response `readBounded` to collect bytes into `ByteArrayOutputStream` before UTF-8 decode, preventing multi-byte character corruption when a character straddles an 8KB read boundary.
- **Correctness**: fixed `BrightnessAction` auto mode to set `SCREEN_BRIGHTNESS_MODE` to automatic instead of writing `-1` to the brightness value. Manual brightness values now explicitly set the mode to manual first.
- **Correctness**: fixed `ScreenTimeoutAction` to clamp the timeout value to 0 to 30 minutes, preventing `Long`-to-`Int` truncation on large values.
- **Correctness**: fixed `SunEventCalculator` DST offset to use the offset at the approximate event time instead of noon, preventing sunrise/sunset times from being off by 1 hour on DST transition days.
- **Correctness**: seeded `battery_level` and `charging` in `StateContextSourceImpl.seedInitialState()` from the sticky `ACTION_BATTERY_CHANGED` broadcast so battery-based profile conditions evaluate correctly immediately after service start.
- **Crash fix**: `FlowGraphCard` now uses `firstOrNull()` instead of `first()` for the profile node, preventing `NoSuchElementException` if graph data is corrupted.
- **Crash fix**: TTS `SayAction` now guards continuation resume with `AtomicBoolean` to prevent double-resume if TTS callbacks race.
- **Safety**: capped vibration duration to 10 seconds to prevent extended uncontrolled vibration.
- **Safety**: capped queued task depth per profile to 50 in QUEUED automation mode, preventing unbounded memory growth from rapid triggers.
- **Safety**: changed database backup WAL checkpoint from `FULL` to `TRUNCATE` for safer backup consistency.
- **Safety**: fixed notification button `PendingIntent` request codes to use hash-based IDs instead of `notifId * 10 + i`, preventing integer overflow for large notification IDs.
- **Memory**: `ShakeDetector` now uses `applicationContext` to prevent potential `Service`/`Activity` context leak.
- **UX**: fixed `disabledAlpha` modifier to use `Modifier.alpha()` instead of a semi-transparent black overlay, which broke disabled element appearance in light theme.
- **UX**: warning color in scene validation now uses warm amber/peach instead of green (tertiary), which was confusing since green implies success.
- **UX**: added `contentDescription` to navigation bar icons for screen reader accessibility.
- **Design system**: added `Radii.xxl` (18dp) token and `SemanticColor.warningDark`/`warningLight` to the design system. Replaced ~11 hardcoded `RoundedCornerShape(18.dp)` instances across all screens with the design token.

## v0.2.63 - 2026-06-15

Release-polish pass.

- Added IME padding to the main Compose scaffold so focused forms have safer keyboard behavior.
- Reduced bottom-navigation crowding by showing labels only for the selected destination.
- Added confirmation before deleting global variables and preserved variable search/edit/delete dialog state across recreation.
- Made widget task rows explicit button-role targets with minimum row height and long-text ellipsis.
- Added button roles to clickable flow-graph nodes.
- Preserved task/profile/action editor drafts with saveable state across configuration changes.

## v0.2.62 - 2026-06-15

Action editor compatibility and UI polish.

- Aligned dynamic action form metadata with runtime argument keys for brightness, screenshots, file read/write/append/list, and HTTP GET/POST actions.
- Kept legacy saved-action keys working (`level`, `filename`, `variable`, `content`, and `body`) so older automations still prefill and execute correctly after the metadata correction.
- Replaced full-round badge geometry with bounded 8dp corners and removed the unused full-round radius token.
- Changed action/template/context picker lists from fixed heights to adaptive max-height constraints for better small landscape and split-screen behavior.
- Made checkbox action fields full-row switch targets with explicit switch role and on/off state descriptions.
- Added regression coverage for metadata field keys and legacy HTTP POST body handling.

## v0.2.61 - 2026-06-14

Security hardening, platform readiness, and new actions/functions.

- **Target SDK 36**: raised `targetSdk` from 35 to 36 for Android 16 platform compliance.
- **HTTP POST body bound**: POST bodies are now capped at 1 MB and use fixed-length streaming mode before the network connection opens.
- **Regex match timeout**: user-authored regex operations in variable expansion now have a 2-second wall-clock timeout to prevent ReDoS.
- **Network Security Config**: added platform-level scoping that blocks public-host cleartext while permitting LAN/private-range HTTP (forward-compat with Android 17 `usesCleartextTraffic` deprecation).
- **android:allowBackup=false**: explicitly declared for privacy-first posture.
- **Android 17 audio gating**: `sound.play` and `tts.speak` now fail honestly on Android 17+ when background audio requires a media FGS type the engine does not hold; capability registry updated.
- **Hilt shrinker cleanup**: removed stale `Hilt_OpenTaskerApp` and `dagger.hilt.android.HiltAndroidApp` keep rules from proguard-rules.pro.
- **Theme toggle**: added DataStore-backed System/Dark/Light theme preference with a toggle card in the Setup screen; wired into MainActivity and widget config.
- **Wake-on-LAN action** (`wol`): sends a magic packet to wake devices on the local network with MAC validation, configurable broadcast IP/port, and unit tests.
- **Date template function**: added `{{ value | date:'pattern' }}` for epoch-millis formatting with bounded patterns, Locale.ROOT output, and fail-closed rejection of invalid patterns or non-numeric input.
- **Registry-metadata parity test**: bidirectional contract test ensuring every runtime action has UI metadata and vice versa.
- **Action guard tests**: new `ActionGuardsTest` covering POST body cap, URI scheme allowlist, wait duration cap, HTTP policy, ping host validation, missing-argument failures, and WoL packet construction.

## Unreleased

- Fixed State context matching so battery, charging, headphones, and screen facts persist across partial broadcasts instead of replacing one another.
- Added State context aliases and fail-closed numeric predicate handling for malformed thresholds.
- Added `lintDebug` to the normal GitHub Actions build workflow.
- Fixed Event context matching so repeated identical one-shot events can retrigger profiles while level contexts keep activation/deactivation semantics.
- Fixed boot Event context truthfulness by routing manifest boot starts through `AutomationService` into a replay-safe `event=boot_completed` pulse, and removed unsupported SMS-received trigger advertising from the active event source.
- Removed the legacy parallel automation engine, second `automation.db` Room database, legacy Hilt provider module, dead minimal activity, shell-capable legacy action, and dead battery/geofence manifest receivers. Active app, WiFi, and time monitors now publish into core context bridges; rebuilt APKs shrank from 22,321,836 to 21,799,321 bytes (debug) and 2,107,361 to 2,041,684 bytes (release unsigned).
- Added configurable Run Log retention with short, standard, and extended presets. The standard default keeps 30 days or 1,000 entries, prunes on service/UI startup and hourly after inserts, and includes DAO pruning coverage.
- Added Setup-tab database backup and restore controls. Backups checkpoint and export the active Room database through Android's document picker; imported backups are validated, staged for the next startup, applied before Room opens, and roll back to the previous database if restore fails.
- Added Profiles-tab OpenTasker JSON bundle export/import. Exports use Android's document picker, imports preview schema/version/counts/warnings/capability requirements before confirmation, and imported profiles are always disabled for review.
- Added a Play distribution manifest policy gate that omits SMS and phone-state permissions, hides SMS setup, and marks the SMS action unsupported while keeping standard/F-Droid SMS behavior intact.

## v0.2.59 - 2026-05-05

Dependency modernization, visual flow, scene editor, and navigation polish.

- Added typed graph-node targets to the pure automation flow model so profile, context, task, action, and missing-reference nodes can route back to existing editors.
- Made Flow tab nodes selectable and wired them into the current profile/task/action/context edit dialogs, with stale-target feedback if the underlying Room data changes.
- Added first-class conditional action metadata to the flow graph so conditional steps render with `if ...` edge labels and compact conditional markers instead of being hidden inside generic action details.
- Added a compact, horizontally scrollable Flow lane overview for profile/context/enter/exit lanes as the first read-only canvas interaction before drag/drop editing.
- Added deterministic Flow graph accessibility summaries and node labels, then wired them into Compose semantics for screen readers and UI automation.
- Added Flow-tab mutation shortcuts for adding contexts to a graph profile and adding steps to enter/exit task lanes through the existing context and action pickers.
- Added Scene-tab element creation/editing for button, text, slider, and image controls, with tap and long-press task binding pickers plus removable element rows.
- Replaced the Scene card text-only preview with a scaled canvas projection that renders element positions and sizes against the scene dimensions.
- Added drag-to-move editing on the scaled Scene canvas, converting preview offsets back to bounded scene dp coordinates before updating Room.
- Shortened bottom navigation labels from `Inspector` to `Inspect` and `Run Log` to `Log` so compact navigation items align consistently.
- Upgraded Hilt/Dagger from `2.46` to the intermediate `2.52` line while leaving Kotlin, KSP, AGP, Room, and runtime startup wiring unchanged.
- Verified the Hilt batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and the F-Droid release profile.
- Upgraded Room from `2.6.1` to `2.8.4` on the existing `androidx.room` artifact line after the Kotlin/KSP/compiler batch; Room 3.0 remains a separate future migration because it uses the new `androidx.room3` group.
- Verified the Room batch with connected migration instrumentation tests on `SM-S938B`.
- Upgraded WorkManager from `2.9.1` to `2.11.2`; no active workers are registered yet, so this batch is dependency/build compatibility only and passed the standard dependency gate.
- Upgraded the stable Compose/AndroidX UI dependency set within the current API 35 / AGP 8.7 constraints: Compose BOM `2025.07.00` and Activity Compose `1.10.1`; newer Activity/Navigation lines are deferred because they require API 36 and AGP 8.9.1, while Compose BOM `2025.08.01+`, Hilt Navigation Compose `1.3.0`, and Lifecycle `2.9.x+` are deferred because they resolve Lifecycle lint checks that need a newer AGP/Kotlin analysis stack.
- Upgraded the runtime-support dependency subset to Core KTX `1.18.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.11.0`, and Gson `2.14.0`.
- Upgraded the compiler alignment set to Kotlin/Compose plugin `2.3.21` and KSP `2.3.7`, migrating Gradle configuration from deprecated `kotlinOptions` to `compilerOptions`.
- Resolved the earlier Kotlin `2.3.21`/KSP `2.3.7` blocker by moving Hilt/Dagger from `2.52` to `2.59.2` after the AGP 9 batch.
- Upgraded the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, while keeping target SDK `35`.
- Verified the AGP/API 36 batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`; the previous release R8 Kotlin metadata warnings are gone.
- Upgraded the API 36-unblocked AndroidX stable dependency set: Core KTX `1.18.0`, Compose BOM `2026.04.01`, Activity Compose `1.13.0`, Lifecycle `2.10.0`, Navigation Compose `2.9.8`, and Hilt Navigation Compose `1.3.0`.
- Verified the AndroidX follow-up with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Upgraded the AGP 9 compatibility stack to Gradle wrapper `9.4.1`, AGP `9.2.1`, Hilt/Dagger `2.59.2`, Kotlin/Compose plugin `2.3.21`, KSP `2.3.7`, and Kotlinx Serialization JSON `1.11.0`.
- Added temporary AGP 9 compatibility flags for the explicit Kotlin plugin path: `android.builtInKotlin=false` and `android.newDsl=false`; these keep the build green now but must be removed before AGP 10 by migrating to built-in Kotlin and Android Components/new DSL APIs.
- Verified the AGP 9 stack with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Migrated AGP 9 to built-in Kotlin and the new DSL by removing the explicit `org.jetbrains.kotlin.android` plugin, deleting the temporary AGP 9 opt-out flags, and replacing the deprecated androidTest asset source-set mutation.
- Verified the built-in Kotlin/new DSL migration with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Reviewed Room 3 and deferred migration because the new `androidx.room3:room3-*` artifact group is alpha-only (`3.0.0-alpha03`) and would touch both persisted databases plus migration tests.

## v0.2.58 - 2026-05-05

Tasker XML import UI and F-Droid release verification.

- Added a user-facing Tasker XML import flow to the Profiles screen using Android's document picker.
- The preview reads selected XML with a bounded 4 MB limit, parses it through the existing secure Tasker importer, and shows source counts, import counts, mapped/unsupported actions, migration warnings, and capability review notes.
- Confirmed imports now reuse the existing Room-backed OpenTasker bundle repository and create imported Tasker profiles disabled by default for review.
- Added a pure `TaskerImportPlanner` for preview summaries and disabled-by-default confirmed bundles with JVM coverage.
- Synced the draft F-Droid metadata to version `0.2.58` / code `60` and pinned it to release source commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`.
- Added `:app:verifyFdroidMetadata` plus CI/release workflow coverage so F-Droid metadata version fields, commit pinning, Gradle properties, preassemble hooks, changelog URL, and unsigned APK output stay in sync.
- Added `tools/verify-fdroid-release.ps1` for release-tag checks, F-Droid lint/build execution, and signature-agnostic APK payload comparison against a signed upstream APK.
- Verified local `fdroid lint` and WSL fdroidserver 2.4.4 `fdroid build --no-tarball com.opentasker.app:60` with Java 17 and Android SDK 35.

## 0.2.75+189 — 2026-07-12

The **cool-running release**: the 音楽端灯 meteors move from a WebView canvas to a **native METEOR
scene element** — same dance, roughly **a third of the CPU** during music playback (~195 % → ~60 %,
measured) — plus a headless **adb workspace-transfer bridge** (broadcast-driven export/import) that
powers a fully automated build-test cycle, physically **rounded screen corners** with their own knob,
and a 電池線 fix that stops the charging flame at 100 % and turns the line blue.

### 音楽端灯 — native METEOR element (the heat fix)
- **Why:** the phone heated up badly while playing music. Systematic measurement (live `top`
  sampling per variant, thread-level breakdown) traced the load to **WebView's per-frame canvas
  machinery, not the meteor math**:
  - the real meteor page cost **~185–195 % CPU** sustained (app process + WebView renderer);
  - a bare full-screen canvas drawing ONE line at 60 fps already cost **~60 %**;
  - four edge-strip canvases cost **more** (~110 %) — per-layer commit overhead, not pixels;
  - an rAF loop with **no** canvas drawing cost ~0 % — the commit path itself was the furnace.
- **New scene element `METEOR`** (`scenes/EdgeMeteors.kt` + a `SceneActivity` renderer branch):
  a 1:1 native port of the meteor page — perimeter ribbons in a rounded-rect band, layered glow,
  comet-taper core, white-hot head star, per-ribbon twinkle, hue drift, and the full **音楽反応 v3
  tempo-locked** behaviour (beat-grid pump, auto-gain-normalised dynamics, onset fallback) — drawn
  by the app's **own RenderThread** like the 電池線 charging fire, reading
  **`MusicPulseSource.Bridge` natively** (no JS bridge, no WebView renderer process at all).
- **Three rendering pathologies found and eliminated on the way** (each measured, each ~190 %+):
  - `BlurMaskFilter` Gaussian glow — CPU-rasterized per blurred path on a hardware canvas (~225 %);
  - wide anti-aliased **stroked paths** (even 3-point ones) — HWUI software-rasterizes every stroke
    into a mask texture on its `hwuiTask` threads each frame;
  - the band's **even-odd ring `clipPath`** — a full-window coverage mask rasterized every frame;
    this was the invariant cost across ALL variants, the original WebView page included.
- **Final architecture — GPU-native primitives only**: every ribbon is split at the screen corners
  it crosses into 1–3 **axis-aligned capsules** (`drawRoundRect`); the glow is concentric widening
  capsules with a Gaussian-ish alpha falloff; the core taper is a per-run axis-aligned
  `LinearGradient`; the band's inner hole is punched by **one `PorterDuff.CLEAR` round-rect**
  instead of a clip. Result: `hwuiTask` raster threads at **0.0 %**, RenderThread ~21 %,
  **~60 % total at 60 fps / ~50 % at 45 fps** (vs ~195 %), dance visually intact.
- **Physically rounded screen corners** (白い熊's design): four **opaque-black corner masks** drawn
  over the band — ribbons run into the corner squarely underneath and emerge from behind the curve.
  The mask path is static and HWUI-cached (rebuilt only on size/radius change), so it is free.
  Its radius is an independent live knob **`%Ongaku_Corner`** (default 18; `0` = square corners,
  `32` = the band's own rounding) in `音楽端灯の設定 [01]`.
- **All knobs are now %var-live**: the METEOR config maps every `%Ongaku_*` variable through the
  scene engine's live expansion, so palette, speeds, glow, fps, reactive tuning — everything —
  applies **instantly without re-showing the scene** (the WebView read them once at page load).
- **The FPS cap became a true linear heat dial**: a capped-out frame is skipped before the sim
  step — no state write, no recompose, no draw, no commit — so `%Ongaku_Maxfps` now scales cost
  almost proportionally. Default retuned **60 → 45** (visually smooth, measurably cooler);
  the dead interim `Ongaku_Glowres` knob was removed from 設定.
- Invisible ribbons (fade-in/fade-out ends of life) skip all drawing; `METEOR` is editable in the
  scene editor (element-type list, defaults, size).
- Screen-off gating as before: the element leaves composition when the display sleeps — the frame
  loop stops dead, nothing computes in the dark.

### Workspace-transfer bridge — headless export/import over adb
- **New `WorkspaceTransferReceiver`** (`core/transfer/`), an exported ordered-broadcast bridge
  gated by the shared protocol extra (same convention as the widget bridges):
  - **`shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE`** — writes a full workspace export
    (tasks, profiles, scenes, variables, templates, projects, groups, item metadata) as an
    OpenTaskerBundle JSON to `/sdcard/tmp/白い熊 自由作業盤.<yyyy-MM-dd_HH-mm-ss>.json` (or an
    explicit `extra.PATH`), answering with the written path and item counts;
  - **`shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE`** — imports the bundle JSON at `extra.PATH`
    (a bare filename resolves against `/sdcard/tmp`) with the standard strategies (merge projects,
    overwrite same-name items in place), answering with a human-readable import summary and any
    validation warnings; failures return the error message in the broadcast result.
- Powers the new **fully automated dev cycle**: baseline export → mirror sync → build →
  `adb install -r` → push bundles → headless import → test → final export → archive — no manual
  file picking anywhere.

### 電池線 — stop the charging flame at 100 %, full-charge line goes blue (workspace-side)
- `denchi.update` gained a gate: at **100 % battery `%Charging` is forced to `false`**, so the
  charging fire stops while the plug stays in (it previously burned forever on a full battery,
  since EXTRA_PLUGGED-based charging detection stays true).
- **`%Denchi_Full`** (the 100 % line colour) changed green → **blue `#0000FF`**; label updated.
- Both tasks re-shipped fully literate (labels on every action + task notes).

### Packaging & docs
- Dev-workflow overhaul recorded in `CLAUDE.md` and the repo skills (`build-apk`,
  `workspace-mirror`): wireless adb (direct or via the `skhw` ssh tunnel), automatic
  `adb install -r`, `/sdcard/tmp` keeps only the current APK, end-of-cycle archives the final
  export and APK to the on-phone backup tree.
- Version tail: builds `+182` – `+189`; `versionCode = 770189`.

## 0.2.75+181 — 2026-07-11

The **music-reactive release**: the 音楽端灯 edge-light meteors now **dance to the actual music** —
tempo-locked to a beat grid the engine derives live from the device's output mix — plus a
narrow-screen (folded-cover) layout mode, a fix for the 物理鍵 grabber silently dead since the
variable-demotion campaign, and a Visualizer feasibility diagnostic.

### 音楽端灯 — 音楽反応: tempo-locked, audio-reactive meteors
- New engine component **`MusicPulseSource`** (`core/media`): taps the device **output mix** via
  `Visualizer` on audio session 0 with the **push capture listener** at the device max rate
  (~20 Hz) and distills it into small live signals:
  - **level** — smoothed loudness 0..1 (fast attack, slow release);
  - **beat** — a decaying 0..1 impulse on bass onsets (low-FFT-bin flux over a ~2 s running
    average, with a **180 ms refractory** so one drum hit is one onset);
  - a **beat grid** — tempo + phase + confidence, from inter-onset-interval clustering **folded
    into the 60–180 BPM band** (half/double-time hits agree), modal cluster ±8 %, gentle period
    tracking, and **PLL-style anchor nudging** toward on-beat onsets; confidence fades out ~2 s
    after onsets stop (quiet outros, pauses).
- **Nothing is recorded** — the Visualizer yields transient 8-bit visualization snapshots only.
- The bridge is injected into WEB scene elements as **`window.OngakuPulse`**
  (`level()/beat()/bpm()/beatPhase()/tempoConf()`), polled from the page's rAF loop (R8 keep rule
  for the `@JavascriptInterface` methods).
- **Ref-counted lifecycle**: the Visualizer exists only while a reactive scene element is visible
  AND the screen is on AND the knob is on — the scene's `musicPulse` config is live-%var-expanded
  (`%Ongaku_Reactive`), so flipping the knob to `0` releases the capture completely.
- The meteor canvas gained a reactive branch (scene bundle):
  - with a confident tempo, the whole flow **pumps precisely ON each grid beat** —
    `exp(-beatPhase × sharpness)`: sharp attack at the beat instant, decaying to the next
    (metronomic, immune to missed/extra onsets); ribbons **flash** and head stars **swell** on
    the same grid;
  - the **baseline speed follows the music's dynamics, auto-gain normalised** against the track's
    own rolling min/max (~8 s window) — mastering compression holds raw RMS nearly constant, which
    froze speed in the first iteration; normalisation restores the full slow↔fast swing;
  - low tempo confidence falls back to per-onset surges with a gentle drift; `Ongaku_Reactive=0`
    reverts to the original random walk exactly.
- **Five knobs, recipe-documented** in `音楽端灯の設定 [01]`: `%Ongaku_Reactive` (master),
  `%Ongaku_Reactgain` (dynamics→speed sensitivity), `%Ongaku_Reactpulse` (beat flash),
  `%Ongaku_Reactkick` (pump depth), `%Ongaku_Reactsharp` (pump shape: 2 = swell … 10 = jab). All
  nine tuning labels (incl. `Speedmin/Speedmax/Twinkle/Headglow`) were rewritten as **cross-linked
  recipes** — every label states its reactive-mode role and names partner knobs with concrete
  setting ideas (EDM jab, ballad breathing, flash-only, no-stall floor …).

### `music.viz.test` — Visualizer feasibility diagnostic
- New Media action: taps `Visualizer(0)` for N seconds (play music!) and reports frames received,
  live-frame share, peak RMS and peak bass energy, ending in a clear ✅/🔴 verdict — this decided
  the reactive pipeline was buildable on the Mate XT before any of it was written.
- Handles two verified EMUI quirks: a fresh `Visualizer(0)` can arrive **already enabled** (resize
  then throws "wrong state 2" — disable → resize best-effort → re-enable), and **polled**
  `getWaveForm()` is throttled to ~4 Hz (hence the push listener in the real pipeline).
- Wired into the capability pre-flight (Microphone, blocking); manifest gains
  `MODIFY_AUDIO_SETTINGS`; ships with a `視覚化試験` task in the 音楽端灯 project.

### 物理鍵 — grabber dead since the variable demotion (fix)
- The 2026-07-05 demotion campaign renamed the `%PKEY_*` super-globals to project-scoped
  `%Pkey_*` and rewrote every task — but the engine's `ShizukuKeyEventListener` still read the
  deleted ALL-CAPS names from the super-global bucket, so `enabled()` was permanently false and
  the volume-key grabber (double-press → camera etc.) had been silently dead since that day.
  It now resolves the MixedCase project-globals via `snapshotAll()` (the listener runs outside any
  task, so it can't know the owning project's id), keeping the legacy ALL-CAPS names as fallback.
- Same class of fix for the edge-bar long-swipe threshold: `SceneActivity` read the demoted
  `%LONGSWIPE_DP` and silently fell back to the default — now `%Longswipe_Dp` first.
- A full audit of all 40 demoted names found no other live code readers; stale prose mentions in
  18 item notes were refreshed on-device via a minimal notes bundle.

### Narrow screen — compact layout on the folded cover panel
- Under **480 dp** window width (the folded Mate XT cover, ~336 dp; semi/unfolded stay regular),
  the list layouts switch to a compact mode:
  - the per-level **group indent shrinks 56 → 14 dp** (the old indent ate ~17 % of the panel);
  - list side gutters 16 → 6 dp; task-card inner padding 16 → 10 dp;
  - action rows **reflow: each argument on its own full-width line** — the key pill plus a value
    that takes the whole rest of the row and **wraps to 2 lines** before ellipsising, so
    `%Ongaku_*`-length names read in full instead of "Ong…".

### Workspace content (bundles, not APK)
- **Battery 割 display**: the short battery form (`%ST_BattShort` — the `batt-fold` widget and the
  相撲字時計 overlays) now renders round tens as 九割/八割/…/一割 instead of 九〇/八〇 (non-round
  values like 八五 and 100 = 全 unchanged; same character width, so no layout shifts).
- The 18 pre-demotion variable names still mentioned in task/scene **notes** were freshened to the
  current `%MixedCase` names (and the 画面操作 01 note's "super-global" claim corrected to
  project-global).

## 0.2.75+175 — 2026-07-09

The **living-overlay release**: a native **charging-fire animation** on the battery line, a rewritten
**buttery-smooth music edge-light** canvas, strict **screen-off gating** so no overlay ever computes
behind a dark screen, a full set of **documented tuning variables** in the projects' `[01]` settings
tasks, a **task-target bridge** for sister launchers, and an action-row fix so variable names always
render in full.

### 電池線 — charging fire (native scene renderer)
- While charging (and only with the screen on), **two fire-comets glide in from both ends of the
  battery line, meet in the middle** with a red-orange collision bloom, and slide back out — a
  seamless, breathing loop (cosine-eased turnarounds, so it never jumps or disappears).
- Each comet has a **blood-red → hot orange-red gradient body** (soft-blurred capsule) and a red head
  glow — no white anywhere in the flame.
- **Red star-cross glints** twinkle at each flame tip: tiny `+`-shaped strokes flashing in and out on
  fast per-glint cycles — a genuine red sparkle instead of a solid dot.
- An **ember burst** sprays red sparks in all directions from each tip (bright red-orange at birth,
  cooling to deep crimson), arcing down under gravity into the scene's below-line head-room.
- A decaying **heat field** tints the bar deep red where a comet just passed — a lingering
  "residual fire" trail (~1.3 s time-constant) that cools back to the line's own colour.
- The visible bar itself stays a thin strip (`barThickness` config, default 3 dp) at the top of the
  now-taller scene; the line keeps its state colours (base / low-battery red / full green) at all
  times, and the whole overlay stays fully tap-through.
- **Fully variable-tunable** via the scene config → `電池線の設定 [01]`: `%Denchi_Cycle` (seconds per
  converge-and-return breath), `%Denchi_Hibana` (ember count), `%Denchi_Kirameki` (glint count),
  `%Denchi_Nokoribi` (heat-trail linger seconds; `0` disables the trail loop entirely).
- The effect exists in composition **only while `charging && screen-on`** — unplugging or blanking
  the screen stops the animation clock dead (zero off-screen CPU).

### 音楽端灯 — smooth, rich, heat-controlled edge-light (scene canvas)
- The WebView canvas hot loop was rewritten **allocation-free**: no per-frame arrays (the perimeter
  mapper writes globals), per-ribbon colours resolved once at spawn, in-place particle compaction,
  and the ribbon core drawn as **one gradient stroke** instead of 24 per-segment strokes — roughly
  **9× fewer draw calls per frame**. This eliminated the GC-pause stutter ("choppy, interrupted").
- Runs at the **full display refresh rate** with a settable cap: `%Ongaku_Maxfps`
  (`0` = uncapped, `60` = default, `30` = power-saver) — the overheating control.
- **New eye-candy knobs**, all injected as canvas variables: `%Ongaku_Headglow` (a near-white head
  star melting into the ribbon colour — shooting-star tips), `%Ongaku_Twinkle` (per-ribbon shimmer
  depth), `%Ongaku_Huedrift` (deg/s — a ribbon's colour slowly walks the colour wheel as it orbits).
- Palette reworked: the original bright multicolour set **plus blood-reds** (`#ff0000`, `#c00000`,
  `#ff2a00`) interleaved, so ~1 in 4 meteors runs red among the bright ones.

### Screen-off gating (engine)
- New opt-in WEB-element config **`pauseWhenScreenOff`**: when the display turns off, the native
  renderer calls `WebView.onPause()` *and* `window.__scenePlay(false)` (a JS hook the page defines),
  freezing both the compositor and the rAF loop; both resume on screen-on. **Opt-in by design** so
  通知明滅's over-lockscreen wakedance scenes — which must draw while the screen is off — are
  untouched.
- The battery-line comet effect leaves composition entirely on screen-off (same guarantee, native).

### Settings-task workflow
- Both projects' `[01]` settings tasks now carry the **complete knob set with a documentation label
  on every action** — the task doubles as the manual. Re-running the project's `⇨ 起動 [71]` task
  (which runs `[01]`) idempotently applies any settings change; a live scene reloads with the new
  values automatically.

### Task-target bridge (sister-launcher integration)
- New **`GET_TASK_TARGET_PACKAGE`** ordered-broadcast receiver: a sister launcher holding one of our
  run-task shortcuts can ask *which app the task ultimately opens* (by task name or id) and point its
  "app info" / "uninstall" menu entries at that app instead of at us. Newer shortcuts additionally
  bake the target package into the shortcut intent's extras.

### UI fixes
- **Action rows: variable names always render in full.** The arg renderer's hard 160 dp cap on
  non-last values (the `var.set` *name*) truncated most real-world names on a wide screen; names now
  take their natural width, with a 3:1 weight backstop so the value keeps ≥~25% of the row and a
  pathological name ellipsises at ~75% instead of pushing the value off.

## 0.2.75+164 — 2026-07-05

A large feature release over 0.2.75+127: the **相撲字時計** fold-aware over-lockscreen clock, a **task & action UI overhaul**, a full **Variables-tab redesign** with an in-app **dead-globals analyzer** and hard guards against scope leaks, a new **Edit Action** action, tap-through **permission deep-links**, and a switch to **event-local** notification/broadcast variables.

### 相撲字時計 — fold-aware overlay clock
- A new over-lockscreen overlay clock rendered in **相撲字 (sumo-script)** style, ported from the Tasker 時間と日付 project and driven entirely by the app. Three layouts — **folded / semi-folded / unfolded** — swap automatically with the device's fold state.
- **`%FOLD` via the HALL sensor**: a `fold` event context reads the hinge/HALL sensor and publishes the fold state, so the clock (and any task) can branch on `%FOLD`.
- The wide (semi/unfolded) layout **centres the time itself** on screen, with 午前/午後 and the weekday placed relative to it (center-anchored scene positioning, `xc`).
- Scenes are **touch-through** — the overlay passes taps to whatever is beneath it.
- An **app-multiselect picker** dialog chooses which apps hide the clock; the selection is committed to the blacklist variable *and* written back into the 設定 task so it survives the next startup.
- Bundles are **id-free / name-based** (zero ids; everything referenced by unique name), so a re-import can't dangle a profile→task link.

### Task & action UI overhaul
- Redesigned **action rows** with UI-settable styling.
- **Per-task and per-action menus** with **clipboards** — copy/cut/paste actions between tasks and duplicate tasks.
- Removed the olive accent throughout (**olive-free theme**).

### Variables tab — redesign
- Variable **name in blue, value bold**, both at action-view sizes — colours and sizes independently **UI-settable** (defaulting to parity with the action editor). Name and value share **one line**; the folded view is a single line.
- **Row padding** defaults to 2px and is settable.
- A yellow **magnifying-glass** icon sits to the right of the search bar.
- **Foldable scope sections** (Global, Project-global, …) with larger underlined headings, a fold triangle to the right of each heading, slight indentation, and a **live count** in parentheses.
- **Project-filter pills** in the top bar scope the list (and the project-global counts) to the selected project; a pill expands with a wrap-around rounded border around its members when unfolded.

### Dead-globals analyzer + scope guards
- A **"Clean up dead globals"** analyzer folds into the Variables tab. It classifies every persisted global and lists — per category, expandable — exactly what will be deleted and where:
  - **Shadow-copies** — a super-global duplicating a live project-global (with the twin project named).
  - **Orphans** — globals referenced by no task, profile, scene, or widget template.
  - **Dangling project-globals** — rows whose `projectId` points to a deleted/re-created project (previously invisible to both the analyzer and the Var tab).
- Deletion is **cache-consistent** (the in-memory global cache updates in lockstep with the DB).
- **Hard guards against the root cause** of scope leaks:
  - Deleting a project now **cascades to its variables** (they can't re-home to a dead project id).
  - Startup **sweeps dangling** project-globals before warming the cache.
  - `set()` redirects a MixedCase name written at projectId 0 to **task-local**, and import **skips** MixedCase-at-0 — so a project-scoped name can never land in the super-global bucket.

### New action
- **Edit Action** (`task.editaction`) — programmatically edit another task's action: locate it by index or by `matchType`/`matchName`, set one arg, and persist. Used by the clock's blacklist picker to keep its 設定 task in sync.

### Engine & permissions
- **Tap-through permission deep-links**: the permission block/warning dialog shows an **"Open &lt;permission&gt; settings →"** pill per missing permission, deep-linking straight to the correct System settings page (via `CapabilityState.settingsIntent`), on the shared dialog host and the task pre-flight block.
- **Event-local notification/broadcast variables**: `%NOTIF_*` (notification listener) and `%INTENT_*` (broadcast receiver — e.g. Poweramp's ~30 `TRACK_CHANGED` extras) are **no longer persisted as super-globals**. They're threaded per-invocation to the triggered enter task via the event's `vars`, and a startup sweep clears any stale copies — keeping the global namespace to genuine app/engine state.

## 0.2.75+127 — 2026-07-03

A point release over 0.2.75+122 — one targeted fix.

### Fixes
- **Huawei Mate XT foldable**: restored the folded-portrait wakedance fold-compensation the upstream reconcile had dropped. On the folded cover panel held in portrait, EMUI reserves a 105px top system-bar strip and confines the over-lockscreen wakedance Activity below it, clipping the 通知明滅 black mask + edge-light. The fix pulls that window up 105px so the blink covers the full panel again — applied **only** in that one state (folded landscape, semi-folded, unfolded, and the screen-on overlay blink are all untouched).

## 0.2.75+122 — 2026-07-03

A large update over 0.2.75+36: cross-app **protected contacts**, a **drag-reorder** pass across the app, a **Review Import** overhaul, **critical data-loss hardening**, task-card & group-header **styling controls**, a **Termux keyboard** trick, and a resync onto the latest OpenTasker.

### Protected contacts (cross-app privacy)
- Companion feature with the sister apps (白い熊 GNU Jami, 白い熊 Arcane Chat): for a marked contact, the messenger posts a **content-free notification** (a fixed “着信あり：新着伝言。” body) instead of the sender name / message text, so lock-screen and Android-Auto read-outs stay private. A per-package marker keeps it opt-in per messenger.
- 自由作業盤 pushes the protected-contact list to each messenger over an ordered broadcast, and can **read it back** (a `GET_PROTECTED_CONTACTS` query channel) to verify state.
- A **保護試験** test group (per-messenger checks + a cumulative check) confirms each messenger's stored list with a 🟢/🔴 dialog.

### Review Import
- Importing a JSON bundle now opens a near-full-screen **Review import**: per-category counts (“Tasks: N”, with “N exists” flagged), a **folder tree** (project → type → group → items) showing exactly where each item lands (and marking projects/groups that will be created), a **global conflict strategy** with per-item overrides (Overwrite / Overwrite + backup / Keep both), and Cancel/Import.
- Review typography (per-text sizes, a readable sky-blue conflict colour, row padding) is UI-settable.
- **An import never downgrades a Manual-sorted tab** — a partial import no longer silently re-sorts untouched groups.

### Drag-reorder & projects
- **Tasks, the Projects tab, and the top project-tabs** all support drag-to-reorder (the Projects tab's up/down arrows are gone; a long-press on a project tab gives a grab haptic).
- **Projects tab**: tap a project to make it active; the current project is clearly highlighted (accent border + tint).

### Task list & group-header styling
- Icon-less task cards stay **compact** and honour the padding settings; the empty “add icon” placeholder is small and visually subdued.
- Shrunk the ⋮ menu buttons so they no longer inflate rows/headers — **“Padding between cards”** and **“Padding inside group headers”** are now actually respected.
- **Group-header background** is a settable ARGB, with a settable **header border** (thin yellow by default).
- The **task-icon-size** slider now works; the **advanced full-screen, category-foldable action picker** is restored and default-on; an **app-picker** (choose from installed apps) is on app.kill / app.launch / intent.launch / intent.send / notify.dismiss.


### Reliability & engine
- **Critical data-loss fix**: hardened task-action persistence against a strict-decode landmine that could blank a task's whole action list after an app update (tolerant storage codec + an overwrite guard + write logging).
- **Battery**: removed the engine's permanent partial wake-lock, gated the shake / orientation / app-usage sensors to run only when a profile needs them, and stopped eager high-accuracy GPS.
- Generic **Send Intent** gained ordered-broadcast **result capture** (`result_var`).

### More
- **Termux keyboard**: an edge-bar up-swipe re-focuses Termux to bring its IME back (for mosh/emacs sessions where a screen tap won't).
- Themed black/yellow **snackbar & flash** everywhere; **oval-bar borders** (settable width + colour) on the volume/brightness panels; the **Variables** tab's folded rows now show each variable's value.
- The open tab + active project **persist across app exit**.

### Upstream
- Resynced onto **OpenTasker 0.2.75 / code 77**, including upstream's hardened database-backup publishing (WAL-checkpointed, schema-validated backups).

## 0.2.75+36 — 2026-06-27

**Pure-black, yellow-framed popups everywhere**, a task **icon from a song's album art**, and a **redesigned launcher shortcut picker** — now a tall floating dialog whose tasks are organised into bordered folder-boxes, with its own UI-customization controls.

### Menus & dialogs
- **All popup menus and dialogs are now true black with a yellow border**, matching the cards and search box — no more the lifted, brownish Material surface. The theme's `surfaceContainer*` roles are pinned to pure black, and a shared `ThemedDropdownMenu` (black container + 1.5 dp yellow border) backs **every** dropdown: the `+` FAB menu, the project switcher, the scene / widget / sort / font menus, and the action-clipboard menu. Standalone dialogs (task icon, etc.) carry the same yellow rounded frame.

### Task icons
- **New "Audio" icon source** in the task icon picker (App / Picture / Emoji / **Audio** / Clear): pick an **mp3 / ogg / flac / m4a** and the task takes its **embedded album art** as the icon — extracted with `MediaMetadataRetriever`, centre-cropped and snapshotted to a PNG like the other sources. A toast tells you if the file has no embedded artwork.

### Launcher shortcut picker (add-to-home-screen → 白い熊 自由作業盤)
- **Now a floating dialog**, not a fullscreen page: a tall + wide (94 % × 90 %) card with a **yellow rounded frame** over a dimmed scrim. Dismiss by tapping outside or the bottom **Cancel** button (black background, yellow rounded border).
- **Tasks are organised by group.** Each project, unfolded, shows its groups as **bordered rounded folder-boxes** — a folded group is visibly a closed box, so its siblings below can no longer be mistaken for its contents — then the project's ungrouped tasks. Groups **nest**, order is preserved, expanded group contents are indented deeper, and the old misleading play-arrow on task rows is gone.
- **New UI-customization → Shortcut picker section:** font size, row spacing, indent per level, group-box roundness, group-box border, and font — all applied live.

## 0.2.75+31 — 2026-06-26

**Move actions around freely** — long-press to multi-select and **clone / copy / cut / delete / paste** actions within and **between** tasks — plus a workspace-wide shift to **name-based linking**: scenes, scene-element task links, and `task.run` all resolve by **name** (not fragile ids), imports **overwrite in place**, and item names are now **unique within a project**. And `sound.play` can read tones anywhere in shared storage.

### Task editor — action multi-select & clipboard
- **Long-press an action** in a task to select it (multi-select by long-pressing / tapping more rows; selected rows are highlighted with a ✓). The long-press menu acts on the **whole selection**: **Clone** (duplicate in place), **Copy**, **Cut**, **Delete**.
- **Paste before / Paste after** the long-pressed action (shown once something has been copied/cut). An app-wide **`ActionClipboard`** holds the copied/cut actions, so you can **move actions between tasks**, not just within one.
- The drag handle keeps its own long-press reorder; a plain tap still expands a row (or toggles its selection while selecting).

### Link everything by name (no more broken links on re-import)
- **`scene.show` / `scene.hide`** resolve a scene by **`(project, name)`** — the scene in the calling task's project wins, then any-project by name (deterministic by position then id), then a numeric id only as a legacy fallback. `VariableStore.projectId` is exposed so the action knows the caller's project.
- **Scene element task links** — tap, long-press, and the edge-gesture handlers — now carry a **task name** (`tapTaskName` / `longPressTaskName`, JSON-only, no migration) and resolve **name-first** at run time *and* on import. A re-imported or recreated task no longer silently drops a slider/button's action: the editor stores the name on pick, **export back-fills** names from ids, and **import re-binds** by name → the bundle id map → the raw id.
- **`task.run`** resolves **name-first** (exact, then case-insensitive), with the numeric id only as a legacy fallback — matching the scene/profile resolvers.

### Import — overwrite in place
- **Profiles and scenes now overwrite *in place*** on import (reuse the existing row id, matched by name), just like tasks already did — so a re-import **keeps each item's id, group membership, and notes** instead of deleting + re-inserting and orphaning them. A profile overwrite preserves its enabled state.
- The **default conflict strategy is now Overwrite** (in place), and the conflict dialog leads with it. The "missing tap task" import warning no longer misfires when a scene element carries a name to re-bind against.

### Name uniqueness
- The **task / profile / scene editors block a duplicate name within the same project**, and the **project editor blocks a duplicate project name** — Save is disabled with an inline error (widget templates already did this).
- Enforced at the DB level by **UNIQUE indices** on `(projectId, name)` for tasks/profiles/scenes and `(name)` for projects (**DB schema v16 → v17**, `MIGRATION_16_17`). The migration **self-heals** first — any pre-existing collision is renamed `"<name> (<id>)"` so the index build can never fail; SQLite treats Unfiled (null-project) rows as distinct, so the editor's UI check covers those.

### Sound
- **`sound.play` — all-files access.** A new **All-files capability** (`Environment.isExternalStorageManager()` / `MANAGE_EXTERNAL_STORAGE` on API 30+, `READ_EXTERNAL_STORAGE` below) lets `sound.play` read custom tones **anywhere in shared storage** (e.g. the 通知明滅 Jami notification tone), surfaced as a capability pill on the action and a new **"All files access"** row on the Setup tab with a deep-link to grant it.

## 0.2.75+25 — 2026-06-25

**Freeze bubbles** — a native port of the Tasker 凍結 融解 re-freeze workflow — plus a tiled app picker, app-icon launcher tasks, an inline freeze toggle + tappable task icon, fully styleable bubbles, and every numeric UI-customization setting converted to a slider.

### Freeze bubbles
- **Per-task "Freeze bubble" flag** (DB **v16**, `MIGRATION_15_16`). Running a flagged task queues a re-freeze bubble for the app it launches/unfreezes (package read from its `app.launch` / `app.unfreeze` action). Toggle it in the task editor **or inline on the expanded task card**; **Make Launcher Tasks enables it by default**.
- **Desktop-gated overlay** — bubbles render as draggable `TYPE_APPLICATION_OVERLAY` windows shown **only while the default home launcher is foreground** (auto-detected), hidden everywhere else. Each shows the app's icon + a ❄ badge + label.
- **Tap a bubble → freeze the app** (`app.freeze` via Shizuku) and remove it; **long-tap → dismiss only**. Bubbles are **draggable**, **persist across reboots** (`FreezeBubbleStore`), de-dupe per app, and **re-clamp on rotation / fold** keeping their position relative to the top-right edge.
- **Fully styleable** under *UI customization → Freeze bubbles*: icon size, icon roundness, label size, label weight, and label font — with a **live preview**.
- The Setup tab's **Overlay access** row now notes freeze bubbles.

### Launcher tasks & icons
- The **Make Launcher Tasks** app picker is now a **yellow-bordered grid of app-icon tiles** (icon + name, multi-select with a check badge), replacing the plain text list — shared with every app-package field.
- Generated unfreeze-then-launch tasks now **default their icon to the selected app's icon**.
- A task's **icon is tappable in the list** — opens the icon picker (App / Picture / Emoji / Clear) without opening the editor; tasks with no icon show an "add icon" affordance when expanded. The picker is a shared component (`TaskIconEditorRow` / `TaskIconPickerDialog`) used by both the card and the editor.

### UI customization
- **Every numeric setting is now a slider** (was +/− steppers): Borders → Border width; Typography → Text size; Flash / toast → Border width, Corner radius, Text size; plus the freeze-bubble sizes. The flash and bubble sections keep their live previews.

### Infrastructure
- New `FreezeBubbleStore` (SharedPreferences) + `FreezeBubbleOverlayManager`, started from `AutomationService`; bubble enqueue hooked into `executeAndLogTask` (covers every run path). `TaskIconStore` gains a context-free `saveFromApp(pkg)` for non-UI callers.

## 0.2.75+18 — 2026-06-23

**Home-screen shortcuts that run a task directly**, each with a **persisted custom icon** (from an app, a picture, or an emoji), a **launcher shortcut picker**, a global **icon-size** control, and **cross-device icon transfer** in exports.

### Task shortcuts
- **Launcher "create shortcut" picker** — a new `CreateTaskShortcutActivity` registers for the system `CREATE_SHORTCUT` flow, so long-pressing the home screen → *Shortcuts* → **白い熊 自由作業盤** opens a **foldable projects → tasks picker** (all projects folded by default); choosing a task drops a home-screen shortcut that runs it.
- The in-app **Pin to home screen** path now uses the task's custom icon (previously always the app icon).
- **`TaskRunActivity` is now exported**, so a third-party launcher (e.g. 白い熊 雷起動盤 / raikidoban) that fires the raw shortcut intent itself can start the task — fixes the launcher's "this shortcut isn't associated with a valid app". Shortcuts carry the task **id + name**, so they survive a task re-import.

### Per-task icon
- Assign a task's icon in its editor from three sources: an **installed app's icon**, a **picture** (photo picker), or an **emoji / glyph** (new `EmojiPickerDialog` — type or tap a quick-pick, with a live preview). The chosen icon is **snapshotted to a PNG** in app storage (`TaskIconStore`) at pick time, so it keeps displaying even if the source picture is deleted or the source app is frozen, and is **baked as a bitmap** into shortcuts so it survives in the launcher regardless of the app's state.
- The icon shows in the **editor preview**, next to the task in the **task list** (folded and unfolded), and on its **shortcut**; no icon set → the app's launcher icon.
- **Global task-icon size** — a new **slider with a live preview** under *UI customization → Task list* (`ThemePrefs.taskIconSizeDp`, 16–96 dp) sizes every task's icon on its card.
- The **app picker** dialog was rebuilt as a **yellow-bordered grid of app-icon tiles** (icon + name), replacing the plain text list — used here and by every app-package field.

### Import / export
- Task icons now **travel across devices**: an export embeds each icon as base64 (`Task.iconData`); import **re-materializes** it into local storage (reusing the existing local file on a same-device re-import). The bundle schema stays **v4** — the field is additive, so older builds ignore it — and `iconData` is never written to the database.

### Schema
- **DB v15** — new `tasks.iconPath` column (the saved icon's path) with a `14 → 15` migration; existing data is untouched on update.

## 0.2.75+11 — 2026-06-23

Re-based the **entire fork onto upstream OpenTasker 0.2.75**, and added **Turn Screen Off**, **Freeze / Unfreeze App** + a **multi-select launcher-task generator**, a **capability-aware action editor**, and the screen-off **通知明滅 wakedance**.

### Re-synced onto upstream 0.2.75
- Rebased the whole fork delta from upstream **0.2.68 → 0.2.75**, keeping every customization (~31 file overlaps resolved by hand). The full pre-resync history is preserved in `backup/custom-0.2.68-pre-resync`.
- Inherited upstream's intervening work: **Locale plugin** interop (both *condition* contexts and a *setting* target), **encrypted DB backup** (AES-256-GCM), a real **Shizuku** elevated backend + **Termux** script dispatch, scene **multi-select / alignment guides / resize**, a **visual flow editor** (zoom, edge routing, branch & subflow markers), `var.persist`, dotted/bracketed `var.set` JSON paths, **RE2/J** linear-time regex, a **Run-Log expression debugger**, and a large batch of concurrency / teardown / schema-drift hardening, plus i18n scaffolding.
- Our **DB schema stays at v14** — no migration when you update; existing data is untouched. Upstream's redundant per-item `group` tag is dropped in favour of our richer project grouping. Gradle dependency-verification is disabled (it only fights local builds).

### New actions
- **Turn Screen Off** (`screen.off`) — accessibility `GLOBAL_ACTION_LOCK_SCREEN` first (no Shizuku needed), Shizuku `KEYCODE_SLEEP` fallback; no longer greyed out.
- **Freeze App** (`app.freeze`) / **Unfreeze App** (`app.unfreeze`) — disable/enable any app through Shizuku (`pm disable-user` / `pm enable`).
- **Make Launcher Tasks** (`tasks.launchers`) — a **multi-select app picker** (all installed user apps, *including frozen ones*, searchable) that writes one **unfreeze-then-launch** task per chosen app into a named project group, **re-sorted alphabetically on every run**, skipping duplicates; the group is auto-created beneath the generator task.

### The 通知明滅 screen-off wakedance
- When a notification arrives **screen-off**, the device now **wakes over the lockscreen** and rotates through every unread app (colour + sender + preview) before sleeping, repeating on a sub-minute timer. Beats EMUI's ~2 s teardown via a `SCREEN_BRIGHT` wakelock, draw-before-wake, an **opaque show-when-locked `WakedanceActivity`**, and a clean self-sleep — no lockscreen or wallpaper flash.
- New engine primitives: a **`sec_tick`** sub-minute event trigger; `state.get screen=on/off`; `wake` / `screen.off` via Shizuku key events.

### Capability-aware action editor
- Each action now shows a **live status pill** — **red** with a one-tap **deep-link to the exact Settings screen** when its permission/service isn't set up, **yellow** (FYI) when it is — evaluated against the **same checks the Setup tab uses** (accessibility, Shizuku, modify-settings, overlay, Do-Not-Disturb, notifications). Consistent across the action picker, the in-task list, and the config dialog.
- New **`APP_PACKAGE`** field type — type a package name / `%variable`, or pick from an installed-apps list.

### Battery, scenes & recomposition
- **Charging detection = `EXTRA_PLUGGED` only** — Huawei lingers `isCharging`/status after unplug, so charging state now follows the plug (covers wireless, drops instantly). The **電池線** battery line turns **solid red while charging**.
- **Per-variable scene recomposition** (`derivedStateOf`) — a scene element re-renders only when *its own* expanded values change, not on every global write, cutting idle overlay CPU.

### Profiles & engine
- Profiles now link their **enter/exit task by NAME** (DB **v14** + migration) with the id as fallback, so re-importing a task — which re-ids it — no longer orphans the profile (“Missing task #N”).
- The **Monitor** tab aggregates engine task-activity and a widget-pull log.

### Docs
- **README** fully rewritten: a two-line title (白い熊 自由作業盤 / ShiroiKuma Jiyūsagyōban), the *Jiyūsagyōban* gloss, the full **Triggers** + **115-action** tables, and fork-vs-upstream feature sections.

## 0.2.68+107 — 2026-06-21

The **通知明滅 notification edge-light** port, a **self-healing always-on engine** with a live **Monitor tab**, new **notification / broadcast / orientation / app-foreground triggers**, the **music edge-light** & a full **edge-gesture** system, **item grouping** across every tab, and reliability fixes for OEM battery management.

### 通知明滅 — notification edge-lights (new project port)
- A notification from a configured app **frames the whole screen in that app's colour** as a permanent edge light; several lit apps share **one frame that cycles** through their colours and titles (~2 s each). Built entirely from tasks + a full-screen tap-through WebView scene, with per-app colours and an `%TSUCHI_*` slot model rebuilt into the cycle list.
- **Three off-paths:** a persistent **“all-off”** control notification (tap → clear every light, keep the apps' own notifications); and **entering an app** (via its notification or the launcher) → that app's light off and its notification dismissed, while the others keep glowing. Ongoing/persistent notifications never light; a notification arriving while you're already in the app doesn't light it; per-app gates (e.g. blink only on missed calls).

### Triggers (new)
- **Notification trigger** — an `EVENT` context (`event=notification`, optional `package` allowlist) fires a profile when a matching app posts a notification.
- **Broadcast (Intent Received) trigger** (`7c7c343`) — fire on any system/app broadcast action, with **typed intent extras** parsed into variables; profiles now **reload live** as you edit them.
- **Device-orientation trigger** (`8060ddc`) — an `EVENT` source for portrait / landscape / reverse changes, exposed as `%DEVICE_ORIENTATION`; orientation is named by the **on-screen** orientation, not the device-natural angle (`4e17243`), fixing foldables.
- **App-to-foreground trigger** (`8060ddc`) — fires when an app comes to the foreground (`%APP_PACKAGE`), fed from the accessibility service so it works where OEM UsageStats is dead (`3bcec99`). Powers the **Previous/Next App** switcher (`bd45920`, `9037117`).

### Notifications
- **`%NOTIF_*` super-globals** (`8bc12c7`) — a posting notification's package, title and body, plus its **ongoing flag** (`%NOTIF_ONGOING`, `4001fb3`), exposed for tasks to read.
- **Per-invocation event vars** (`3a3715e`) — each event now carries its own `%NOTIF_*` snapshot, threaded through the matcher and the **task queue** to the fired task and injected as locals that shadow the shared globals, so a **burst from different apps never mixes up** colours/titles under a QUEUED profile.
- **`notify.show tap_task`** (`c7737de`) — run a task when a notification body is tapped (works while collapsed).
- **Dismiss Notifications** (`notify.dismiss`, `7e3ead6`) — cancel another app's clearable notifications **by package**, via the notification-listener service.
- **`scene.show` expands `%vars`** in element configs at show time (`23f4611`) — so an overlay reflects live globals (the edge-light colour/title).

### Engine & reliability
- **Survives OEM battery management** (`d695587`) — the foreground service holds a partial **wakelock** and a Doze-proof **minute alarm resurrects** it if the process is reaped.
- **Survives coroutine death** (`d181881`) — the engine scope uses a `SupervisorJob` so one failed trigger can't cascade and freeze the rest; a **heartbeat** stamps the per-minute tick and **re-arms** the engine within ~2.5 min if it ever stalls.
- **Auto-run on start** (`d181881`) — pick tasks (e.g. a master “start everything”) to run automatically on every fresh engine start, so overlays/state return after an app update or reboot without manual intervention.

### Monitor tab (new)
- A left-most **Monitor** tab (`fff63ed`) showing engine status / uptime / seconds-since-last-tick, the **overlays actually on screen**, each enabled profile's **real activity** (a trigger firing ≠ its overlay being drawn), and a **history** of every start / re-arm / resurrect — refreshing every second.
- The **“Run on start”** picker (`fff63ed`, `5921666`) — a bordered dialog that groups tasks by project, folds per project, and keeps each project's **manual task order**; every monitor section folds.

### Scenes & edge overlays
- **Music edge-light** (`ec036a2`) — a WebView scene element, a full-screen overlay mode, edge HUDs and custom fonts.
- **Edge-gesture system** — fraction-height edge strips and invisible swipe-only sliders (`291f8f4`); a full edge-bar gesture set with **per-third placement** (`a01678d`); **short/long swipe** + a bottom edge bar, headings honouring fonts (`21959fd`); a **bottom edge bar via an accessibility overlay** that captures the flush gesture-nav area (`ebde11f`); edge-swipe **direction detection** + task-id remap on import (`eb4d91b`).
- **Live sliders, edge-centred panels, tap-outside-close, drag-to-keep-alive** (`45512c4`); a **font picker** in element style (`956db2b`).
- **Battery line charge sweep** now **ping-pongs** (left↔right) instead of snapping back (`d0831ae`).

### Actions
- **Take Screenshot** (`nav.screenshot`, `a2a489d`) — system screenshot via accessibility.
- **Previous App / Next App** (`bd45920`) — switch using the accessibility foreground history.
- **Percent volume / brightness** + **editable dropdown** fields (`446a5da`).
- **Hybrid Back / Recents** (`1380db3`) — accessibility first, Shizuku fallback, with an accessibility-setup row.

### Items, grouping & navigation
- **Grouping on all five tabs** (`c2fcbaa`) with direct **New group / New subgroup**, **nested subgroups** and **foldable per-item notes** (`a5431af`); **drag** rows into groups (`acce065`) or **out** to an *Ungrouped* zone (`e1257d9`); a new group **inherits the item's project** (`326e873`).
- **Project sort toggle, group-delete cascade, collapsed-task quick-run** (`901ee01`); fixed folded-nav covering the screen, **last-tab memory**, and **swipe between tabs** (`e30a42a`); a **Settings link stays** on already-granted permissions (`a68f269`).

### Import / export
- **Overwrite-in-place** (`b0d69cb`) — re-importing a task keeps its id, so profiles and scenes stay linked (no more “Missing task”).
- **Export everything** from the project menu + **timestamped** default filenames (`1340cde`).

## 0.2.68+16 — 2026-06-17

A **battery line** (電池線): a thin bar over the status bar showing charge, built from a new scene element and a full-width overlay mode.

### Scenes
- **Progress-bar element** (`PROGRESS`) — a new scene element type: a horizontal fill bar whose `value` (0–100), `fillColor` and `trackColor` are variable-bound and **re-render live**. A truthy `charging` flag draws a **red sweeping glow** along the fill — advanced by a delay-driven state loop so it animates inside the system-overlay window (where an infinite-transition frame clock doesn't reliably tick) — over a static red tint. It's a first-class element in the editor (palette entry, default size 220 × 12).
- **Full-width overlay** — `scene.show fullWidth=true` shows a non-modal overlay that spans the whole screen width and lays out **over the status bar**, flush to the top edge (`FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`). Scene-card elements sized `widthDp`/`heightDp ≤ 0` fill the card, so a single element can span the entire bar.

### Actions
- **Get Device State** (`state.get`) — charging detection hardened: it now also consults the live `BatteryManager.isCharging`, not only the sticky battery intent's `plugged` / `status`, so charge state is reliable across OEMs.

### 電池線 (battery line)
- These compose into a battery line: a full-width **3 dp** bar at the very top whose length tracks battery %, coloured **amber** normally, **red** ≤ 20 %, **green** at 100 %, with the red charging glow while plugged in — refreshed every minute and updated **instantly** on plug-in/out via a `charging=true` state trigger.

### Docs
- Recorded 白い熊's version-controlled Tasker reference projects directory in `CLAUDE.md` (the porting source for the kanji clock, battery line, etc.).

## 0.2.68+10 — 2026-06-17

Live home-screen widgets and widget UX, on top of the 0.2.68+3 widget/clock system.

### Actions
- **Get Device State** (`state.get`) — read battery % (zero-padded), charging, WiFi-enabled and airplane-mode into variables; no permissions needed. Drives the live status widgets.
- **Toggle Airplane Mode** (`airplane.toggle`) — fixed: the `AIRPLANE_MODE` broadcast is system-only and fails from the Shizuku shell, which previously failed the whole action even though the setting applied. The broadcast is now best-effort, so success tracks the setting write (and dependent widgets update).

### Home-screen widgets & the kanji clock
- **Live status widgets** — WiFi (無線 / 無線無し), Airplane (機内 / 機内無し) and Battery (八割三分 / 全, with a charging line) read real device state every minute; tapping the WiFi or Airplane widget toggles it through Shizuku and the kanji flips instantly.
- **Tap task bound by name** — a widget's tap task is stored by **name** and resolved at tap time, so it survives bundle re-imports (no re-pointing). The widget config now offers a **task picker** dropdown instead of a typed task name.
- **Themed tap feedback** — widget taps give an immediate **vibration** plus a black-and-yellow **Flash** confirmation anchored at the bottom of the screen (a system toast can't be recoloured on a modern targetSdk); failures still surface.
- **Legible preview thumbnails** — template thumbnails render at a canvas scaled to the template's largest font, with `%vars` expanded against the live globals, then scaled down — so big-screen clock templates read as mini widgets instead of a narrow wrapped strip.
- **Wider one-line widgets** — the rendered-bitmap cap was raised 1440 → 2880 px and the one-line time templates set `maxLines = 1`, so a wide one-line widget renders at full width instead of wrapping.

### Theme
- **Serif / Minchō font** — `font: "serif"` (also `明朝` / `mincho`) renders CJK in the built-in serif family (= 明朝 / Minchō), so widgets can use Minchō without importing a font; `"sans"` / `"gothic"` fall back to sans-serif.

### Infrastructure
- Declared the **VIBRATE** permission (widget-tap haptics).

## 0.2.68+3 — 2026-06-16

Rebased onto **OpenTasker 0.2.68** (up from 0.2.60), with a large round of new fork features on top.

### Scenes — a floating-overlay UI builder
- Build interactive overlays from elements and show them with **Show Scene** (`scene.show`) / **Hide Scene** (`scene.hide`) — as a foreground panel or, with “display over other apps”, a **system-wide overlay** that floats over any app and works from background triggers.
- **Element types**: Text, Button, Edit Text, Slider (horizontal & vertical), **Number picker** (− / + stepper), Checkbox, Toggle, Spinner, Image, Rectangle, Oval.
- **Inputs write variables**: sliders, steppers, checkboxes, toggles, spinners and text fields set a `%var` (case-scoped) and run a per-element tap task; shown scenes **re-render live** when a bound variable changes.
- **Styling**: per-element text/label colour, size, bold, alignment, background and border; **panel styling** (background, corner radius, modal scrim, border) with black/yellow theme defaults.
- **`scene.show` options**: position (top/center/bottom), modal vs tap-through HUD, auto-dismiss timeout, dismiss-on-outside — plus **per-scene defaults** so a scene remembers how it likes to show.
- **Editor**: drag-to-move and **drag-to-resize** on a live canvas, a **live styling preview**, element **duplicate** and **z-order** (bring forward / send back), and a project-aware library.

### Home-screen widgets & a template library
- **Set Widget** (`widget.set`) renders a styled bitmap widget from a layout (text / columns / rows, fonts, colours, padding) with a visual **layout editor** — RGBA colour pickers, ± number steppers, per-field sliders, a resizable preview, and **Tasker Widget V2 import**.
- A **Template Library** (new “Widgets” tab) of named layouts referenced by name — edit once, every widget using it updates. File & clipboard import/export; templates also travel inside JSON bundles.
- **Pull / placeholder model**: bind a placed widget to a template, and **Refresh Widgets** (`widget.refresh`) re-renders them all from current variables — no per-widget wiring. Plus a `SET_WIDGET_NAME` broadcast receiver.
- **Fonts**: import `.ttf` / `.otf`, delete from the picker, UTF-8 names preserved.

### Kanji clock (時間と日付)
- A modular, fully app-driven port of the Tasker 勘亭流 kanji clock/date: calc tasks compose spoken-kanji time and date into `%DT_*` variables, two widget templates (clock & date), and a per-minute refresh.
- New **every-minute “clock tick”** EVENT trigger (`event=minute`) to drive it.

### Variables
- **Persistent, project-scoped globals** with case-based scoping: `%ALLCAPS` → app-wide super-global, `%MixedCase` → project-global, `%lowercase` → task-local. Globals now **survive across runs and reboots**. The Vars tab shows super-globals plus the selected project’s globals.

### UI & navigation
- **Uniform top bar** — the project selector now appears on every tab.
- **Name search** on every list tab (Profiles, Tasks, Scenes, Vars, Widgets): a pinned, case-insensitive filter.
- **Multi-select** with batch delete / move-to-project across Profiles, Tasks, Scenes, Vars and Widgets.
- **Help tab** — concepts, variable scoping, the bundle schema and an auto-generated action reference, in collapsible sections.
- **Unified import/export** — one JSON-bundle engine with a per-tab “+” menu (New / Import / Export), a persistent import-result dialog and clearer conflict prompts.
- Foldable cards on every list tab; a horizontally-scrollable bottom navigation.

### Infrastructure
- **Room DB v10**; export **bundle schema v4** (widget templates; per-scene styling & defaults; project-scoped variables).
- CI build workflows disabled (local builds only).

## 0.2.60+35 — 2026-06-15

The action catalogue grew from a few dozen built-ins to **~100**. New built-in actions, grouped:

### Actions — Variables & Arrays (pure logic, no permissions)
- **Variable Clear** (`var.clear`) — unset a variable (and any array of the same name).
- **Variable Split** (`var.split`) — split a value into an array (custom delimiter; empty = per character).
- **Variable Join** (`var.join`) — join an array back into a single value.
- **Variable Search Replace** (`var.replace`) — regex replace; optionally capture matches to an array; ignore-case / multi-line.
- **Variable Convert** (`var.convert`) — upper / lower / trim / length / reverse / capitalize / URL-encode/decode / Base64-encode/decode / MD5 / SHA-1 / SHA-256.
- **Variable Add** (`var.add`) — add a number to a numeric variable, with wrap-around and round.
- **Parse/Format DateTime** (`datetime`) — now / epoch-seconds / epoch-millis / formatted input → a formatted output string.
- **Array Set / Push / Pop / Clear** (`array.set` / `array.push` / `array.pop` / `array.clear`).
- **Array Process** (`array.process`) — sort / sort-desc / numeric / reverse / shuffle / unique / squash.
- **Arrays Merge** (`array.merge`) — concatenate several arrays into one.

### Actions — Dialogs
- **Input Dialog** (`dialog.input`), **List Dialog** (`dialog.list`), **Text Dialog** (`dialog.text`).
- Backed by a transparent host activity + a `CompletableDeferred` result bridge, so the task **suspends until the user answers**; themed black-and-yellow, with an optional close-after timeout, and they cancel cleanly (the task never hangs).

### Actions — Interface gestures (new opt-in `AccessibilityService`)
- **Back** (`nav.back`), **Recents** (`nav.recents`), **Notifications Panel** (`panel.notifications`), **Quick Settings** (`panel.quicksettings`), **Power Dialog** (`nav.power`), **Lock Screen** (`screen.lock`, Android 9+). Global-action only — no screen content is read.

### Actions — Platform
- **Flash** (`flash`) — reworked into a styled overlay window: per-flash text / background / border colours, nine anchor positions with X/Y dp offsets, an **HTML** toggle, and defaults from the UI; falls back to a plain toast without overlay permission.
- **Comment** (`flow.comment`) — a labelled no-op for documenting a task.
- **Set Clipboard** (`clipboard.set`), **Get Clipboard** (`clipboard.get`).
- **Compose Email** (`email.compose`), **Set Wallpaper** (`wallpaper.set`).
- **Open File** (`file.open`) — via a new `FileProvider` over the app sandbox.
- **Move File** (`file.move`), **Create Directory** (`file.mkdir`).
- **List Apps** (`apps.list`), **Keyboard Picker** (`ime.pick`), **Wi-Fi Settings** (`wifi.settings`).
- **Place Call** (`call.place`) — `CALL_PHONE`, else opens the dialer.
- **Profile Status** (`profile.toggle`) — enable / disable / toggle a profile by name.
- **Get Setting** (`setting.get`), **Set Setting** (`setting.put`) — read any of System/Secure/Global; write System via Write-Settings (Secure/Global via Shizuku).
- **Auto Brightness** (`brightness.auto`).
- **Set Volume** (`volume.set`) — per-stream (music / ring / alarm / notification / call / system).

### Actions — Elevated tier (via Shizuku)
- **Run Shell** (`shell.run`) — runs `sh -c <cmd>` with ADB/root privileges; stores `%stdout` / `%stderr` / `%exit`; optional ignore-exit-code.
- Rerouted through Shizuku (now functional, previously stubbed): **Toggle Wi-Fi** (Android 10+), **Toggle Airplane Mode**, **Toggle Mobile Data**, **Take Screenshot** (`screencap`), **Secure/Global Set Setting**.
- New: **Location Mode** (`location.mode`), **Set Keyboard** (`ime.set`).

### Actions — Flagship & flow
- **Send Intent** (`intent.send`) — the reason the fork exists: fire an arbitrary intent (action, category, MIME, data, three `key:value` extras, target package/class, broadcast / activity / service).
- **Named task parameters** — Run Task passes `param:<name>`; sub-task reads `{{ param.name }}` / `%@name`.
- **Return Values** (`task.return`) — return named values to the caller (`%prefix_name`, `%prefix_ok`, `%prefix_error`).
- **Fail** (`flow.fail`) — signal a task error.

### Features
- **Projects** — Tasker-style grouping of profiles/tasks/scenes: data model, DB migration, top-bar switcher, management UI, move-between-projects, per-project filtering, Unfiled catch-all.
- **Per-tab sorting** — Alphabetical or Manual per tab (Profiles / Tasks / Scenes), with long-press **drag-and-drop** reorder; persisted and round-tripped through export/import.
- **白い熊 自由作業盤 UI customization page** — colours (background / text / accent / surface / border), border width, font (incl. imported `.ttf`/`.otf`) and text scale, live; plus a configurable **Flash / toast** section (colours, border width, corner radius, text size, weight) with a live preview.
- **JSON export/import** — versioned bundles (**schema v3**): export all / a project / hand-picked items; per-item, per-project and selective export; conflict prompt (overwrite vs keep-both) and unique-name handling on import; preserves each item's manual **position** and the tab's **sort method**.
- **Editor & workflow** — advanced full-screen, searchable, category-folded **action picker**; **foldable task list**; **full-screen action editor**; **RGBA colour-picker** fields (4 sliders + preview); **drag-to-reorder actions**; task-name picker for Run Task; continue-on-error toggle.

### Changed
- Renamed all user-facing **“OpenTasker” → “白い熊 自由作業盤”** (top bar, export/import dialogs, the home-screen widget, permission prompts, OEM battery-guidance, notifications/channels, NFC/file/backup messages, Shizuku/Termux setup text). Code identifiers, logcat tags, notification-channel IDs and the upstream URL were left intact.
- **Black-and-yellow** as the default theme; pure `#FFFF00` throughout; a yellow border on every dialog; fixed the Material3 1.4 button-label colour so labels render full-strength.
- Capability badges updated: the elevated actions moved from **Unsupported → Requires setup (Shizuku)**.

### Infrastructure
- `applicationId` `shiroikuma.jiyusagyoban`; black-yellow launcher icon; signed release; **side-by-side** install with upstream.
- Added: an `AccessibilityService`, a `FileProvider`, and **Shizuku** (`dev.rikka.shizuku:api` + `:provider`).
- **Room DB v6** — `position` columns on profiles/tasks/scenes; **export bundle schema v3**.

---

# Changelog

## v0.2.86

Audit-driven release: a deep multi-pass audit filed 60 findings, and this ships all of them plus a critical defect the audit itself missed.

### Fixed — critical

- **Tasks could not run on any real device.** `TaskRunner` compiled a regex with unescaped closing braces in a class initializer. Desktop `java.util.regex` accepts that; Android's ICU engine rejects it, so the initializer threw on first use and every task execution failed — not only ones using array references. Present since 2026-08-10 and invisible to 1255 green JVM tests. Found by the new end-to-end instrumented test.
- **Deleting a project destroyed every secret variable it held.** Variables were moved by copying the stored row to the new project, but a secret's envelope authenticates its project id, so each relocated secret failed verification and decoded as empty and unavailable with no way back. Secrets are now decrypted and re-encrypted under the destination, and a secret that cannot be decrypted aborts the move rather than being relocated as dead ciphertext.
- **The database could freeze permanently.** The variable mutation lock and Room's write transaction were acquired in both orders — bundle import, variable rename and delete took the transaction first, the engine's commit path took the lock first — so the two could interleave and hold the single write connection until the process died. Lock order is now always mutation lock, then transaction.
- **Automations fired on unrelated events.** A pulse advanced whenever any event reached an EVENT context, whether or not the context was watching for it, so a profile whose expression was already true for another reason activated on unrelated traffic: `EVENT(nfc) OR STATE(wifi=Home)` ran on every notification while on that network, and two OR'd EVENT leaves turned one physical event into two runs.
- **NFC taps stacked a new copy of the app.** Manifest NFC dispatch starts a new activity unless the target is `singleTop`, so `onNewIntent` never ran and the armed tag-write editor stayed buried while its result went to an instance the user could no longer see.

### Fixed — data and execution

- A scheduled download to a fixed path replaced the good file with the 404 or 503 error body before the status was checked.
- `clipboard.get` turned Android's background-read denial into an empty string and reported success, silently feeding blank data to whatever came next.
- `sound.play` streamed arbitrary remote URLs, bypassing the private-network and cleartext policy the HTTP action enforces in code.
- `app.archive`/`app.unarchive` advertised themselves as supported but could never succeed: Android's confirmation response was treated as a terminal failure.
- The `unlocked` device state latched true after the first unlock for the rest of the service's life.
- Calendar automations ran once a minute for the whole event; a 60-minute meeting ran its task about 60 times.
- `data.read` with `format=xml` failed on every device — the same parser-parity defect as the Tasker XML import bug, in the one parser that never got the fix.
- Tasker XML export then re-import dropped every variable.
- Widget and shortcut runs ignored the concurrency limits and circuit breakers the in-app Run button respects, and a second tap started a concurrent run.
- Wake-on-LAN could target any public address; `ping` demanded local-network permission even for public hosts; a broker could make the MQTT client allocate up to 256 MB.
- A persisted cooldown was deleted when a profile was merely disabled.

### Fixed — interface

- A refused save no longer discards the whole form, and now says why: automation lint, duplicate names, and reference guards state their reason instead of "Operation failed".
- Cold start no longer blocks the main thread waiting for the database — the launch after staging a restore could freeze for up to 30 seconds.
- The Run Log keeps its entries while reloading, reports a failed load as a failure rather than "no runs match", and debounces search.
- Task widgets refresh after a rename or delete instead of showing a stale label and a run that cannot succeed.
- A widget task no longer leaves an invisible overlay covering the launcher for the duration of the run.
- Large editors ignore a stray tap on the scrim, the context-logic editor survives background writes, and the elapsed counter on a running task advances.
- Status bar icons are visible in the widget, quick-settings and Locale editors in light theme; light mode no longer flashes black on launch; warning text meets contrast; notifications use the app's own icon and accent.
- AMOLED card and section borders are visible again, disabled action-picker entries look disabled, and search announces its result to screen readers.
- Duplicated automations are named "Name (copy)" rather than "Name(copy)"; export and retention messages are proper plurals; and one term is used per concept throughout.

### Changed

- Verification: the Room schema gate now detects real drift rather than checking that files exist, the decoder fuzz harness can surface unexpected exceptions instead of swallowing them, the baseline profile must be recaptured for the release it ships in, capability counts are checked against the compiled runtime, and the JVM test floor no longer counts skipped tests.
- Performance: one shared Locale-plugin poll replaces one per context (previously N x N broadcasts per interval), and media polling only runs for contexts that read media state.

## v0.2.85

- Fixed the release build, which had failed since the staged module split: the core/* modules compile sources that still live under `app/`, so every shared class was compiled twice and R8 rejected the duplicate types. `:app` holds the only copy that ships and the module jars are no longer merged into the APK.
- The action editor now says where to satisfy a setup-gated action, not just what is missing — elevated device actions point at the Setup tab and Shizuku rather than leaving the manifest looking like the answer.
- Added an **AMOLED black** theme that uses true `#000000` surfaces, so an OLED panel can actually switch pixels off — the existing dark scheme is `#101211` and is unchanged, now named for what it is. Body text keeps 18.6:1 and secondary text 9.3:1 on black.
- Added an opt-in **Material You** theme on Android 12+, which follows the system light/dark setting and is not offered on older releases where it would do nothing.
- Profile and task lists reserve room for the floating action button, so the last row is no longer permanently covered, and empty states scroll — at large font scale their last action could previously sit off-screen with no way to reach it.
- Undo and Redo on profile, task, and scene cards are now disabled when there is nothing to undo or redo, and announce why to a screen reader, instead of being permanently live and answering with a snackbar. A setup item with no available action shows status text rather than a button that only reported the item was already ready.
- Profiles, Tasks, and Scenes now show a loading state until Room delivers its first snapshot, instead of flashing the first-run "Build your first automation" screen at every cold start for users who already have data.
- Profile execution slots now decide and store under one lock for every automation mode, not only QUEUED, so a second dispatch path reaching the same slot cannot start a SINGLE profile twice or leave a superseded RESTART job running untracked. The queue consumer takes the same lock order, and the invariant is covered by concurrency tests.
- The exported automation broadcast target now answers status and name-lookup requests with bounded `COUNT(*)` and indexed name queries instead of loading the whole profile and task tables inside the `goAsync()` window, and reuses one supervisor scope rather than creating one per broadcast. Name matching moved into SQLite's `COLLATE NOCASE`, which folds ASCII only — two names differing solely in the case of a non-ASCII letter are now distinct.
- Added an opt-in, coverage-guided JVM fuzz task for bundle, Tasker XML, template-expression, and structured-data decoders, with a checked-in seed and regression corpus kept out of release dependency graphs.
- Added headless Compose screenshot regression coverage for primary screens and shared states across system, light, dark, and high-contrast themes, 1×/2× font scales, and an RTL pseudolocale; reference validation is part of the local quality gate.
- Automation lint now reports shadowed, unreachable, and action/revert rules, and supports bounded device-state invariants with localized diagnostics, a reusable predicate editor, and optional bundle import/export.
- Added an opt-in, non-F-Droid release update check that reports newer GitHub releases without downloading or installing them.
- Added a disabled-by-default, API-36-only AppFunctions prototype that can submit only user-approved task IDs through the existing signature-protected execution boundary.
- Follow-up audit covered scene editing/runtime, legacy context producers, search/grouping/share dialogs, and widget/quick-settings configuration; no new defect was found, with large-font and inert-history-control follow-ups remaining tracked separately.
-Home Assistant Companion `message`/`data` notification envelopes are now first-class webhook inputs, and ntfy's documented push field names are accepted by the token-authenticated push bridge so a `broadcast` action can trigger OpenTasker without a relay app.
-Release truth now records the annotated release tag and sync target; the verification gate checks every versioned changelog release from v0.2.58 onward, rejects lightweight or mismatched tags, and requires the current release to be tagged before publishing its manifest.
-Scene canvas elements now announce their localized type, label, position, size, and selection state; screen-reader users can select, nudge, and resize them through custom actions, while the resize handle keeps a labeled 48dp target and the visible canvas text includes the element type.
- Action and context pickers now have clearable search across localized names, descriptions, and stable IDs; empty queries show a clear no-match state, and localized catalogs are rebuilt only when the configuration changes.
- Back navigation now returns from secondary destinations to Profiles and exits only from the start screen; app-wide settings have a dedicated primary destination separate from the permission checklist, with navigation state retained across recreation.
- Dismissing the first-run starter-template picker now completes onboarding, including Back/scrim dismissal and cancelling the template details step; the template browser remains available from the workspace.
- User-facing automation labels, setup requirements, capability levels, share trust, diagnostics levels, and scene element types now resolve through localized resources; unknown action IDs and operation failures use safe generic copy while raw details remain in diagnostics logs.
- Compose instrumentation now runs the Accessibility Test Framework across the primary UI flows, with regression fixtures for unlabeled controls and undersized touch targets.
- Locale verification now examines every existing locale directory, rejects empty directories by name, reports how many it examined, and documents that the current release ships English only.
- Shizuku now binds a versioned AIDL user service for the six allowlisted elevated actions, rechecks exact argv in the privileged process, keeps the persisted kill switch and fail-closed behavior, and unbinds the service during application teardown.
- UnifiedPush registration now uses the official connector service for distributor discovery, SDK-versioned identity registration, RFC 8291 decryption, endpoint persistence, failure status, and delivery acknowledgement; ntfy's standard JSON reaches `event=push`, while the bounded/redacted legacy broadcast remains compatible.
- Setup permission, preference, companion, push-token, and grant snapshots now load through an IO-backed ViewModel, so returning from system settings refreshes without blocking composition.
- Profile, task, and scene deletion now snapshots the complete entity for snackbar Undo/Redo, while action, context, and scene-element removal is immediate and undoable without a confirmation dialog.
- Exported Locale condition queries now require revocable grants bound to the selected profile, context, or variable; unauthorized queries return unknown before database access, and the receiver reuses a bounded scope.
- MQTT TLS publishes now pin the vetted TCP address while preserving the broker hostname for HTTPS endpoint verification and SNI; a mismatched certificate is rejected before credentials or payloads are sent.
- Success feedback now remains resource-backed from the call site through snackbar collection, so profile toggles and editor removals cannot fall through an English literal map and appear as errors; variable and scene messages retain their arguments until the active locale resolves them.
- Split model, common logging, storage, engine state, and automation blueprint input presentation into dependency-directed `core/*` and `feature/*` modules, with source-boundary contracts and an interim screen-size ceiling enforced by tests.
- Converted starter templates into versioned, serializable automation blueprints with typed selectors, collapsible input sections, bounded bundle validation, local installation tracking, and review-only update diffs that never overwrite instantiated profiles.
- New lint findings, lifecycle suppression reasons, admission decisions, duplicate names, semantic-diff labels and enum values, and run-status words now resolve through resource-backed presentation adapters, so localized screens no longer inherit English-only copy from the core engine.
- The WorkManager 2.12 metrics evaluation records the beta-only API's incremental stop-count, runtime, and retry evidence, its experimental dependency cost, and the stable-release trigger; OpenTasker remains on 2.11.2 until that trigger is met.
- The pinned build tuple is refreshed to Gradle 9.7.0, KSP 2.3.11, and Compose BOM 2026.06.01, with wrapper, checksum, release-truth, and documentation contracts updated together.
- The repository now carries the English F-Droid store listing, four current phone screenshots, and a versioned changelog; the release gate verifies the listing and screenshot capture version.
- The JSON bundle format document describes schema v2 rather than v1, including what importing a v1 bundle does and which versions are accepted. It had claimed v1 since the v2 migration shipped.
- The bundle's supported import range is published in `tools/release-truth.json` and checked against the codec and the format document, so narrowing or widening what OpenTasker will import can no longer happen without updating the published contract.
- Two checked-in bundle fixtures pin the compatibility contract: a v1 document and exactly the v2 document the codec produces from it.

## v0.2.84

- Upgrading a database created before OpenTasker encrypted its storage no longer discards it. The conversion copied the new empty file onto itself instead of reading the old one, leaving a database with no tables; the app then refused to open it. The migration now verifies it carried the tables across before publishing the result.
- Exported backups restore on a different device and after a reinstall. Both export paths wrote the on-disk ciphertext, which is keyed to a randomly generated key that is destroyed with the app's data and never transferred, so every exported `.otbackup` was unopenable in exactly the situations backups exist for. Exports now carry a portable copy inside the passphrase-encrypted envelope, and the staging copy is shredded afterwards. First-class secret values still stay on the device that created them.
- Startup recovery no longer marks a just-started execution as interrupted. Recovery runs alongside engine startup, so a boot-triggered run that reached the journal first was reported as interrupted, written to the run log twice, and then blocked from recording its real result.
- Applying a staged restore and converting a legacy database now happen off the main thread, so a large database cannot stall app start or exhaust the boot broadcast's time budget.
- The exported Locale condition receiver answers a variable comparison only for a variable the user explicitly exposed. It is exported without a permission by contract, and its bundle is supplied by the caller, so any app could previously name any non-secret variable and read its contents a comparison at a time from the result code. Choosing a variable in the condition editor now mints a read grant bound to that variable. Conditions configured before this change report unknown until the variable is re-selected.
- Simulating a trigger from the profile editor now simulates what is on screen and leaves the editor open behind it. It previously closed the editor, discarded the pending edits, and reported the values from the last save.
- Testing a synthetic event from the context editor keeps the editor open, so a fully configured context is no longer lost the moment its simulation is dismissed.
- A simulation dialog survives rotation instead of vanishing.
- Nodes changed by an undo or redo stay highlighted on the Flow tab after the review dialog closes. The highlight was tied to the dialog that covered Flow, so the dialog's own "highlighted in Flow" note pointed at something no one could ever see.
- `verifyDocumentationTruth` now checks the CHANGELOG and release-truth files it already declared as inputs, so a release with no matching changelog section fails instead of passing silently.
- The F-Droid release verifier's tag check accepts the repository's documented two-commit release flow, so it no longer has to be skipped on every release. An always-true assertion in the F-Droid readiness check is gone.
- Changed nodes on the flow canvas carry the same "Changed" pill as the list, and a screen reader now hears that a node changed along with the variables it produces. The canvas previously marked a change with border colour alone.
- The home-screen widget follows the app's palette and the light/dark setting instead of shipping the retired purple-on-navy colours in permanent dark.
- One name for the simulation feature, "Profile lifetime" instead of "Automation lifetime" beside the other profile settings, and real plurals in place of "(s)" in the diff, lint, and admission summaries.
- The locale completeness gate no longer counts non-locale resource folders such as values-night as untranslated languages.
- Tasker XML export is reachable: the workspace card has an Export Tasker XML button. The exporter shipped with no caller at all, so the changelog announced a feature nobody could run and its redaction path never ran outside tests.
- A JSON export now redacts an action argument that contains a literal copy of a secret variable's value, matching what the Tasker XML exporter already did. `docs/OPEN_JSON_BUNDLE.md` describes what export redaction does and does not cover.
- Secret variables are encrypted with the project bound into the authenticated data, so an envelope can no longer be moved between two same-named secrets in different projects and still decrypt. Existing secrets keep working and are rewritten in the new format the next time they are saved.
- Opening the trigger simulation no longer reads preferences on the UI thread, and long diff or import lists render only the rows on screen.
- Execution journal progress writes are rate-bounded instead of one database update per action, which a long flow could repeat tens of thousands of times in a single run.
- The import boundary corpus covers the DOCTYPE internal-subset scanner, the share-intent payload boundary, and the secret-variable refusal in the Locale condition receiver.
- Setup can turn on automatic local snapshots: a scheduled local database snapshot with a bounded count and age, a status line showing the last result and disk use, and a retention choice. Snapshots never leave the device and are never applied automatically - restoring one still goes through the existing review.
- Backups no longer leave orphaned `-wal`/`-shm` files beside them, and deleting or pruning a backup takes its sidecars with it.
- Two tasks in Wait mode that run each other no longer deadlock the engine. Each held a per-task lock for its whole run and acquired them in opposite orders, and because a sub-task returns before the per-action timeout nothing bounded the wait; the stuck runs then held admission leases until the engine's global cap wedged.

## v0.2.83

- The pre-unlock time trigger setting is now a single toggleable row, so a screen reader announces its name along with its state instead of an unnamed on/off switch.
- Trigger lint findings name their severity in text, and the profile editor's option groups expose radio-button selection state, so neither depends on colour alone.
- The trigger simulation dialog's cooldown and admission copy comes from string resources, with a proper plural for the countdown.
- The localization gate scans every file in the screens package instead of a hand-written list, which had let new dialogs ship uncovered.
- Run now and Replay ignore repeat taps while a run is in flight.
- Cleartext MQTT now requires every address a host resolves to be private, and connects to the address it vetted. Accepting a host because any one record was private, then letting the socket re-resolve the name, could send the connect packet and its credentials to a public address.
- `url.open` rejects a URI with no scheme instead of passing it through the scheme allowlist unchecked.
- Pasting something that is not an OpenTasker bundle now explains that in plain language instead of showing the JSON parser's own message and echoing the pasted text back.
- Out-of-range profile priority and grace period now show an error naming the valid range. Both fields accepted more digits than they allow, so a value like 500 quietly disabled Save with nothing on screen explaining why.
- An expired profile reports the date it expired rather than a raw millisecond timestamp.
- A kept run-log entry is labelled "Kept" instead of repeating the "Unkeep" button's own label.
- Diagnostics shows profile names instead of raw database ids.
- Dialogs and dropdown menus now use the app's own surfaces in all three themes. The surfaceContainer roles were never defined, so Material's purple-tinted baseline showed through on every editor dialog - lavender against the light theme's cream, and purple-grey against the dark theme's graphite.
- `flow.try` retries now require every action in the try body to be retry-safe, not just the one that failed. A retry restarts the whole body, so a body such as send-message then fetch-URL re-sent the message on each attempt.
- `%FLOW_ERROR_CAUGHT` is now `true` inside a `flow.catch` handler; it was always `false` because the catch marker that records it was skipped.
- Text such as `%count-1` expands the `%count` variable again and keeps the rest. Allowing hyphens in variable names had made the whole token scan as one undefined name and collapse to an empty string; a variable that really is named with a hyphen still wins.
- Re-delivering an external command whose id has aged out of the dedupe ledger is acknowledged as a duplicate delivery instead of reported as a failed execution.
- A global fallback task that no longer exists is cleared on load rather than left dangling.
- Held run-log entries and the execution journal are now bounded. Held rows were skipped by retention so a pending replay survived, but nothing else ever removed them - one entry per admission rejection, each up to 16 KB - and the journal was only trimmed at process start, so it grew for as long as the foreground service stayed up.
- Replaying a held entry consumes it, so the Replay action can no longer run the same held execution repeatedly.
- Manual runs and held replays are now admitted by the engine's live admission controller instead of a separate in-memory one, so they respect a saturated profile and an open circuit breaker. A failure during a manual run now reports an error instead of crashing.
- The F-Droid distribution builds unsigned again, restoring the `app-release-unsigned.apk` artifact its build recipe and the reproducibility harness both name; adding the repo-owned signing fallback had silently renamed it to `app-release.apk`. The metadata gate now derives the expected artifact path from the signing decision and refuses to run against a non-F-Droid distribution instead of reporting a pass.
- Device orientation now follows the Android sensor convention: a normally held phone reports `portrait` rather than `portrait_upside_down`, and the landscape sub-values are no longer swapped.
- Tethering state now reports the union of Wi-Fi hotspot and interface tethering and publishes an initial value on registration. Any delivery of the legacy tethering broadcast previously latched the state to on, so it stayed on after tethering stopped, and a `tethering=false` predicate could never match.
- A state context whose predicate cannot be parsed no longer starts GPS, telephony receivers, and every sensor for a context that can never match.
- Step cadence is published in buckets, so walking no longer re-evaluates every profile on each step.
- Roaming and phone-call state presets are hidden on distributions that do not declare the Phone permission, where their Setup row cannot exist.
- The new roaming, call-state, and tethering receivers register as not-exported, matching every other dynamic receiver in the app.
- Project rows in Manage projects no longer break the project name across two lines; rename is now an icon button matching the reorder and delete controls, leaving the name room to render in full.
- Duplicating a profile now keeps its review requirement. An imported profile awaiting risk review could previously be duplicated into a copy that enabled without ever showing the risk disclosure.
- Fresh installs now start with the Default project. It was only ever created by the schema 8 to 9 migration, so a new install had no project row and the first project the user created silently took the reserved default id, becoming an undeletable workspace that owned every existing task and profile.
- Missed-trigger detection no longer reports healthy minute ticks as missed. The ledger's persisted state is now the single state of record shared by the service scheduler, the tick receiver, and the watchdog, and a tick is only counted as dropped once the following tick is also overdue.
- Profiles now only yield to a *strictly* higher-priority profile. Equal-priority profiles run concurrently, so a long-lived matched profile no longer suppresses every other matching profile through a profile-ID tie-break; when a higher-priority profile deactivates, every profile it was outranking is released.
- Causal loop protection now ends when the parent execution finishes and no longer treats a profile's own exit task as a loop back into itself, so exit tasks and ordinary re-triggers within 30 seconds are no longer skipped as "Causal profile cycle stopped".
- The `state.temporary` action now captures the current setting before applying a reversible change; it previously read the target action id out of the target's own arguments and failed every invocation with "current state is unavailable".
- The local release gate now reports observed JVM tests separately from the configured release floor, and the README uses explicit threshold-versus-observed wording instead of duplicating a suite count.
- Admitted executions now persist a bounded journal with source/lineage and last known step, reconcile process-death interruptions into one visible Interrupted run-log outcome, and never retry interrupted work automatically.
- Import boundaries now share a deterministic hostile-input corpus covering OpenTasker JSON, Tasker XML, external intents, Locale bundles, and Termux payloads; variable and condition contracts fail closed with bounded diagnostics.
- Exported diagnostics, run logs, OpenTasker bundles, and Tasker XML now share field-aware redaction for action metadata, secret-derived templates, credentials, URLs, and query values, with explicit secret re-entry warnings.
- State contexts now cover orientation, proximity, physical activity, speed, roaming, tethering, and phone-call state through demand-gated platform sensors and callbacks; Setup exposes Activity Recognition, location, and Phone access only when needed, and unsupported or ungranted sources fail closed with Inspector guidance.
- OpenTasker now exposes a Locale/Tasker condition-plugin target for profile-active, context-satisfied, and non-secret variable comparisons; edit/query bundles are typed and bounded, live matcher state returns satisfied/unsatisfied/unknown, and malformed or secret-bearing inputs fail closed.
- Debug builds now detect Android 17 implicit URI grants; configurable URI dispatch rejects missing explicit read/write grants, and the Sharesheet receiver checks content-URI readability off the main thread with visible failure feedback.
- Built-in actions now use one typed declaration catalogue for runtime factories, categories, retry safety, capability resolution, editor metadata binding, and release-truth counts; adding an action without a canonical declaration is rejected by the action base contract.
- The build toolchain now uses Gradle 9.6.1 and AGP 9.3.1; Kotlin 2.4.10 remains on the stable line with local-cache-only guidance for its build-cache advisory, and the release graph rejects Netty/grpc-netty components.
- jsoup is upgraded to 1.23.1, the patched release for [GHSA-pmhh-3w7g-xqp8](https://github.com/advisories/GHSA-pmhh-3w7g-xqp8); the release SBOM now reports no unapproved OSV advisories.
- Undo/redo and OpenTasker bundle import review now show typed semantic diffs for profiles, contexts, tasks, actions, scenes, and variables with sensitive values masked; changed Flow nodes are highlighted during review.
- Profiles, tasks, and scenes can now be duplicated from their overflow menus with collision-safe names, fresh nested IDs, disabled profile copies, remapped self-bindings, and undo/redo support.
- Add an opt-in Direct Boot path for the app-owned minute time trigger: a device-protected DataStore arms one bounded pending pulse before first unlock, while profiles, tasks, Room, secrets, and all other trigger families remain post-unlock only; Setup now discloses the exact scope.
- Diagnostics now reports API 36 pending-job reason history and API 37 aggregate pending durations where available, labels expanded WorkManager stop reasons including abandoned timeouts, and explains standby buckets as delivery consequences with explicit unavailable states on older Android versions.
- Active tasks now request Android 16+ promoted ongoing notifications with short status text when eligible; the standard foreground notification remains the fallback when promotion is unavailable, denied, or unsupported.
- Action rows, flow nodes, and preflight cards now render localized one-line parameter summaries through a shared redaction-aware formatter; every built-in action is covered by a summary completeness guard.
- Action metadata now declares typed outputs; action-editor text fields offer compatible outputs from earlier steps, event data, and globals as variable chips that persist as ordinary `{{ }}` templates, with array references preserved for loop/join inputs and flow nodes showing produced names.
- Unhandled task failures can now run a per-profile or global fallback task with bounded structured error variables, terminal diagnostics, and non-recursive recovery; retry state is cleared when an action eventually succeeds.
- Profiles can now override active and burst execution admission limits within bounded ranges, choose logged/held or silent overflow behavior, and surface structured rejection counts plus circuit-breaker trip state in Diagnostics and shared reports.
- Profiles now support deterministic priority arbitration, symmetric activation/deactivation grace periods, and never/date/once lifetimes with expiry and one-shot persistence; the editor, profile cards, Inspector, bundle codec, and Tasker mapping expose the policy and explain suppressions.
- Undo/redo now validates every Task, Profile, and Scene snapshot before the Room write; malformed or wrong-entity history fails closed and leaves the current record untouched.
- Release truth now derives and verifies SDK/toolchain, capability, bundle, Room schema, README, and F-Droid artifact claims from shipped sources; the generator also derives engine-handled actions from FlowControl and subtask execution.
- `flow.try` now classifies all built-in actions for retry safety, previews retryable and non-retryable actions in the editor, and records skipped-retry reasons in the run log.
- Dependency verification now requires signatures, explicit trusted keys, and independently checked upstream provenance for every checksum; Gradle-generated origins and blanket trust are rejected by the local release gate.
- Profile-triggered executions now carry causal parent/depth metadata; repeated profile chains stop with named CAUSAL_LOOP terminal reasons in Run Log, Diagnostics, and the Context Inspector.
- Diagnostics now pairs stale engine heartbeats with Android 11+ historical process-exit reasons, timestamps, and downtime gaps, with an explicit unsupported-platform state below API 30.
- Scheduled triggers now persist expected fire times; the watchdog records overdue delivery once with delay, standby bucket, and battery/exact-alarm remediation in the Run Log.
- Admission-rejected executions are now retained as HELD rows with bounded redacted trigger data and the rejecting policy; Run Log offers linked manual replay, and held or user-starred rows are exempt from retention pruning.
- Profile editors and the Context Inspector can now simulate triggers with pinned synthetic events for every context family, showing predicate, expression, cooldown, and admission decisions without running a task or writing production run-log state.
- Automation lint now reports missing reversals, repeated state triggers, conflicting setting writers, and inter-profile loops during saves, imports, flow inspection, and Context Inspector review; equal-priority writer conflicts block while other findings remain actionable warnings.
- Variable renames now rewrite legacy and template references across action arguments, conditions, profile context bindings, and scenes atomically; referenced variables cannot be deleted without showing their dependent sites.
- The local quality gate now emits a JaCoCo debug report, enforces per-area instruction floors for scheduling, resilience, receivers, and UI utilities, and ratchets the JVM test floor to 1,049 passing tests; the newly covered areas include exact-alarm decisions, graceful degradation, time-event routing, notification message formatting, side-effect-free trigger simulation, automation lint, and profile lifecycle policy.
- Locale packaging now uses AGP's generated per-app language configuration with an explicit `en-US` default; incomplete locale placeholders are removed, and the release gate rejects alternate locales below the documented 80% translated-string threshold.
- Record the current evaluation decisions to wait on Glance and Navigation3 stability, and to keep unrestricted accessibility automation out of the product.

## v0.2.82

- Declare `ACCESS_NOTIFICATION_POLICY` so OpenTasker appears on the Do Not Disturb / Modes access settings page and DND access can actually be granted; `dnd.set`, `zen.rule.set`, and `zen.rule.clear` were dead on every device without it. Adds a manifest contract test. (#4)
- Fix Tasker XML import failing on-device with "disallow doctype decl": Android's Expat-backed parsers reject the Apache secure-parsing feature URI the importer treated as mandatory, so every import failed regardless of file content. The feature is now best-effort, benign DOCTYPE prologs in real Tasker exports are stripped in text before parsing, and doctypes with entities or external DTD references are still rejected (XXE-safe). (#5)
- Cover the importer with instrumentation tests that run on Android's own XML parser. The JVM
  suite cannot see this class of defect at all — desktop Xerces accepts the feature URI Android
  rejects — so #5 shipped with every unit test green. The new tests were checked against the
  pre-fix importer: four of six fail there, including the plain no-doctype import, with the same
  message users saw.
- Sign published APKs with a signing key kept in the repository instead of the machine-global
  Android debug keystore. That file is regenerated by the SDK tooling, and the key that signed
  v0.2.79 was lost that way. **Upgrading in place over v0.2.79 or earlier will fail with a
  signature mismatch — uninstall the old build first.** This is a one-time break; the new key is
  checked in, so future releases upgrade normally.

## v0.2.81

- Add an explicit Android predictive-back callback bridge with a legacy-compatible root-screen fallback and lifecycle-safe teardown.
- Add adaptive navigation regression coverage for compact/medium/expanded widths, large-font scaling, resize/fold state restoration, and accessible navigation semantics.

## v0.2.80

- Add a build-gated `event=sms_received` SMS/MMS trigger with sanitized sender/body metadata, sender/body filters, Android 17 OTP-delay disclosure, and Play-artifact exclusion.
- Add Android 16 Advanced Protection live detection, diagnostics/setup banners, and `event=advanced_protection` enabled/disabled transitions with reflection-safe callback teardown.

- Add Android 16 Bluetooth `bluetooth_key_missing` and `bluetooth_encryption_change` event contexts with device/security metadata, editor presets, permission setup copy, and low-SDK no-op gating.

- Add owned Android 15+ Zen rule set/clear actions with grayscale, dim-wallpaper, and night-mode effects, plus transient DND fallback on older Android versions.

- **App archive actions**: added SDK-gated `app.archive` and `app.unarchive` actions with bounded PackageInstaller status handling, package validation, explicit self-archive refusal, and fail-closed permission/installer errors.

- **Screen-recording trigger**: added Android 15/API 35-gated visibility callbacks, visible/not-visible editor presets, and setup guidance for `event=screen_recording` without capturing screen contents.

- **Bluetooth aggregate trigger**: added a tracked final-disconnect transition as `event=bluetooth_all_disconnected`, an editor preset, and multi-device sequence tests.

- **Offline bundle import**: added bounded clipboard/QR-text JSON import that reuses the existing disabled-by-default review before any database write, with malformed and oversized input rejection.

- **Companion presence trigger**: added user-confirmed CompanionDeviceManager association and revocation setup, OS-managed presence callbacks, and `event=companion_presence` present/absent matching without a scan loop.

- **Matcher pulse continuity**: profile matcher rebuilds now carry event-pulse sequence state across edits and suppress replayed push/share/boot deliveries per context slot, preventing reconcile-time drops and duplicate runs.

- **Room migrations and writes**: additive schema transitions now use Room 2.8 auto-migrations backed by the exported schema history, while semantic rewrites remain explicit; profile and variable writes use `@Upsert` to remove insert/update races.

- **Performance evidence**: added a validated baseline-profile artifact and a separate Macrobenchmark module covering cold start and first navigation, with explicit API 35+ device-run commands and no hosted-CI requirement.

- **Documentation truth**: the local quality gate now checks current README release claims and reports stale version, schema, and capability claims in historical research snapshots without treating those snapshots as current product documentation.

- **USB trigger**: tasks can now react to filtered USB/input-device attach and detach events with sanitized device identity metadata.

- **Shortcut publishing**: tasks can publish dynamic launcher shortcuts or request pinned shortcuts with bounded labels, stable IDs, and explicit task bindings.

- **Progress notifications**: added an Android 16 `Notification.ProgressStyle` action with ordered segments and a standard progress-bar fallback on older releases.

- **Adaptive shell**: medium and expanded windows now use a scrollable navigation rail with every destination visible, while compact windows keep bottom navigation; the layout switches from live window width so rotation and resize preserve the selected screen.

- **Scene authoring safety**: image drafts no longer save placeholder sources; picker-selected images must decode before save, invalid imports are rejected, accessibility descriptions/decorative state flow into overlays, and shared slider/image config validation is enforced by editor, import, diagnostics, and runtime.

- **Failure recovery**: added structured Try/Catch/End Try flow blocks with bounded exponential retry for explicitly idempotent actions, redacted `FLOW_ERROR_*` handler variables, and honest validation of retry bounds and block nesting.

- **Monitor and editor coverage**: callback monitor registration now has explicit retryable lifecycle semantics, AppUsage permission loss is covered as a pause/resume policy, shake debounce and exact-alarm fallback are locked down, and Compose task-editor drafts are tested across saved-instance restoration.

- **Build toolchain**: synchronized Kotlin 2.4.10, KSP 2.3.10, Compose BOM 2026.06.00, Lifecycle 2.11.0, Coroutines 1.11.0, and stable immutable collections 0.5.1. Gradle remains pinned at 9.4.1, with refreshed SHA-256 verification metadata for the complete resolved graph.

- **Foreground precision**: application contexts can now constrain a package to an exact or bounded glob Activity component. UsageStats class names flow through the Context Inspector, while missing OEM component data is shown as unavailable and never degrades a configured component match to package-only.

- **Database privacy**: OpenTasker now encrypts the complete Room database with the F-Droid-compatible SQLCipher Community Edition. A random database key is wrapped by Android Keystore, existing plaintext files migrate atomically before Room opens, managed backup validation understands encrypted files, and incorrect keys fail closed.

- **Native release safety**: the local release gate now inspects every packaged ELF library and fails if any PT_LOAD segment is below 16 KB alignment. The current SQLCipher, DataStore, and Compose native payloads pass the audit.

- **Predictive back**: audited the target-37 activity and Compose surfaces. The manifest is opted into `OnBackInvokedCallback`, `MainActivity` has no legacy `onBackPressed()` override, and dialogs consistently use Compose `onDismissRequest`, preserving both gesture and 3-button dismissal paths through AndroidX.

- **Media context**: added a level-triggered `media_active` state with optional active-package matching. It observes local audio playback and accessible media sessions, emits stop transitions, and fails closed when notification-listener access is unavailable.

- **Keyboard actions**: added `ime.info` for current/enabled input-method variables and `ime.set` for enabled-target validation plus the system picker. Android does not allow a normal app to silently choose another keyboard, so that limitation is reported explicitly.

- **Temporary state**: added a bounded `state.temporary` action for brightness, volume, ringer mode, and DND. It captures the prior value, applies the requested setting, and uses a replaceable unique WorkManager job to restore it after a duration, including after process death.

- **Edit history**: task, profile, and scene edits now keep a bounded five-step undo/redo stack. Undo moves one revision at a time without discarding newer history, redo restores newer revisions, and saving after an undo starts a new branch. Scene edits are snapshotted with the full scene so dimensions and names recover as well as elements.

- **Global search**: added a live cross-project search for profiles, tasks, actions, variables, and scenes. Results include task/profile/scene references and deep-link into the matching editor or library surface without indexing secret variable values.

- **Personal-data actions**: added bounded clipboard get/set actions and a contacts lookup action. Clipboard and contact outputs are marked sensitive and never written to run-log messages; unattended contact lookup requires an explicit Setup grant, while Android 17+ picker mode uses a field-scoped selection with a timeout.

- **Quick Settings tiles**: added four active per-task tile slots with long-press configuration, persistent task/label/subtitle/icon/state settings, direct task dispatch, slot-aware tile events, and a functional `tile.set` action.

- **Visual system**: the command-center shell now uses a calmer sage/graphite palette, larger readable type, tighter spacing tokens, compact headers, border-light navigation, and text-led status indicators. Profile/task summaries and action/context rows rely on grouping and hairline rhythm instead of nested outlined boxes. The nine-page redesign reference is preserved at `design/mockups/opentasker-command-center-v2.png`.

- **Execution reliability**: service-owned task runs now pass through a persisted admission controller with global and per-profile active limits, burst windows, and a circuit breaker. Rejected runs are recorded as skipped with an explainable reason, while accepted leases are released safely even when cancellation unwinds the coroutine tree.
- **Execution safety**: statically provable `task.run` cycles are surfaced during imported-profile review, and newly created or edited enabled profiles are held disabled until the feedback-loop risk is explicitly reviewed.

- **Setup clarity**: the checklist now separates engine baseline, enabled-automation requirements, optional integrations, and reliability guidance. Permission rows are derived from enabled profiles and reachable task actions, so an empty workspace no longer reports unrelated automation blockers.

- **Execution identity**: profile, manual, widget, shortcut, notification, Locale, Scene, and external runs now share a bounded structured envelope and idempotent command ledger. Run Log entries persist one execution ID, producer, causal parent, and redaction-safe terminal reason; active execution state and external dispatch preserve the same identity across admission, cancellation, and terminal projection.

- **Diagnostics**: engine health now aggregates timestamped Loading/Ready/Stale/Error evidence instead of treating one old observation as current. Heartbeat freshness, matcher failures, standby throttling, exact-alarm fallback, watchdog failures, scheduler constraints, advanced-protection warnings, and active/pending executions expose concrete reasons in Diagnostics and the redacted report.

- **Context observation**: calendar and sun event pulses now use one demand-counted hot bus shared by engine matchers and the visible Context Inspector. Calendar queries start only when a calendar event is requested, and the Inspector releases its collectors when it leaves the screen while showing Loading/Ready/Stale/Error observation health and age.

- **Localization coverage**: secondary navigation, workspace notices, ViewModel messages, Context Inspector, Run Log, Diagnostics, and Flow graph copy now resolve through Android resources, with source-contract tests preventing new hardcoded visible English.

- **Locale compatibility**: host component discovery and broadcast transport are injectable for a deterministic synthetic setting/condition plugin fixture. Instrumentation coverage now exercises configuration, fire/query dispatch, request-query events, result codes, and bundle-argument redaction without depending on a third-party plugin installation.

- **Home Assistant bridge**: added a bounded outbound webhook action using the existing HTTP/LAN policy. HTTPS is the default, webhook URLs and JSON payloads are redacted, payloads are capped at 16 KB, and only transient failures receive capped exponential retry.

- **MQTT bridge**: added `mqtt.publish` with an in-app MQTT 3.1.1 QoS 0/1 client, platform TLS, retained messages, bounded payloads/timeouts, optional credentials, and private-LAN gating for cleartext. No new client dependency is required for F-Droid.

- **Push trigger spike**: added an authenticated `event=push` bridge for a de-googled UnifiedPush distributor, with a per-install Setup token, bounded payloads, duplicate suppression, and redacted event metadata.

- **Tasker migration**: expanded XML import/export mappings for safe notification, variable, speech/vibration, volume/brightness/timeout, torch/media, app/URL, screenshot, and structured flow actions. Unsupported actions remain explicit, and lossy notification/volume fields are listed in the migration report.

- **Profile sharing**: added an editable local community-share preview for workspace and imported bundles. The preview accepts bounded screenshot attachments with local thumbnails, renders trust and safety findings, exposes the computed bundle import plan, and hands off to the existing variable-conflict review before any Room write.

- **Preflight runner**: added side-effect-free task/profile previews with bounded synthetic event variables, flow branch decisions, expanded arguments, setup gaps, intended effects, and explicit blockers for unsupported actions. The review surface never invokes runtime actions or persists variables.

- **Intent dispatch**: generalized `intent.launch` into bounded activity, explicit broadcast, and explicit service delivery with URI/MIME support, allowlisted flags, capped primitive extras, ordered-broadcast result capture, and exported-target checks. Unsafe URI schemes, parcelable-style extras, ambiguous or non-exported external targets, and unapproved implicit broadcast/service dispatch fail closed.

- **Local projects**: added a first-class Room-backed workspace boundary with atomic Default-project migration, project-scoped runtime variables, shared filtering across automation surfaces, project-preserving bundle import/export, cross-project reference warnings, and explicit variable-safe reassignment before deletion.

- **Structured data**: `data.read` now supports bounded HTML parsing with CSS selectors and normalized element text. The jsoup dependency is pinned, checksum-verified, MIT-licensed, and performs no network I/O in the action.

- **Received Share trigger**: OpenTasker now appears in Android's Sharesheet for bounded text, URLs, MIME-typed content, and single or multiple file/content URIs. Share filters can match MIME, text, URI, and multiplicity; sanitized `share_*` variables reach the selected task, while oversized and arbitrary Parcelable extras fail closed.

- **Nested context logic**: profiles can now persist and edit recursive ALL/ANY/NOT expressions over their existing context leaves. Legacy implicit-AND profiles remain unchanged, the Inspector explains the evaluated tree, OpenTasker bundles preserve grouping, and Tasker XML export reports its unavoidable flattening.

- **Flow validation**: added a complex graph fixture covering multiple contexts, conditional branch labels, subflow markers, missing-task repair targets, continuation semantics, and screen-reader summaries. The Flow surface keeps zoom/pan and picker-backed add commands, while direct drag/drop persistence remains deferred until those editor paths have broader UI coverage.

- **Release trust**: a generated `tools/release-truth.json` manifest now owns version/code, Android SDK, dependency, capability-count, bundle/Room schema, and immutable artifact-commit claims. The local quality gate validates the manifest against shipped source, README, and F-Droid metadata with configuration-cache-safe verification.

- **Execution authoring**: profile editing can now select or clear an exit task; task editing exposes the previously stored collision policy; action editing exposes conditions and continue-after-failure; and task cards provide accessible move-up/down controls backed by a transactional history snapshot. Task collision admission now runs at the shared execution boundary for profile, manual, nested, widget/shortcut, notification, and external requests: Abort new logs a skipped run, Abort existing cancels the active coroutine tree, Wait serializes requests, and Run both permits overlap. Profile re-trigger mode remains the earlier profile-specific decision, while the referenced task's collision policy is the global last-mile rule.

- **App authoring**: application, notification, app-launch/kill, intent-launch, and Locale plugin package fields now share a searchable installed-app picker. Results show icon, label, and package from Android's scoped package visibility, Application contexts can reuse the latest package observed by the Inspector, and validated manual entry remains available without requesting `QUERY_ALL_PACKAGES`.

- **Build supply chain**: the Gradle 9.4.1 binary distribution is now pinned with Gradle's published SHA-256, and the stale 8.13-era executable wrapper JAR has been regenerated from 9.4.1. The local release gate verifies the configured version/URL, distribution pin, and checked-in JAR against independent expected hashes before it invokes Gradle, records both hashes in its report, and has a release-truth contract that fails if the property, gate pin, or binary drifts. The documented wrapper-upgrade procedure treats the URL, distribution hash, JAR, JAR hash, scripts, and test as one rollback-friendly change. The gate's Room-schema Git check also resolves a mapped drive to its canonical share and supplies that exact safe-directory identity, so it works from the repository's supported `Z:\` location without weakening Git ownership checks globally.

- **Bundle data integrity**: schema-1 OpenTasker exports now pass through an explicit, deterministic schema-1→2 migration with a checked-in golden fixture, while future schemas fail before domain decoding. Import review enumerates every same-name variable and requires an explicit keep, deterministic rename, or replace decision; safe programmatic imports preserve existing values by default, and replacing an existing secret changes its value without declassifying it. Task IDs are remapped across profile enter/exit links, scene gestures, `task.run`, and notification buttons inside one Room transaction, with rollback tests covering a conflict failure after task insertion. The first on-device secret replacement test also exposed and fixed Android Keystore rejecting the secret codec's caller-supplied GCM IV; encryption now lets the Keystore generate the nonce and stores it in the authenticated envelope.

- **External task dispatch**: Locale setting-plugin fires and scene tap/long-press bindings now construct `RUN_TASK` requests through one explicit protocol-v2 builder instead of duplicating raw intent strings. Both paths had omitted `PROTOCOL_VERSION=2`, so the hardened external receiver rejected them before enqueue. The shared builder validates task IDs and variable names, bounds values/counts, preserves a canonical source label into the run log, and is protected by a source contract that rejects new raw internal `RUN_TASK` producers; unknown or legacy external protocol versions remain fail-closed.

## v0.2.79

- **Backup & restore**: selecting a database to restore now opens a review instead of staging it immediately. Selection previously replaced the pending-restart journal outright, so a user could not inspect the candidate, could not tell it apart from a restore staged earlier, and had no way to back out. The review reports the source, size, schema version, compatibility, and profile/task/scene/variable/run-log counts, names the staged restore it would replace, and stages nothing until Stage is pressed. Setup gained a "Cancel staged restore" action that removes only the validated pending journal — backups, the live database, and the pre-restore snapshot are untouched — and the pending banner now describes what is actually queued (including a staged file that has since become unreadable).

- **Run logs**: the Run Log now shows what the engine is running *right now* — task, origin, current step, and elapsed time — with a Cancel button for each. Previously the service tracked its jobs privately and the UI showed only completed runs, so a runaway automation (a long wait, a hung request, an accidental loop) was invisible and unstoppable short of force-stopping the app. Cancel unwinds the whole run including nested "run sub-task" steps and any bounded blocking action suspended inside it, and records a terminal **Cancelled** outcome — a distinct state from Skipped, which means the run never started.

- **External intents (breaking, protocol v2)**: `RUN_TASK` is now asynchronous. It previously held the broadcast open with `goAsync()` until the whole task finished and returned its terminal success — but Android expects broadcast work to complete in roughly 10 seconds while an OpenTasker task can wait up to 30 minutes, so the reply reported an outcome that had not happened yet and the system could kill the receiver mid-run with no run-log entry. The receiver now authenticates, validates, hands the run to the foreground engine service, and replies immediately with `ACCEPTED` plus an execution ID; callers poll terminal status with the new `com.opentasker.action.QUERY_EXECUTION`. Results are retained for the 64 most recent executions and survive a process restart, and a run that was in flight when the engine died resolves to `FAILED` rather than leaving a caller polling forever. Callers must declare `com.opentasker.extra.PROTOCOL_VERSION=2`; a request without it is refused with an explicit error naming the required extra rather than being silently reinterpreted. `SET_PROFILE_ENABLED` and `QUERY_STATUS` are unchanged. See `docs/EXTERNAL_INTENTS.md`.

- **Run logs**: each step now records the variables it actually set. Traces previously showed only the values that went *into* an action, so a finished run never answered "what did this task write?". Every step captures its task, global, and array deltas (added vs. updated; a rewrite of the same value is not recorded), and the Run Log renders them in an expandable per-step inspector next to the existing expression debugger. Secret-derived values are redacted at the serialization boundary, so the raw value never reaches the stored log.

- **Engine**: the automation-mode dispatch rules (SINGLE suppression, RESTART preemption, the QUEUED cap, exit tasks never consuming cooldown) and cooldown reservation are now pure, directly tested components that the foreground service delegates to, instead of logic reachable only through a running service. Cooldown check-and-reserve is locked against the check-then-write race where two contexts matching the same profile in the same instant both start a run.

- **Actions**: the capability contract is now total and fails closed. An action that was registered but never classified used to report itself as "Ready" by default; every action now needs an explicit contract entry, and an unreviewed one is Unsupported. `app.kill` is marked Unsupported (force-stopping another app has always been impossible without privileged access, but it advertised itself as working and failed only at run time). `screen.timeout` is correctly gated on Write Settings, which the app now actually declares — `WRITE_SETTINGS` was missing from the manifest, so `Settings.System.canWrite()` could never become true and both it and Set brightness were permanently broken while claiming to be one grant away. Setup gained a "Modify system settings" row with a working deep link. Wake-on-LAN is gated on local network access on Android 17+. The README's action counts are now derived from the registry and verified (they had drifted to 58/59 against an actual 60).

- **Data integrity**: deleting or renaming a task is now reference-safe. Deletion previously checked only a profile's enter/exit task columns, so a task referenced by another task's "run sub-task" step, a notification button, or a scene tap/long-press gesture could be deleted out from under them; a rename silently broke every reference that named the task. Deleting a referenced task now lists every dependent object and requires an explicit choice — reassign them all to another task, or clear the optional ones — applied in a single transaction (a profile's enter task cannot be cleared, so only reassignment is offered when one is present). A rename pins name-based references to the task's stable id first. The same rewriter also fixes OpenTasker bundle import, which never remapped task-to-task references inside imported actions: an imported "run sub-task" step pointed at whatever local task happened to own the exporter's id.

- **Security**: stored action arguments are now redacted everywhere they are displayed, not just in runtime traces. The task list and the flow graph previously joined raw arguments into their subtitle, so an HTTP `authorization` header, request `body`, or query string typed into an action appeared on screen, in screenshots, and in accessibility semantics. A single shared formatter (`ActionArgumentSensitivity`) now decides what is masked, driven by an explicit `sensitive` flag on the action's registered field metadata and backed by an argument-name heuristic so unregistered actions and forward-compatible keys fail closed. `var.set` masks its value when the variable it writes is itself named like a secret, and a source guard prevents any new surface from rendering raw arguments.

- **Diagnostics**: the engine-health panel now shows a "Scheduled jobs blocked by" row that reports, in plain language, why the app's deferrable jobs (watchdog, log pruning) are still pending — app standby bucket, no connectivity, not charging, out of run quota, device thermal/power state, and so on — using Android 14+ `JobScheduler.getPendingJobReason`. It answers the common "why hasn't my scheduled automation fired" question; below Android 14 or when nothing is blocking, it reads "Nothing blocking".
- **Security**: secret/taint flags on global and array variables are now monotonic — once a variable is marked sensitive it stays sensitive for the life of the run. A concurrent plain write from another parallel profile run can no longer race the flag off and leak the value into a later log or trace (the flag is set before the value is published and never cleared by a subsequent write).
- **Run logs**: an action that fails while consuming a secret-derived argument now records its real error class and location (e.g. `threw: request failed for <redacted>`) instead of the opaque blanket "details redacted" message. The raw secret value is scrubbed from the message and the throwable cause is dropped, so failures stay debuggable without leaking the secret.

## v0.2.78

- **Diagnostics**: the engine-health panel now flags a throttled app-standby bucket (`RARE`/`RESTRICTED`) with an explicit warning pointing to the battery-optimization exemption, and the overall health indicator treats any throttled bucket (not just `RESTRICTED`) as unhealthy.
- **Contexts**: Location conditions gain a "Match when outside" toggle for geofence-exit automations ("fire when NOT at a place"), with dwell measured outside the radius. Existing inside-only geofences are unchanged, and the FOSS evaluator, matcher, and dwell tracker all honor the mode through the shared evaluator.
- **UI**: selected filter chips and rows now use an opaque composited fill (`selectedContainerColor`) instead of a translucent alpha-on-alpha wash, so the selection reads as a distinct solid fill in both the AMOLED and light themes rather than being distinguishable only by its border.
- **Security**: the exported external-trigger receivers (`AutomationTargetReceiver`, `LocaleSettingFireReceiver`) now declare `android:intentMatchingFlags="enforceIntentFilter"` so incoming intents must match their declared filters (blocking mismatched-action redirection), and debug builds enable StrictMode `detectUnsafeIntentLaunch()`.
- **Networking**: the ACCESS_LOCAL_NETWORK (Android 17+) policy is now a pure, unit-tested function covering the below-37 no-op, granted, and denied/revoked paths; LAN actions continue to fail closed with a clear "grant it in Setup" message.
- **Tasker import**: the `Wait` action now reads Tasker's five fixed time fields (ms/seconds/minutes/hours/days) by their argument index instead of a dense list, fixing imported waits that were mis-scaled by up to 1000× when zero fields were omitted from the export.
- **Diagnostics**: detects Android 16 Advanced Protection Mode (API 36+, read defensively via reflection so it fails closed and is a no-op below 36) and surfaces its state plus a graceful-degradation note in the engine-health panel — OpenTasker's triggers keep working since it uses no Accessibility service, but privileged extensions may be limited while it is on.

## v0.2.77

### Roadmap drain (correctness, security, and consolidation)

- **Engine**: sub-task (`task.run`) input variables are now scoped to the child invocation, so lowercase inputs no longer leak into the parent task's later actions. QUEUED-mode retriggers arriving while a task is running now queue instead of being dropped as "cooldown active" — the cooldown is reserved only when a fresh run actually starts. Notification-button taps run inside the foreground `AutomationService` instead of the receiver's ~10 s `goAsync` window, so long tasks (e.g. `flow.wait` up to 30 min) complete and log reliably.
- **Actions**: the `download` action now delegates to the shared `HttpRequestAction` transport (same-origin redirects, atomic fsync'd writes, the 50 MB cap, cleartext-private DNS, and the LAN-permission gate) instead of a parallel OkHttp path; downloads land in the shared `user_files` sandbox so `file.*` actions can read them. `FileActions` reads/writes with no-follow (`O_NOFOLLOW`) semantics and rejects symlinked path components, closing a TOCTOU sandbox-escape window. Notification-button PendingIntents use a collision-free request-code allocator so a newer notification can no longer overwrite an older button's intent.
- **Import/validation**: `InputValidation` field limits (name length, task priority, non-empty actions, blank action type, profile name/cooldown) are now enforced at the OpenTasker bundle import boundary and on profile save, instead of being an unenforced module.
- **Scenes**: a scene slider bound to a task now fires it on release with the value exposed as a variable; task-firing overlay controls (button and bound slider) drop obscured touches (`filterTouchesWhenObscured`) as a tapjacking guard. The multi-selection is reconciled when the element list changes and is preserved while dragging a selected member. The resize handle and Run Log expression-debugger expand control now meet the 48 dp touch-target minimum.
- **UI/polish**: flow-canvas connectors are drawn in density-correct dp (were raw px, misaligned at density ≠ 1×) and the sub-task badge keys off a structural node flag rather than an English literal. Removed the dead `PremiumComponents` module and the deprecated edge-to-edge status/navigation bar color setters.

## v0.2.76

### Deep audit fixes (2026-07-17)

- **Engine**: exit tasks now run on their own job slot and never consume the profile cooldown, so a cooldown, SINGLE-mode in-flight enter task, or RESTART can no longer silently drop the exit task. Closed a QUEUED lost-task race where a retrigger could be enqueued into a queue whose consumer had already decided to exit.
- **Engine**: plugin conditions no longer flap — the shared Locale plugin poll source multiplexes every subscription, so the matcher now holds state for results addressed to a different plugin/bundle instead of driving every plugin context true→false→true each 30 s cycle. The internal `sun_tick` minute pulse can no longer satisfy a generic/blank-filter EVENT context (previously firing imported event profiles every minute); blank-event/blank-filter specs fail closed.
- **Contexts**: all-day calendar events match on the local day instead of the raw midnight-UTC bounds (they were shifted by the zone offset). The Context Inspector is now read-only and no longer resets the engine's persisted location dwell timers, and its match explanations honor OR groups like the engine. Serialized the two-thread state-source merge and synchronized camera/mic AppOps start/stop against a watcher leak.
- **Data**: added indexes on `run_logs(timestamp)` and `edit_history(entityType, entityId)` (schema v8, migrated + instrumented).
- **Actions**: `download` runs on OkHttp with a policy-DNS hook so the cleartext private-LAN rule and the API 37 `ACCESS_LOCAL_NETWORK` gate are enforced against the addresses actually connected to (closing a DNS-rebinding TOCTOU), fails on non-2xx instead of saving a redirect stub over a good file, and fsyncs before the atomic rename. `tile.set` fails honestly and is capability-gated Unsupported instead of reporting a no-op Success. `screen.timeout` rejects the "0 = never" value that actually turns the screen off immediately. `flow.wait` at its 30-minute maximum no longer always times out; `sound.play`/`tts.speak` get a 10-minute budget and TTS queue failures fail fast. `datetime.*` zone typos fail closed; `data.read` CSV supports RFC 4180 quoted fields. Added missing editor fields for ping, download, sound.play, and media.mute.
- **UI**: fixed a Diagnostics crash from duplicate log keys (now keyed on a monotonic sequence) and stopped its polling while backgrounded. The one-time NFC write is disarmed on dialog close, expires after 60 s, and runs its tag I/O off the main thread. `deleteVariable` reports real success/failure instead of an optimistic toast; undo no longer reports false success; `updateTask`/`updateProfile` are transactional. Editing an unknown action type shows a message instead of a dead tap. The context editor blocks saving garbled TIME windows and out-of-range coordinates. New profiles default to disabled, the enter-task selection no longer resets mid-edit, and backup state loads off the main thread.
- **Theming**: scene warning text follows the applied theme's luminance (was near-invisible in Light-app-on-dark-system), the Locale plugin edit activity honors the persisted theme, run-log detail lines no longer render twice, and the scene overlay is clamped on screen so it can't be dragged fully offscreen. Removed dead duplicate helpers.

### Prior unreleased work

- **Diagnostics**: added a secondary Diagnostics destination with live engine heartbeat, active foreground-service types, app-standby bucket, exact-alarm delivery, last matcher failure, WorkManager watchdog stop reason, bounded process logs, and redacted crash previews. Shared diagnostic reports now include that health snapshot, up to 100 ring-buffer entries, and bounded crash excerpts; Authorization/Bearer credentials are redacted in addition to existing secret patterns.
- **Background reliability**: time/day contexts now consume AlarmManager wake pulses in addition to an aligned in-process minute clock, so a Doze wake reaches the matcher instead of only producing a log line. Inexact fallback alarms use `setAndAllowWhileIdle`; a persisted service heartbeat and 15-minute WorkManager watchdog re-arm dropped ticks, and foreground-service timeout leaves a recovery alarm armed before shutdown.
- **Variable reliability**: global write-back now compares each run against its hydrated snapshot under a process-wide mutation coordinator and publishes accepted rows as one Room batch. Concurrent runs merge disjoint globals without loss; stale same-global writers preserve the first committed value and add an explicit conflict note to the run log instead of silently clobbering it.
- **Variables**: unified variable-name normalization across runtime writes, `var.persist`, the Variable vault, durable storage, and Tasker XML imports. Any uppercase letter now consistently identifies a global; all-lowercase names stay task-local, explicit lowercase persistence targets are promoted instead of silently disappearing, invalid targets fail visibly, and root-local event values cannot leak into durable snapshots.
- **Networking**: replaced the split HTTP GET/POST editor actions with one cancellable `http.request` transport on OkHttp 5.4.0. It supports GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, bounded structured query/header/auth input, inline or file bodies, status/header/body variables, atomic response files, per-stage timeouts, and explicit no-redirect/same-origin redirect policy. TLS bypasses and cross-origin redirects fail closed, cleartext remains private-LAN-only, header traces redact credentials, and stored GET/POST IDs remain hidden compatibility aliases.
- **Release/docs**: expanded the release-truth contract from README-only checks to source-derived capability and version checks across architecture, dependency, F-Droid, scenes, visual flow, Shizuku, Termux, and Locale documentation. The gate excludes explicitly historical dependency logs and includes a deterministic stale-document failure example.
- **Backup reliability**: encrypted `.otbackup` exports now use chunked format v2, authenticating each bounded 64 KiB frame plus an explicit terminal frame before validated restore staging is atomically published; v1 backups remain restorable. Wrong passphrases, corruption, truncation, cancellation, write failures, and interrupted staging clean temporary plaintext without replacing an existing pending restore, while startup restore keeps its pending journal through same-directory atomic database replacement.
- **Battery/reliability**: production Wi-Fi, connectivity, app-usage, shake, camera/mic, package, and Bluetooth context monitors now start only while an enabled profile depends on them and stop after the final dependent profile is disabled or deleted. Profile edits reconcile reference counts without duplicate registrations, and an explicit subscription barrier prevents a newly activated pulse source from firing before its matcher is listening. Camera/mic AppOps pulses are now also wired into event-context matching.
- **Security**: every built-in action now has an explicit data-access, external-transmission, device-control, destructive, or local-only classification. Bundle schema v2 carries a computed task/profile power manifest and flags potential data-to-external chains; unknown actions fail before import or task side effects. Imported profiles persist a review-required state, stay outside the engine registry, and require an in-app acknowledgement before their first enable.
- **Security**: global variables can now be explicitly stored as Android Keystore-backed AES-256-GCM secrets. Secret provenance survives legacy/template expansion and derived writes, redacts nonsensitive argument fields, action logs, traces, and failures, and keeps values out of ordinary OpenTasker/Tasker exports. Cross-device or key-loss restores fail closed with a deliberate re-entry flow.
- **Maintainability**: split the scene library's list/cards, interactive canvas, element dialogs, and overlay controls into focused Compose modules; the public screen is now a 160-line coordinator protected by source-boundary, localization, accessibility, and scene behavior tests.
- **Security**: Termux scripts now require a Setup-managed SHA-256 allowlist and a matching Termux-side preflight hash before every run. The app declares and requests `RUN_COMMAND`, requires Termux 0.109+ result support, receives results through a non-exported one-shot callback, bounds arguments/stdin/timeouts/stdout/stderr/pending commands/rate-limit state, redacts captured content from logs, and can map stdout, stderr, exit code, and original lengths to variables.
- **Data safety**: completed the fail-closed stored-payload boundary across manual, widget, notification, external-intent, export, inspector, and widget-configuration paths; corrupt rows stay untouched for database recovery and skipped runs now record the reason. Edit-history pruning is also entity-scoped, so trimming one task can no longer delete another task, profile, or scene history.
- **Scenes**: overlay rendering now uses the editor's authored scene projection and exact element bounds, supports overlapping elements and bounded local-image decoding from persistable content URIs, and reads both legacy slider `progress` and current `value` deterministically.
- **Reliability**: notification action buttons now bind to immutable task IDs through a task picker. Renames preserve bindings, deleted tasks fail visibly, and legacy name bindings migrate only when the name is unique; duplicates never select an arbitrary task.
- **Scenes**: multi-selected elements now move as one rigid, edge-clamped mutation with one transactional undo snapshot; resize gestures use independent horizontal and vertical canvas scales and stay within scene bounds.
- **Onboarding**: first-run template onboarding now completes only after an explicit skip or successful install and resumes after dismissal or recreation. Runtime permission results update Setup immediately, repeated denial routes to app settings, and grant/revocation resets recovery state.
- **Accessibility**: scene overlays now use a 48dp close target, expose screen-reader move actions, and retain a proper touch click path. Profile switches, task/profile actions, nested action/context controls, run-log filters, and expression details now expose specific names and authored state without duplicate decorative icon announcements.
- **Platform**: Android 17 audio hardening is now eligibility-aware instead of disabling every audio action. Visible task launches and while-in-use-eligible automation services attempt sound, TTS, volume, ringer, mute, and media-key operations; boot/background runs fail before side effects with recovery guidance, while exact-alarm access is honored only for alarm-stream changes.
- **Security**: Shizuku permission can no longer promote elevated capabilities or route commands through an ordinary app-UID `ProcessBuilder`. The kill switch is persisted and defaults on, Setup distinguishes stopped/permission/unavailable/disabled states, and every elevated action remains `Unsupported` until a privileged user-service transport exists.
- **Security**: OpenTasker JSON and Tasker XML imports now enforce shared entity/action/context/scene/string budgets, plus streaming token/node and nesting preflights before model or DOM allocation. Named violations fail before the Room transaction.
- **Release**: added one local quality/release gate covering blocking lint, the JVM test floor, Room schema drift, Android-test compilation, resolved dependency/repository/checksum policy, a CycloneDX SBOM with OSV advisory results, configuration-cache reuse, and Play/F-Droid release assemblies. Enabling permission lint also caught and fixed the missing manifest permission for the shipped vibrate action.
- **i18n**: moved action/context catalogs, setup and backup copy, capability diagnostics, widget plurals, and scene-overlay labels to Android resources; seeded Spanish setup translations, enabled `en-XA`/`ar-XB` debug pseudolocales, and expanded localization guards.
- **Reliability**: Wake-on-LAN now rejects MAC addresses with mixed `:`/`-` separators (e.g. `AA:BB-CC:...`); a consistent separator is required.
- **Actions**: added a text/regex action pack — **Match Text** (`text.match`, captures become an array), **Replace Text** (`text.replace`, `$1` group refs), **Split Text** (`text.split`, literal or regex), **Join Text** (`text.join`), and **Substring** (`text.substring`). Regex uses the linear-time RE2 engine with bounded pattern/input sizes, so patterns can't hang the runner.
- **Actions**: added date-time actions — **Format Date/Time** (`datetime.format`), **Parse Date/Time** (`datetime.parse`), and **Add to Date/Time** (`datetime.add`). Convert between epoch milliseconds and formatted strings with optional time zones, and do calendar-aware date arithmetic (seconds through years), all deterministic and offline. Fixed units are exact zone-independent deltas; months/years honor calendar length.
- **Actions**: added a **Read Data** action (`data.read`) that parses JSON, CSV, or XML into variables entirely on-device — ideal for turning HTTP responses and file contents into usable automation data. Supports JSON path selectors (`items[0].name`), CSV column/cell selection, and XML element paths (`root/item/name`), sets an array plus a `%var_count`, is size-bounded, hardened against XML external entities, and fails closed on malformed input or an unresolved selector.
- **Security**: the external-automation broadcast target now bounds the number of supplied variable extras (64) in addition to the existing per-value length cap, name validation, and signature permission.
- **Interoperability**: OpenTasker bundle import now tolerates hand-edited JSON — `//` comments, trailing commas, and case-insensitive enum values decode cleanly, while unknown keys and oversized bundles are still rejected. Export output is unchanged.
- **Reliability**: task execution now runs off the main thread. Every run path (manual, profile trigger, widget/shortcut, notification action, Locale/external) executes actions on `Dispatchers.IO`, and the automation service's matching/dispatch runs on `Dispatchers.Default`. Previously blocking actions (HTTP GET/POST, download, ping, Wake-on-LAN, file I/O) launched from the main thread threw `NetworkOnMainThreadException` and failed silently. Debug builds now install StrictMode to flag any accidental main-thread disk/network I/O.
- **Privacy**: SMS recipient numbers are now masked in run logs (e.g. `***6789`) instead of stored in full — run-log redaction does not otherwise scrub phone numbers.
- **Reliability**: hardened smaller action/import edge cases — the Termux script action no longer passes a spurious empty argument when `arguments` is blank or double-spaced; `file.list` reports a clean "invalid file name pattern" failure instead of leaking a raw Java exception for a bad glob; and OpenTasker bundle import no longer counts updated variables as newly inserted.
- **Reliability**: hardened the variable engine. A `var.set` targeting a huge array index (e.g. `%X[2000000000]`, reachable from an imported/shared profile) no longer tries to grow a multi-billion-entry list — out-of-range writes fail closed. Array storage now evicts the genuinely least-recently-used array at its cap instead of an arbitrary one, and is synchronized for concurrent tasks. Ternary conditions whose test contains parentheses (e.g. `(%A(+1) > 5) ? a : b`) are now parsed correctly instead of silently falling through.
- **Reliability**: event/notification text matching with `regex=true` now uses the linear-time RE2 engine (as variable regex already does) instead of the JDK backtracking engine, so a pathological user pattern can no longer hang the matcher on an incoming event.
- **Correctness**: battery-level triggers now normalize `EXTRA_LEVEL` against `EXTRA_SCALE`. On devices that report a non-100 scale (some report 255), `battery_level` thresholds previously never/always matched.
- **Security**: the exported Locale fire receiver now requires a revocable execution grant. Any app could previously broadcast a chosen task id to the receiver and have OpenTasker run it. Configuring the plugin now issues a high-entropy token bound to the selected task; the receiver dispatches only when the incoming bundle carries a token that is still stored and bound to that exact task, so forged, missing, mutated, revoked, and deleted-task grants are rejected without dispatch. Grants are revoked automatically when their task is deleted.
- **Networking**: cleartext HTTP to LAN/private hosts now actually works. The network-security config previously listed private ranges as `<domain>` hostnames (Android has no CIDR support there), which silently blocked every literal LAN IP. Cleartext is now gated solely by the runtime policy — HTTPS stays the default, `allow_http` is an explicit opt-in, and any host not resolving to a loopback/link-local/site-local/IPv6-ULA address is rejected before a connection opens. IPv6 Unique Local Addresses (`fc00::/7`), previously misclassified as public, are now recognized.
- **Variables**: global (`%UPPERCASE`) variables and `var.persist` values are now genuinely durable. Every execution path (manual run, profile trigger, widget/shortcut, notification action, Locale/external intent) hydrates persisted globals before running and commits any globals changed during the run to the database before reporting success, so they survive across runs and process restarts. Local (lowercase) variables still never escape their invocation, and the Variables vault now reflects real global state.
- **Reliability**: the running automation engine now reconciles itself from the profiles table. Creating, editing, enabling, disabling, or deleting a profile rebuilds matchers and plugin subscriptions live, without needing a service restart, while leaving any in-flight task run untouched. Purely cosmetic edits (name, group) no longer thrash the engine.
- **Data safety**: corrupt stored automation payloads now fail closed. Task, profile, and scene rows whose JSON no longer decodes are surfaced with the exact record and field, cannot be executed (profiles skip them with a run-log note and `task.run` refuses corrupt sub-tasks), and cannot be overwritten by the normal editors (the raw bytes are preserved for undo/backup recovery). Scene edits now also snapshot to edit history, and stored payloads decode through a shared codec that tolerates unknown additive fields.
- **Release**: refreshed the draft F-Droid metadata pin and local fdroidserver lint/build evidence for `0.2.75`/`77`.
- **Release**: added the Kotlin/Gradle dependency verification hashes needed by clean fdroidserver source checkouts.
- **Reliability**: hardened database backup creation so local backups wait for a complete WAL checkpoint, publish only schema-validated copies, clean up failed temporary files, and keep backup UI state from getting stuck after failures.
- **Testing**: added Compose instrumentation coverage for setup onboarding, task/profile editor validation, action/context required-field validation, scene creation, and incompatible import review states.
- **Accessibility**: added repeatable source gates for setup, profile/task editors, action/context editors, scenes, destructive dialogs, and run-log states; converted remaining app-shell and setup semantic labels to string resources.
- **i18n**: completed the core active automation, editor, flow, scene, and premium-state string-resource extraction pass; added a JVM source guard for hardcoded Compose strings and valid Weblate locale targets.
- **Reliability**: routed remaining direct platform log calls through `AppLogger` and added a source-level regression guard so `android.util.Log` stays isolated to the logging wrapper.
- **Maintainability**: finished the active-automation shell split into owned view-model, list, editor, action, and context modules while keeping `ActiveAutomationUi.kt` under 1,500 lines.
- **Release**: synced draft F-Droid metadata and the PowerShell release verifier with the current `0.2.75`/`77` Gradle release contract.
- **Docs**: added a release-truth contract test so README release values and shipped-feature claims stay aligned with Gradle metadata and current backend docs.

## v0.2.75 - 2026-06-19

Scene editor finishing pass and visual flow editor authoring.

- **Feature**: scene overlay launch via `SYSTEM_ALERT_WINDOW` — each scene card shows a "Show" button (when overlay permission is granted) that displays the scene as a draggable floating window with dark-themed element views and tap-to-run-task bindings.
- **Feature**: scene element multi-select — drag-starting an element selects it (highlighted border); when multiple elements are selected, dragging one applies the delta to all selected elements as a group.
- **Feature**: alignment guides on scene canvas — elements snap to canvas edges, center lines, and other element edges/centers during drag. Dashed guide lines render during the gesture with a 6dp threshold.
- **Feature**: flow canvas pinch-zoom (0.5x-2.5x) and pan gestures for the lane overview.
- **Feature**: flow edge routing — vertical connectors between lanes and horizontal connectors between nodes drawn as Canvas lines with endpoint dots.
- **Feature**: branch and subflow markers — action nodes with sub-task references show a Subflow pill; conditional actions show a Branch pill with the if-condition text.

## v0.2.74 - 2026-06-19

i18n bootstrap, engine v3, dependency upgrade, encrypted backup, Shizuku/Termux backends, and Locale interop.

- **i18n**: expanded `strings.xml` from 49 to 170+ string resources covering all major UI surfaces. Converted ImportReviewDialogs, VariablesScreen, RunLogScreenContent, ContextInspectorScreen, and SceneLibraryScreen to use `stringResource()`. Created locale skeleton directories for 13 languages. Added contributor translation workflow docs to README.
- **Feature**: `var.set` now supports dotted and bracketed path syntax (`config.theme`, `items[0]`, `Data.user.profile.name`) for nested JSON writes via `VariableStore.setAtPath()`. Array indices auto-grow with empty-string padding.
- **Feature**: Run-Log expression traces now render in an expandable debugger surface with per-expression arg name, scope source, monospace expression→value mapping, and warning highlights.
- **Feature**: encrypted database backup/restore using AES-256-GCM with PBKDF2-derived keys (600k iterations). `.otbackup` file format with 4-byte magic, salt, IV, and authenticated ciphertext.
- **Feature**: Shizuku elevated backend with real API 13.1.5 integration. Checks Shizuku service state (ping, permission), exposes Ready/PermissionNeeded/Disabled/ManagerInstalled states. ShizukuShellRunner validates commands against a strict allowlist. Kill-switch toggle. ActionCapabilities dynamically promotes elevated actions when Shizuku is active.
- **Feature**: Termux RUN_COMMAND dispatch with executable path, arguments, working directory, and background execution. SHA-256 script hash pinning for allowlist verification. 1-second per-script frequency cap. Output-to-variable mapping via capture prefix.
- **Feature**: Tasker XML export for the mappable action subset (notify, wait, log, var.set). Exports Time, Day, Application, State, and Event contexts. Reports skipped actions and unmappable contexts.
- **Feature**: Locale plugin target bridge — OpenTasker now appears as a Locale-compatible setting plugin for Tasker/MacroDroid. Edit activity shows task picker; fire receiver dispatches tasks through the existing automation pipeline.
- **Feature**: scene element resize handles on the canvas preview. Drag the bottom-right handle to resize elements within scene bounds.
- **Dependencies**: upgraded Compose BOM from 2026.04.01 to 2026.05.00 with updated dependency verification checksums.
- **Style**: adopted DesignSystem spacing and radius tokens across 5 major UI screen files.

## v0.2.73 - 2026-06-17

Hardening, test coverage, and expression engine improvements.

- **Security**: applied Android 17+ `ACCESS_LOCAL_NETWORK` permission guard to Ping and Wake-on-LAN actions; all LAN socket actions now enforce the same gate.
- **Security**: extended the Android 17+ local-network guard to HTTPS requests targeting private, loopback, or link-local hosts so URL-backed LAN actions cannot bypass Setup permission state.
- **Reliability**: added Room schema v5 drift gate — CI now fails if any schema version file is missing; added migration tests for 2→3, 4→5, and full 1→5 path.
- **Supply chain**: enabled Gradle dependency verification with SHA-256 checksums for all resolved artifacts.
- **Feature**: added `var.persist` action to promote local variables to global scope across task invocations.
- **Testing**: broadened action guard coverage for file, settings, app, and notification-channel operations; expanded retention policy boundary tests.
- **i18n**: centralized common UI strings (navigation, dialogs, setup, empty states) in `strings.xml`.
- **Docs**: updated Setup permission copy to list all guarded network actions.
- **Safety**: `AutomationService.onDestroy()` now snapshots job collections before cancelling to prevent `ConcurrentModificationException` during service teardown.
- **Safety**: `reloadProfiles()` cleans up stale queued tasks for deleted/disabled profiles, preventing memory accumulation.
- **Safety**: `ArrayStore` now caps at 500 entries to prevent unbounded growth from `%var(split:...)` operations within a single task run.
- **Safety**: `OpenTaskerBundleCodec.decode()` now rejects JSON payloads larger than 16 MB to prevent OOM from malicious imports.
- **Safety**: capped `file.write` and `file.append` payloads at the existing 1 MB file-action boundary and fail before creating or expanding files beyond that limit.
- **Safety**: bounded imported database restore staging to 100 MB and remove temporary files if the import stream fails or exceeds the limit.
- **Safety**: `WiFiNetworkMonitor` and `ConnectivityMonitor` handle null `ConnectivityManager` gracefully instead of crashing on devices where the system service is unavailable.
- **Reliability**: serialized persisted Location dwell-state read/modify/write operations so concurrent matchers cannot lose inside-since updates.
- **Reliability**: cleaned up LocationManager listener registration on provider-set changes and partial registration failures to avoid duplicate callback chains.
- **Safety**: surfaced corrupted profile/task JSON as visible UI warnings while keeping safe fallback domain objects.
- **Safety**: hardened database backup validation with current schema-shape checks, required-table row-count reads, and a consumed WAL checkpoint before copy.
- **Maintainability**: split run-log rendering and import-review dialogs out of `ActiveAutomationUi.kt`, with source tests guarding the screen ownership boundary.
- **Reliability**: made `flow.wait`, `vibrate`, and `screen.timeout` fail clearly on missing, malformed, or out-of-range durations instead of silently defaulting or clamping.
- **Fix**: implemented deterministic `file.list` filename glob filtering and added the missing action editor field for `pattern`.
- **Security**: tightened import validation by rejecting oversized/DOCTYPE Tasker XML before parsing and blocking OpenTasker bundles with duplicate task IDs or variable names.
- **UX**: bounded long Tasker/OpenTasker import review dialogs so warnings and mapped-action lists stay scrollable on compact screens.
- **Reliability**: replaced API 33-only service receiver registration with AndroidX compatibility calls and gated camera/mic active watchers to Android 11+ APIs.
- **Safety**: made downloads write to a temporary file and replace the destination only after a complete bounded copy succeeds.
- **Performance**: reduced scene-canvas drag recomposition overhead by using primitive float state and lambda offsets.
- **UX**: polished first-run onboarding, labeled create actions, the widget task picker, and the home-screen widget treatment so setup and secondary flows feel more guided and intentional.
- **Reliability**: made widget and launcher-shortcut task runs close with clear feedback even when task execution throws, avoiding stranded translucent runner activities.
- **Reliability**: guaranteed external automation broadcast pending-results finish even if ordered-result publication fails.
- **Cleanup**: removed duplicate `ArrayStore.joinWith` method (identical to `join`).

## v0.2.72 - 2026-06-16

Setup and secondary-state polish pass.

- **Setup**: replaced the theme button grid with an accessible radio-style selector that exposes selected/not-selected state and avoids no-op selected buttons.
- **Backup**: tightened backup/restore copy, added a calm state banner, and changed secondary backup actions into compact side-by-side controls so the card scans better on compact screens.
- **Permissions**: normalized setup action button shape for a more consistent control language.
- **Flow/Scenes/Inspector**: upgraded sparse empty states into framed, explanatory surfaces with status cues and clearer next-step copy.
- **Docs**: bumped app metadata and README/roadmap state for v0.2.72.

## v0.2.71 - 2026-06-16

Premium UX polish pass.

- **Navigation**: promoted Run Log into the primary bottom navigation, clarified destination labels, and tightened selected-state geometry for more stable compact-screen behavior.
- **Theme**: synced the navigation bar color with the AMOLED/light/high-contrast theme selection so edge-to-edge chrome feels intentional.
- **Profiles/Tasks**: made status and secondary action rows horizontally safe on compact screens, added filtered no-match notices, and kept long mode/group/collision labels from crowding primary content.
- **Run Log**: moved outcome and duration chips below the run header so diagnostics keep readable width with long task names and trace detail.
- **Variables**: upgraded the Variables tab into a summary-driven variable vault with metrics, clear search, polished empty states, consistent cards, and explicit sensitive-value masking labels.
- **Design system**: added reusable screen spacing and opacity tokens to reduce hardcoded visual decisions across Compose surfaces.

## v0.2.70 - 2026-06-16

Profile organization and diagnostic sharing.

- **Profile groups**: profiles carry an optional group, set from a new editor field, shown as a pill badge on profile cards and offered as filter chips in the profile list once any group exists. Room migration v4→v5 adds the column, and the field carries through JSON export/import.
- **Diagnostic share**: the Run Log summary card can share a redacted diagnostic bundle — app version, device info, recent run logs, and permission state — through the Android share sheet, with regression coverage for the redaction.
- **Action guard coverage**: missing-argument validation tests for `ReadFile`, `WriteFile`, `PlaySound`, `LaunchApp`, `SetVariable`, and `SayAction`'s text length cap, plus the expanded `OpenUrl` scheme allowlist (`tel`, `mailto`, `geo`, `data`, `blob`).
- **Supply chain**: pinned the then-current GitHub Actions from mutable `v4` tags to full commit SHAs.

## v0.2.69 - 2026-06-16

Locale condition plugin context UX (N7).

- **Feature**: added `ContextType.PLUGIN` for Locale/Tasker condition plugins as first-class profile context predicates; users can pick a condition plugin, configure it, and have profiles activate/deactivate based on the plugin's satisfied/unsatisfied state.
- **Feature**: added `LocalePluginConditionContextSource` that polls subscribed condition plugins every 30 seconds with last-known-state caching through the existing `LocalePluginConditionStateCache`.
- **Feature**: added Plugin context row in the context picker with package, config JSON, description, and timeout fields.
- **Feature**: Context Inspector shows plugin condition source health, config summary (package + blurb), and match state.
- **Engine**: `AutomationService` registers plugin subscriptions when enabled profiles are loaded and clears them on destroy.
- **Tests**: added evaluator tests for plugin matching, package/bundle validation, inversion, and inspector config summary.

## v0.2.68 - 2026-06-16

Safety and correctness patch.

- **Safety**: replaced legacy Java/Kotlin regex worker threads in variable `%regex` and `%replace` operators with RE2/J linear-time matching, eliminating leaked `regex-eval` threads from pathological user-authored patterns.
- **Safety**: unsupported advanced regex syntax now fails closed for variable regex operations instead of attempting cancellable backtracking.
- **Correctness**: fixed `torch.set` toggle semantics by reading the current torch state through `CameraManager.TorchCallback`; if Android cannot report the state, toggle now fails honestly and tells users to use explicit `on`/`off`.
- **Correctness**: torch actions now select a camera that actually reports flash availability instead of using the first camera id.

## v0.2.67 - 2026-06-15

Deep engineering, security, and UX audit pass.

- **Thread safety**: made `ArrayStore` concurrent-safe with `ConcurrentHashMap` to prevent `ConcurrentModificationException` when tasks run in parallel automation mode.
- **Thread safety**: upgraded `VariableStore` local scope maps to `ConcurrentHashMap` to prevent race conditions between concurrent coroutines reading/writing the same scope.
- **Thread safety**: marked `WiFiNetworkMonitor.lastState` and `ConnectivityMonitor.lastState` as `@Volatile` since `NetworkCallback` methods fire on binder threads.
- **Thread safety**: marked `CameraMicContextEvents` camera/mic callback fields as `@Volatile` to prevent races between `start()` and `stop()` on different threads.
- **Resource leak**: added `CameraMicContextEvents.stop()` call in `AutomationService.onDestroy()` to unregister `AppOpsManager` watchers that were previously leaked.
- **Data corruption**: fixed HTTP response `readBounded` to collect bytes into `ByteArrayOutputStream` before UTF-8 decode, preventing multi-byte character corruption when a character straddles an 8KB read boundary.
- **Correctness**: fixed `BrightnessAction` auto mode to set `SCREEN_BRIGHTNESS_MODE` to automatic instead of writing `-1` to the brightness value. Manual brightness values now explicitly set the mode to manual first.
- **Correctness**: fixed `ScreenTimeoutAction` to clamp the timeout value to 0–30 minutes, preventing `Long`-to-`Int` truncation on large values.
- **Correctness**: fixed `SunEventCalculator` DST offset to use the offset at the approximate event time instead of noon, preventing sunrise/sunset times from being off by 1 hour on DST transition days.
- **Correctness**: seeded `battery_level` and `charging` in `StateContextSourceImpl.seedInitialState()` from the sticky `ACTION_BATTERY_CHANGED` broadcast so battery-based profile conditions evaluate correctly immediately after service start.
- **Crash fix**: `FlowGraphCard` now uses `firstOrNull()` instead of `first()` for the profile node, preventing `NoSuchElementException` if graph data is corrupted.
- **Crash fix**: TTS `SayAction` now guards continuation resume with `AtomicBoolean` to prevent double-resume if TTS callbacks race.
- **Safety**: capped vibration duration to 10 seconds to prevent extended uncontrolled vibration.
- **Safety**: capped queued task depth per profile to 50 in QUEUED automation mode, preventing unbounded memory growth from rapid triggers.
- **Safety**: changed database backup WAL checkpoint from `FULL` to `TRUNCATE` for safer backup consistency.
- **Safety**: fixed notification button `PendingIntent` request codes to use hash-based IDs instead of `notifId * 10 + i`, preventing integer overflow for large notification IDs.
- **Memory**: `ShakeDetector` now uses `applicationContext` to prevent potential `Service`/`Activity` context leak.
- **UX**: fixed `disabledAlpha` modifier to use `Modifier.alpha()` instead of a semi-transparent black overlay, which broke disabled element appearance in light theme.
- **UX**: warning color in scene validation now uses warm amber/peach instead of green (tertiary), which was confusing since green implies success.
- **UX**: added `contentDescription` to navigation bar icons for screen reader accessibility.
- **Design system**: added `Radii.xxl` (18dp) token and `SemanticColor.warningDark`/`warningLight` to the design system. Replaced ~11 hardcoded `RoundedCornerShape(18.dp)` instances across all screens with the design token.

## v0.2.66 - 2026-06-15

Shell navigation and scene control polish.

- Reworked the app shell's navigation surface and the scene library's control layout and state handling.

## v0.2.65 - 2026-06-15

Scene editor state and destructive affordances.

- Clarified scene editor state transitions and destructive-action affordances; aligned the Variables surface with the same treatment.

## v0.2.64 - 2026-06-15

Form state and accessibility polish.

- Improved form state handling and accessibility across the active automation shell and the scene library, and fixed compact-width navigation layout.

## v0.2.63 - 2026-06-15

Release-polish pass.

- Added IME padding to the main Compose scaffold so focused forms have safer keyboard behavior.
- Reduced bottom-navigation crowding by showing labels only for the selected destination.
- Added confirmation before deleting global variables and preserved variable search/edit/delete dialog state across recreation.
- Made widget task rows explicit button-role targets with minimum row height and long-text ellipsis.
- Added button roles to clickable flow-graph nodes.
- Preserved task/profile/action editor drafts with saveable state across configuration changes.

## v0.2.62 - 2026-06-15

Action editor compatibility and UI polish.

- Aligned dynamic action form metadata with runtime argument keys for brightness, screenshots, file read/write/append/list, and HTTP GET/POST actions.
- Kept legacy saved-action keys working (`level`, `filename`, `variable`, `content`, and `body`) so older automations still prefill and execute correctly after the metadata correction.
- Replaced full-round badge geometry with bounded 8dp corners and removed the unused full-round radius token.
- Changed action/template/context picker lists from fixed heights to adaptive max-height constraints for better small landscape and split-screen behavior.
- Made checkbox action fields full-row switch targets with explicit switch role and on/off state descriptions.
- Added regression coverage for metadata field keys and legacy HTTP POST body handling.

## v0.2.61 - 2026-06-14

Security hardening, platform readiness, and new actions/functions.

- **Target SDK 36**: raised `targetSdk` from 35 to 36 for Android 16 platform compliance.
- **HTTP POST body bound**: POST bodies are now capped at 1 MB and use fixed-length streaming mode before the network connection opens.
- **Regex match timeout**: user-authored regex operations in variable expansion now have a 2-second wall-clock timeout to prevent ReDoS.
- **Network Security Config**: added platform-level scoping that blocks public-host cleartext while permitting LAN/private-range HTTP (forward-compat with Android 17 `usesCleartextTraffic` deprecation).
- **android:allowBackup=false**: explicitly declared for privacy-first posture.
- **Android 17 audio gating**: `sound.play` and `tts.speak` now fail honestly on Android 17+ when background audio requires a media FGS type the engine does not hold; capability registry updated.
- **Hilt shrinker cleanup**: removed stale `Hilt_OpenTaskerApp` and `dagger.hilt.android.HiltAndroidApp` keep rules from proguard-rules.pro.
- **Theme toggle**: added DataStore-backed System/Dark/Light theme preference with a toggle card in the Setup screen; wired into MainActivity and widget config.
- **Wake-on-LAN action** (`wol`): sends a magic packet to wake devices on the local network with MAC validation, configurable broadcast IP/port, and unit tests.
- **Date template function**: added `{{ value | date:'pattern' }}` for epoch-millis formatting with bounded patterns, Locale.ROOT output, and fail-closed rejection of invalid patterns or non-numeric input.
- **Registry-metadata parity test**: bidirectional contract test ensuring every runtime action has UI metadata and vice versa.
- **Action guard tests**: new `ActionGuardsTest` covering POST body cap, URI scheme allowlist, wait duration cap, HTTP policy, ping host validation, missing-argument failures, and WoL packet construction.

## v0.2.60 - 2026-06-14

- Fixed State context matching so battery, charging, headphones, and screen facts persist across partial broadcasts instead of replacing one another.
- Added State context aliases and fail-closed numeric predicate handling for malformed thresholds.
- Added `lintDebug` to the normal GitHub Actions build workflow.
- Fixed Event context matching so repeated identical one-shot events can retrigger profiles while level contexts keep activation/deactivation semantics.
- Fixed boot Event context truthfulness by routing manifest boot starts through `AutomationService` into a replay-safe `event=boot_completed` pulse, and removed unsupported SMS-received trigger advertising from the active event source.
- Removed the legacy parallel automation engine, second `automation.db` Room database, legacy Hilt provider module, dead minimal activity, shell-capable legacy action, and dead battery/geofence manifest receivers. Active app, WiFi, and time monitors now publish into core context bridges; rebuilt APKs shrank from 22,321,836 to 21,799,321 bytes (debug) and 2,107,361 to 2,041,684 bytes (release unsigned).
- Added configurable Run Log retention with short, standard, and extended presets. The standard default keeps 30 days or 1,000 entries, prunes on service/UI startup and hourly after inserts, and includes DAO pruning coverage.
- Added Setup-tab database backup and restore controls. Backups checkpoint and export the active Room database through Android's document picker; imported backups are validated, staged for the next startup, applied before Room opens, and roll back to the previous database if restore fails.
- Added Profiles-tab OpenTasker JSON bundle export/import. Exports use Android's document picker, imports preview schema/version/counts/warnings/capability requirements before confirmation, and imported profiles are always disabled for review.
- Added a Play distribution manifest policy gate that omits SMS and phone-state permissions, hides SMS setup, and marks the SMS action unsupported while keeping standard/F-Droid SMS behavior intact.

## v0.2.59 - 2026-05-05

Dependency modernization, visual flow, scene editor, and navigation polish.

- Added typed graph-node targets to the pure automation flow model so profile, context, task, action, and missing-reference nodes can route back to existing editors.
- Made Flow tab nodes selectable and wired them into the current profile/task/action/context edit dialogs, with stale-target feedback if the underlying Room data changes.
- Added first-class conditional action metadata to the flow graph so conditional steps render with `if ...` edge labels and compact conditional markers instead of being hidden inside generic action details.
- Added a compact, horizontally scrollable Flow lane overview for profile/context/enter/exit lanes as the first read-only canvas interaction before drag/drop editing.
- Added deterministic Flow graph accessibility summaries and node labels, then wired them into Compose semantics for screen readers and UI automation.
- Added Flow-tab mutation shortcuts for adding contexts to a graph profile and adding steps to enter/exit task lanes through the existing context and action pickers.
- Added Scene-tab element creation/editing for button, text, slider, and image controls, with tap and long-press task binding pickers plus removable element rows.
- Replaced the Scene card text-only preview with a scaled canvas projection that renders element positions and sizes against the scene dimensions.
- Added drag-to-move editing on the scaled Scene canvas, converting preview offsets back to bounded scene dp coordinates before updating Room.
- Shortened bottom navigation labels from `Inspector` to `Inspect` and `Run Log` to `Log` so compact navigation items align consistently.
- Upgraded Hilt/Dagger from `2.46` to the intermediate `2.52` line while leaving Kotlin, KSP, AGP, Room, and runtime startup wiring unchanged.
- Verified the Hilt batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and the F-Droid release profile.
- Upgraded Room from `2.6.1` to `2.8.4` on the existing `androidx.room` artifact line after the Kotlin/KSP/compiler batch; Room 3.0 remains a separate future migration because it uses the new `androidx.room3` group.
- Verified the Room batch with connected migration instrumentation tests on `SM-S938B`.
- Upgraded WorkManager from `2.9.1` to `2.11.2`; no active workers are registered yet, so this batch is dependency/build compatibility only and passed the standard dependency gate.
- Upgraded the stable Compose/AndroidX UI dependency set within the current API 35 / AGP 8.7 constraints: Compose BOM `2025.07.00` and Activity Compose `1.10.1`; newer Activity/Navigation lines are deferred because they require API 36 and AGP 8.9.1, while Compose BOM `2025.08.01+`, Hilt Navigation Compose `1.3.0`, and Lifecycle `2.9.x+` are deferred because they resolve Lifecycle lint checks that need a newer AGP/Kotlin analysis stack.
- Upgraded the runtime-support dependency subset to Core KTX `1.18.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.11.0`, and Gson `2.14.0`.
- Upgraded the compiler alignment set to Kotlin/Compose plugin `2.3.21` and KSP `2.3.7`, migrating Gradle configuration from deprecated `kotlinOptions` to `compilerOptions`.
- Resolved the earlier Kotlin `2.3.21`/KSP `2.3.7` blocker by moving Hilt/Dagger from `2.52` to `2.59.2` after the AGP 9 batch.
- Upgraded the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, while keeping target SDK `35`.
- Verified the AGP/API 36 batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`; the previous release R8 Kotlin metadata warnings are gone.
- Upgraded the API 36-unblocked AndroidX stable dependency set: Core KTX `1.18.0`, Compose BOM `2026.04.01`, Activity Compose `1.13.0`, Lifecycle `2.10.0`, Navigation Compose `2.9.8`, and Hilt Navigation Compose `1.3.0`.
- Verified the AndroidX follow-up with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Upgraded the AGP 9 compatibility stack to Gradle wrapper `9.4.1`, AGP `9.2.1`, Hilt/Dagger `2.59.2`, Kotlin/Compose plugin `2.3.21`, KSP `2.3.7`, and Kotlinx Serialization JSON `1.11.0`.
- Added temporary AGP 9 compatibility flags for the explicit Kotlin plugin path: `android.builtInKotlin=false` and `android.newDsl=false`; these keep the build green now but must be removed before AGP 10 by migrating to built-in Kotlin and Android Components/new DSL APIs.
- Verified the AGP 9 stack with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Migrated AGP 9 to built-in Kotlin and the new DSL by removing the explicit `org.jetbrains.kotlin.android` plugin, deleting the temporary AGP 9 opt-out flags, and replacing the deprecated androidTest asset source-set mutation.
- Verified the built-in Kotlin/new DSL migration with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Reviewed Room 3 and deferred migration because the new `androidx.room3:room3-*` artifact group is alpha-only (`3.0.0-alpha03`) and would touch both persisted databases plus migration tests.

## v0.2.58 - 2026-05-05

Tasker XML import UI and F-Droid release verification.

- Added a user-facing Tasker XML import flow to the Profiles screen using Android's document picker.
- The preview reads selected XML with a bounded 4 MB limit, parses it through the existing secure Tasker importer, and shows source counts, import counts, mapped/unsupported actions, migration warnings, and capability review notes.
- Confirmed imports now reuse the existing Room-backed OpenTasker bundle repository and create imported Tasker profiles disabled by default for review.
- Added a pure `TaskerImportPlanner` for preview summaries and disabled-by-default confirmed bundles with JVM coverage.
- Synced the draft F-Droid metadata to version `0.2.58` / code `60` and pinned it to release source commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`.
- Added `:app:verifyFdroidMetadata` plus CI/release workflow coverage so F-Droid metadata version fields, commit pinning, Gradle properties, preassemble hooks, changelog URL, and unsigned APK output stay in sync.
- Added `tools/verify-fdroid-release.ps1` for release-tag checks, F-Droid lint/build execution, and signature-agnostic APK payload comparison against a signed upstream APK.
- Verified local `fdroid lint` and WSL fdroidserver 2.4.4 `fdroid build --no-tarball com.opentasker.app:60` with Java 17 and Android SDK 35.

## v0.2.57 - 2026-05-05

Calendar and sun device smoke evidence.

- Added `tools/collect-calendar-sun-evidence.ps1` to capture adb calendar/sun smoke evidence.
- The harness launches OpenTasker, optionally grants Calendar access, captures package/service/provider evidence, and can require Calendar permission, CalendarProvider access, and foreground `AutomationService` state.
- Verified the debug app on API 36 device `SM-S938B` with evidence `build/device-evidence/calendar-sun/20260505-152622`.
- The smoke run confirmed `READ_CALENDAR` was granted, CalendarProvider calendar and instance queries succeeded, and `AutomationService` was foreground after app launch.
- Patched the new adb evidence scripts for Windows PowerShell 5.1 process-argument compatibility.

## v0.2.56 - 2026-05-05

Calendar and sun context presets.

- Added reusable Event context presets for during-meeting, before-meeting, all-day busy, at sunrise/sunset, and offset sunrise/sunset windows.
- Added preset controls to the Event context editor when `event=calendar`, `event=sunrise`, or `event=sunset` is selected.
- Presets preserve unrelated filters such as calendar allowlists while replacing the state/window fields they own.
- Added JVM coverage for calendar preset coverage, sun offset windows, and preset application behavior.

## v0.2.55 - 2026-05-05

NFC write-helper flow.

- Added an NFC tag write session that arms a one-time NDEF text-record write and consumes the next scanned tag while armed.
- Supports writable and formattable NDEF tags with size/read-only failure messages surfaced through the write session.
- Added an NFC write helper card to the Event context editor when `event=nfc` is selected.
- MainActivity now gives armed writes priority over normal NFC trigger publication.
- Added JVM coverage for NFC write-label normalization and payload-size estimation.

## v0.2.54 - 2026-05-05

Locale plugin validation harness.

- Added `tools/validate-locale-plugin.ps1` to capture adb evidence for an installed Locale/Tasker-compatible plugin package.
- The harness records package path, `dumpsys package`, resolver command output, contract-action checks, and a structured `summary.json`.
- Supports required setting/condition contract checks and an optional synthetic `REQUEST_QUERY` broadcast to OpenTasker.
- Documented the harness as the repeatable sample-plugin validation path for X3 follow-up testing.

## v0.2.53 - 2026-05-05

Locale request-query event handling.

- Added a foreground-runtime listener for Locale `ACTION_REQUEST_QUERY` broadcasts from condition plugins.
- Emits sanitized `event=locale_request_query` context events with the requested condition activity class and deterministic bundle JSON.
- Rejects blank or malformed activity class names and reuses primitive-only bundle sanitization for request-query payloads.
- Added package visibility for `REQUEST_QUERY` and JVM coverage for request-query event construction.

## v0.2.52 - 2026-05-05

Locale plugin configuration result handling.

- Added explicit edit-setting and edit-condition intent resolution for Locale-compatible plugin configuration activities.
- Fails closed when a plugin package exposes no matching configuration activity or multiple ambiguous activities.
- Added guarded configuration result parsing that accepts only primitive bundle values and emits deterministic JSON plus bounded blurb text.
- Reused the same string-only bundle safety policy for plugin-returned configuration data, rejecting null, nested, parcelable, and arbitrary object values.
- Added JVM coverage for deterministic bundle JSON encoding and primitive-only configuration result sanitization.

## v0.2.51 - 2026-05-05

Locale condition unknown-state handling.

- Added a bounded in-memory last-known-state cache for Locale condition plugin query results.
- Resolves `RESULT_CONDITION_UNKNOWN` to the last known state for the same plugin package and guarded bundle.
- Treats unknown condition results without history as unsatisfied instead of exposing an ambiguous success path.
- Added JVM coverage for last-known fallback, no-history behavior, and bundle-scoped cache keys.

## v0.2.50 - 2026-05-05

Locale condition plugin query baseline.

- Added `plugin.locale.query` to issue explicit `QUERY_CONDITION` ordered broadcasts to Locale/Tasker-compatible condition plugin receivers.
- Added guarded parsing for Locale condition result codes: satisfied, unsatisfied, unknown, and unrecognized-result fail-closed handling.
- Hardened Locale setting execution to resolve a single explicit receiver component before dispatch instead of broadcasting to an entire package.
- Extended Locale plugin discovery metadata with setting/condition receiver permissions for future disclosure UI.
- Added package-visibility queries for Locale execution receivers and JVM coverage for condition result-code mapping.

## v0.2.49 - 2026-05-05

Day schedule polish.

- Added a shared `DaySchedule` parser for day contexts with canonical weekday order, weekday/weekend/daily aliases, numeric day tokens, and inclusive day ranges such as `MON-FRI`.
- Updated Day context matching to use the shared parser so imported, typed, and UI-created schedules evaluate consistently.
- Replaced raw Day context editing with quick presets, individual day toggles, canonical save output, and validation that blocks invalid day schedules before saving.
- Improved profile and inspector summaries so Day contexts show human-readable labels such as `Weekdays`, `Weekends`, or `Every day`.
- Added JVM coverage for day aliases, wrapped ranges, numeric tokens, and ContextMatchEvaluator day matching.

## v0.2.48 - 2026-05-05

Post-reconnect unplugged evidence checks.

- Extended `tools/collect-location-evidence.ps1` with `-RequireRecentUnpluggedHistory`, `-MinimumUnpluggedHistorySeconds`, and `-MaximumUnpluggedHistoryAgeMinutes` for workflows where USB ADB is unavailable while the phone is unplugged.
- Added recent unplugged interval parsing from `dumpsys battery` power and battery-change history so post-reconnect runs can fail closed on duration.
- Captured post-reconnect API 36 evidence `build/device-evidence/location/20260505-125057`; the device history showed a recent unplugged interval from `2026-05-05T12:48:23.598` to `2026-05-05T12:50:14.389`, about 111 seconds, which was below the 600-second roadmap threshold.
- Captured follow-up API 36 evidence `build/device-evidence/location/20260505-143254`; the recent unplugged interval from `2026-05-05T14:21:53.052` to `2026-05-05T14:32:08.107` lasted 615.055 seconds and satisfied the 600-second post-reconnect history gate with GPS/network provider cadence evidence present.

## v0.2.47 - 2026-05-05

Location durability evidence gates.

- Extended `tools/collect-location-evidence.ps1` with structured battery parsing for plug state, charge counter, current, voltage, and sample deltas.
- Added `-RequireUnpluggedSample` so future battery evidence fails closed if the device is connected to USB/AC/wireless/dock power before or after the sample.
- Added `-RequireProviderCadenceEvidence` so Location evidence can assert that `dumpsys location` contains expected OpenTasker GPS/network cadence registrations or historical aggregates.
- Verified the new collector gates on connected API 36 device `SM-S938B` with evidence `build/device-evidence/location/20260505-120448`; the run correctly detected USB power and GPS/network cadence, so it is tooling evidence only, not an unplugged battery reliability claim.

## v0.2.46 - 2026-05-05

Background Location delivery evidence.

- Verified the installed/enabled `Location evidence log` template on connected API 36 device `SM-S938B` with the app sent home.
- Used a shell-owned GPS test provider to deliver the template coordinates while `AutomationService` stayed foreground with `specialUse|location`.
- Captured Room evidence under `build/device-evidence/location/20260505-085413` showing a successful `Location evidence log Task` run log after evidence collection started.
- Extended `tools/collect-location-evidence.ps1` so `-RequireRunLogMessagePattern` can match the recent run-log message, task name, or the triggered task's action JSON.

## v0.2.45 - 2026-05-05

Location event evidence assertions.

- Extended `tools/collect-location-evidence.ps1` to snapshot the debug app's Room database through `run-as`.
- Writes `room-summary.json` with profile, task, and recent run-log counts/details when local Python/SQLite support is available.
- Added optional `-RequireRunLogMessagePattern` and `-RequireLogcatPattern` checks so a background Location run can fail closed unless execution evidence is present.
- Kept database capture non-fatal for non-debug or non-`run-as` builds while preserving foreground-service validation.

## v0.2.44 - 2026-05-05

Location evidence template.

- Added a disabled-by-default `Location evidence log` profile template for configuring a test radius with latitude, longitude, radius, max-accuracy, and dwell slots.
- The template installs as a normal Location context plus a log action, so future device smoke work can verify actual Location event delivery without manual context construction.
- Kept the template setup-required with explicit foreground/background location and device Location prerequisites.
- Added JVM coverage for the template catalog entry and generated Location context config.

## v0.2.43 - 2026-05-05

Location device evidence harness.

- Added `tools/collect-location-evidence.ps1` to collect adb-backed foreground-service, permission, location, logcat, and battery snapshots for Location/geofence verification.
- The harness writes timestamped JSON summaries and raw evidence files under ignored `build/device-evidence/location/`.
- Supports optional permission grants and an app-to-home sample to verify the foreground automation service remains active while the app is backgrounded.
- Verified the harness against connected API 36 device `SM-S938B`; a 10-second home/background sample kept `AutomationService` foreground with `specialUse|location` and recorded battery snapshots.

## v0.2.42 - 2026-05-05

Foreground service launch repair.

- Started `AutomationService` from `MainActivity` using `ContextCompat.startForegroundService`.
- Kept boot receiver startup intact while ensuring app launch also activates the automation engine.
- Logged foreground-service startup failures from the activity path.
- Added a JVM source contract test for the activity-to-service startup path.
- Verified on a connected API 36 device that app launch starts the foreground service with the `specialUse|location` type after foreground/background location permissions and device location are enabled.

## v0.2.41 - 2026-05-05

Location policy disclosures.

- Added shared Android location policy disclosure copy for Setup and Context Inspector.
- Explains that Android 11+ background location is granted from app settings instead of the foreground permission dialog.
- Explains that approximate foreground access limits background precision.
- Adds Android 14+ foreground-service location gating copy when foreground and background location prerequisites are ready.
- Added JVM coverage for the location disclosure policy text.

## v0.2.40 - 2026-05-05

Geofence cadence tuning.

- Added a balanced location provider request policy for the FOSS `LocationManager` source.
- Requests GPS updates less aggressively than network updates to reduce baseline location polling pressure.
- Added cadence metadata to the waiting-for-location setup event for inspector/debug visibility.
- Extended location setup rechecks from 30 seconds to 60 seconds.
- Added JVM coverage for cadence defaults and validation.

## v0.2.39 - 2026-05-05

Geofence dwell cleanup.

- Added profile-scoped persisted dwell-state cleanup for deleted profiles.
- Cleared a profile's persisted Location dwell keys when its context list changes, preventing removed or reindexed geofences from retaining stale timers.
- Routed the active automation view model through the application context so profile edits can maintain location dwell storage.
- Kept enable/disable and profile metadata edits from resetting dwell timers when contexts are unchanged.

## v0.2.38 - 2026-05-05

Context Inspector dwell detail.

- Added per-profile Location observation enrichment in the Context Inspector using the same persisted dwell state as runtime matching.
- Added location check rows that show inside, outside, accuracy-blocked, or unknown dwell status with elapsed time against configured dwell duration.
- Kept source cards raw while profile check rows display geofence-specific dwell metadata for the selected profile/context.
- Added regression coverage for transformed Location observations during profile inspection.

## v0.2.37 - 2026-05-05

Persisted geofence dwell state.

- Added profile/context-scoped Location dwell keys with config hashes so edited geofences do not reuse stale inside-since state.
- Added a pure dwell-state tracker that preserves `insideSinceEpochMs` across accurate inside samples and clears it when a sample leaves the radius.
- Persisted dwell state in app-local preferences so dwell timers can survive process restarts.
- Wired ProfileMatcher to enrich Location context events with persisted dwell metadata before FOSS geofence evaluation.
- Added regression coverage for first-entry persistence, dwell carry-forward, outside clearing, low-accuracy preservation, and stable key hashing.

## v0.2.36 - 2026-05-05

Live FOSS location source baseline.

- Added a registered `location` context source backed by Android `LocationManager`, with GPS/network providers and last-known-fix seeding.
- Added fail-closed source events for missing permissions, disabled providers, unavailable services, and source errors.
- Declared the Android 14+ location foreground-service contract while keeping background geofence reliability gated behind background location and device verification.
- Updated Setup and Context Inspector copy for foreground, approximate, precise, and background location states.
- Added regression coverage for location event metadata, runtime source registration, and manifest foreground-service location declarations.

## v0.2.35 - 2026-05-05

Template regex policy.

- Made regex-like template functions (`match`, `matches`, `regex`, and `replace`) explicitly unsupported.
- Preserved fail-closed behavior by keeping the original template token when regex-like functions are used.
- Kept existing bounded legacy `%var(regex:...)` behavior separate from the new template engine.
- Added regression coverage for explicit regex-template rejection.

## v0.2.34 - 2026-05-05

Template condition expansion.

- Added bounded `{{ ... }}` expansion to action conditions before legacy predicate evaluation.
- Preserved legacy `%var` condition behavior and applied template expansion only when a condition contains template tokens.
- Made template condition warnings fail closed by skipping the action instead of running on an unsafe or unknown expression.
- Added regression coverage for template conditions, JSON path conditions, and warning-based condition skips.

## v0.2.33 - 2026-05-05

Per-expression template diagnostics.

- Persisted bounded per-expression template trace lines beneath action trace summaries.
- Parsed template trace lines back into structured run-log diagnostics with argument name, source scope, expression, value, and optional warning.
- Rendered individual template expressions in Run Log trace rows, including source scope and redacted values for sensitive arguments.
- Added regression coverage for persisted template trace lines, sensitive expression redaction, and structured parsing.

## v0.2.32 - 2026-05-05

Template run-log diagnostics.

- Parsed template expansion details out of action trace messages into structured run-log diagnostics.
- Added per-step expanded argument summaries and template warning counts to the Run Log UI.
- Preserved ordinary parenthesized failure messages while recognizing generated template detail suffixes.
- Added regression coverage for parsing expanded argument details, warning counts, and normal parenthesized messages.

## v0.2.31 - 2026-05-05

Runtime template argument expansion.

- Wired action argument expansion through the bounded `TemplateExpressionEngine` after legacy `%var` expansion.
- Added `VariableStore` template snapshots for task-local, event, global, and array scopes.
- Added sanitized expanded-argument summaries, template warnings, and per-argument expansion traces to `ActionExecutionTrace`.
- Redacted sensitive argument names such as tokens, keys, secrets, cookies, and passwords from run-log summaries.
- Added regression coverage for runtime template expansion, event scope lookup, array lookup, warning propagation, and summary redaction.

## v0.2.30 - 2026-05-05

Template expression engine baseline.

- Added a pure `TemplateExpressionEngine` for bounded `{{ ... }}` template expansion.
- Added task/event/global scope precedence, explicit scope prefixes, array indexing/count/join support, and JSON path reads from scoped values.
- Added safe string and math pipe functions with traces and warnings for debugging expansion behavior.
- Added fail-closed limits for template length, expression count, function chains, resolved value size, output size, and unknown functions.
- Documented the template expression baseline and added regression coverage for scope, defaults, string/math transforms, JSON paths, arrays, and expansion limits.

## v0.2.29 - 2026-05-05

FOSS geofence evaluator baseline.

- Added a pure `FossGeofenceEvaluator` with Haversine distance, radius checks, optional max accuracy, and dwell-time evaluation.
- Wired active Location context matching through the FOSS evaluator without adding Play Services dependencies.
- Added Location editor fields for max accuracy and dwell seconds.
- Reused the same evaluator for the older geofence trigger distance path and added regression coverage for radius, accuracy, dwell, and active context matching.

## v0.2.28 - 2026-05-05

Profile sharing manifest baseline.

- Added a pure profile-share manifest model for OpenTasker bundles with stable slugs, counts, trust state, and submission metadata.
- Added safety findings for unsupported/setup-required actions, schema warnings, lossy import warnings, and missing screenshots.
- Added GitHub Discussions submission markdown generation without adding network publishing or verified-template claims.
- Documented the sharing baseline and added unit coverage for manifest counts, blockers, slug validation, and submission text.

## v0.2.27 - 2026-05-05

Termux script readiness baseline.

- Added a gated `script.termux.run` action with metadata and a runtime failure path that does not execute scripts.
- Added Termux and Termux:Tasker package visibility and optional setup status detection.
- Added Setup checklist copy for the Termux script bridge while excluding it from required readiness progress.
- Documented the non-executing scripting baseline and added tests for package constants, manifest queries, and capability gating.

## v0.2.26 - 2026-05-05

Shizuku readiness baseline.

- Added package visibility and runtime status detection for the Shizuku manager without linking the Shizuku API.
- Added an optional Setup checklist row for Shizuku power mode that is excluded from required readiness progress.
- Added elevated-action hints for Shizuku candidates while keeping restricted actions blocked.
- Documented the safe readiness scope and added tests for status, action hints, and manifest package visibility.

## v0.2.25 - 2026-05-05

Scene library baseline.

- Added a Room-backed Scenes tab that lists persisted scenes and supports safe scene creation/deletion.
- Added scene validation for positive dimensions, empty scenes, element bounds, and missing tap/long-press task bindings.
- Added scene cards with canvas summaries, element/binding previews, overlay-permission readiness status, and validation messages.
- Documented the scene baseline and updated roadmap/version metadata for L2.
- Added unit coverage for scene validation warnings, geometry errors, missing task references, and valid bounded elements.

## v0.2.24 - 2026-05-05

Visual flow baseline.

- Added a pure automation flow graph model that maps profiles to contexts, enter/exit tasks, actions, edges, and warnings.
- Added an optional Flow tab that renders read-only per-profile graphs from the active Room data without replacing the list/form editor.
- Added graph warnings for missing tasks, empty contexts, and empty task lanes.
- Documented the visual flow baseline and updated roadmap/version metadata for L1.
- Added unit coverage for enter chains, exit chains, missing task references, and empty-context warnings.

## v0.2.23 - 2026-05-05

Dependency modernization baseline.

- Added a Gradle version catalog for Android, Kotlin, Compose, Room, WorkManager, Coroutines, Hilt, Gson, and test dependency versions.
- Converted root and app Gradle plugin/dependency declarations to catalog aliases without changing dependency versions.
- Documented the staged dependency modernization order, risk rules, and verification gates for future upgrade batches.
- Updated F-Droid draft metadata and version metadata for the centralized dependency baseline.

## v0.2.22 - 2026-05-05

F-Droid readiness baseline.

- Added an `openTaskerDistribution=fdroid` Gradle profile without changing existing Android variant names.
- Pinned Android build tools to `35.0.0` and exposed `BuildConfig.DISTRIBUTION`.
- Added `verifyFdroidReadiness` to block common proprietary dependency families from the F-Droid profile.
- Added CI coverage for the F-Droid release profile.
- Added F-Droid readiness docs and a draft fdroiddata metadata file for `com.opentasker.app`.

## v0.2.21 - 2026-05-05

Tasker XML import baseline.

- Added a secure Tasker XML parser that converts common task/profile/variable structures into an OpenTasker JSON bundle.
- Added a migration report model with mapped actions, unsupported Tasker action placeholders, skipped profile/context warnings, variable counts, and scene exclusions.
- Added an explicit unsupported imported Tasker action runtime failure path and capability metadata.
- Documented the supported import surface and updated roadmap/README/version metadata for X10.
- Added regression tests for action mapping, unsupported action preservation, profile skipping, variable import, scene warnings, and Wait conversion.

## v0.2.20 - 2026-05-05

Calendar and sun trigger baseline.

- Added a local CalendarProvider event bridge that emits redacted `event=calendar` metadata for busy current or upcoming events.
- Added sunrise/sunset matching with user-provided latitude/longitude, offset minutes, and bounded trigger windows.
- Added Calendar access onboarding, Event context editor fields for calendar/sun filters, and Inspector setup copy.
- Promoted the meeting-mode calendar template from planned to setup-required installation.
- Updated roadmap/docs/version metadata and regression tests for calendar filtering, sun calculations, and template installation.

## v0.2.19 - 2026-05-05

NFC tag trigger baseline.

- Added an NFC event bridge that accepts tag/tech/NDEF discovery intents and emits `event=nfc` context events with normalized tag IDs.
- Routed cold-start and foreground NFC intents through `MainActivity` into the existing Event context source.
- Added NFC tag ID filtering to Event contexts and exposed an NFC tag ID field in the context editor.
- Promoted the nightstand NFC sleep template from planned to setup-required installation.
- Updated inspector/setup copy, roadmap/docs/version metadata, and regression tests for NFC matching and template installation.

## v0.2.18 - 2026-05-05

Notification listener trigger baseline.

- Added a `NotificationListenerService` event bridge that emits `event=notification` context events without logging notification text.
- Merged notification events into the existing Event context source for profile matching and context inspection.
- Added package allowlists, title/body filters, bounded regex matching, and fail-closed invalid-regex behavior for Event contexts.
- Expanded the context editor for notification event filters and updated docs/version metadata for the X7 baseline.

## v0.2.17 - 2026-05-05

Context inspector baseline.

- Added an Inspector tab with live registered context-source health, latest observed values, setup status, and source errors.
- Added per-profile match explanations that show whether enabled profiles currently match and which context blocks activation.
- Added a reusable context-inspection model with tests for source health, missing events, all-context matching, and inverted contexts.
- Updated roadmap, project notes, README metadata, and app version metadata for the X6 baseline.

## v0.2.16 — 2026-05-04

Automation mode baseline.

- Added per-profile automation modes: single, restart, queued, and parallel.
- Added a Room v1-to-v2 migration that persists `automationMode` on profiles.
- Added profile editor mode selection and profile cards showing the current mode.
- Updated `AutomationService` dispatch so re-triggers can be skipped, restarted, queued, or run in parallel.
- Added unit coverage for profile entity automation-mode round trips and legacy fallback.

## v0.2.15 — 2026-05-04

External automation target baseline.

- Added a permission-scoped exported receiver for documented external automation intents.
- Added external actions to run tasks, enable/disable profiles, query automation status, and pass task variables.
- Persisted external task runs to the Room run log with action trace summaries.
- Added manifest permission strings and security documentation for external callers.
- Added unit coverage for external variable-name validation and documented variable extra names.

## v0.2.14 — 2026-05-04

Locale plugin host baseline.

- Added Locale/Tasker-compatible setting plugin dispatch through a new `plugin.locale.fire` action.
- Added explicit package validation, string-only JSON bundle decoding, bundle size limits, blurb handling, and timeout wrapping.
- Added plugin discovery metadata for Locale edit-setting/edit-condition packages and requested permission disclosure.
- Added manifest package visibility queries for Locale-compatible plugin discovery.
- Documented the supported plugin host surface and added parser/trust-boundary unit tests.

## v0.2.13 — 2026-05-04

Open JSON bundle baseline.

- Added schema-versioned OpenTasker JSON bundle models for profiles, tasks, actions, contexts, variables, scenes, and metadata.
- Added deterministic export ordering and capability requirement metadata for setup-required or unsupported actions.
- Added import planning/reporting with warnings for unsupported actions and lossy missing-reference handling.
- Added Room-backed export/import repository logic with task ID remapping, variable upsert, profile remapping, and scene element link remapping.
- Documented the v1 JSON bundle format and added unit coverage for sorting, capability metadata, validation, and JSON round trips.

## v0.2.12 — 2026-05-04

Profile template baseline.

- Added an on-device profile template catalog with eight roadmap-backed starter patterns.
- Added slot substitution for template names, context configs, and action arguments.
- Added a Compose template picker and slot form that installs templates as disabled profiles with starter tasks.
- Gated planned calendar, NFC, and external-intent templates so they are visible but cannot create broken profiles yet.
- Added unit coverage for catalog completeness, unsupported-action gating, slot expansion, and planned-template blocking.

## v0.2.11 — 2026-05-04

Public documentation truthfulness pass.

- Corrected README action counts and active runtime-context claims to match the compiled APK.
- Clarified that plugin hosting, Tasker XML import/export, day schedules, and location/geofence runtime support are planned or still being hardened rather than shipped.
- Updated architecture docs to describe the current foreground-service trigger monitors and action capability gates.
- Removed stale audit/checkpoint documents that overclaimed completion against older source snapshots.

## v0.2.10 — 2026-05-04

Regression-test hardening pass.

- Hardened cron step/range parsing so malformed expressions fail closed instead of throwing.
- Added tests for malformed cron steps and valid minute/hour cron matching.
- Added tests for variable scope shadowing and missing-variable expansion.
- Updated README/roadmap metadata for the expanded regression coverage.

## v0.2.9 — 2026-05-04

Run log tracing baseline.

- Added action execution traces with index, label, action type, duration, status, and message.
- Persisted summarized action traces in task run-log messages.
- Expanded run-log cards to show multi-line action trace summaries.
- Added unit coverage for trace summary formatting.

## v0.2.8 — 2026-05-04

Capability gating baseline.

- Added a central action capability registry for supported, setup-required, and unsupported actions.
- Annotated task action rows and action picker cards with setup/unsupported status.
- Disabled unsupported privileged or unimplemented actions in the add-action flow.
- Added warning copy in action configuration dialogs for actions that require setup.
- Added unit coverage for capability gating defaults.

## v0.2.7 — 2026-05-04

Runtime registry and stub-failure hardening pass.

- Registered built-in action implementations and context sources during app startup.
- Aligned runtime action IDs with the action metadata IDs saved by the Compose editor.
- Replaced success-shaped action stubs with real behavior where practical and explicit unsupported failures where Android requires privileged access.
- Implemented notification, intent launch, SMS send, volume, media-key, HTTP POST, and HTTPS download execution paths.
- Removed unused placeholder context source files and stopped silently swallowing application-context polling errors.
- Added unit coverage to ensure every UI action metadata ID has a runtime action implementation.

## v0.2.6 — 2026-05-04

App-open trigger hardening pass.

- Removed the unused plain background `AppOpenService` and its manifest entry.
- Added a foreground-service-owned `AppUsageMonitor` that polls `UsageStatsManager` only when usage access is granted.
- Added opened/closed `AppEvent` dispatch when the foreground package changes.
- Shared usage-access detection between setup UI and the app-open monitor.
- Added focused unit coverage for foreground package selection.
- Updated README/roadmap metadata for app-open monitoring.

## v0.2.5 — 2026-05-04

WiFi trigger hardening pass.

- Replaced the manifest `CONNECTIVITY_CHANGE` receiver with a lifecycle-owned `ConnectivityManager.NetworkCallback`.
- Added WiFi event dispatch from the foreground automation service with duplicate-state suppression.
- Added Android 13 nearby WiFi devices permission metadata and setup checklist coverage.
- Added SSID normalization tests for quoted and unknown platform values.
- Updated README/roadmap metadata for platform-safe WiFi monitoring.

## v0.2.4 — 2026-05-04

Exact alarm hardening pass.

- Removed `USE_EXACT_ALARM` so OpenTasker no longer declares the alarm-clock/calendar-only permission.
- Added an app-owned time tick scheduler that uses exact `AlarmManager` delivery when allowed and inexact `setWindow()` fallback when exact alarms are denied.
- Replaced the manifest `TIME_TICK` dependency with an internal scheduled receiver and exact-alarm permission-change rescheduling.
- Added focused unit coverage for minute-boundary scheduling.
- Updated setup text and README/roadmap metadata for exact-alarm fallback behavior.

## v0.2.3 — 2026-05-04

Permission onboarding pass.

- Added a Setup tab with live status for Android runtime permissions and special access gates.
- Added direct request/open-settings actions for notifications, exact alarms, battery optimization, usage access, notification access, overlay access, foreground/background location, Bluetooth, SMS, and DND access.
- Added Bluetooth scan permission metadata for Android 12+ Bluetooth setup.
- Updated README/version metadata for the setup checklist.

## v0.2.2 — 2026-05-04

Active UI reintegration pass.

- Replaced the launcher-only status screen with a live Compose management UI.
- Added profile creation, editing, enable/disable toggling, deletion, and context attachment backed by Room.
- Added task creation, editing, deletion, and action add/edit/delete flows driven by the action metadata registry.
- Restored run-log browsing inside the active APK.
- Registered built-in action metadata during app startup so dynamic action forms are populated.
- Updated README/version metadata to reflect the active UI state.

## v0.2.1 — 2026-05-04

Production hardening pass.

- Fixed Windows and Linux Gradle bootstrap scripts so builds work from paths containing `--`.
- Aligned app version metadata and README badge to the shipped APK version.
- Re-enabled release minification and resource shrinking while keeping unsigned release builds possible without local secrets.
- Consolidated release CI and added a push/PR build workflow.
- Removed tracked local build artifacts and machine-specific configuration from the repository.
- Replaced broken Hilt runtime entrypoints with the active non-Hilt application singleton wiring.
- Hardened shell, intent, file, network, notification, settings, geofence, receiver, backup, and JSON parsing paths.
- Added Room schema export and focused validation unit tests.
- Improved shared Compose component semantics and light-theme error contrast.

## v0.2.0 — 2026-05-04

Full UI layer with database integration and action editor.

- **Database integration:** Room DAOs with StateFlow live updates for profiles and tasks
- **Profile CRUD:** Create, edit, delete profiles with persistence
- **Task CRUD:** Create, edit, delete tasks with action lists
- **Action editor:** Dynamic form generation for registered action definitions based on metadata registry
- **Context picker:** Multi-select context families with predicate configuration (app, time, day, location, state, event)
- **Action metadata system:** Comprehensive metadata for all built-in actions with field types and validation
- **Task list screen:** Dedicated view to browse and manage all tasks
- **Profile enable/disable toggle:** Toggle profiles on/off with database update
- **Gradle 8.9 toolchain:** Updated from 8.7 for AGP 8.7.2 compatibility
- **Lint baseline:** Suppressed MissingPermission and CoarseFineLocation warnings

## v0.1.0 — 2026-05-03

Initial scaffold.

- Project skeleton: Kotlin 2.0 + Jetpack Compose + Material 3
- Core data model: Profile / Context / Task / Action / Scene / Variable
- AMOLED-black default theme
- Architecture document (`docs/ARCHITECTURE.md`)
- Roadmap (`ROADMAP.md`) tracking parity with Tasker feature surface
- MIT license, shields.io badges
