# FOSS Geofencing Baseline

OpenTasker v0.2.37 has a Play-services-free geofence path for Location contexts. It combines a platform `LocationManager` source with the pure evaluator added in v0.2.29 and persisted dwell state added in v0.2.37; it is still not a device-verified background geofence reliability claim.

## Active Scope

- Uses Haversine distance through `FossGeofenceEvaluator.distanceMeters`.
- Registers `LocationContextSourceImpl` as the runtime `location` source.
- Uses Android framework GPS and network providers instead of Google Play Services.
- Seeds matching from the best last-known GPS/network fix when one is available.
- Emits fail-closed setup events for missing foreground location permission, disabled providers, unavailable location service, and source errors.
- Persists `insideSinceEpochMs` per profile/context/config so dwell timers can survive process restarts.
- Uses a config hash in dwell-state keys so edited geofences do not reuse stale inside-since state.
- Clears persisted dwell state when a valid sample leaves the radius.
- Preserves previous inside-since state across low-accuracy samples that are inside the radius but blocked by max-accuracy policy.
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
- No battery optimization strategy for location polling.
- No Play flavor split.
- No device-verified geofence reliability statement yet.

## Matching Rules

- A location must be within radius to match.
- If max accuracy is configured, the event must include accuracy and be at or below the configured maximum.
- If dwell time is configured, the event must include observed and inside-since timestamps, and the elapsed inside time must meet or exceed the dwell threshold.
- Invalid or incomplete config fails closed.

## Next Work

1. Surface geofence-specific dwell state in the Context Inspector.
2. Tune provider cadence and battery behavior with device evidence.
3. Expand setup/policy copy after real foreground/background verification.
4. Add cleanup or migration for stale dwell keys if profile/context deletion starts leaving meaningful residue.
5. Verify behavior on a device before making public geofence reliability claims.
