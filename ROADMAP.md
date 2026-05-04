# OpenTasker Roadmap

Parity-with-Tasker tracker. Items are grouped by milestone.

## v0.1.x — Foundation
- [x] Repo + license + docs scaffold
- [x] Core data model (Profile / Context / Task / Action / Scene / Variable)
- [ ] Room persistence layer + migrations
- [ ] `AutomationService` foreground service skeleton
- [ ] Compose nav graph: Profiles / Tasks / Scenes / Vars / Settings
- [ ] AMOLED + Catppuccin Mocha + light theme switcher
- [ ] Profile list + create/edit screens

## v0.2.x — Trigger Engine
- [ ] Time context (clock-based, repeating)
- [ ] Day context (weekly schedule)
- [ ] Application context (foreground app — UsageStats)
- [ ] State contexts: battery, headphones, charging, screen on/off, wifi connected
- [ ] Event contexts: SMS received, notification posted, boot completed
- [ ] Context match engine (AND across contexts within a profile)

## v0.3.x — Action Library (Tier 1, ~50 actions)
- [ ] Settings: WiFi / Bluetooth / Mobile data / Brightness / Volume / Airplane / Location
- [ ] Notifications: Notify / Notify cancel / TTS / Vibrate / Sound
- [ ] Files: Read / Write / Append / Copy / Move / Delete / List
- [ ] Net: HTTP request / Download / Ping
- [ ] Media: Music play/pause/skip / Camera photo / Screen record
- [ ] App: Launch / Kill / Go home / Take screenshot
- [ ] Variables: Set / Clear / Math / Split / Join / Convert

## v0.4.x — Tasks & Flow Control
- [ ] Task editor (drag-reorder actions)
- [ ] Flow: If / Else / End-If / For / End-For / Goto / Stop / Wait / Wait Until
- [ ] Variable expansion (`%name`, `%name(#)`, `%name(<arr>)`)
- [ ] Local vs global variable scope
- [ ] Run log + per-action timing

## v0.5.x — Scenes
- [ ] Scene editor canvas (Compose)
- [ ] Elements: Button / Text / EditText / CheckBox / Slider / Image / Web / Map / Spinner
- [ ] Element actions tied to tasks
- [ ] Overlay window (`SYSTEM_ALERT_WINDOW`) rendering

## v0.6.x — Plugin SDK
- [ ] Public AIDL contract (drop-in compatible with `com.twofortyfouram.locale.intent.action.EDIT_*` where possible)
- [ ] Sample plugin app
- [ ] Plugin discovery + permission handling

## v0.7.x — Tasker Compat
- [ ] Import `.prj.xml` / `.tsk.xml` / `.prf.xml` / `.scn.xml`
- [ ] Best-effort action mapping table
- [ ] Migration report screen

## v1.0 — Public Release
- [ ] Full action library (target ~150 actions)
- [ ] Signed release on F-Droid
- [ ] Documentation site
