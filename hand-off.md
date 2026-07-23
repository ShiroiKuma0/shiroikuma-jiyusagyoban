# Hand-off — silence 白い熊 GNU Jami's watchdog (housekeeping/wedge) notifications

**From:** the shiroikuma-jami session, 2026-07-23.
**Task for jiyusagyoban:** 白い熊 keeps getting the *message-arrival* sound + vibration for Jami's
internal auto-recovery ("watchdog") notifications — they use the system default notification sound,
so they are indistinguishable by ear from a real incoming message. Configure notification handling so
these housekeeping/wedge notifications **never sound or vibrate**. Real messages and calls must keep
sounding — the match criteria below separate them cleanly.

## The one channel that covers ALL of them

Every watchdog notification — recoveries, wedges, error storms, restricted-network, network-down —
goes through a single code path (`ConnectionWatchdog.notifyUser`) and therefore a single channel:

| Property | Value |
|---|---|
| Package | `shiroikuma.jami` (app label 白い熊 GNU Jami) |
| Channel ID | `shiroikuma_watchdog` |
| Channel name | 「自動回復」(ja) / created with name "自動回復" |
| Channel importance | `IMPORTANCE_DEFAULT` (3), sound = system default notification sound |
| Notification IDs | **58000–58019** (`58_000 + incidentSeq % 20`) |
| Title (constant for ALL of them) | 「白い熊 Jami 自動回復」(ja) / "白い熊 Jami · auto-recovery" (en) |
| Auto-cancel | true (tap dismisses; no action buttons) |

**Match rule (recommended):** `package == shiroikuma.jami && channel == shiroikuma_watchdog`.
Fallbacks if the channel isn't visible to the listener: notification ID in [58000, 58019], or title
== 「白い熊 Jami 自動回復」/ "白い熊 Jami · auto-recovery".

Per-account wedge *recoveries* (strike 1, the frequent ~2–3 min ones) do NOT post notifications at
all — they only write to the incidents log. What DOES notify is the list below; all on that channel.

## Complete list of the watchdog notifications (content texts)

The `%s` / `%1$d` etc. are runtime fill-ins (a time stamp like `17:16`, minute counts, account ids).
Japanese first (the phone's locale), English variant after.

| Trigger | ja text | en text |
|---|---|---|
| Error storm → smart recover | エラーストーム検出 → スマート回復 (%s) | Error storm detected → smart recover (%s) |
| Error storm repeat → hard reset | エラーストーム再発 → ハードリセット (%s) | Error storm again → hard reset (%s) |
| Account deaf → recover | アカウント受信沈黙 %d 分 → 回復 (%s) | Account silent %d min → recover (%s) |
| Account deaf → full-DHT recover | アカウント受信沈黙 %d 分 → フルDHT回復 (%s) | Account silent %d min → full-DHT recover (%s) |
| Delivery stuck → hard reset | 配信詰まり継続 → ハードリセット (%s) | Delivery still stuck → hard reset (%s) |
| Delivery failing → stand-down | 配信不能 (%s) — 回復を %d 分停止 | Delivery failing (%s) — pausing recovery %d min |
| Restricted network entered | 制限ネットワーク検出（UDP遮断）→ プロキシ固定ON・中継モード (%s) | Restricted network (UDP blocked) → proxy pinned ON, relay mode (%s) |
| Network appears down | ネットワーク切断の可能性 — 接続を確認してください (%s) | Network appears down — check your connection (%s) |
| Restricted network exited | 通常ネットワーク復帰 — UDP開通 (%s) | Normal network restored — UDP open (%s) |
| Uniform wedge (all accounts silent, probe unanswered) | 全アカウント沈黙・プローブ無応答 → 回復します (%s) | All accounts silent and probe unanswered → recovering (%s) |
| Deaf after mode switch | モード切替後に受信なし → 再登録します (%s) | No inbound after the mode switch → re-registering (%s) |

(A Czech locale set also exists in the app — e.g. title "白い熊 Jami · automatická obnova" — but the
title always contains 「白い熊 Jami」, so a title-substring match is locale-proof.)

## What must KEEP sounding (do not over-match)

Everything else from `shiroikuma.jami` is a real user-facing event on OTHER channels — leave these
fully audible:

- Messages / conversations (per-conversation channels, upstream "Messages" channel).
- Incoming calls, missed calls (upstream call channels — the fork recently ADDED a missed-call
  notification for swarm conversations; it must sound).
- Trust requests, file transfers (`file_transfer`), sync/foreground-service ones (already silent).

So: suppress **only** `channel == shiroikuma_watchdog` (equivalently title 「白い熊 Jami 自動回復」),
nothing broader. The notifications should still appear visually — 白い熊 wants them gone from the
*ear*, not from the shade.

## Context (why they fire often right now)

An account-deafness investigation is running on the Mate XT (4 Jami accounts in one daemon; proxy
subscriptions wedge every few minutes and the watchdog auto-recovers). Incident rate is elevated
while this is being root-caused, so the sounds are currently frequent — another reason to silence
the channel rather than tune the watchdog's thresholds.
