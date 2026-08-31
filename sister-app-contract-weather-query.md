# Contract — asking 白い熊 天気 for weather

**Between:** `shiroikuma.jiyusagyoban` (白い熊 自由作業盤) and `shiroikuma.tenki` (白い熊 天気).
**Agreed:** 2026-08-23, between the two repos' chats, with 白い熊 directing.
**Status:** both halves are built. 自由作業盤's shipped first; 天気's landed in `6.2.1+062` after
白い熊 gave the go-ahead. One piece is deliberately not implemented — see `refresh` below.

## Why this exists

白い熊's HUAWEI Band 11 Pro shows weather on its watch face. **The band never fetches anything.** It
displays whatever the companion phone last pushed over Bluetooth, so the face stays stale until a
task pushes a new number. 自由作業盤 can push; it has nothing to push.

The obvious fix — have 自由作業盤 call a weather API itself — was rejected by 白い熊, and the reason
is the point of this contract: **天気 already fetches weather and already holds their location.**
Asking it means no second app sees those coordinates. That constraint is load-bearing; a later change
that makes 自由作業盤 phone out directly would defeat the whole design.

白い熊 also required a choice of provider. 天気 holds several sources per location — a fork feature
白い熊 built to stack one chart per source — so which one reaches the wrist is theirs to pick.

## The channel

Identical to the 保存復元 contract in `sister-app-contract-backup-automation-hand-off.md`, and for
identical reasons. Briefly:

- **Out:** an ORDERED broadcast to 天気's exported
  `org.breezyweather.tenki.automation.StateExportReceiver`, with `FLAG_INCLUDE_STOPPED_PACKAGES` —
  白い熊 freezes apps, and a plain broadcast is dropped for a stopped target.
- **Back:** a fresh plain broadcast to `reply_package` / `reply_action`, carrying `reply_id` and
  **string extras only**.
- **Never a binder.** This EMUI does not reliably carry a `ResultReceiver` or `PendingIntent` into
  another app's manifest receiver — one got the whole broadcast dropped, the other was delivered and
  never fired. Both apps learned this separately and arrived at the same answer.

Spoofing is bounded by `reply_id`, a per-request UUID the target echoes verbatim and never
interprets.

### One change this needed on 自由作業盤's side

`IntentReplyBridge` used to surface only the `result` extra and discard everything else. That was
adequate while a round-trip meant "did it work?" and **wrong the moment an app answered with
values** — every figure below would have been dropped in silence. It now carries all string extras,
and `intent.send` writes them as `%<result_var>_<key>`.

## Authentication

The existing token, unchanged: a 24-byte hex string, constant-time compared, held in 天気's own prefs
file which its backup deliberately never reads, behind an automation switch that defaults OFF.
**The same token covers all six actions**, so there is nothing new for 白い熊 to paste.

自由作業盤 stores it as `%Huawei_TenkiToken` in 「健康の設定 -- [727][01]」, and every task here
refuses with an explanation when it is blank rather than sending an unauthenticated request.

Failures reuse the existing vocabulary — `ERROR:automation disabled` and `ERROR:bad token` stay
distinct, because they debug differently.

## The three actions

Every reply carries `result`, a status line beginning `OK:` or `ERROR:`. **That line is the gate.**
自由作業盤 tests it by comparing the first three characters, not with a "starts with" operator —
its condition evaluator has only `==`, `!=` and the numeric comparisons, and a condition it cannot
parse evaluates FALSE, so an invented operator would silently never take the error branch.

### `shiroikuma.tenki.action.LIST_LOCATIONS`

| extra | required | meaning |
|---|---|---|
| `token` | yes | the automation token |
| `reply_id`, `reply_package`, `reply_action` | yes | the reply channel |

Reply: `result` = `OK:` followed by one line per location, TAB-separated:
`formattedId⇥name⇥primary_source`. With no saved locations at all the reply is `ERROR:no locations`
rather than an empty `OK:` — same reasoning as the provider list below.

**Why locations and not coordinates.** 天気's weather is cached per saved location, not per point on
the globe. Handing it a latitude and longitude would mean either matching them back to a saved
location or doing a live fetch for somewhere 白い熊 never saved — the second needs reverse geocoding
plus per-location source parameters that only exist once a location is properly added, and would burn
API quota on every band update. Addressing a location answers from cache: instant, offline, free.
This was 天気's correction to 自由作業盤's original proposal, and it is the better design.

`latitude` / `longitude` remain accepted as a **fallback, not an address**: they snap to the nearest
saved location within **25 km** and otherwise answer `ERROR:unknown location`. They never trigger a
fetch. Use them only where a `formattedId` is genuinely unavailable.

### `shiroikuma.tenki.action.LIST_PROVIDERS`

| extra | required | meaning |
|---|---|---|
| `token` | yes | |
| `reply_id`, `reply_package`, `reply_action` | yes | |
| `location` | no | a `formattedId`, the literal `current`, or blank for the first saved location |
| `latitude`, `longitude` | no | **fallback only** — see below |
| `all` | no | `true` also lists the full forecast-capable catalogue |

Reply: `result` = `OK:` followed by one line per provider:
`id⇥name⇥configured⇥cached`.

- **`configured`** — the source is usable: it has its key and is not restricted.
- **`cached`** — 天気 is holding that source's data right now.

Three semantics worth stating because they are not guessable:

1. **A known location never answers an empty list.** Your wording was right, and here it is exactly:
   `Location.orderedHourlyForecastSources` is self-healing — an empty configuration yields the
   primary alone, and a non-empty list missing the primary gets it **prepended**. The list this
   action answers is the union of the hourly and daily source lists; the daily one is *not* forced to
   contain the primary, but the hourly one always is, so the union always does. The primary is baked
   into the location's `formattedId` and so is always present. **A blank `OK:` list is therefore a
   bug on 天気's side, not a state to render** — 自由作業盤 reports it as unexpected
   rather than saying "only the primary is available", which would describe a condition that should
   have produced one line.
2. `configured=1 cached=0` is a real location whose weather has not been fetched yet. Offer it; it
   starts working at the next refresh.
3. The catalogue under `all=true` is **greyed, not hidden**, for sources that are not configured —
   hiding a source 白い熊 could enable by pasting a key is worse than showing it unavailable.

Ids are stable machine strings (`openmeteo`, `metno`, `chmi`, …); **names are localized and
country-qualified at runtime** and the available set depends on build flavour. So the picker is built
from the live reply and hardcodes no table.

### `shiroikuma.tenki.action.QUERY_WEATHER`

| extra | required | meaning |
|---|---|---|
| `token` | yes | |
| `reply_id`, `reply_package`, `reply_action` | yes | |
| `location` | no | a `formattedId`, the literal `current`, or blank for the first saved location |
| `provider` | no | an id from LIST_PROVIDERS; blank = the location's primary |
| `refresh` | no | **not implemented — answers `ERROR:refresh not supported`.** See below |

Reply: `result` = `OK:…` / `ERROR:…`, plus these string extras:

`temperature` · `high` · `low` · `humidity` · `place` · `provider` · `provider_name` ·
`observed_at` · `observed_at_epoch` · `age_minutes` · `temperature_kind` · `stale` · `unit`

Temperatures are plain °C to one decimal with an explicit `unit=C`, **independent of 天気's own
display setting**, so the band never shifts under 自由作業盤 when 白い熊 changes a preference.

#### Empty is not zero

A field 天気 cannot supply arrives **empty**, never as `0`. The band draws a real 0 °C if handed one,
and a wrong temperature on the wrist is worse than a blank. Every one of these is nullable upstream
and null maps to the empty string.

That rule governs a missing *field within a real reading*. **A reading that does not exist at all is
an `ERROR:`** — asking for a provider 天気 holds no data for answers `ERROR:no data for provider`,
not an `OK:` with nine blanks, because the latter would make the caller reconstruct that fact itself.
Same for a location that has never been refreshed.

#### `temperature_kind` — the field that keeps this honest

- **`observed`** — a real observation. This requires the chosen provider to be the location's
  primary, but does **not** follow from it: plenty of sources report no current conditions at all,
  and a primary without them falls back to `hourly` like any alternate. So read the field; never
  infer the kind from whether the provider happens to be the primary.
- **`hourly`** — the chosen provider is an alternate. 天気's alternate forecasts deliberately carry
  only daily and hourly arrays and **no current conditions**, so no observation exists. The honest
  value is that source's own forecast for the hour containing now.

`high` and `low` come from the chosen provider's own daily entry either way, so those are genuinely
per-provider in both cases.

自由作業盤 surfaces this rather than burying it: the confirmation says which kind is on the wrist.
This band has already produced two values that looked like measurements and were not — a resting heart
rate that was the band's null stored as `0`, and a sleep card confidently showing a two-day-old night
— so a field that distinguishes a forecast from a reading earns its place.

#### Freshness, and a trap in it

- `observed_at` — ISO-8601 with offset, in the **location's** timezone.
- `observed_at_epoch` — the same instant in seconds, for arithmetic.
- `age_minutes` — integer.
- `stale` — `1`/`0`, 天気's own judgement: the cache is older than the app's refresh interval.

**Do not derive freshness by subtracting `observed_at` from now.** For an `observed` reading it is
when 天気 last fetched current conditions for that location. For an `hourly` one **it is the timestamp of the forecast hour
containing now, and so may be up to an hour in the FUTURE** — it is not a measurement time, because
no measurement happened. A naive reader computes a negative age and concludes the clock is wrong.

`age_minutes` and `stale` are defined for both kinds precisely so nothing downstream needs to know
which kind it is holding. Use `stale` when it is set — "the app that owns this data considers it out
of date" is a stronger claim than any age comparison made from outside — and `age_minutes` when a
stricter threshold is wanted.

### The error vocabulary

Beyond `ERROR:automation disabled` and `ERROR:bad token`, which are unchanged:

| line | means |
|---|---|
| `ERROR:no locations` | nothing saved in 天気 at all |
| `ERROR:unknown location` | the `formattedId` is not ours, or the coordinates snapped to nothing within 25 km |
| `ERROR:unknown provider` | no such source id is built into this app — re-run the provider picker |
| `ERROR:no data for provider` | the source is real but this location holds nothing from it, or has never been refreshed |
| `ERROR:refresh not supported` | `refresh=true` was asked for; see below |

**A note on the hour we report.** For an `hourly` reading 天気 picks the cached hour nearest to now,
which covers sources that go six-hourly out in the week — MET Norway does exactly that. Beyond **six
hours** from now there is no reading of the present to give and the answer is
`ERROR:no data for provider` rather than a figure from the wrong part of the day.

### Why `refresh` is not implemented

The design allowed for it and 天気 chose not to ship it, deliberately rather than by oversight.
Going out to a source would need the foreground-service path the export already uses, because a
network fetch inside a broadcast window is precisely the ANR the 保存復元 contract was shaped to
avoid — and the cache-only answer is what a watch face actually wants: instant, offline, free.

`refresh=true` therefore answers `ERROR:refresh not supported` rather than being silently ignored, so
nothing can quietly believe it forced a fetch. If 白い熊 ever wants it, it is a service, not a flag.

## 自由作業盤's side

| task | position | what it does |
|---|---|---|
| 「天気地点（Huawei）」 | 28 | LIST_LOCATIONS → 白い熊 puts an id in `%Huawei_WeatherLocation` |
| 「天気提供元（Huawei）」 | 29 | LIST_PROVIDERS → an id in `%Huawei_WeatherProvider` |
| 「天気送信（Huawei）」 | 27 | QUERY_WEATHER → pushes the result to the band |

Settings in 「健康の設定 -- [727][01]」: `%Huawei_TenkiToken`, `%Huawei_WeatherLocation`,
`%Huawei_WeatherProvider`. Reply values land as `%Huawei_Tenki_<key>`.

The push previously read a hand-typed `%Huawei_WeatherTemp`; the builder now asserts that variable is
gone from it, so the manual path cannot quietly return.

## What is deliberately NOT here

- **No condition or icon codes.** One capture cannot pin them, and a confidently wrong icon on the
  wrist is worse than none.
- **No automatic schedule.** 白い熊 has not asked for the band's weather to update on a timer, and a
  profile that woke the radio every hour is theirs to want, not ours to assume.
