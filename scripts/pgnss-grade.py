#!/usr/bin/env python3
"""Grade a generated `HW_PGNSS_*` set against a precise orbit product, block by block.

    python3 scripts/pgnss-grade.py DIR "SP3-GLOB" [--system BDS] [--kind GEO]

WHY THIS AND NOT THE BUILDER'S OWN NUMBER
`pgnss-build.py` reports the residual of its own fit against the orbit it fitted to. That is a real
measurement of one thing — whether a Kepler set can represent that trajectory over its two-hour slice
— and no measurement at all of whether the trajectory was right. When the trajectory is BeiDou's, the
last two days of it were INTEGRATED here, so the builder's residual is the one number in the whole
pipeline that cannot see the error that matters.

So this decodes the shipped bytes with a decoder written from the format rather than from the
encoder, propagates them with the band's own formula, and compares against orbits somebody else
produced. Run it on a window that has since passed and the comparison is against observations.

    * a fresh window can only be graded over the part the product already covers;
    * a window that has closed can be graded end to end, which is the honest number.
"""
import argparse
import datetime
import glob
import math
import pathlib
import struct
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "pgnss_build", str(pathlib.Path(__file__).resolve().parent / "pgnss-build.py"))
pgb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pgb)

GPS_EPOCH = pgb.GPS_EPOCH
LEAP = pgb.LEAP
PI = pgb.PI
BLOCKS = pgb.BLOCKS


def utc(t):
    return GPS_EPOCH + datetime.timedelta(seconds=t - LEAP)


# ── decoders, written from the layout rather than from the encoders they check ────────────────────
def _u(rec):
    return lambda o, w, s: int.from_bytes(rec[o:o + w], "little", signed=s)


def dec_bds(rec):
    u = _u(rec)
    return dict(prn=u(0, 2, 0) + 1,
                toc=u(8, 4, 0) * 8, af0=u(12, 4, 1) * 2 ** -33, af1=u(16, 4, 1) * 2 ** -50,
                toe=u(28, 4, 0) * 8, sqrtA=u(32, 4, 0) * 2 ** -19, e=u(36, 4, 0) * 2 ** -33,
                omega=u(40, 4, 1) * 2 ** -31 * PI, dn=u(44, 4, 1) * 2 ** -43 * PI,
                m0=u(48, 4, 1) * 2 ** -31 * PI, omega0=u(52, 4, 1) * 2 ** -31 * PI,
                omegadot=u(56, 4, 1) * 2 ** -43 * PI, i0=u(60, 4, 1) * 2 ** -31 * PI,
                idot=u(64, 2, 1) * 2 ** -43 * PI,
                cuc=u(68, 4, 1) * 2 ** -31, cus=u(72, 4, 1) * 2 ** -31,
                crc=u(76, 4, 1) * 2 ** -6, crs=u(80, 4, 1) * 2 ** -6,
                cic=u(84, 4, 1) * 2 ** -31, cis=u(88, 4, 1) * 2 ** -31)


def dec_gps(rec):
    u = _u(rec)
    return dict(prn=u(0, 2, 0) + 1, week=u(2, 2, 0),
                m0=u(8, 4, 1) * 2 ** -31 * PI, dn=u(12, 4, 1) * 2 ** -43 * PI,
                e=u(16, 4, 0) * 2 ** -33, sqrtA=u(20, 4, 0) * 2 ** -19,
                omega0=u(24, 4, 1) * 2 ** -31 * PI, i0=u(28, 4, 1) * 2 ** -31 * PI,
                omega=u(32, 4, 1) * 2 ** -31 * PI, omegadot=u(36, 4, 1) * 2 ** -43 * PI,
                idot=u(40, 2, 1) * 2 ** -43 * PI,
                cuc=u(42, 2, 1) * 2 ** -29, cus=u(44, 2, 1) * 2 ** -29,
                crc=u(46, 2, 1) * 2 ** -5, crs=u(48, 2, 1) * 2 ** -5,
                cic=u(50, 2, 1) * 2 ** -29, cis=u(52, 2, 1) * 2 ** -29,
                af0=u(56, 4, 1) * 2 ** -31, af1=u(60, 4, 1) * 2 ** -43,
                toe=u(72, 2, 0) * 16, toc=u(74, 2, 0) * 16)


def dec_gal(rec):
    u = _u(rec)
    # 0-based, like GPS and BeiDou. Reading it as the bare PRN puts every Galileo satellite one slot
    # along and grades it against its neighbour, which reads as 43 000 km — most of an orbit's
    # diameter, the signature of comparing a satellite with a different one rather than of a bad
    # orbit. See the note beside `idx` in pgnss-build.py's `_fit_job`.
    return dict(prn=u(0, 4, 0) + 1, toc=u(4, 4, 0) * 60,
                af1=u(12, 4, 1) * 2 ** -46, af0=u(16, 4, 1) * 2 ** -34, toe=u(24, 4, 0) * 60,
                omega=u(28, 4, 1) * 2 ** -31 * PI, dn=u(32, 4, 1) * 2 ** -43 * PI,
                m0=u(36, 4, 1) * 2 ** -31 * PI, omegadot=u(40, 4, 1) * 2 ** -43 * PI,
                e=u(44, 4, 0) * 2 ** -33, idot=u(48, 2, 1) * 2 ** -43 * PI,
                sqrtA=u(52, 4, 0) * 2 ** -19, i0=u(56, 4, 1) * 2 ** -31 * PI,
                omega0=u(60, 4, 1) * 2 ** -31 * PI,
                crs=u(64, 2, 1) * 2 ** -5, cis=u(66, 2, 1) * 2 ** -29,
                cus=u(68, 2, 1) * 2 ** -29, crc=u(70, 2, 1) * 2 ** -5,
                cic=u(72, 2, 1) * 2 ** -29, cuc=u(74, 2, 1) * 2 ** -29)


def dec_glo(rec):
    """GLONASS is a STATE VECTOR, not a Kepler set — there is nothing to propagate at its own tb.

    `Records.encodeGlonass`: idx u16, tb u16, tau i32 (2**-30 s, NEGATED — the field is tau_n and
    SP3 publishes the bias itself), a spare word, the satellite-type flag, then per axis position
    (km, 2**-11), velocity (km/s, 2**-20) and acceleration (km/s^2, 2**-30, one signed byte).

    The slot is 1-BASED here, alone among the four systems. See `Records`' own note: Huawei's block 0
    carries index 0, which cannot be a slot number because GLONASS slots start at 1.
    """
    u = _u(rec)
    p, v = [], []
    for base in (16, 28, 40):
        p.append(u(base, 4, 1) * 2 ** -11 * 1e3)
        v.append(u(base + 4, 4, 1) * 2 ** -20 * 1e3)
    return dict(prn=u(0, 2, 0) + 1, tb=u(2, 2, 0),
                # af0 and af1 in the shape the rest of this script expects, so one clock check
                # serves all four systems. GLONASS broadcasts no drift term at all.
                af0=-u(4, 4, 1) * 2 ** -30, af1=0.0, toc=None, toe=None,
                sqrtA=float("nan"), state=(np.array(p), np.array(v)))


SYSTEMS = {                              # reclen, capacity, decoder, SP3 prefix, seconds GPS -> own
    "GPS": (80, 32, dec_gps, "G", 0),
    "GALILEO": (76, 36, dec_gal, "E", 0),
    "BDS": (92, 63, dec_bds, "C", pgb.BDT_OFFSET),
    # 8 sub-blocks per block, 900 s apart, and the file is `HW_PGNSS_GLONASS`. Never graded once
    # before 2026-09-14 — the table simply had no row for it, so `--system GLONASS` died with a
    # KeyError and a quarter of the constellation went out unchecked for the life of the feature.
    "GLONASS": (52, 24, dec_glo, "R", 0),
}
SUBS = {"GLONASS": (8, 900.0)}           # sub-blocks per block, and their spacing


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("sp3")
    ap.add_argument("--system", default="BDS")
    ap.add_argument("--all-epochs", action="store_true",
                    help="use every epoch of the product, not just each file's observed first day; "
                         "needed for a five-day prediction, and the only way to reach a window that "
                         "has not happened yet — but then the comparison is prediction against "
                         "prediction, not against measurement")
    ap.add_argument("--step", type=float, default=1800.0,
                    help="sample this often inside each block's own 2-hour slice")
    args = ap.parse_args()

    reclen, cap, dec, prefix, offset = SYSTEMS[args.system]
    path = pathlib.Path(args.dir) / f"HW_PGNSS_{args.system}"
    if not path.is_file():
        sys.exit(f"{path} does not exist")
    b = path.read_bytes()

    truth = pgb.merge_sp3(sorted(glob.glob(args.sp3)), prefix=prefix,
                          keep=None if args.all_epochs else 86400.0)
    if not truth:
        sys.exit(f"no {prefix} satellites in {args.sp3}")
    tmin = min(d["t"][4] for d in truth.values())
    tmax = max(d["t"][-5] for d in truth.values())
    print(f"{path}\n  truth: {len(truth)} satellites, {utc(tmin):%Y-%m-%d %H:%M} .. "
          f"{utc(tmax):%Y-%m-%d %H:%M} UTC "
          f"({'every epoch, predictions included' if args.all_epochs else 'observed halves only'})")

    first = struct.unpack_from("<III", b, 0)[0]
    kinds = {}
    if args.system == "BDS":
        for sat, d in truth.items():
            # Classify at the first epoch the satellite's OWN arc covers, not at the window's start.
            #
            # It used to require the arc to span `first`, and a satellite whose product coverage
            # begins later than the window simply got no class. That is not a cosmetic gap: a GEO
            # with no class is propagated with the ordinary variant, which is the wrong formula for
            # a geostationary orbit, and C02 duly graded at 3.3e13 m. A whole day was spent on that
            # "BeiDou divergence" before it turned out to be this line — the grader was wrong, not
            # the set. Anything that reads like a satellite having left the solar system is a bug in
            # the measurement.
            t = min(max(first, d["t"][4]), d["t"][-5])
            kinds[sat] = pgb.bds_kind(d, t)

    nsub, subgap = SUBS.get(args.system, (1, 0.0))
    rows, semi, skipped, clocks = {}, {}, set(), {}
    for i in range(BLOCKS):
        ts, off, blen = struct.unpack_from("<III", b, 12 * i)
        for sub in range(nsub):
            at = off + sub * (4 + cap * reclen)
            n = struct.unpack_from("<I", b, at)[0]
            # GLONASS SUB-EPOCHS SIT ON THE UTC HOUR, not on the block stamp.
            #
            # The block stamp is :59:42 in UTC and the GLONASS stamps are an hour earlier than the
            # other constellations' — see `Records.glonassHour`, which floors the stamp to the hour
            # in UTC and adds the leap seconds back. Sampling at `stamp + 900*sub` instead put every
            # comparison up to an hour and eighteen minutes away from the state it was reading, and
            # GLONASS covers about 12 000 km in an hour. That is exactly what this first reported:
            # a median "error" of 11 923 km, on a set whose decoded radii are a flawless 25 508 km
            # and which is byte-for-byte the same shape as the set that fixed in 13 s. The
            # instrument was wrong, which is the failure this file already warns about for BeiDou.
            tsub = (LEAP + 3600 * ((ts - LEAP) // 3600)) + sub * subgap if nsub > 1 else ts
            for k in range(min(n, cap)):
                rec = b[at + 4 + k * reclen: at + 4 + (k + 1) * reclen]
                if not any(rec):
                    continue
                el = dec(rec)
                sat = f"{prefix}{el['prn']:02d}"
                semi[sat] = el["sqrtA"] ** 2 / 1000.0
                if sat not in truth:
                    continue
                # ── the CLOCK, which nothing graded until 2026-09-14 ───────────────────────────
                #
                # A range is orbit plus clock and every check here was orbit-only, so BeiDou C10
                # shipped a clock line 150x too steep and the wrong sign — 1.1 km of range in every
                # block past the splice — behind a 0.03 m orbit grade. The floor of this comparison
                # is the CONVENTIONS: a broadcast clock applies the relativistic eccentricity
                # term separately and carries the group delay of its own signal pair, while an SP3
                # clock already contains the one and is referenced to another. Measured 2026-09-14
                # that floor sits at a median of 15 m for GPS, Galileo and GLONASS, and 0.01 m for
                # BeiDou, whose clock is spliced straight from the product. **It is a floor, not a
                # calibration** — no known-good vintage has been graded here, so read the SPREAD and
                # not the level. C10 that day read 8569 m against a fleet whose worst was 63 m, and
                # that is the shape a real fault makes.
                def clock_at(t):
                    """|ours - the product's|, as range, at one instant. None where it cannot be asked."""
                    if not (tmin < t < tmax) or not pgb.spanned(truth[sat]["t"], t):
                        return None
                    want_c = pgb.interp(truth[sat]["t"], truth[sat]["c"], t)
                    if not np.isfinite(want_c):
                        return None
                    dtc = 0.0 if el["toc"] is None else (
                        ((t - offset) % 604800 - el["toc"] + 302400) % 604800 - 302400)
                    return abs(float(el["af0"] + el["af1"] * dtc - want_c)) * 299792458.0

                if args.system == "GLONASS":
                    c = clock_at(tsub)
                    if c is not None:
                        clocks.setdefault(sat, []).append(c)
                    # No propagation: the record IS the state at its own tb, so the comparison is
                    # the position it carries against the product at the same instant.
                    if not (tmin < tsub < tmax) or not pgb.spanned(truth[sat]["t"], tsub):
                        skipped.add(sat)
                        continue
                    want = pgb.interp(truth[sat]["t"], truth[sat]["p"], tsub)
                    rows.setdefault(sat, []).append(
                        (tsub - first, float(np.linalg.norm(el["state"][0] - want))))
                    continue
                for dt in np.arange(-3600, 3601, args.step):
                    t = ts + dt
                    if not (tmin < t < tmax):
                        continue
                    if not pgb.spanned(truth[sat]["t"], t):
                        skipped.add(sat)
                        continue
                    tow = (t - offset) % 604800
                    if args.system == "BDS":
                        got = pgb.propagate_bds(el, tow, kinds.get(sat) == "GEO")
                    else:
                        got = pgb.propagate(el, tow)
                    want = pgb.interp(truth[sat]["t"], truth[sat]["p"], t)
                    rows.setdefault(sat, []).append((t - first, float(np.linalg.norm(got - want))))
                    c = clock_at(t)
                    if c is not None:
                        clocks.setdefault(sat, []).append(c)

    if skipped:
        print(f"  samples dropped where the product has a hole: {' '.join(sorted(skipped))}")
    if not rows:
        sys.exit("  the window and the product do not overlap — nothing to grade")
    allrows = [r for v in rows.values() for r in v]
    hs = np.array([r[0] for r in allrows]) / 3600.0
    es = np.array([r[1] for r in allrows])
    print(f"  {len(es)} samples over {hs.min():.0f}..{hs.max():.0f} h from the first block\n")
    print(f"  {'hours into window':>18} {'n':>6} {'median':>10} {'p95':>10} {'max':>10}")
    for lo in range(0, 72, 6):
        m = (hs >= lo) & (hs < lo + 6)
        if m.sum():
            print(f"  {lo:>8}..{lo+6:<8} {m.sum():6} {np.median(es[m]):10.2f} "
                  f"{np.percentile(es[m], 95):10.2f} {es[m].max():10.2f}")
    print(f"\n  {'satellite':>10} {'class':>6} {'a km':>9} {'n':>5} {'median':>10} {'max':>12}")
    for sat in sorted(rows, key=lambda s: -np.median([r[1] for r in rows[s]])):
        v = np.array([r[1] for r in rows[sat]])
        print(f"  {sat:>10} {kinds.get(sat, ''):>6} {semi.get(sat, float('nan')):9.0f} "
              f"{len(v):5} {np.median(v):10.2f} {v.max():12.2f}")
    good = np.array([e for s in rows if kinds.get(s) != "GEO" for _, e in rows[s]])
    if len(good) and len(good) != len(es):
        print(f"\n  excluding the GEO satellites: median {np.median(good):.2f} m, "
              f"p95 {np.percentile(good, 95):.2f} m, max {good.max():.2f} m")
    print(f"  overall: median {np.median(es):.2f} m, p95 {np.percentile(es, 95):.2f} m, "
          f"max {es.max():.2f} m")

    if clocks:
        allc = np.array([c for v in clocks.values() for c in v])
        print(f"\n  CLOCK, as range ({len(allc)} samples; conventions put a floor under this "
              f"— read the spread, not the level)")
        print(f"  {'satellite':>10} {'n':>5} {'median':>10} {'max':>12}")
        # Sorted by the WORST sample, not the median. A satellite whose clock is right for the part
        # spliced from the product and wrong for the part extrapolated past it has an excellent
        # median and ships kilometres — C10 on 2026-09-14 read 0.01 m median and 8569 m max.
        for sat in sorted(clocks, key=lambda s: -max(clocks[s]))[:10]:
            v = np.array(clocks[sat])
            print(f"  {sat:>10} {len(v):5} {np.median(v):10.2f} {v.max():12.2f}")
        print(f"  overall: median {np.median(allc):.2f} m, p95 "
              f"{np.percentile(allc, 95):.2f} m, max {allc.max():.2f} m")
    else:
        print("\n  CLOCK: the orbit product carries no clocks — nothing to compare")


if __name__ == "__main__":
    main()
