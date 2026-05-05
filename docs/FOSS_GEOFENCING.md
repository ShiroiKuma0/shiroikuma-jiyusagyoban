# FOSS Geofencing Baseline

OpenTasker v0.2.29 adds a Play-services-free geofence evaluator for Location contexts. It is a pure matching layer, not a full background location engine.

## Active Scope

- Uses Haversine distance through `FossGeofenceEvaluator.distanceMeters`.
- Evaluates `latitude`, `longitude`, and `radiusMeters` Location context config.
- Supports aliases: `lat`, `lon`, `lng`, and `radius`.
- Supports optional `maxAccuracyMeters` or `maxAccuracy` config.
- Supports optional `dwellMillis`, `dwellMs`, `dwellSeconds`, or `dwellSec` config.
- Reads event metadata for `latitude`, `longitude`, `accuracyMeters`, `observedAtEpochMs`, `timestampEpochMs`, `insideSinceEpochMs`, and `enteredAtEpochMs`.
- Wires active `ContextMatchEvaluator` Location matching through the FOSS evaluator.
- Reuses the same evaluator for the older geofence trigger distance path.

## Non-Goals

- No Google Play Services geofencing dependency.
- No live background location source.
- No persisted dwell-state tracker.
- No background-location policy claim.
- No battery optimization strategy for location polling.
- No Play flavor split.

## Matching Rules

- A location must be within radius to match.
- If max accuracy is configured, the event must include accuracy and be at or below the configured maximum.
- If dwell time is configured, the event must include observed and inside-since timestamps, and the elapsed inside time must meet or exceed the dwell threshold.
- Invalid or incomplete config fails closed.

## Next Work

1. Add a foreground-service-owned location source using platform Android location APIs.
2. Persist inside/outside state per geofence so dwell time survives process restarts.
3. Surface accuracy and dwell state in the Context Inspector.
4. Add setup/policy copy for foreground and background location use.
5. Verify behavior on a device before making public geofence reliability claims.
