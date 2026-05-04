# OpenTasker Roadmap

Parity-with-Tasker tracker + FOSS-first feature roadmap. Items grouped by milestone and priority (based on L1 research conducted May 2026).

---

## v0.3.0 — Execution Engine & Run Log ⏳ IN PROGRESS

**Release goal:** Complete profile execution pipeline + run log viewer with cooldown enforcement.

### Core Execution (Phase 3, v0.3.0)
- [x] AutomationService foreground service with profile loading
- [x] ProfileMatcher context subscription + state change emission
- [x] TaskRunner integration: execute tasks on profile activation/deactivation
- [x] RunLogEntry database persistence (task execution history)
- [x] RunLogScreen with live StateFlow updates
- [x] Cooldown logic: prevent profile re-trigger within N minutes
- [ ] **BUILD FIX:** Resolve Gradle wrapper issue (PowerShell path parsing with `--` in username)
- [ ] **TESTING:** End-to-end execution test on device (context match → task run → log entry)
- [ ] **RELEASE:** Build signed APK, push v0.3.0 tag, create GitHub release

**Dependencies:** None (all engine pieces in place as of v0.3-dev commit)

---

## v0.4.0 — Variable System & Scheduled Execution ⏳ IN PROGRESS

Research-backed high-impact items blocking advanced automation use cases.

### Variable System Expansion (TIER 2 P1)
**Effort:** 6 pts | **Impact:** 9/10 | **Blocker:** Many use cases  
Current state: Basic `%expansion` string substitution only  
Target: Full expression evaluator with arrays, math, regex, JSON parsing

- [x] **Arrays:** `%list(#)` count, `%list(1)` indexed access, `%list()` join
- [x] **Math operators:** `%var(+5)`, `%var(*2)`, `%var(//)` floor, `%var(/round)`
- [x] **String manipulation:** Case conversion (upper/lower), substring, trim, split/join
- [x] **Regex support:** `%text(regex:pattern:group)` extract, `regex-replace:pattern:replacement`
- [x] **JSON parsing:** Parse HTTP response JSON → extract nested fields via `%json.path.to.field`
- [x] **Conditional assignment:** `%var = (expr) ? true_val : false_val`
- [ ] **Local variable scope:** Task-scoped vs global (prevent side effects across tasks)
- [ ] **Variable persistence:** Save/restore across profile cycles if needed
- [ ] **Expression parser integration:** MVEL, SpEL, or custom lightweight evaluator

**Research:** 8+ competitors expose full variable systems; table-stakes for FOSS Tasker alternative.

### Scheduled Execution (TIER 2 P1)
**Effort:** 5 pts | **Impact:** 9/10 | **Blocker:** Cron-like profiles  
Current state: Only event-driven execution (context match)  
Target: Trigger profiles on schedule (daily, weekly, at specific time)

- [x] **AlarmManager vs WorkManager trade-off:** Study battery impact, persistence, API 31+ constraints
- [x] **Schedule context type:** Add to context family (periodic trigger like cron)
- [x] **Time window refinement:** Extend beyond simple "from/to" to recurring (every Mon 09:00)
- [ ] **Persistent wake lock:** Ensure scheduled tasks run even if device asleep (with battery warnings)
- [ ] **Cancellation:** Stop scheduled task if profile disabled or task list cleared

**Research:** Home Assistant, N8N, Node-RED all have scheduling; critical for "turn on lights at 08:00" use case.

### Action Library Expansion (TIER 2 P1)
**Effort:** 5 pts | **Impact:** 8/10 | **Enables:** Calendar/contacts automations  
Current: 43 built-in actions  
Target: +5-10 high-demand actions

- [x] **Calendar:** Read events by date range, create event, send invites
- [x] **Contacts:** Query by name/number, read email/phone, manage groups
- [ ] **Notification listener:** Listen for app notifications → trigger on text match
- [x] **Bluetooth discovery:** Scan for BT devices, connect/disconnect (complement toggle)
- [ ] **Call operations:** Answer/hang up call, speaker on/off (system permissions required)
- [x] **Clipboard:** Read/write system clipboard, listen for changes
- [ ] **SMS/MMS:** Read SMS threads, send MMS (complement existing SMS send)
- [ ] **Email:** Send via Gmail provider intent if installed

**Research:** Tasker has ~350 actions (broad), Macrodroid ~200 (focused). OpenTasker targeting ~70 high-value actions by v0.5.0.

### UI Polish: Design System & Component Refinement (TIER 2 P1)
**Effort:** 4 pts | **Impact:** 7/10  
- [ ] **Design system foundation:** Unified spacing, radius, typography, elevation scales ✅ (DesignSystem.kt)
- [ ] **Screen refactoring:** Apply design system to all 11 major screens (6 done, 5 in progress)
- [ ] **Batch operations:** Select multiple profiles → enable/disable/delete at once ✅ (BatchOperationsScreen)
- [ ] **Search/filter:** Find profiles by name, context type, action type
- [ ] **Pinned profiles:** Mark favorite profiles, show at top of list
- [ ] **Drag-reorder:** Reorder actions/contexts in editor without delete/re-add
- [ ] **Quick shortcuts:** Home screen widget for task execution

**Research:** Standard in Tasker, MacroDroid, IFTTT; quality-of-life feature.

---

## v0.5.0 — Import/Export & Advanced Contexts ⏳ QUEUED

### Tasker `.prj.xml` Import (TIER 3 P2)
**Effort:** 7 pts | **Impact:** 8/10 | **Enables:** User migration from Tasker  
- [ ] **Parser:** Parse Tasker project XML (profiles, tasks, scenes, variables, globals)
- [ ] **Action mapping:** Tasker action ID → OpenTasker action ID (1:1 where possible, fallback lossy)
- [ ] **Scene import:** Convert scene XML to OpenTasker Scene model (requires scene editor complete)
- [ ] **Variable migration:** Preserve globals, local variables, array definitions
- [ ] **Migration report:** Show mapping success/failures, suggest manual fixes
- [ ] **One-click import:** File picker → parse → import profiles to database

**Research:** Community requests this heavily; Tasker has active user base with custom automations.

### Geofence Refinement (TIER 3 P2)
**Effort:** 4 pts | **Impact:** 7/10  
- [ ] **Google Play Services geofence:** Replace skeleton with real geofence API
- [ ] **Multiple geofences per profile:** Define enter/exit triggers for multiple locations
- [ ] **GPS accuracy tuning:** Balance battery vs precision
- [ ] **Dwell time:** Trigger only after user stays in geofence for N minutes

**Research:** Home automation apps (Home Assistant, Domoticz) rely heavily on geofences; high demand from community.

### Scene Editor (TIER 3 P2)
**Effort:** 7 pts | **Impact:** 7/10 | **Enables:** Custom UI automation  
- [ ] **Visual layout builder:** Canvas + element palette (Button, Text, Slider, Image, WebView, Map)
- [ ] **Element properties:** Styling, position, size, font, color
- [ ] **Event binding:** Button click → trigger task, slider change → set variable
- [ ] **Scene management:** Save/delete scenes, edit in profile context
- [ ] **Runtime rendering:** Display scene as overlay (system alert window permission required)

**Research:** Tasker scenes enable rich custom UIs; important for advanced users.

### Plugin SDK Stabilization (TIER 2 P1 or TIER 3 P2?)
**Effort:** 4 pts | **Impact:** 7/10 | **Enables:** Action extensibility  
- [ ] **Sample plugin:** Weather condition action (read current weather → trigger automations)
- [ ] **Plugin authoring guide:** Documentation + Gradle template
- [ ] **Plugin discovery:** Intent filter parsing, metadata extraction
- [ ] **Testing harness:** Host app + mock plugin for validation
- [ ] **Permission scoping:** Plugin declares permissions, host enforces via intent filtering

**Research:** Locale plugin ecosystem (50+ plugins) shows viability; reduces core bloat.

---

## v1.0 — Public Release 🚀 VISION

**Target:** Full-featured FOSS Tasker alternative, signed on F-Droid.

- [ ] **Action library:** ~100 built-in actions (Tasker covers ~350, but niche; OpenTasker targeting high-value subset)
- [ ] **Complete variable system:** Arrays, math, regex, JSON, local scope
- [ ] **Scheduled execution:** Cron-like profiles, dwell-time geofences
- [ ] **Import from Tasker:** `.prj.xml` + `.tsk.xml` support
- [ ] **Plugin SDK:** Stable AIDL contract + sample plugins
- [ ] **Scene editor:** Custom UI overlays for advanced automations
- [ ] **Full test coverage:** Unit + integration + e2e tests
- [ ] **Documentation:** User guide, API docs, developer onboarding
- [ ] **F-Droid approval:** Security scan, privacy audit, reproducible builds
- [ ] **Community:** Active issue tracker, roadmap transparency, contributor guidelines

---

## Out of Scope / Deferred

Items considered and rejected (with reasoning):

- **Proprietary Tasker plugin ecosystem compatibility:** Too coupled to Tasker; plugin SDK uses Locale AIDL, which is compatible-but-separate
- **Voice automation (Google Assistant):** Out of charter; low demand from core user base (automation via voice is niche)
- **Home Assistant native integration:** Better solved via Home Assistant's REST API client; OpenTasker can expose HTTP server mode for callbacks
- **Multi-user profiles (enterprise):** Too specialized; focus on single-user / consumer use cases first
- **Time-series analytics:** Run log trending is nice-to-have; focus on execution reliability first
- **IFTTT recipe import:** Complex format, low demand; web service integration can be solved via HTTP action

---

## Research Methodology

**L1 ROADMAP Research (May 2026):**
- **Phase 0:** Repository state assessment (~4.7K LOC Kotlin, beta maturity)
- **Phase 1:** Landscape scan across 9 source categories (30+ distinct sources: OSS competitors, commercial refs, awesome-lists, community forums, standards, academic blogs, dependency updates, security advisories)
- **Phase 2:** Feature harvest (80-200+ raw items extracted from sources)
- **Phase 3:** Scoring matrix (Fit/Impact/Effort/Risk/Dependencies/Novelty) → tier assignment (Now/Next/Later/Under Consideration/Rejected)
- **Phase 4:** ROADMAP reconciliation (merge research tiers with current roadmap, reorder by impact)
- **Phase 5:** Self-audit (7-point checklist: source traceability, tier reasoning, category coverage, consistency, adversarial review, charter alignment, file-on-disk)

**Key findings:**
- Variable system + scheduled execution are P1 blockers (enable advanced use cases)
- Action library expansion is table-stakes (~70 actions covers 80% of user automations)
- Tasker import is high-value but effort-heavy (v0.5+)
- Plugin SDK stabilization reduces core bloat and enables community contribution

**Confidence level:** High (triangulated across 30+ sources, community signal, competitor analysis)

---

**Last updated:** 2026-05-03 (L1 research complete)  
**Next review:** After v0.3.0 release + 2 sprints (target: mid-June 2026)
