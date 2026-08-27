#!/usr/bin/env python3
"""Decode sequence_data 700021 — the band's per-beat RR series — and check it against rrisqi.

    python3 scripts/rri-decode-700021.py <700021.bin> <rrisqi.bin>

700021 is a 44-byte record header (start/end epoch, BE uint32) followed by (uint16 LE interval in ms,
uint16 LE quality) pairs until the next header. The variable beat count is why it read as strideless.

The band also writes a PAGE STAMP into every 976th byte of this file — that byte is the page's own
number, not data (see HuaweiPagedFile.kt). Measured 2026-08-27 on a 97789-byte capture: 100 stamps,
91 of them in a quality field's spare high byte and 9 in a record header. Three of those nine took
the low byte of a header's big-endian start, which made the record unreadable and handed its beats
to the previous one — and that, not the band, is the "263 of 266" this script used to report. The
true start survives as a little-endian echo at +0x24, so the headers are found by the echo and the
stamped bytes are skipped rather than read.

None of the stamps has ever landed in an INTERVAL, and that is structural rather than lucky: headers
are 44 bytes and beats are 4, so every beat pair sits at an offset congruent to 1 (mod 4) while the
stamps sit at 0, which puts each one on the same byte of the same field every time. The intervals
these metrics are computed from were never touched.

This is the ground truth that named f5 (RMSSD) and f3 (the RR range on the 20 ms grid) without
Huawei Health: with per-beat intervals, the standard metrics are computable here. Pull both files
with the 健康 tasks バンド書類700021（Huawei） and バンドRR書類（Huawei）.
"""
import struct, sys, math, statistics as st, datetime, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
PLAUS = range(1_780_000_000, 1_800_000_000)

from huawei_700021 import beats as read_700021


def beats(path):
    """The shared reader, with what the page stamps cost reported where it can be seen."""
    records, mended = read_700021(path)
    print(f"page stamps repaired: {mended['headers']} record header(s), {mended['beats']} beat(s)")
    return records


def rrisqi(path):
    b = open(path, "rb").read(); out, off = [], 0x30
    while off + 0x42 <= len(b):
        r = b[off:off+0x42]; off += 0x42
        s, e = struct.unpack_from(">I", r, 1)[0], struct.unpack_from(">I", r, 5)[0]
        if s not in PLAUS or e not in PLAUS or e < s: break
        out.append({"s": s, "e": e, "b13": r[13],
                    "f": {i+1: struct.unpack_from(">f", r, 19+i*4)[0] for i in range(10)}})
    return out

B, R = beats(sys.argv[1]), rrisqi(sys.argv[2])
print(f"700021: {len(B)} records, {sum(len(x['beats']) for x in B)} beats")
print(f"rrisqi: {len(R)} windows\n")

# sanity: beat count vs window span
ok = sum(1 for x in B if x["beats"] and abs(sum(v for v,_ in x["beats"])/1000 - (x["e"]-x["s"])) < 25)
print(f"sum(RR) within 25 s of the window span: {ok}/{len(B)}  <- the series really is the window")
qs = {}
for x in B:
    for _, q in x["beats"]: qs[q] = qs.get(q, 0) + 1
print(f"quality values: {sorted(qs.items(), key=lambda kv: -kv[1])[:6]}\n")

# pair each rrisqi window with the 700021 record whose start is nearest
pairs = []
for w in R:
    c = min(B, key=lambda x: abs(x["s"] - w["s"]))
    if abs(c["s"] - w["s"]) <= 90 and c["beats"]:
        pairs.append((w, c))
print(f"{len(pairs)} rrisqi windows pair with a 700021 record within 90 s\n")

def metrics(arr):
    rr = [v for v, q in arr if 300 <= v <= 2000]
    good = [v for v, q in arr if q == 100 and 300 <= v <= 2000]
    if len(rr) < 3: return None
    d = [rr[i+1]-rr[i] for i in range(len(rr)-1)]
    return {
        "n_all": len(arr), "n_rr": len(rr), "n_good": len(good),
        "mean": st.mean(rr), "sdnn": st.pstdev(rr),
        "rmssd": math.sqrt(sum(x*x for x in d)/len(d)) if d else 0,
        "pnn50": 100.0*sum(1 for x in d if abs(x) > 50)/len(d) if d else 0,
        "min": min(rr), "max": max(rr), "range": max(rr)-min(rr),
        "cv": st.pstdev(rr)/st.mean(rr),
    }

cands = {"mean": [], "sdnn": [], "rmssd": [], "pnn50": [], "min": [], "max": [], "range": [], "cv": [],
         "n_all": [], "n_rr": [], "n_good": []}
fields = {i: [] for i in range(1, 11)}; b13 = []
for w, c in pairs:
    m = metrics(c["beats"])
    if not m: continue
    for k in cands: cands[k].append(m[k])
    for i in range(1, 11): fields[i].append(w["f"][i])
    b13.append(w["b13"])

def pear(a, bb):
    ma, mb = st.mean(a), st.mean(bb)
    va = math.sqrt(sum((x-ma)**2 for x in a)); vb = math.sqrt(sum((x-mb)**2 for x in bb))
    return float("nan") if va == 0 or vb == 0 else sum((x-ma)*(y-mb) for x, y in zip(a, bb))/(va*vb)

print(f"{'':>6} " + " ".join(f"{k:>7}" for k in cands))
for i in list(range(1, 11)):
    print(f"f{i:<5} " + " ".join(f"{pear(fields[i], cands[k]):>+7.2f}" for k in cands))
print(f"{'byte13':>6} " + " ".join(f"{pear(b13, cands[k]):>+7.2f}" for k in cands))

print("\n=== confirmed pairs only: the anchor must agree ===")
# f6 is established as the mean RR on a 20 ms grid. Keep only pairs where the computed mean lands
# within one grid step of it: that is the same window, not merely a nearby one.
conf = []
for w, c in pairs:
    m = metrics(c["beats"])
    if m and abs(m["mean"] - w["f"][6]) <= 20: conf.append((w, c, m))
print(f"{len(conf)} of {len(pairs)} pairs confirmed by the anchor (|mean - f6| <= 20 ms)")
if conf:
    F = {i: [w["f"][i] for w, c, m in conf] for i in range(1, 11)}
    C = {k: [m[k] for w, c, m in conf] for k in cands}
    B13 = [w["b13"] for w, c, m in conf]
    print(f"  anchor check: f6 vs computed mean r = {pear(F[6], C['mean']):+.3f}")
    print(f"\n{'':>6} " + " ".join(f"{k:>7}" for k in C))
    for i in range(1, 11):
        print(f"f{i:<5} " + " ".join(f"{pear(F[i], C[k]):>+7.2f}" for k in C))
    print(f"{'byte13':>6} " + " ".join(f"{pear(B13, C[k]):>+7.2f}" for k in C))

    print("\n=== exact-equality tests ===")
    def eq(a, b, tol=0.5): return sum(1 for x, y in zip(a, b) if abs(x-y) <= tol)
    print(f"  f1 == count of quality-100 beats : {eq(F[1], C['n_good'])}/{len(conf)}")
    print(f"  f1 == count of plausible RRs     : {eq(F[1], C['n_rr'])}/{len(conf)}")
    print(f"  byte13 == all beats in record    : {eq(B13, C['n_all'])}/{len(conf)}")
    print(f"  byte13 == plausible RRs          : {eq(B13, C['n_rr'])}/{len(conf)}")
    for i in (2,3,4,5,7,8,9,10):
        for k in ("sdnn","rmssd","pnn50","min","max","range","cv"):
            n = eq(F[i], C[k], tol=max(0.02*abs(st.mean(C[k])), 0.5))
            if n > len(conf)*0.5:
                print(f"  f{i} ~= {k} within 2% : {n}/{len(conf)}")
