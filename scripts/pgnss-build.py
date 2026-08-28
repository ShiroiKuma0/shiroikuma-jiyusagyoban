#!/usr/bin/env python3
"""Build a fresh 72-hour `HW_PGNSS_*` predicted-ephemeris set from CODE's free 5-day prediction.

    python3 scripts/pgnss-build.py [--sp3 code5d.sp3] [--out DIR] [--keep DIR]

WHY THIS EXISTS
The band fixes in ~20 s when it holds a predicted ephemeris set and takes minutes when it does not.
Measured on 白い熊's band: 2026-08-27 08:11, predicted set alive, instant start — 21 s to first fix.
2026-08-28 08:41, forty-one minutes after that set expired, instant start, with fresh broadcast RTCM
served at the moment the band asked — 582 s, by which point he was 403 m from home. Broadcast
ephemeris does not substitute for the predicted set, so the set has to be refreshed, and Huawei's
own predicted endpoint is signed with a credential issued at runtime to Health's package and
certificate. Hence: generate it.

WHAT IT PRODUCES
`HW_PGNSS_GPS`, `HW_PGNSS_GALILEO`, `HW_PGNSS_GLONASS` — 36 blocks 7200 s apart, 72 hours, in the
exact EEV2 layout the band already accepts. `HW_PGNSS_EXTRA` (almanacs, GLONASS channel table,
Klobuchar iono) is copied from the capture unchanged: it is static and does not expire on this
timescale. BeiDou and QZSS are copied too — there is no free 5-day product for either, and QZSS is
regional to the Asia-Pacific anyway, so it is irrelevant in Prague.

THE FORMAT, as decoded 2026-08-26 and re-verified here
* 1008-byte header: 36 × (u32 full GPS seconds, u32 offset, u32 length), then 576 zero bytes.
* Each block: u32 count, then a fixed-capacity zero-padded record array — GPS 32 × 80 B,
  Galileo 36 × 76 B, GLONASS 8 sub-blocks of (u32 count + 24 × 52 B) at 900 s steps.
* Satellite id is a 0-BASED INDEX: PRN = idx + 1.
* Records are ordinary broadcast Kepler element sets, except GLONASS, which is an ECEF state
  vector with luni-solar acceleration — mirroring each system's own broadcast form.
* Galileo uses a different field ORDER from GPS and its toe/toc are in 60 s units, not 16 s.

WHY A FIT AND NOT A CONVERSION
Extrapolating a broadcast Kepler set is useless — 28 m at toe+4 h, 438 m at +24 h. What works is
fitting fresh elements to a precise predicted orbit, one element set per 2-hour slice, against the
very propagator the band will use. That is what the file is: 36 slices precisely so each set is
only ever evaluated near its own toe.
"""
import argparse
import datetime
import math
import multiprocessing
import pathlib
import shutil
import struct
import sys

import numpy as np
from scipy.optimize import least_squares

MU = 3.986005e14
OMEGA_E = 7.2921151467e-5
PI = 3.1415926535898
GPS_EPOCH = datetime.datetime(1980, 1, 6, tzinfo=datetime.timezone.utc)
LEAP = 18                      # GPS − UTC, seconds, as of 2026
BLOCKS = 36
STEP = 7200                    # seconds between blocks; 36 × 7200 = 72 h

CAPACITY = {"GPS": 32, "GALILEO": 36, "GLONASS": 24}
RECLEN = {"GPS": 80, "GALILEO": 76, "GLONASS": 52}


# ── SP3 ───────────────────────────────────────────────────────────────────────────────────────────
def read_sp3(path):
    """{satellite: {'t': GPS seconds, 'p': ECEF metres (N,3), 'c': clock seconds}}.

    Positions are ITRF/ECEF, which is the frame the broadcast propagator outputs, so no rotation is
    needed anywhere in this file. Clock values of 999999.999999 are SP3's "not available".
    """
    sats = {}
    t = None
    for line in open(path, "r", errors="replace"):
        if line.startswith("*"):
            f = line.split()
            dt = datetime.datetime(
                int(f[1]), int(f[2]), int(f[3]), int(f[4]), int(f[5]),
                tzinfo=datetime.timezone.utc,
            ) + datetime.timedelta(seconds=float(f[6]))
            t = (dt - GPS_EPOCH).total_seconds() + LEAP
        elif line.startswith("P") and t is not None:
            sat = line[1:4].strip()
            x, y, z = (float(line[4:18]), float(line[18:32]), float(line[32:46]))
            try:
                clk = float(line[46:60])
            except ValueError:
                clk = 999999.999999
            d = sats.setdefault(sat, {"t": [], "p": [], "c": []})
            d["t"].append(t)
            d["p"].append((x * 1e3, y * 1e3, z * 1e3))
            d["c"].append(clk * 1e-6 if clk < 999999 else np.nan)
    for s, d in sats.items():
        d["t"] = np.array(d["t"])
        d["p"] = np.array(d["p"])
        d["c"] = np.array(d["c"])
    return sats


def interp(times, values, t, order=9):
    """Lagrange interpolation on the [order] samples nearest [t]. SP3's own recommended reader."""
    i = int(np.searchsorted(times, t))
    lo = max(0, min(i - order // 2, len(times) - order))
    ts = times[lo:lo + order]
    vs = values[lo:lo + order]
    out = np.zeros(vs.shape[1:]) if vs.ndim > 1 else 0.0
    for k in range(len(ts)):
        w = 1.0
        for j in range(len(ts)):
            if j != k:
                w *= (t - ts[j]) / (ts[k] - ts[j])
        out = out + w * vs[k]
    return out


def state_at(d, t):
    """Position and velocity, the velocity by symmetric difference — SP3 carries no velocities."""
    h = 1.0
    p = interp(d["t"], d["p"], t)
    v = (interp(d["t"], d["p"], t + h) - interp(d["t"], d["p"], t - h)) / (2 * h)
    return p, v


# ── the propagator the band uses ──────────────────────────────────────────────────────────────────
def propagate(el, t):
    """Standard broadcast Kepler propagation, ECEF. [t] is GPS seconds of week."""
    A = el["sqrtA"] ** 2
    tk = t - el["toe"]
    tk = (tk + 302400) % 604800 - 302400
    n = math.sqrt(MU / A ** 3) + el["dn"]
    M = el["m0"] + n * tk
    E = M
    for _ in range(25):
        E -= (E - el["e"] * math.sin(E) - M) / (1 - el["e"] * math.cos(E))
    v = math.atan2(math.sqrt(1 - el["e"] ** 2) * math.sin(E), math.cos(E) - el["e"])
    phi = v + el["omega"]
    s2, c2 = math.sin(2 * phi), math.cos(2 * phi)
    u = phi + el["cus"] * s2 + el["cuc"] * c2
    r = A * (1 - el["e"] * math.cos(E)) + el["crs"] * s2 + el["crc"] * c2
    i = el["i0"] + el["cis"] * s2 + el["cic"] * c2 + el["idot"] * tk
    xp, yp = r * math.cos(u), r * math.sin(u)
    Om = el["omega0"] + (el["omegadot"] - OMEGA_E) * tk - OMEGA_E * el["toe"]
    return np.array([
        xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om),
        xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om),
        yp * math.sin(i),
    ])


ORDER = ["sqrtA", "e", "i0", "omega0", "omega", "m0", "dn", "omegadot", "idot",
         "cuc", "cus", "crc", "crs", "cic", "cis"]


def seed_elements(p, v, toe, tow):
    """Osculating Kepler elements from one ECEF state — the fit's starting point.

    ECEF is rotated to an inertial-like frame at toe first, because osculating elements are only
    meaningful there; the fit then absorbs whatever this gets slightly wrong.
    """
    th = OMEGA_E * 0.0
    R = np.array([[math.cos(th), math.sin(th), 0], [-math.sin(th), math.cos(th), 0], [0, 0, 1]])
    r = R @ p
    vi = R @ (v + np.cross(np.array([0, 0, OMEGA_E]), p))
    rn = np.linalg.norm(r)
    h = np.cross(r, vi)
    hn = np.linalg.norm(h)
    evec = np.cross(vi, h) / MU - r / rn
    e = float(np.linalg.norm(evec))
    a = 1.0 / (2.0 / rn - float(vi @ vi) / MU)
    i0 = math.acos(max(-1, min(1, h[2] / hn)))
    nvec = np.array([-h[1], h[0], 0.0])
    nn = np.linalg.norm(nvec)
    Om = math.atan2(nvec[1], nvec[0]) if nn > 0 else 0.0
    w = math.acos(max(-1, min(1, float(nvec @ evec) / (nn * e)))) if nn > 0 and e > 0 else 0.0
    if evec[2] < 0:
        w = 2 * PI - w
    nu = math.acos(max(-1, min(1, float(evec @ r) / (e * rn)))) if e > 0 else 0.0
    if float(r @ vi) < 0:
        nu = 2 * PI - nu
    E = 2 * math.atan2(math.tan(nu / 2) * math.sqrt(1 - e), math.sqrt(1 + e))
    M = E - e * math.sin(E)
    return dict(sqrtA=math.sqrt(a), e=e, i0=i0, omega0=Om + OMEGA_E * toe, omega=w, m0=M,
                dn=0.0, omegadot=-2.5e-9, idot=0.0,
                cuc=0.0, cus=0.0, crc=0.0, crs=0.0, cic=0.0, cis=0.0, toe=toe)


def fit(d, toe_abs, toe_tow, half=3600, samples=25):
    """Fit one element set to the precise orbit over [toe−half, toe+half].

    The residual is evaluated with [propagate] — the band's own algorithm — so what is minimised is
    the error the BAND will see, not the error of some tidier model.
    """
    ts = np.linspace(toe_abs - half, toe_abs + half, samples)
    truth = np.array([interp(d["t"], d["p"], t) for t in ts])
    tows = toe_tow + (ts - toe_abs)
    p, v = state_at(d, toe_abs)
    el = seed_elements(p, v, toe_tow, toe_tow)
    x0 = np.array([el[k] for k in ORDER])
    scale = np.array([1e-3, 1e-9, 1e-9, 1e-9, 1e-9, 1e-9, 1e-12, 1e-12, 1e-12,
                      1e-8, 1e-8, 1e-3, 1e-3, 1e-8, 1e-8])

    def resid(x):
        e = dict(zip(ORDER, x))
        e["toe"] = toe_tow
        return (np.array([propagate(e, tw) for tw in tows]) - truth).ravel()

    # Bound every parameter to what its FIELD can hold, not to what the orbit could want. An
    # unconstrained fit is free to drive idot — a 16-bit count of 2⁻⁴³ semicircles, so |idot| ≤
    # 1.17e-8 rad/s — far past its ceiling and let the encoder clip it: 112 of 1116 GPS records did
    # exactly that, and clipping a rate turns a sub-metre fit into 1.9 km of error at the edge of
    # the slice. Constrained, the fit spends the error on parameters that survive the round trip.
    # The three angles are deliberately UNBOUNDED. Ω0 is referenced to the week start, so it
    # legitimately reaches 30-odd radians — bounding it at ±4π clipped good solutions and forced a
    # refit from a broken point, which is what threw away 631 of 2196 sets. They cost nothing to
    # leave free because [wrap] folds them at encode time; what must be bounded is only what the
    # FIELDS cannot hold no matter how it is written.
    # e up to 0.30, not the 0.05 a nominal MEO needs: Galileo E14 and E18 are the two satellites
    # launched into wrong, highly elliptical orbits (e ≈ 0.16). Bounding e at 0.05 dropped both of
    # them from all 36 blocks. The field is a 32-bit count of 2⁻³³, so it holds up to 0.5.
    inf = np.inf
    lo = np.array([4.0e3, 0.0, -PI, -inf, -inf, -inf, -7.6e-4, -7.6e-4, -1.17e-8,
                   -6.10e-5, -6.10e-5, -1023.0, -1023.0, -6.10e-5, -6.10e-5])
    hi = np.array([7.0e3, 0.30, PI, inf, inf, inf, 7.6e-4, 7.6e-4, 1.17e-8,
                   6.10e-5, 6.10e-5, 1023.0, 1023.0, 6.10e-5, 6.10e-5])
    # Unbounded Levenberg-Marquardt first: it is twenty times faster and nine records in ten land
    # inside the fields anyway. Only the ones that would CLIP are refitted under bounds, which is
    # where the cost is worth paying — a clipped idot cost 1.9 km at the edge of its slice.
    sol = least_squares(resid, x0, x_scale=scale, method="lm", max_nfev=2000)
    if np.any(sol.x < lo) or np.any(sol.x > hi):
        sol = least_squares(resid, np.clip(sol.x, lo + 1e-12, hi - 1e-12), x_scale=scale,
                            bounds=(lo, hi), method="trf", max_nfev=4000)
    out = dict(zip(ORDER, sol.x))
    out["toe"] = toe_tow
    out["rms"] = float(np.sqrt(np.mean(sol.fun ** 2) * 3))
    return out


def clock_fit(d, toe_abs, half=3600):
    """af0/af1 from the predicted clock, or zeros when CODE published none for this satellite."""
    m = (d["t"] >= toe_abs - half) & (d["t"] <= toe_abs + half) & np.isfinite(d["c"])
    if m.sum() < 3:
        return 0.0, 0.0
    x = d["t"][m] - toe_abs
    a1, a0 = np.polyfit(x, d["c"][m], 1)
    return float(a0), float(a1)


# ── encoders ──────────────────────────────────────────────────────────────────────────────────────
def wrap(a):
    """Fold an angle into [−π, π).

    Not cosmetic: the broadcast convention carries Ω0 referenced to the week start, so a fit hands
    back values like 31.4 rad — ten times π. The field is a signed 32-bit count of 2⁻³¹ semicircles,
    so anything outside ±π SATURATES, and a saturated Ω0 puts the satellite on the far side of its
    orbit: 27 000 km of error from a set whose own fit residual was under a metre. Everything the
    propagator does with these angles goes through a sine or a cosine, so folding is free.
    """
    return (a + PI) % (2 * PI) - PI


def sgn(v, bits):
    lo, hi = -(1 << (bits - 1)), (1 << (bits - 1)) - 1
    return int(max(lo, min(hi, round(v))))


def uns(v, bits):
    return int(max(0, min((1 << bits) - 1, round(v))))


def put(buf, off, value, width, signed):
    buf[off:off + width] = int(value).to_bytes(width, "little", signed=signed)


def enc_gps(idx, week, el, af0, af1, toe_tow, toc_tow):
    r = bytearray(80)
    put(r, 0, idx, 2, False)
    put(r, 2, week, 2, False)
    put(r, 8, sgn(wrap(el["m0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 12, sgn(el["dn"] / PI * 2 ** 43, 32), 4, True)
    put(r, 16, uns(el["e"] * 2 ** 33, 32), 4, False)
    put(r, 20, uns(el["sqrtA"] * 2 ** 19, 32), 4, False)
    put(r, 24, sgn(wrap(el["omega0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 28, sgn(wrap(el["i0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 32, sgn(wrap(el["omega"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 36, sgn(el["omegadot"] / PI * 2 ** 43, 32), 4, True)
    put(r, 40, sgn(el["idot"] / PI * 2 ** 43, 16), 2, True)
    put(r, 42, sgn(el["cuc"] * 2 ** 29, 16), 2, True)
    put(r, 44, sgn(el["cus"] * 2 ** 29, 16), 2, True)
    put(r, 46, sgn(el["crc"] * 2 ** 5, 16), 2, True)
    put(r, 48, sgn(el["crs"] * 2 ** 5, 16), 2, True)
    put(r, 50, sgn(el["cic"] * 2 ** 29, 16), 2, True)
    put(r, 52, sgn(el["cis"] * 2 ** 29, 16), 2, True)
    put(r, 54, 0, 1, True)                                   # tgd — not predicted, and small
    put(r, 56, sgn(af0 * 2 ** 31, 32), 4, True)
    put(r, 60, sgn(af1 * 2 ** 43, 32), 4, True)
    put(r, 72, uns(toe_tow // 16, 16), 2, False)
    put(r, 74, uns(toc_tow // 16, 16), 2, False)
    return bytes(r)


def enc_gal(idx, el, af0, af1, toe_tow, toc_tow):
    """Galileo/QZSS 76-byte record — a DIFFERENT field order from GPS, and 60 s time units."""
    r = bytearray(76)
    put(r, 0, idx, 4, False)
    put(r, 4, uns(toc_tow // 60, 32), 4, False)
    put(r, 8, 0, 4, True)                                    # af2
    put(r, 12, sgn(af1 * 2 ** 46, 32), 4, True)
    put(r, 16, sgn(af0 * 2 ** 34, 32), 4, True)
    put(r, 20, 0, 2, True)                                   # tgd
    put(r, 24, uns(toe_tow // 60, 32), 4, False)
    put(r, 28, sgn(wrap(el["omega"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 32, sgn(el["dn"] / PI * 2 ** 43, 32), 4, True)
    put(r, 36, sgn(wrap(el["m0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 40, sgn(el["omegadot"] / PI * 2 ** 43, 32), 4, True)
    put(r, 44, uns(el["e"] * 2 ** 33, 32), 4, False)
    put(r, 48, sgn(el["idot"] / PI * 2 ** 43, 16), 2, True)
    put(r, 52, uns(el["sqrtA"] * 2 ** 19, 32), 4, False)
    put(r, 56, sgn(wrap(el["i0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 60, sgn(wrap(el["omega0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 64, sgn(el["crs"] * 2 ** 5, 16), 2, True)
    put(r, 66, sgn(el["cis"] * 2 ** 29, 16), 2, True)
    put(r, 68, sgn(el["cus"] * 2 ** 29, 16), 2, True)
    put(r, 70, sgn(el["crc"] * 2 ** 5, 16), 2, True)
    put(r, 72, sgn(el["cic"] * 2 ** 29, 16), 2, True)
    put(r, 74, sgn(el["cuc"] * 2 ** 29, 16), 2, True)
    return bytes(r)


GLO_MU = 3.986004418e14
GLO_AE = 6378136.0
GLO_J2 = 1.0826257e-3


def luni_solar(p, v, a_total):
    """The part of the acceleration the band does NOT model itself.

    Its integrator adds central gravity, J2, and the rotating-frame terms; the record carries only
    the luni-solar residual on top, which is why Huawei's own files sit around 3 µm/s². Storing the
    TOTAL acceleration instead overruns the field — 8 signed bits of 2⁻³⁰ km/s² stop at 118 µm/s² —
    so it saturates and is wrong in the bargain. Measured against the capture: 205 µm/s² (clipped)
    against their 3.09.
    """
    x, y, z = p
    r = math.sqrt(x * x + y * y + z * z)
    mr = GLO_MU / (r * r)
    rho = GLO_AE / r
    k = 1.5 * GLO_J2 * mr * rho * rho
    om = OMEGA_E
    modelled = np.array([
        -mr * (x / r) + k * (x / r) * (5 * z * z / (r * r) - 1) + om * om * x + 2 * om * v[1],
        -mr * (y / r) + k * (y / r) * (5 * z * z / (r * r) - 1) + om * om * y - 2 * om * v[0],
        -mr * (z / r) + k * (z / r) * (5 * z * z / (r * r) - 3),
    ])
    return a_total - modelled


def enc_glo(idx, tb, p, v, a, tau, freq):
    """GLONASS 52-byte state vector: km, km/s, km/s², exactly its own broadcast form."""
    r = bytearray(52)
    put(r, 0, idx, 2, False)
    put(r, 2, tb, 2, False)
    put(r, 4, sgn(tau * 2 ** 30, 32), 4, True)
    put(r, 8, 0, 4, True)
    put(r, 12, sgn(freq, 32), 4, True)
    for k, base in enumerate((16, 28, 40)):
        put(r, base, sgn(p[k] / 1e3 * 2 ** 11, 32), 4, True)
        put(r, base + 4, sgn(v[k] / 1e3 * 2 ** 20, 32), 4, True)
        put(r, base + 8, sgn(a[k] / 1e3 * 2 ** 30, 8), 1, True)
    return bytes(r)


def assemble(stamps, blocks, capacity, reclen, sub=1):
    """Header + blocks. [sub] > 1 nests sub-blocks (GLONASS: 8 per block, 900 s apart)."""
    blen = sub * (4 + capacity * reclen)
    head = bytearray(1008)
    body = bytearray()
    for i, (ts, recsets) in enumerate(zip(stamps, blocks)):
        struct.pack_into("<III", head, 12 * i, ts, 1008 + i * blen, blen)
        for s in range(sub):
            recs = recsets[s] if sub > 1 else recsets
            chunk = bytearray(4 + capacity * reclen)
            struct.pack_into("<I", chunk, 0, len(recs))
            for k, rec in enumerate(recs[:capacity]):
                chunk[4 + k * reclen:4 + (k + 1) * reclen] = rec
            body += chunk
    return bytes(head) + bytes(body)


_SATS = {}


#: A record worse than this is dropped rather than shipped. AGNSS wants tens of metres; a fit that
#: cannot reach 50 m has not converged at all, and a diverged Kepler set does not degrade gracefully
#: — it puts the satellite on the far side of its orbit and would send the receiver hunting in the
#: wrong place. A block with one satellite fewer costs nothing: Huawei's own GPS blocks carry 29.
MAX_ERROR_M = 50.0


def _check(el, d, ts, tow):
    """Worst position error over the slice this set is responsible for, against the precise orbit."""
    return max(
        float(np.linalg.norm(propagate(el, tow + dt) - interp(d["t"], d["p"], ts + dt)))
        for dt in (-3600, -1800, 0, 1800, 3600)
    )


def _fit_job(args):
    """One element set, for the process pool. Top-level because a pool cannot pickle a closure.

    Every set is CHECKED against the orbit it came from before it is allowed out. The fit is a
    fifteen-parameter non-linear solve on someone else's model and a few of them do diverge — the
    first parallel run produced one 27 800 km set among 2196 — and a residual reported by the solver
    is not evidence, because a solver that has wandered off reports its own happiness.
    """
    system, sat, ts = args
    d = _SATS[sat]
    week, tow = divmod(int(ts), 604800)
    el = fit(d, ts, tow)
    err = _check(el, d, ts, tow)
    if err > MAX_ERROR_M:
        el = fit(d, ts, tow, half=1800, samples=13)      # a shorter arc is an easier problem
        err = _check(el, d, ts, tow)
    if err > MAX_ERROR_M:
        return system, ts, sat, None, err
    af0, af1 = clock_fit(d, ts)
    idx = int(sat[1:]) - 1
    rec = (enc_gps(idx, week, el, af0, af1, tow, tow) if system == "GPS"
           else enc_gal(idx, el, af0, af1, tow, tow))
    return system, ts, sat, rec, err


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sp3", default=".scratch/code5d.sp3")
    ap.add_argument("--out", default=".scratch/pgnss-out")
    ap.add_argument("--keep", default=".scratch/hw2/satellite",
                    help="where the captured EXTRA/BDS/QZS are copied from")
    ap.add_argument("--start", default=None, help="first block, ISO UTC; default: now, floored")
    args = ap.parse_args()

    sats = read_sp3(args.sp3)
    print(f"SP3: {len(sats)} satellites, "
          f"{sum(1 for s in sats if s[0]=='G')} GPS / "
          f"{sum(1 for s in sats if s[0]=='R')} GLONASS / "
          f"{sum(1 for s in sats if s[0]=='E')} Galileo")

    if args.start:
        now = datetime.datetime.fromisoformat(args.start).replace(tzinfo=datetime.timezone.utc)
    else:
        now = datetime.datetime.now(datetime.timezone.utc)
    now_gps = (now - GPS_EPOCH).total_seconds() + LEAP
    t0 = int(now_gps // STEP) * STEP
    stamps = [t0 + i * STEP for i in range(BLOCKS)]
    span = sats[next(iter(sats))]["t"]
    if stamps[-1] + 3600 > span[-1]:
        sys.exit(f"the SP3 ends before the 72 h window does "
                 f"({(GPS_EPOCH+datetime.timedelta(seconds=span[-1]-LEAP)):%Y-%m-%d %H:%M} UTC)")
    print(f"window {(GPS_EPOCH+datetime.timedelta(seconds=t0-LEAP)):%Y-%m-%d %H:%M} .. "
          f"{(GPS_EPOCH+datetime.timedelta(seconds=stamps[-1]-LEAP)):%Y-%m-%d %H:%M} UTC (72 h)")

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rms_all = {"GPS": [], "GALILEO": []}

    # 2196 element sets, every one of them independent — so they are fitted across all cores. The
    # serial version took twenty-five minutes, which is long enough that a refresh becomes a chore
    # and chores do not get done every third day.
    globals()["_SATS"] = sats
    jobs = [(system, sat, ts)
            for system, prefix in (("GPS", "G"), ("GALILEO", "E"))
            for ts in stamps
            for sat in sorted(x for x in sats if x[0] == prefix)]
    done = {}
    with multiprocessing.Pool() as pool:
        dropped = []
        for n, (system, ts, sat, rec, rms) in enumerate(pool.imap_unordered(_fit_job, jobs, 8), 1):
            if rec is None:
                dropped.append((system, sat, ts, rms))
                done.setdefault((system, ts), [])
                continue
            done.setdefault((system, ts), []).append((sat, rec))
            rms_all[system].append(rms)
            if n % 200 == 0:
                print(f"  {n}/{len(jobs)} element sets", flush=True)

    if dropped:
        print(f"  dropped {len(dropped)} element sets that would not converge "
              f"(worst {max(d[3] for d in dropped):.0f} m): "
              + ", ".join(f"{d[1]}@{(GPS_EPOCH+datetime.timedelta(seconds=d[2]-LEAP)):%m-%d %H:%M}"
                          for d in dropped[:8]))
    for system in ("GPS", "GALILEO"):
        blocks = [[r for _, r in sorted(done[(system, ts)])] for ts in stamps]
        data = assemble(stamps, blocks, CAPACITY[system], RECLEN[system])
        (out / f"HW_PGNSS_{system}").write_bytes(data)
        print(f"{system}: {len(data)} bytes, {min(len(b) for b in blocks)}..{max(len(b) for b in blocks)} "
              f"satellites per block, worst-case error over its own slice: "
              f"median {np.median(rms_all[system]):.2f} m, max {max(rms_all[system]):.2f} m")

    # GLONASS: state vectors, eight sub-epochs of 900 s per block, and an hour earlier than the rest
    glo_stamps = [t - 3600 for t in stamps]
    gblocks = []
    for ts in glo_stamps:
        subs = []
        for s in range(8):
            t = ts + 900 * s
            recs = []
            for sat in sorted(x for x in sats if x[0] == "R"):
                d = sats[sat]
                if not (d["t"][0] + 5 < t < d["t"][-1] - 5):
                    continue
                p, v = state_at(d, t)
                a = luni_solar(p, v, (state_at(d, t + 1)[1] - state_at(d, t - 1)[1]) / 2.0)
                tau = 0.0 if not np.isfinite(interp(d["t"], d["c"], t)) else float(interp(d["t"], d["c"], t))
                tb = int((t % 86400) // 900)
                recs.append(enc_glo(int(sat[1:]) - 1, tb, p, v, a, tau, 0))
            subs.append(recs)
        gblocks.append(subs)
    data = assemble(glo_stamps, gblocks, CAPACITY["GLONASS"], RECLEN["GLONASS"], sub=8)
    (out / "HW_PGNSS_GLONASS").write_bytes(data)
    print(f"GLONASS: {len(data)} bytes, {len(gblocks[0][0])} satellites per sub-epoch")

    keep = pathlib.Path(args.keep)
    for name in ("EXTRA", "BDS", "QZS"):
        src = keep / f"HW_PGNSS_{name}.bin"
        if src.is_file():
            shutil.copy(src, out / f"HW_PGNSS_{name}")
            print(f"{name}: copied unchanged from the capture ({src.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
