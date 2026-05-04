# OpenTasker Roadmap

Parity-with-Tasker tracker. Items are grouped by milestone.

## v0.1.x — Foundation ✅ COMPLETE
- [x] Repo + license + docs scaffold
- [x] Core data model (Profile / Context / Task / Action / Scene / Variable)
- [x] Room persistence layer + migrations
- [x] `AutomationService` foreground service (operational)
- [x] Compose nav graph: Profiles / Tasks / Scenes / Vars / Settings
- [x] AMOLED + Catppuccin Mocha + light theme switcher
- [x] Profile list + create/edit screens (UI scaffolded)
- [x] Action library tier 1 (43 actions: settings, app, file, net, media, system)
- [x] Real context sources: App, Time, Day, State, Event
- [x] Profile matcher + task runner full flow
- [x] Run log model + DAO + screen

## v0.2.x — Database Integration & CRUD
- [ ] Profile list + load from DB, live updates
- [ ] Profile create/edit with DB save
- [ ] Task CRUD with dynamic action editor
- [ ] Context picker UI (select type, configure predicates)
- [ ] Variable manager (create/edit/delete globals)
- [ ] Run log live updates (StateFlow per task)

## v0.3.x — Action Editor & UX
- [ ] Dynamic action argument form builder
- [ ] Action search/filter UI
- [ ] Drag-reorder actions in task editor
- [ ] Quick-launch widgets (task shortcuts)
- [ ] Batch operations (enable/disable multiple profiles)

## v0.4.x — Tasker Compat
- [ ] Import `.prj.xml` / `.tsk.xml` / `.prf.xml` / `.scn.xml`
- [ ] Best-effort action mapping table
- [ ] Migration report screen
- [ ] Export profile as XML

## v0.5.x — Advanced Features
- [ ] Location context refinement (Google Play Services geofence)
- [ ] Plugin SDK stability + sample plugin
- [ ] Custom script execution (JavaScript, shell)
- [ ] Notification listener integration (event context)
- [ ] Multi-profile collision handling (cooldowns, queue)

## v1.0 — Public Release
- [ ] Full action library (target ~100 actions)
- [ ] Signed release on F-Droid
- [ ] Documentation site
- [ ] Community translation support


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
