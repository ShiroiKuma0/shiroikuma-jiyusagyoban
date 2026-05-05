# FOSS Geofencing Baseline

OpenTasker v0.2.45 has a Play-services-free geofence path for Location contexts. It combines a platform `LocationManager` source with the pure evaluator added in v0.2.29, persisted dwell state added in v0.2.37, Context Inspector dwell detail added in v0.2.38, stale dwell-key cleanup added in v0.2.39, balanced provider cadence added in v0.2.40, shared Android location policy disclosures added in v0.2.41, app-launch foreground-service startup repaired in v0.2.42, the adb-backed device evidence harness added in v0.2.43, a setup-required disabled Location evidence template added in v0.2.44, and run-log/logcat evidence assertions added in v0.2.45; it is still not a device-verified background geofence reliability claim.

## Active Scope

- Uses Haversine distance through `FossGeofenceEvaluator.distanceMeters`.
- Registers `LocationContextSourceImpl` as the runtime `location` source.
- Starts the foreground automation service from app launch and boot.
- API 36 device smoke confirmed app launch starts the service foreground with `specialUse|location` when foreground/background location permissions and device location are enabled.
- Includes a disabled-by-default `Location evidence log` template with latitude, longitude, radius, max-accuracy, dwell, and log-action defaults for later event-delivery smoke tests.
- `tools/collect-location-evidence.ps1` collects repeatable adb evidence for Location/geofence verification, including permission state, foreground-service type, app-to-home service persistence, logcat tail, location dumps, battery dumps, and batterystats snapshots.
- The evidence collector snapshots debug Room data through `run-as`, writes `room-summary.json`, and can require matching recent run-log or logcat evidence before a run passes.
- A 10-second API 36 harness run with the app sent home kept `AutomationService` foreground with `specialUse|location` and captured battery before/after snapshots; the short USB-powered sample is evidence that the harness works, not a battery optimization claim.
- Uses Android framework GPS and network providers instead of Google Play Services.
- Seeds matching from the best last-known GPS/network fix when one is available.
- Requests GPS updates at a 180-second/100-meter cadence and network updates at a 90-second/150-meter cadence by default.
- Rechecks setup readiness every 60 seconds while the source is active.
- Uses shared Setup and Context Inspector copy for Android 11+ background-location settings, approximate precision carryover, and Android 14+ location foreground-service gating.
- Emits fail-closed setup events for missing foreground location permission, disabled providers, unavailable location service, and source errors.
- Persists `insideSinceEpochMs` per profile/context/config so dwell timers can survive process restarts.
- Uses a config hash in dwell-state keys so edited geofences do not reuse stale inside-since state.
- Clears profile-scoped persisted dwell keys when a profile is deleted or its context list changes.
- Clears persisted dwell state when a valid sample leaves the radius.
- Preserves previous inside-since state across low-accuracy samples that are inside the radius but blocked by max-accuracy policy.
- Shows profile/context-specific dwell status in Context Inspector rows: inside elapsed time, outside reset, accuracy-blocked, or unknown.
- Evaluates `latitude`, `longitude`, and `radiusMeters` Location context config.
- Supports aliases: `lat`, `lon`, `lng`, and `radius`.
- Supports optional `maxAccuracyMeters` or `maxAccuracy` config.
- Supports optional `dwellMillis`, `dwellMs`, `dwellSeconds`, or `dwellSec` config.
- Reads event metadata for `latitude`, `longitude`, `accuracyMeters`, `observedAtEpochMs`, `timestampEpochMs`, `insideSinceEpochMs`, and `enteredAtEpochMs`.
- Wires active `ContextMatchEvaluator` Location matching through the FOSS evaluator.
- Reuses the same evaluator for the older geofence trigger distance path.
- Declares `FOREGROUND_SERVICE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, and `android:foregroundServiceType="specialUse|location"` for the automation foreground service.
- Only adds the Android 14+ foreground-service location type at runtime when background-location prerequisites are already satisfied; otherwise the service stays on its special-use type and the location source fails closed until setup is ready.

## Non-Goals

- No Google Play Services geofencing dependency.
- No public background-location reliability claim.
- No device-verified battery optimization claim for location polling.
- No Play flavor split.
- No device-verified geofence reliability statement yet.

## Matching Rules

- A location must be within radius to match.
- If max accuracy is configured, the event must include accuracy and be at or below the configured maximum.
- If dwell time is configured, the event must include observed and inside-since timestamps, and the elapsed inside time must meet or exceed the dwell threshold.
- Invalid or incomplete config fails closed.

## Device Evidence Harness

Run the adb evidence collector from the repo root after installing the APK on a connected device:

```powershell
.\tools\collect-location-evidence.ps1 -SampleSeconds 600 -GrantLocationPermissions -SendHomeDuringSample -RequireRunLogMessagePattern "Location evidence log|Log location evidence"
```

For actual Location event-delivery verification, install the `Location evidence log` template, adjust the coordinates/radius to the test site, grant foreground/background location prerequisites, enable the created profile, then run the collector while the service is backgrounded. The script writes timestamped output under ignored `build/device-evidence/location/`, including `room-summary.json` when debug `run-as` and local Python/SQLite are available. Use longer unplugged samples for battery work; short USB-powered samples only prove the harness and foreground-service state checks are functioning.

## Next Work

1. Install, configure, and enable the `Location evidence log` template on a device, then extend smoke coverage from foreground-service persistence to actual background Location event delivery.
2. Run longer unplugged provider-cadence and battery samples with `tools/collect-location-evidence.ps1`.
3. Verify behavior on a device before making public geofence reliability claims.
