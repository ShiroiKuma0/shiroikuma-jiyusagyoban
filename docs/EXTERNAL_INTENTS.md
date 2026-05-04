# External Automation Intents

OpenTasker exposes a narrow broadcast receiver so other apps can trigger user-created automation without binding to internal services.

## Security boundary

Receiver: `com.opentasker.core.external.AutomationTargetReceiver`  
Required permission: `com.opentasker.permission.AUTOMATION`

The receiver is exported only behind this custom permission. Callers should send explicit broadcasts to the OpenTasker package and use ordered broadcasts when they need result extras.

## Actions

### Run a task

Action: `com.opentasker.action.RUN_TASK`

Extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.TASK_ID` | long | Preferred stable local ID. |
| `com.opentasker.extra.TASK_NAME` | string | Case-insensitive fallback lookup. |
| `com.opentasker.var.<Name>` | string-compatible | Optional task variables. Names must match `[A-Za-z][A-Za-z0-9_]{0,63}`. |

Ordered-broadcast result extras:

| Extra | Type |
|---|---|
| `com.opentasker.extra.TASK_SUCCESS` | boolean |
| `com.opentasker.extra.TASK_DURATION_MS` | long |
| `com.opentasker.extra.ERROR` | string, present on failure |

### Set profile enabled state

Action: `com.opentasker.action.SET_PROFILE_ENABLED`

Extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.PROFILE_ID` | long | Preferred stable local ID. |
| `com.opentasker.extra.PROFILE_NAME` | string | Case-insensitive fallback lookup. |
| `com.opentasker.extra.ENABLED` | boolean | Desired enabled state. |

### Query status

Action: `com.opentasker.action.QUERY_STATUS`

Optional extras:

| Extra | Type |
|---|---|
| `com.opentasker.extra.PROFILE_ID` | long |
| `com.opentasker.extra.PROFILE_NAME` | string |

Ordered-broadcast result extras include task/profile counts, enabled profile count, and profile-specific enabled/context state when a profile is found.

## adb examples

```bash
adb shell am broadcast \
  -a com.opentasker.action.RUN_TASK \
  -p com.opentasker.app \
  --el com.opentasker.extra.TASK_ID 1 \
  --es com.opentasker.var.User "demo"
```

```bash
adb shell am broadcast \
  -a com.opentasker.action.SET_PROFILE_ENABLED \
  -p com.opentasker.app \
  --el com.opentasker.extra.PROFILE_ID 1 \
  --ez com.opentasker.extra.ENABLED true
```
