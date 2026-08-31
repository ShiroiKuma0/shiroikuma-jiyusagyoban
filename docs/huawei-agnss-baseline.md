# The satellite assistance baseline — 2026-08-30

**A known-good state to return to.** On 2026-08-30 the band fixed in **20 seconds** on a set this
repository generated, after a three-hour gap — level with the 20 s measured on Huawei Health's own
file. This document exists so the state that produced it can be reconstructed exactly, because it took
four independent catastrophic bugs to get here and every one of them was invisible to the checks in
place at the time.

An earlier reading of 13 s on the same data is recorded below and is **not** the figure to quote: it
was taken about an hour after another fix, and the band was still living off ephemeris it had decoded
from the sky itself. The archive directory is named `…_13s` after that first reading; the name is
historical, the contents are the set that produced the 20 s.

## The measurement, and what is wrong with it

| Configuration | Time to first fix |
| --- | --- |
| Huawei Health's own complete set, alive | 20 s |
| No valid set at all (expired) | 581 s |
| Ours, GPS 54 km out, stale BeiDou | 95 s |
| Ours, GPS 54 km out, BeiDou removed | ~2 min 35 s |
| Ours, everything broken | 5 min, band gave up |
| GPS + GLONASS correct, Galileo mislabelled, BeiDou stale | 1 min 40 s |
| PC-built, BeiDou stale — **~1 h after a fix** | 13 s |
| **the same PC-built set, ~3 h after a fix** | **20 s** |
| **everything built on the phone, 2026-08-30 evening** | **13 s** |

**Two readings in this table say 13 s and they are not the same measurement.** The first is the
PC-built set on a warm start and is the one the warning below is about. The last is the finished
system — the whole set generated on the phone — and is the result the project closed on; see the end
of this document.

**Of the two PC-built readings, the 13 s was a warm start and the 20 s is the number to keep.** Same
data, same spot, one variable:
the gap since the previous fix. At about an hour it read 13 s; at about three hours, 20 s. The band
decodes broadcast ephemeris from the sky during any fix and caches it for two to four hours — that
happens whether or not we upload anything, so a test taken soon after a walk measures the band's own
harvest rather than our assistance data.

**We are therefore level with Huawei's own file.** Their 20 s was measured after a **47.7-hour** gap
and ours after three, so position and almanac are cached in both but some satellites' ephemeris may
still have been marginally usable in ours — comparable rather than proven equal, and note it was
achieved with **BeiDou still stale**. The overnight run that would have settled it was never made
and is not planned: the on-phone set has since read 13 s, which is comfortably inside the same band,
and the project is closed.

**To repeat it** — should there ever be a reason to, which there is not today: same spot, after a gap of at least three hours (four to be safe) or overnight, workout started BEFORE the
fix, standing still. `.scratch/ttff.py read <label>` reads the number out of the band's own track and
marks anything under 10 s as a waited start that measures nothing.

## Where the artefacts are

`~/〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro/agnss-baseline_2026-08-30_13s/`

The six served files, their MD5s, the generator and verifier that produced them, the git HEAD they
were built at, and the two captured Huawei reference vintages used to validate them. Kept out of this
repository deliberately: the BeiDou, QZSS and EXTRA files are Huawei's own bytes.

Window of the archived set: **2026-08-30 03:59 → 2026-09-02 01:59 UTC**. Rebuild with
`python3 scripts/pgnss-build.py --sp3 .scratch/code5d.sp3 --out .scratch/pgnss-out --keep .scratch/hw2/satellite`.

## The format conventions that had to be right

Each of these was wrong at some point and cost a measurable amount. None can be derived from the
format alone; every one was settled against an external reference.

1. **SP3 epochs are already GPS time. Never add leap seconds to them.** Every file declares
   `%c M  cc GPS` and its `##` week/seconds-of-week equals the plain difference of its first calendar
   epoch. Adding 18 s put every satellite **54 km** along-track. `LEAP` is still correct in `now_gps`
   and in every display — those convert real UTC.
2. **The satellite index in a predicted-ephemeris record is `PRN − 1`, for every constellation** —
   GPS, Galileo, BeiDou and GLONASS alike. Verified two ways: each of Huawei's Galileo records matches
   a real satellite to 1–9 m by orbit alone and carries `PRN − 1`; and their GLONASS block 0 carries
   index **0**, which cannot be a slot number because GLONASS slots start at 1.

   Getting this wrong for Galileo labelled every satellite one too high, which is worse than omitting
   the constellation. An earlier version of this list claimed GLONASS was an exception storing the
   true slot — it is not, and the claim was corrected on 2026-08-30 when the Kotlin port's
   byte-identity test would only pass with `PRN − 1`. Note the almanac inside `HW_PGNSS_EXTRA` is a
   different format with its own slot field, and nothing here describes it.
3. **GPS record byte 77 = 0xFF.** Constant in all 1044 of Huawei's records; a per-record usable
   marker. We shipped 0x00 and the band accepted the file, counted it down as valid, and did not use
   it.
4. **GPS byte 54 is TGD**, a per-satellite hardware constant. Lifted from the capture — identical
   across all 36 epochs for every satellite, which is the check that says lifting is safe.
5. **GLONASS `τn` is the NEGATIVE of the clock bias.** SP3 publishes the bias itself. Writing it
   through un-negated cost 3.75–209 km of range bias per satellite, median 37.9 km, in every record.
   Confirmed against the captured RTCM 1020 broadcast on all 21 slots.
6. **GLONASS byte 12 = 1** — the satellite-type flag, not the frequency channel. 1 in all 11 960 of
   Huawei's records.
7. **GLONASS sub-epoch 0 sits on the UTC hour**, not on the block stamp, which is `:59:42` in UTC.
   Sub-epochs step 900 s.
8. **GLONASS `tb` is Moscow time-of-day** (UTC+3) in 900 s units. Huawei's 44 = 11:00 MSK = 08:00 UTC.
9. **GLONASS block stamps are one hour earlier** than GPS/Galileo; BeiDou is stamped +14 s (BDT).
10. **Δn is bounded non-negative and inside 16 bits.** Huawei's own values are all positive
    (3.44–5.79e-9 rad/s) and bytes 14–15 are zero in every record they ship. Non-negative is correct
    under both readings of that field's width, which is why it is chosen.
11. **Galileo E14 and E18 are excluded** — the two satellites in wrong elliptical orbits, which
    Huawei also omits. Their eccentricity reaches a byte Huawei never touches.
12. **The week field equals `stamp / 604800`** in every record. `pgnss-verify.py` will flag byte 2 as
    a constant mismatch whenever the capture and the build straddle a GPS week roll. That is the
    checker comparing across weeks, not a defect.

## How to verify — and how not to

**The rule: never grade the output against something that shares its assumptions.** Four
catastrophic bugs survived a full day because `pgnss-build.py` measured its fit against the same clock
it had itself corrupted, reporting 0.3 m while shipping 53 km. A round-trip through your own decoder
proves only that you are self-consistent.

Checks that actually bite, in order of value:

- **Against Huawei's own captured files** — `pgnss-verify.py <ours> <reference>` compares every byte
  position: whatever they use must carry something in ours, whatever they leave zero must stay zero.
  Run it against **both** vintages in the archive, not one.
- **Against an independent orbit product**, read without the generator's own epoch handling. Current
  numbers: **GPS 0.24 m median**, **Galileo 0.14 m median** against the precise orbit.
- **Against a broadcast capture** — RTCM 1019/1020 settle clock signs and TGD with no orbit product
  involved at all.
- **By orbit, not by label** — propagate each record and identify which real satellite it *is*, then
  read off the index it carries. This is the only check that catches an indexing convention error,
  and it is the one that was missing.

## Known-wrong, still outstanding

**Superseded on 2026-08-31 by the on-phone build.** Everything below the line was written on
2026-08-30, when the set was produced on the PC and three of its files were Huawei's captured bytes.
The satellite task now builds the whole set on the phone, so the state is:

| file | now |
| --- | --- |
| `HW_PGNSS_GPS` / `GLONASS` / `GALILEO` | built on the phone |
| `HW_PGNSS_BDS` | **built** — `PredictedSet.buildBeiDou`, no longer the stale capture |
| `HW_PGNSS_EXTRA` | **built** — `buildExtra`, and its trailer advertises a window computed at build time |
| `HW_PGNSS_QZS` | still the captured file; regional to the Asia-Pacific and irrelevant over Prague |

The captured directory survives as a **byte donor**, not as files to serve: the roughly 290 bytes of
`HW_PGNSS_EXTRA` that are still undecoded, and the per-satellite group delays that cannot be fitted.
`seedCaptured` writes it once and never again, so a satellite dropped by one run cannot take its
TGD away permanently. Nothing expires on a date any more.

So the old bullets — "BeiDou and QZSS are stale", "EXTRA expires 2026-09-01", "the predicted set is
built on the PC" — are all obsolete, and this note exists because reading them cost an incorrect
answer to 白い熊 on 2026-08-31.

### The measurement, taken 2026-08-30 — and the project is closed

**13 seconds, on the first fully on-phone-built set.** 白い熊 ran the build on the band that evening
and fixed in 13 s; reported 2026-08-31. That is the whole set generated on the phone from free
sources — GPS, GLONASS, Galileo, BeiDou and the almanac — with only QZSS still a captured file, and
it is at least as good as the 20 s measured on Huawei Health's own complete set.

**So the satellite work is done and nothing here is owed.** Do not re-open it to chase a cleaner
number: the earlier readings in the table were taken with the previous-fix gap controlled, this one
was not, and 白い熊's call on 2026-08-31 was that the result is good enough and the question is
closed. A future reader comparing 13 against 20 should know both figures are healthy and neither is
evidence of a regression — the band's own two-to-four-hour ephemeris cache moves a reading by that
much on identical data, which is what the top of this document is about.
