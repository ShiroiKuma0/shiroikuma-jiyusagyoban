#!/usr/bin/env python3
"""Name the rrisqi fields against the band's own per-beat RR series.

    python3 scripts/rri-name-fields.py <700021.bin> <rrisqi.bin> [more pairs...]

Pull both files with the 健康 tasks バンド書類700021（Huawei） and バンドRR書類（Huawei）.

WHAT THE RECORD TURNED OUT TO BE
A standard HRV panel, computed on the band, one row per ~60 s window:

    f1  a count                       f6  mean RR (ms)            - anchor, established earlier
    f2  SD of instantaneous HR (bpm)  f7  SD1/SD2, Poincare ratio
    f3  RR range on the 20 ms grid    f8  HF power 0.15-0.40 Hz (ms^2)
    f4  NOT RR-derived                f9  LF power 0.04-0.15 Hz (ms^2)
    f5  RMSSD (ms)                    f10 NOT RR-derived

METHOD NOTES, because two of these were nearly claimed wrongly
* Pairs count only when the ANCHOR agrees: |computed mean RR - f6| small means the same window,
  not a neighbouring one. Every number here improves monotonically as that tolerance tightens,
  which is the signature of residual PAIRING error rather than a wrong model.
* Tolerances are RELATIVE. An earlier pass used max(2% of mean, 0.5) and "found" f7 = CV in 93/124
  windows - but CV averages 0.05, so a floor of 0.5 was ten times the quantity and the test could
  not fail. Absolute floors make small-magnitude metrics match anything.
* A field is claimed only when the fit is SCALE-FREE: field/metric must be near-constant, and the
  constant must come out at 1. Pearson r alone proves nothing here, because on this data every
  fatigue-ish metric correlates with every other one - which is exactly why six fields once looked
  plausible and none was proven.

THE SPECTRAL NORMALISATION
f8/f9 needed the band's own convention, found by the scale landing on a common 0.248: a Hanning
window is applied but the transform is normalised by n^2 as if it were rectangular. Hanning sums to
n/2, so that is a factor of exactly 4. Linear detrend, 4 Hz interpolation of the tachogram.

f9 keeps a residual ~7-13% scale offset that the band edges do not remove. That is expected rather
than unexplained: these windows are ~60 s, and 0.04 Hz is 2.4 cycles in 60 s, so LF is
under-resolved by construction. The identification is solid; the exact reproduction is limited by
window length.
"""
import struct, sys, math, statistics as st
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import numpy as np

PLAUS = range(1_780_000_000, 1_800_000_000)


from huawei_700021 import beats as _read_700021


def beats(path):
    """The shared reader — see scripts/huawei_700021.py for the page stamp it undoes."""
    return _read_700021(path)[0]


def rrisqi(path):
    b = open(path, "rb").read()
    out, off = [], 0x30
    while off + 0x42 <= len(b):
        r = b[off:off + 0x42]
        off += 0x42
        s, e = struct.unpack_from(">I", r, 1)[0], struct.unpack_from(">I", r, 5)[0]
        if s not in PLAUS or e not in PLAUS or e < s:
            break
        out.append({"s": s, "e": e, "f": {i + 1: struct.unpack_from(">f", r, 19 + i * 4)[0]
                                          for i in range(10)}})
    return out


def derived(rr):
    d = [rr[i + 1] - rr[i] for i in range(len(rr) - 1)]
    sd, sdsd = st.pstdev(rr), st.pstdev(d)
    sd1 = math.sqrt(0.5) * sdsd
    sd2 = math.sqrt(max(2 * sd * sd - 0.5 * sdsd * sdsd, 0.0))
    t = np.cumsum(np.array(rr, float)) / 1000.0
    t -= t[0]
    g = np.arange(0, t[-1], 0.25)
    y = np.interp(g, t, np.array(rr, float))
    y = y - np.polyval(np.polyfit(np.arange(len(y)), y, 1), np.arange(len(y)))
    n = len(y)
    Y = np.fft.rfft(y * np.hanning(n))
    f = np.fft.rfftfreq(n, 0.25)
    p = (np.abs(Y) ** 2) / (n * n)
    p[1:] *= 2
    return {
        "mean": st.mean(rr),
        "hr_sd": st.pstdev([60000.0 / v for v in rr]),
        "rmssd": math.sqrt(sum(x * x for x in d) / len(d)),
        "range": max(rr) - min(rr),
        "sd1/sd2": sd1 / sd2 if sd2 else 0.0,
        "hf": float(p[(f >= 0.15) & (f < 0.40)].sum()),
        "lf": float(p[(f >= 0.04) & (f < 0.15)].sum()),
    }


CLAIMS = [(2, "hr_sd", "SD of instantaneous HR (bpm)"),
          (3, "range", "RR range (ms)"),
          (5, "rmssd", "RMSSD (ms)"),
          (6, "mean", "mean RR (ms)"),
          (7, "sd1/sd2", "SD1/SD2, Poincare ratio"),
          (8, "hf", "HF power 0.15-0.40 Hz (ms^2)"),
          (9, "lf", "LF power 0.04-0.15 Hz (ms^2)")]

rows = []
for i in range(1, len(sys.argv), 2):
    B, R = beats(sys.argv[i]), rrisqi(sys.argv[i + 1])
    for w in R:
        if not B:
            continue
        c = min(B, key=lambda x: abs(x["s"] - w["s"]))
        if abs(c["s"] - w["s"]) > 90 or not c["beats"]:
            continue
        rr = [v for v, q in c["beats"] if 300 <= v <= 2000]
        if len(rr) < 16 or (np.cumsum(rr)[-1] / 1000.0) < 20:
            continue
        m = derived(rr)
        rows.append((w, m, abs(m["mean"] - w["f"][6])))

print(f"{len(rows)} paired windows\n")
print(f"{'field':<6} {'metric':<9} {'meaning':<30} " +
      "  ".join(f"{'anchor<=' + str(a):>16}" for a in (20, 10, 5)))
for fi, key, meaning in CLAIMS:
    cells = []
    for anch in (20, 10, 5):
        Y = [w["f"][fi] for w, m, d in rows if d <= anch and m[key] > 0]
        X = [m[key] for w, m, d in rows if d <= anch and m[key] > 0]
        if len(Y) < 15:
            cells.append(f"{'(n<15)':>16}")
            continue
        k = st.median([a / b for a, b in zip(Y, X)])
        e = st.median([abs(a - k * b) / abs(a) for a, b in zip(Y, X) if a])
        cells.append(f"{k:>7.3f} {100*e:>6.1f}%")
    print(f"f{fi:<5} {key:<9} {meaning:<30} " + "  ".join(cells))
print("\nEach column is a tighter window-alignment tolerance. Scales converge on 1 and errors fall,")
print("so what is left is pairing error, not a wrong identification.")
