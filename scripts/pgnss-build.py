#!/usr/bin/env python3
"""Build a fresh 72-hour `HW_PGNSS_*` predicted-ephemeris set from free orbit products.

    python3 scripts/pgnss-build.py [--sp3 code5d.sp3] [--out DIR] [--keep DIR]
                                   [--bds-sp3 GLOB] [--erp GLOB] [--gfc EGM96.gfc]

WHY THIS EXISTS
The band fixes in ~20 s when it holds a predicted ephemeris set and takes minutes when it does not.
Measured on 白い熊's band: 2026-08-27 08:11, predicted set alive, instant start — 21 s to first fix.
2026-08-28 08:41, forty-one minutes after that set expired, instant start, with fresh broadcast RTCM
served at the moment the band asked — 582 s, by which point he was 403 m from home. Broadcast
ephemeris does not substitute for the predicted set, so the set has to be refreshed, and Huawei's
own predicted endpoint is signed with a credential issued at runtime to Health's package and
certificate. Hence: generate it.

WHAT IT PRODUCES
`HW_PGNSS_GPS`, `HW_PGNSS_GALILEO`, `HW_PGNSS_GLONASS`, `HW_PGNSS_BDS` — 36 blocks 7200 s apart,
72 hours, in the exact EEV2 layout the band already accepts. `HW_PGNSS_EXTRA` (almanacs, GLONASS
channel table, Klobuchar iono) is copied from the capture unchanged: it is static and does not expire
on this timescale. QZSS is still copied — it is regional to the Asia-Pacific and irrelevant in
Prague, so it has not been worth the second data source.

WHERE THE ORBITS COME FROM
GPS, Galileo and GLONASS are fitted to CODE's free five-day prediction, which spans the window
outright. BeiDou has no such product: every BeiDou-bearing file on every anonymous mirror is 48
hours, 24 observed and 24 predicted, and CODE's own five-day file says in its header that it is
derived from "Ultra-Rapid GRE orbits" — G, R and E, no C (re-checked 2026-08-30). So the BeiDou
window is made in two pieces: Wuhan's `WUM0MGXNRT` product is used directly for whichever blocks it
reaches, and the rest is INTEGRATED here, from a dynamical fit to the observed half. See
`scripts/pgnss_orbit.py` for the propagator and `scripts/pgnss-grade.py` for how it is graded.

WHERE THE INPUTS COME FROM (all anonymous, no login)
  CODE 5-day prediction   https://download.aiub.unibe.ch/CODE/COD0OPSPRD_05D.SP3        -> --sp3
  its predicted ERP       https://download.aiub.unibe.ch/CODE/COD0OPSPRD_<yyyy><doy>0000_21D_06H_ERP.ERP
                                                                                        -> --erp
  Wuhan multi-GNSS 48 h   ftp://igs.ign.fr/pub/igs/products/<gpsweek>/
                          WUM0MGXNRT_<yyyy><doy><hh>00_02D_05M_ORB.SP3.gz  (hourly)      -> --bds-sp3
                          IGN mirrors Wuhan's product byte for byte and serves it ~80x
                          faster; curl needs -u anonymous:anonymous@ there, and the files
                          are NOT under mgex/. Wuhan's own igs.gnsswhu.cn/pub/gps/products/
                          mgex/<gpsweek>/ is the origin and the fallback.
  EGM96 gravity field     https://icgem.gfz-potsdam.de/  -> Table of Models -> EGM96 -> gfc
                                                                                        -> --gfc
The ERP is needed because the integration runs in the terrestrial frame and has to know where the
pole is; see `Frame` in pgnss_orbit.py for why 0.3 arcseconds of it is worth 8.8e-7 m/s^2. Keep
several hours of Wuhan files: the newest gives the freshest arc, the older ones fill the observed
span behind it. Licence: IGS terms, free of cost or obligation with acknowledgement; CODE asks for
DOI 10.48620/97774; EGM96 is Lemoine et al., NASA/TP-1998-206861.

THE FORMAT, as decoded 2026-08-26 and re-verified here
* 1008-byte header: 36 × (u32 full GPS seconds, u32 offset, u32 length), then 576 zero bytes.
* Each block: u32 count, then a fixed-capacity zero-padded record array — GPS 32 × 80 B,
  Galileo 36 × 76 B, BeiDou 63 × 92 B, GLONASS 8 sub-blocks of (u32 count + 24 × 52 B) at 900 s.
* Satellite id is a 0-BASED INDEX: PRN = idx + 1.
* Records are ordinary broadcast Kepler element sets, except GLONASS, which is an ECEF state
  vector with luni-solar acceleration — mirroring each system's own broadcast form.
* Galileo uses a different field ORDER from GPS and its toe/toc are in 60 s units, not 16 s.
* BeiDou is a third order again, in 8 s units of the BeiDou week (BDT = GPS − 14 s), with finer
  harmonic scalings, and its four geostationary satellites use a DIFFERENT propagation formula.

WHY A FIT AND NOT A CONVERSION
Extrapolating a broadcast Kepler set is useless — 28 m at toe+4 h, 438 m at +24 h. What works is
fitting fresh elements to a precise predicted orbit, one element set per 2-hour slice, against the
very propagator the band will use. That is what the file is: 36 slices precisely so each set is
only ever evaluated near its own toe.
"""
import argparse
import datetime
import glob
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
#: BeiDou runs on its OWN constants and its own time scale, and both matter.
#:
#: The rotation rate differs from GPS's in the eleventh digit, which would be beneath notice except
#: that the propagation formula carries a term −ω_e·t_oe: the difference is multiplied not by the
#: few thousand seconds since toe but by the up-to-604800 seconds since the start of the week, so
#: 1.5e-12 rad/s becomes 8.9e-7 rad, 25 m at MEO. Graded on HUAWEI's own records against Wuhan's
#: orbits, the BDS value reads 2.42 m median and the GPS value 4.67 m (2026-08-30).
BDS_MU = 3.986004418e14
BDS_OMEGA = 7.2921150e-5
#: BDT = GPS − 14 s, and toe/toc are seconds of the BeiDou week. Every one of the 2236 records in
#: the two captured vintages has toe == (block stamp − 14) mod 604800, exactly.
BDT_OFFSET = 14
PI = 3.1415926535898
GPS_EPOCH = datetime.datetime(1980, 1, 6, tzinfo=datetime.timezone.utc)
LEAP = 18                      # GPS − UTC, seconds, as of 2026
BLOCKS = 36
STEP = 7200                    # seconds between blocks; 36 × 7200 = 72 h

CAPACITY = {"GPS": 32, "GALILEO": 36, "GLONASS": 24, "BDS": 63}
RECLEN = {"GPS": 80, "GALILEO": 76, "GLONASS": 52, "BDS": 92}


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
            # NO leap seconds here. SP3 epochs are already GPS time — every file we read declares
            # `%c M  cc GPS`, and its own `##` week/seconds-of-week equals the PLAIN difference of
            # its first calendar epoch (code5d: 2433 518400 == 1471996800, plain; +18 disagrees by
            # exactly 18 s). Adding LEAP shifted the entire truth timeline, so every element set was
            # fitted to satellites 18 seconds from where they were — about 53 km along-track.
            #
            # It survived because the generator graded itself against the same shifted clock it
            # fitted to: the reported residual was 0.3 m and the shipped file was 53 692 m out. The
            # only checks that could see it are ones that do not share the clock — Huawei's own file
            # against the same orbits reads 1.79 m, and against the captured RTCM broadcast, with no
            # precise orbit involved at all, 0.80 m (2026-08-29).
            #
            # LEAP is still right in `now_gps` and in every display below: those convert real UTC.
            t = (dt - GPS_EPOCH).total_seconds()
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


def spanned(times, t, order=9, tol=1.5):
    """Does the interpolation stencil around [t] sit on CONTIGUOUS samples?

    Wuhan drops a satellite from a file now and then — C01 is absent from the 2026-08-27 issue and
    C03 from the 2026-08-28 one — which leaves a 24-hour hole in an otherwise 300 s series. A
    9-point Lagrange polynomial fitted across a hole that size does not interpolate, it invents:
    C01 and C03 were graded at 37 km and 32 km against nothing but the interpolator's imagination,
    and the orbits were fine (2026-08-30). Anything that reads one of these series has to ask first.
    """
    i = int(np.searchsorted(times, t))
    lo = max(0, min(i - order // 2, len(times) - order))
    hi = min(lo + order, len(times))
    if hi - lo < order:
        return False
    step = np.median(np.diff(times[lo:hi]))
    return bool(times[hi - 1] - times[lo] <= tol * step * (order - 1))


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


#: The rotation the BeiDou ICD applies to GEO satellites, as a matrix acting on the vector.
#:
#: GEO records are not evaluated with the formula above. Their Ω_k drops the −ω_e·t_k term, and the
#: result is then turned by R_X(−5°) and R_Z(ω_e·t_k) — so the elements describe an orbit inclined
#: 5° in an intermediate frame, which is why Huawei's four GEOs carry i0 of 3-6° rather than the
#: fraction of a degree they actually fly at.
#:
#: THE SIGN IS THE OTHER WAY ROUND from `.scratch/pgnss-sem/bdsgeo.py`, which has it inverted.
#: Settled by measurement, not by reading: Huawei's own C01-C04 records against Wuhan's orbits read
#: 5-10 m with this sign, 2.5-7.2 Mm with the other, and 1.3-3.6 Mm with no rotation at all — the
#: latter being the "1257-3623 km of pure latitude" the earlier note describes (2026-08-30).
_BDS_GEO_TILT = math.radians(5.0)


def propagate_bds(el, t, geo=False):
    """BeiDou broadcast propagation, ECEF. [t] is BDT seconds of week. [geo] picks the GEO variant."""
    A = el["sqrtA"] ** 2
    tk = t - el["toe"]
    tk = (tk + 302400) % 604800 - 302400
    n = math.sqrt(BDS_MU / A ** 3) + el["dn"]
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
    if not geo:
        Om = el["omega0"] + (el["omegadot"] - BDS_OMEGA) * tk - BDS_OMEGA * el["toe"]
        return np.array([
            xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om),
            xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om),
            yp * math.sin(i),
        ])
    Om = el["omega0"] + el["omegadot"] * tk - BDS_OMEGA * el["toe"]
    g = np.array([
        xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om),
        xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om),
        yp * math.sin(i),
    ])
    cp, sp = math.cos(_BDS_GEO_TILT), math.sin(_BDS_GEO_TILT)
    y1 = g[1] * cp - g[2] * sp
    z1 = g[1] * sp + g[2] * cp
    q = BDS_OMEGA * tk
    return np.array([g[0] * math.cos(q) + y1 * math.sin(q),
                     -g[0] * math.sin(q) + y1 * math.cos(q),
                     z1])


def geo_frame(p, v, omega_e=BDS_OMEGA):
    """Undo the GEO tilt so a GEO can be seeded with the same osculating-element extraction.

    The forward map is r_ecef = R_Z(ω_e t_k) · A · g with A the 5° tilt; at t_k = 0 that leaves
    g = A⁻¹ r and, differentiating, ġ = A⁻¹ (v + ω × r) — the inertial velocity, tilted.
    """
    cp, sp = math.cos(_BDS_GEO_TILT), math.sin(_BDS_GEO_TILT)
    inv = np.array([[1, 0, 0], [0, cp, sp], [0, -sp, cp]])
    vi = v + np.cross(np.array([0.0, 0.0, omega_e]), p)
    g = inv @ p
    gv = inv @ vi
    return g, gv - np.cross(np.array([0.0, 0.0, omega_e]), g)


ORDER = ["sqrtA", "e", "i0", "omega0", "omega", "m0", "dn", "omegadot", "idot",
         "cuc", "cus", "crc", "crs", "cic", "cis"]


def seed_elements(p, v, toe, tow, omega_e=OMEGA_E):
    """Osculating Kepler elements from one ECEF state — the fit's starting point.

    ECEF is rotated to an inertial-like frame at toe first, because osculating elements are only
    meaningful there; the fit then absorbs whatever this gets slightly wrong.
    """
    th = omega_e * 0.0
    R = np.array([[math.cos(th), math.sin(th), 0], [-math.sin(th), math.cos(th), 0], [0, 0, 1]])
    r = R @ p
    vi = R @ (v + np.cross(np.array([0, 0, omega_e]), p))
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
    return dict(sqrtA=math.sqrt(a), e=e, i0=i0, omega0=Om + omega_e * toe, omega=w, m0=M,
                dn=0.0, omegadot=-2.5e-9, idot=0.0,
                cuc=0.0, cus=0.0, crc=0.0, crs=0.0, cic=0.0, cis=0.0, toe=toe)


def fit(d, toe_abs, toe_tow, half=3600, samples=25, prop=None, seed=None, bounds=None):
    """Fit one element set to the precise orbit over [toe−half, toe+half].

    The residual is evaluated with [prop] — the band's own algorithm, GPS/Galileo by default and
    [propagate_bds] for BeiDou — so what is minimised is the error the BAND will see, not the error
    of some tidier model. [seed] and [bounds] follow the same constellation.
    """
    prop = prop or propagate
    seed = seed or seed_elements
    ts = np.linspace(toe_abs - half, toe_abs + half, samples)
    truth = np.array([interp(d["t"], d["p"], t) for t in ts])
    tows = toe_tow + (ts - toe_abs)
    p, v = state_at(d, toe_abs)
    el = seed(p, v, toe_tow, toe_tow)
    x0 = np.array([el[k] for k in ORDER])
    scale = np.array([1e-3, 1e-9, 1e-9, 1e-9, 1e-9, 1e-9, 1e-12, 1e-12, 1e-12,
                      1e-8, 1e-8, 1e-3, 1e-3, 1e-8, 1e-8])

    def resid(x):
        e = dict(zip(ORDER, x))
        e["toe"] = toe_tow
        return (np.array([prop(e, tw) for tw in tows]) - truth).ravel()

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
    # dn is bounded to [0, 2^16) counts of 2⁻⁴³ semicircles — NON-NEGATIVE, which is a stronger
    # claim than "what the field holds" and deserves its reason. Huawei's own 1044 GPS sets are
    # every one positive, 3.44e-9 to 5.79e-9 rad/s, and bytes 14-15 (the top half of a 32-bit read)
    # are zero in every record of every constellation they ship. Ours drove dn negative in 47 of
    # 1116, which sign-extends 0xFFFF into those two bytes — a pattern their files do not contain.
    # Whether the field is 32 bits or 16 with something else beside it cannot be told from data
    # where the top half is always zero; keeping dn non-negative and inside 16 bits is correct under
    # BOTH readings, which is the point of choosing it. The fit gives up nothing real: a mean-motion
    # correction is physically positive here, and the residual moves onto parameters that survive.
    dn_hi = (2 ** 16 - 1) * PI / 2 ** 43
    lo = np.array([4.0e3, 0.0, -PI, -inf, -inf, -inf, 0.0, -7.6e-4, -1.17e-8,
                   -6.10e-5, -6.10e-5, -1023.0, -1023.0, -6.10e-5, -6.10e-5])
    hi = np.array([7.0e3, 0.30, PI, inf, inf, inf, dn_hi, 7.6e-4, 1.17e-8,
                   6.10e-5, 6.10e-5, 1023.0, 1023.0, 6.10e-5, 6.10e-5])
    if bounds is not None:
        lo, hi = bounds
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


def enc_gps(idx, week, el, af0, af1, toe_tow, toc_tow, tgd=0):
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
    put(r, 54, tgd, 1, True)
    put(r, 56, sgn(af0 * 2 ** 31, 32), 4, True)
    put(r, 60, sgn(af1 * 2 ** 43, 32), 4, True)
    put(r, 72, uns(toe_tow // 16, 16), 2, False)
    put(r, 74, uns(toc_tow // 16, 16), 2, False)
    # 0xFF, because every one of Huawei's 1044 GPS records carries it and none of ours did.
    #
    # It is the same value for every satellite in every one of their 36 epochs, so it is a flag and
    # not data — a per-record "usable" marker is the only reading that fits a constant. We never
    # wrote it, and the set that resulted was accepted, counted down correctly, and produced a
    # 3-4 minute fix where Huawei's own file had produced 21 s (白い熊, 2026-08-29). The orbits were
    # never the problem: the verification propagated them and they are sub-metre. It only ever read
    # the fields it wrote, so the byte nothing wrote was the byte nothing checked.
    put(r, 77, 0xFF, 1, False)
    return bytes(r)


def enc_gal(idx, el, af0, af1, toe_tow, toc_tow, bgd=0):
    """Galileo/QZSS 76-byte record — a DIFFERENT field order from GPS, and 60 s time units."""
    r = bytearray(76)
    put(r, 0, idx, 4, False)
    put(r, 4, uns(toc_tow // 60, 32), 4, False)
    put(r, 8, 0, 4, True)                                    # af2
    put(r, 12, sgn(af1 * 2 ** 46, 32), 4, True)
    put(r, 16, sgn(af0 * 2 ** 34, 32), 4, True)
    put(r, 20, bgd, 2, True)                                 # BGD, 16-bit here rather than GPS's 8
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


def enc_bds(idx, el, af0, af1, toe_bdt, toc_bdt, tail):
    """BeiDou 92-byte record. Times are BDT seconds of week in 8 s units, not GPS and not 16 s.

    [tail] is bytes 20-23 copied verbatim per satellite from the capture — see [read_bds_tail].

    The times MUST already be multiples of 8, and this refuses them otherwise instead of truncating.
    Truncating cost 7.5 km of pure along-track error, and cost it INVISIBLY: the builder's own check
    grades the element set before it is encoded, so it saw the un-truncated toe the fit was anchored
    to and reported 0.60 m while the shipped bytes were 7557 m out. It took a grader that decodes the
    file and compares it against somebody else's orbit to see it (2026-08-30). See BDS_STAMP_OFFSET
    for why the block stamps are what they are.
    """
    if toe_bdt % 8 or toc_bdt % 8:
        raise ValueError(f"BeiDou toe/toc must be multiples of 8 s, got {toe_bdt}/{toc_bdt}")
    r = bytearray(92)
    put(r, 0, idx, 2, False)
    # Bytes 2-3: a 10-bit coarse epoch, floor(toe/512) mod 1024, sitting in the TOP ten bits.
    #
    # It is the same for every satellite in a block and steps by 14 or 15 counts between blocks,
    # which is 7200/512. Read as a plain u16 it is 64 × that, so the low six bits are always clear —
    # a bit-field whose neighbours Huawei leaves empty. Whatever it means, the rule reproduces all
    # 2236 records of both captured vintages exactly, and it is the only field in this record that
    # the byte-position check would otherwise catch us leaving blank (2026-08-30).
    put(r, 2, ((toe_bdt // 512) % 1024) * 64, 2, False)
    put(r, 8, uns(toc_bdt // 8, 32), 4, False)
    put(r, 12, sgn(af0 * 2 ** 33, 32), 4, True)
    put(r, 16, sgn(af1 * 2 ** 50, 32), 4, True)
    r[20:24] = tail
    put(r, 28, uns(toe_bdt // 8, 32), 4, False)
    put(r, 32, uns(el["sqrtA"] * 2 ** 19, 32), 4, False)
    put(r, 36, uns(el["e"] * 2 ** 33, 32), 4, False)
    put(r, 40, sgn(wrap(el["omega"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 44, sgn(el["dn"] / PI * 2 ** 43, 32), 4, True)
    put(r, 48, sgn(wrap(el["m0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 52, sgn(wrap(el["omega0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 56, sgn(el["omegadot"] / PI * 2 ** 43, 32), 4, True)
    put(r, 60, sgn(wrap(el["i0"]) / PI * 2 ** 31, 32), 4, True)
    put(r, 64, sgn(el["idot"] / PI * 2 ** 43, 16), 2, True)
    # BeiDou's harmonics are finer than GPS's: 2⁻³¹ for the angle terms against GPS's 2⁻²⁹, and
    # 2⁻⁶ metres for the radius terms against 2⁻⁵, each in a full 32-bit slot.
    put(r, 68, sgn(el["cuc"] * 2 ** 31, 32), 4, True)
    put(r, 72, sgn(el["cus"] * 2 ** 31, 32), 4, True)
    put(r, 76, sgn(el["crc"] * 2 ** 6, 32), 4, True)
    put(r, 80, sgn(el["crs"] * 2 ** 6, 32), 4, True)
    put(r, 84, sgn(el["cic"] * 2 ** 31, 32), 4, True)
    put(r, 88, sgn(el["cis"] * 2 ** 31, 32), 4, True)
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


def enc_glo(idx, tb, p, v, a, tau):
    """GLONASS 52-byte state vector: km, km/s, km/s², exactly its own broadcast form."""
    r = bytearray(52)
    put(r, 0, idx, 2, False)
    put(r, 2, tb, 2, False)
    # NEGATED. The GLONASS field is tau_n = -(clock bias); SP3 publishes the bias itself. Ours wrote
    # it straight through, so every satellite carried the exact negative of its own clock correction
    # — verified against the captured RTCM 1020 broadcast, where Huawei matches tau to 0.01 us on all
    # 21 slots and ours is its mirror image on all 21. Cost 2*|tau|*c of range bias: 3.75 km to
    # 209 km per satellite, median 37.9 km, in 100% of records (2026-08-29).
    put(r, 4, sgn(-tau * 2 ** 30, 32), 4, True)
    put(r, 8, 0, 4, True)
    # The satellite-type flag (GLONASS-M/K), 1 for every satellite flying. Huawei writes 1 in all
    # 11 960 records of both captured vintages; we wrote 0 in all 6048 of ours. This is not the
    # frequency channel — that varies -7..+6 per slot and lives in the almanac, not here.
    put(r, 12, 1, 4, False)
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


def _check(el, d, ts, tow, prop=None):
    """Worst position error over the slice this set is responsible for, against the precise orbit."""
    prop = prop or propagate
    return max(
        float(np.linalg.norm(prop(el, tow + dt) - interp(d["t"], d["p"], ts + dt)))
        for dt in (-3600, -1800, 0, 1800, 3600)
    )


#: What the BeiDou fields can hold, in the same order as ORDER.
#:
#: Every one is the ICD's own field width, cross-checked against the widest value in Huawei's two
#: captured vintages: dn reaches 65308 of its 65535 counts and is never negative (bytes 46-47 are
#: zero in all 2236 records, so the field reads as 16 unsigned bits exactly as GPS's does), idot
#: stays inside a signed 16 at ±3637, cuc/cus reach 74553 of 131071, crc/crs 68689 of 131071, and
#: cic/cis 4018. Bounding to the FIELD rather than to the orbit is what stops the solver spending
#: its error on a parameter that will be clipped at encode time.
BDS_LO = np.array([4.0e3, 0.0, -PI, -np.inf, -np.inf, -np.inf, 0.0, -3.0e-6, -1.1702e-8,
                   -6.104e-5, -6.104e-5, -2048.0, -2048.0, -6.104e-5, -6.104e-5])
BDS_HI = np.array([7.0e3, 0.30, PI, np.inf, np.inf, np.inf, (2 ** 16 - 1) * PI / 2 ** 43,
                   3.0e-6, 1.1702e-8, 6.104e-5, 6.104e-5, 2048.0, 2048.0, 6.104e-5, 6.104e-5])


def read_tgd(keep):
    """Per-satellite group delay, taken from Huawei's own captured set.

    TGD is a HARDWARE calibration — the delay between a satellite's two carriers — not an orbital
    quantity, so it is not in any orbit product and cannot be fitted. It is also nearly a constant:
    across all 36 epochs of the capture every satellite carries the identical value, 29 of 29 for
    GPS and 18 of 18 for Galileo, which is the check that says it is safe to lift rather than a hope
    that it is. Zeroing it, as this did until 2026-08-29, is worth a metre or two per satellite — not
    the reason a fix took minutes, but not nothing either.

    A satellite the capture does not carry gets 0, which is exactly where it was before.
    """
    out = {"GPS": {}, "GALILEO": {}}
    for system, name, reclen, off, width in (("GPS", "GPS", 80, 54, 1),
                                             ("GALILEO", "GALILEO", 76, 20, 2)):
        src = keep / f"HW_PGNSS_{name}.bin"
        if not src.is_file():
            continue
        b = src.read_bytes()
        for i in range(BLOCKS):
            ts, o, ln = struct.unpack_from("<III", b, 12 * i)
            n = struct.unpack_from("<I", b, o)[0]
            for k in range(n):
                r = b[o + 4 + k * reclen: o + 4 + (k + 1) * reclen]
                idx = (struct.unpack_from("<H", r, 0)[0] if system == "GPS"
                       else struct.unpack_from("<I", r, 0)[0])
                v = (struct.unpack_from("<b", r, off)[0] if width == 1
                     else struct.unpack_from("<h", r, off)[0])
                out[system][idx] = v
    return out


def read_bds_tail(keep):
    """Bytes 20-23 of each BeiDou record, taken per satellite from Huawei's captured set.

    Read as a signed 32-bit word the field is always a multiple of 65536, so the payload is the
    signed 16 bits at offset 22 and the two bytes below it are always zero. The values run -102 to
    +473; as tenths of a nanosecond that is -10.2 to +47.3 ns, which is the range and the resolution
    of the BeiDou group delay TGD1. Like the GPS and Galileo group delays it is a hardware
    calibration, not an orbital quantity: it appears in no orbit product and cannot be fitted, and it
    is identical for every satellite across all 36 epochs of BOTH captured vintages three days apart
    — which is the evidence that lifting it is safe rather than the hope that it is.

    A satellite the capture does not carry gets four zero bytes.
    """
    out = {}
    src = keep / "HW_PGNSS_BDS.bin"
    if not src.is_file():
        return out
    b = src.read_bytes()
    for i in range(BLOCKS):
        _, off, _ = struct.unpack_from("<III", b, 12 * i)
        n = struct.unpack_from("<I", b, off)[0]
        for k in range(n):
            r = b[off + 4 + k * 92: off + 4 + (k + 1) * 92]
            out[struct.unpack_from("<H", r, 0)[0]] = r[20:24]
    return out


# ── BeiDou: stretching a 48-hour product across the 72-hour window ────────────────────────────────
#: Hours of orbit product fitted before the integration epoch. Montenbruck et al. recommend two days
#: and measure the optimum for a multi-GNSS ultra-rapid solution at 42-45 h.
BDS_ARC_H = 48.0
#: Seconds between sampled positions of the integrated track. The Kepler fit that follows reads it
#: with the same 9-point Lagrange interpolator it uses on an SP3, so this is an SP3 cadence.
BDS_GRID = 300.0
#: Hours of clock history fitted for the linear extrapolation. Longer is not better: a linear fit
#: beats a quadratic for extrapolation, and the residual screen below throws out the satellites whose
#: drift changed inside the window rather than shipping a confident wrong number.
BDS_CLOCK_H = 36.0
#: A satellite whose dynamics could not be fitted to this over the arc is not integrated past the end
#: of the product. It keeps the blocks the product itself covers — those are Wuhan's numbers, not
#: ours, and are fine — and is simply absent from the later ones.
#:
#: This screen is here because the check that follows it CANNOT catch a diverged track: the Kepler
#: fit is graded against the very trajectory the integration produced, so a satellite integrated into
#: the wrong orbit fits its own wrong orbit beautifully and ships. The arc residual is the only
#: number in the BeiDou path measured against somebody else's orbit, so it is the only one that can
#: refuse a bad satellite (2026-08-30).
BDS_MAX_ARC_RMS = 50.0
#: BeiDou's blocks are stamped 14 s LATER than every other constellation's, and this is not a detail.
#:
#: Huawei's own captures say so outright. In both vintages the GPS, Galileo and QZSS headers carry a
#: multiple of 7200 and the BeiDou header carries that plus 14 — the one file out of five that is
#: offset. The reason is the record: BeiDou's toe is a count of EIGHT seconds of the BeiDou week, and
#: BDT is GPS − 14, so a stamp that is a multiple of 7200 gives a seconds-of-week congruent to 2 mod
#: 8 and the field cannot hold it. Offsetting the stamp by 14 puts the week-seconds back on a
#: multiple of 7200, hence of 8, and the field is exact.
#:
#: Ignoring this truncated every toe by 2 s. The orbits were right, the encoder was right, and every
#: satellite was 7.5 km along its own track (2026-08-30).
BDS_STAMP_OFFSET = BDT_OFFSET
#: The four geostationary satellites are NOT integrated past the end of the orbit product. They ride
#: whatever blocks the product itself covers and are simply absent from the rest.
#:
#: They manoeuvre. A geostationary satellite holds its slot with station-keeping burns every couple
#: of weeks, and a burn is not in any dynamical model — the satellite leaves the orbit it was on and
#: nothing fitted to the arc before it can follow. In eight days of this product Wuhan dropped C01
#: for the whole of 2026-08-27 and C03 for the whole of 2026-08-28, which is what an analysis centre
#: does around a manoeuvre; propagated across those days our C01 was 58 km out while every MEO in the
#: same file stayed under 30 m.
#:
#: The cost of leaving them out is close to nothing here. From Prague the BeiDou geostationary
#: satellites sit at 80-160 degrees east: C01, C03 and C04 are permanently below the horizon and C02
#: reaches about 7 degrees of elevation. The cost of shipping a 58 km orbit is a receiver hunting for
#: a satellite where it is not (2026-08-30).
BDS_INTEGRATE_GEO = False


def merge_sp3(paths, prefix=None, keep=None):
    """One arc per satellite from several SP3 files; later files win where they overlap.

    [keep] limits each file to its first [keep] seconds. The Wuhan near-real-time product is 48 h of
    which the first 24 are fitted to observations and the second 24 are that file's own prediction,
    so keep=86400 builds an arc of nothing but observed orbits.
    """
    acc = {}
    for path in sorted(paths):
        one = read_sp3(path)
        if not one:
            continue
        t0 = min(d["t"][0] for d in one.values())
        for sat, d in one.items():
            if prefix and sat[0] != prefix:
                continue
            a = acc.setdefault(sat, {})
            for i, t in enumerate(d["t"]):
                if keep is not None and t >= t0 + keep:
                    continue
                a[float(t)] = (d["p"][i], d["c"][i])
    out = {}
    for sat, a in acc.items():
        ts = np.array(sorted(a))
        out[sat] = {"t": ts,
                    "p": np.array([a[t][0] for t in ts]),
                    "c": np.array([a[t][1] for t in ts])}
    return out


def clock_extrapolate(d, grid, hours=BDS_CLOCK_H):
    """Linear clock extrapolation onto [grid], with one outlier-rejection pass.

    Orbits can be integrated; clocks cannot, so the last day of the window carries a straight line
    through the last day and a half of published clock offsets. Measured over five days on this
    product the linear extrapolation beats a quadratic and lands at a median of about 10 m for
    BeiDou, with a long tail from the few satellites whose drift changed — hence the screen.
    """
    m = np.isfinite(d["c"]) & (d["t"] >= d["t"][-1] - hours * 3600)
    if m.sum() < 10:
        return np.full(len(grid), np.nan)
    x, y = d["t"][m] - d["t"][-1], d["c"][m]
    a1, a0 = np.polyfit(x, y, 1)
    resid = y - (a0 + a1 * x)
    good = np.abs(resid) < 3 * max(np.std(resid), 1e-12)
    if good.sum() >= 10:
        a1, a0 = np.polyfit(x[good], y[good], 1)
    return a0 + a1 * (grid - d["t"][-1])


_BDS_CTX = {}


def _bds_job(sat):
    """Fit one BeiDou satellite's dynamics and integrate it across the window."""
    import pgnss_orbit as po
    d = _BDS_CTX["arc"][sat]
    fr = _BDS_CTX["frame"]
    C, S = _BDS_CTX["cs"]
    grid = _BDS_CTX["grid"]
    force = po.Force(C, S, nmax=_BDS_CTX["nmax"], frame=fr)
    t_epoch = float(d["t"][-1])
    ts = np.arange(t_epoch - BDS_ARC_H * 3600, t_epoch + 1, 900.0)
    ts = np.array([t for t in ts if t >= d["t"][4] and spanned(d["t"], t)])
    if len(ts) < 40:
        return sat, None, None, "arc too short or too holed"
    obs = np.array([fr.to_tirs(interp(d["t"], d["p"], t), t)[0] for t in ts])
    p0, v0 = state_at(d, t_epoch - 1.0)
    y0 = np.concatenate([fr.to_tirs(p0, t_epoch)[0], fr.to_tirs(v0, t_epoch)[0]])
    try:
        x, rms = po.fit_arc(ts, obs, t_epoch, y0, force)
        pos = po.propagate(x[None, :6], t_epoch, grid, force, x[None, 6:])[:, 0, :]
    except Exception as exc:                                   # a diverged fit is not a build failure
        return sat, None, None, f"{type(exc).__name__}: {exc}"
    itrs = np.array([fr.to_itrs(pos[i], grid[i])[0] for i in range(len(grid))])
    return sat, rms, itrs, None


def bds_track(arc, grid, gfc, erp, nmax=12, procs=None):
    """{satellite: {'t','p','c'}} over [grid], integrated forward from a fit to [arc].

    This is the whole reason `pgnss_orbit` exists. GPS, Galileo and GLONASS are fitted to a precise
    orbit that already spans the window, because CODE publishes a five-day prediction for them.
    BeiDou has no free product past 48 hours, so the orbit it is fitted to has to be made here.
    """
    import pgnss_orbit as po
    frame = po.Frame(erp)
    _BDS_CTX.update(arc=arc, frame=frame, cs=po.read_gfc(gfc, nmax), grid=np.asarray(grid, float),
                    nmax=nmax)
    out, rmss, failed = {}, {}, []
    with multiprocessing.Pool(procs) as pool:
        for sat, rms, pos, err in pool.imap_unordered(_bds_job, sorted(arc)):
            if err:
                failed.append((sat, err))
                continue
            out[sat] = {"t": np.asarray(grid, float), "p": pos,
                        "c": clock_extrapolate(arc[sat], np.asarray(grid, float))}
            rmss[sat] = rms
    return out, rmss, failed


def bds_kind(d, t):
    """'GEO', 'IGSO' or 'MEO' from the orbit itself, at time [t].

    Huawei's split is reproduced exactly by this rule on their own records: C01-C04 are the four
    geostationary satellites and take the GEO propagation variant, C09 and C10 are inclined
    geosynchronous and take the ordinary one, and everything at 27906 km is MEO.
    """
    p, v = state_at(d, t)
    el = seed_elements(p, v, 0.0, 0.0, omega_e=BDS_OMEGA)
    a_km = el["sqrtA"] ** 2 / 1000.0
    inc = math.degrees(el["i0"])
    if a_km < 40000:
        return "MEO"
    return "GEO" if inc < 20.0 else "IGSO"


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
    # 0-based, for every system here. Do not "fix" this again.
    #
    # On 2026-08-29 this was changed to write the bare PRN for Galileo, on the evidence that Huawei's
    # Galileo ephemeris and almanac indices agree with each other. They do — but that only shows the
    # two live in the SAME space, not what the space IS, and the conclusion was asserted rather than
    # anchored. It shipped, and every Galileo satellite went out labelled one too high.
    #
    # The anchor is ESA's GSSC almanac, whose SVID is the true Galileo PRN. Propagated to a common
    # instant against Huawei's own captured files: Huawei's number = SVID-1 lands at 27 km median,
    # = SVID at 45 120 km. Against their ephemeris, 0-based 20 km and 1-based 48 231 km. GLONASS is
    # the only exception in the whole format — it stores the true slot, 1..24 (2026-08-30).
    prn = int(sat[1:])
    idx = prn - 1
    delay = _TGD[system].get(idx, 0)
    rec = (enc_gps(idx, week, el, af0, af1, tow, tow, delay) if system == "GPS"
           else enc_gal(idx, el, af0, af1, tow, tow, delay))
    return system, ts, sat, rec, err


#: The commands that fetch what BeiDou needs, printed verbatim when it is missing.
#:
#: The dated CODE names lag their own contents by a day — the file called ...<doy>0000 has its first
#: epoch on doy+1 — so "today's" predicted ERP is yesterday's day-of-year and asking for today's
#: returns a 404. The bucket keeps about a week and has no directory listing, so the name has to be
#: computed rather than looked up. `-L` is not optional: without it that host answers a redirect with
#: a 200 and a 162-byte HTML body, which lands on disk looking like a file.
BDS_FETCH_HELP = """
  mkdir -p {d} && cd {d}
  # Earth orientation — CODE's free 21-day PREDICTED ERP. Note the day-of-year is YESTERDAY'S.
  curl -fsSL -o COD0OPSPRD_{yd}0000_21D_06H_ERP.ERP \\
       https://download.aiub.unibe.ch/CODE/COD0OPSPRD_{yd}0000_21D_06H_ERP.ERP
  # Orbits — Wuhan's 48 h multi-GNSS product. Each file carries ONE day of observed orbit, so the
  # files you need are spaced a DAY apart, not an hour: consecutive hourly issues overlap almost
  # entirely and together still give barely more than 24 h of arc. Take the newest, plus one from
  # about 24 h before it and one from about 48 h before it.
  curl -fsS -O ftp://igs.gnsswhu.cn/pub/gps/products/mgex/{week}/WUM0MGXNRT_{yd}HH00_02D_05M_ORB.SP3.gz
  #   ... and the same hour on the two preceding days (mind the week directory at the boundary)
  gunzip -k WUM0MGXNRT_*.gz
  # Gravity field — once, it never changes.
  #   https://icgem.gfz-potsdam.de/  ->  Table of Models  ->  EGM96  ->  gfc   (5.6 MB)
Every one of those is anonymous. COD0OPSULT.ERP is NOT a substitute: it carries one day, and the
integration needs the pole across the whole arc and window."""


def require_bds_inputs(args, stamps):
    """Refuse to build without BeiDou's inputs, BEFORE spending ten minutes on the other three.

    There is no fallback here on purpose. The failure this replaces printed one line about falling
    back and then produced a complete, plausible, correctly-sized set with a BeiDou file whose
    72-hour window had closed two days earlier — the exact thing this script exists to stop
    shipping. Missing inputs are now an error; `--no-bds` is how you say you meant it.
    """
    import pgnss_orbit as po
    bds_paths = sorted(glob.glob(args.bds_sp3))
    erp_paths = sorted(glob.glob(args.erp))
    missing = []
    if not bds_paths:
        missing.append(f"orbit product   : nothing matches {args.bds_sp3}")
    if not erp_paths:
        missing.append(f"Earth rotation  : nothing matches {args.erp}")
    if not pathlib.Path(args.gfc).is_file():
        missing.append(f"gravity field   : {args.gfc} does not exist")
    frame = None
    if erp_paths:
        frame = po.Frame(erp_paths)
        need_from, need_to = stamps[0] - (BDS_ARC_H + 2) * 3600, stamps[-1] + 7200
        if not frame.available():
            missing.append(f"Earth rotation  : {len(erp_paths)} file(s) matched but none parsed")
        elif not frame.covers(need_from, need_to):
            lo, hi = frame.span()
            missing.append(
                f"Earth rotation  : covers MJD {lo:.1f}..{hi:.1f}, window needs "
                f"{(GPS_EPOCH+datetime.timedelta(seconds=need_from-LEAP)):%Y-%m-%d} .. "
                f"{(GPS_EPOCH+datetime.timedelta(seconds=need_to-LEAP)):%Y-%m-%d}")
    if not missing:
        return bds_paths, erp_paths
    # Both the day-of-year and the GPS week belong to the FILE, not to the window. The mirror only
    # creates a week directory once files land in it, so a window that has just crossed a week
    # boundary would otherwise be told to look in a directory that does not exist yet.
    doy_yesterday = GPS_EPOCH + datetime.timedelta(seconds=stamps[0] - LEAP - 86400)
    week = int((stamps[0] - 86400) // 604800)
    sys.exit("\n".join([
        "",
        "  *** REFUSING TO BUILD: BeiDou was requested and its inputs are not usable. ***",
        "",
        *(f"      {m}" for m in missing),
        "",
        "  BeiDou is NOT being quietly replaced by the captured file. If that is what you want,",
        "  pass --no-bds and it will say so loudly. Otherwise fetch these:",
        BDS_FETCH_HELP.format(d=pathlib.Path(args.gfc).parent, week=week,
                              yd=f"{doy_yesterday:%Y}{doy_yesterday.timetuple().tm_yday:03d}"),
        ""]))


def build_bds(args, stamps, out, keep, bds_paths, erp_paths):
    """Generate `HW_PGNSS_BDS`. Returns False if the inputs cannot carry the window.

    Two orbits are read from the same files. `arc` is the FITTED half of each — 24 hours per file,
    observations only — and is what the dynamics are fitted to, so that nothing predicted by anyone
    else gets into our epoch state. `full` is everything, including the newest file's own 24-hour
    prediction, and is used DIRECTLY for whichever blocks it covers: the 36 element sets are
    independent of one another, so there is no continuity to preserve across the seam, and Wuhan's
    own prediction is better over its span (0.12 m median) than anything integrated here.
    """
    import pgnss_orbit as po
    arc = merge_sp3(bds_paths, prefix="C", keep=86400.0)
    full = merge_sp3(bds_paths, prefix="C")
    if not arc:
        print("BDS: the orbit files carry no BeiDou — falling back to the copy")
        return False
    tail = read_bds_tail(keep)
    if not tail:
        print("BDS: no capture to lift the group delays from — falling back to the copy")
        return False
    shipped = {idx + 1 for idx in tail}
    bstamps = [t + BDS_STAMP_OFFSET for t in stamps]
    t_obs_end = max(d["t"][-1] for d in arc.values())
    t_full_end = max(d["t"][-1] for d in full.values())
    grid = np.arange(bstamps[0] - 7200, bstamps[-1] + 7201, BDS_GRID)
    arc_have = (t_obs_end - min(d["t"][0] for d in arc.values())) / 3600.0
    if arc_have < 0.75 * BDS_ARC_H:
        print(f"  *** WARNING: only {arc_have:.0f} h of observed orbit is available and the fit "
              f"wants {BDS_ARC_H:.0f} h. ***\n"
              f"  Wuhan's files each carry one day; consecutive HOURLY issues overlap and do not "
              f"lengthen the arc.\n  Add issues spaced a day apart. The prediction will be worse "
              f"than the measured figures until you do.")
    print(f"BDS: {len(arc)} satellites, {arc_have:.0f} h of observed arc, observed to "
          f"{(GPS_EPOCH+datetime.timedelta(seconds=t_obs_end-LEAP)):%Y-%m-%d %H:%M} UTC, "
          f"product ends {(GPS_EPOCH+datetime.timedelta(seconds=t_full_end-LEAP)):%Y-%m-%d %H:%M}; "
          f"integrating to {(GPS_EPOCH+datetime.timedelta(seconds=grid[-1]-LEAP)):%Y-%m-%d %H:%M} "
          f"(+{(grid[-1]-t_obs_end)/3600:.0f} h past the last observation)")

    track, rmss, failed = bds_track(arc, grid, args.gfc, erp_paths, nmax=args.nmax)
    if failed:
        print("  fits that would not converge: " + ", ".join(f"{s} ({e})" for s, e in failed[:6]))
    if not track:
        print("BDS: no satellite converged — falling back to the copy")
        return False
    print(f"  dynamical fit over the {min(arc_have, BDS_ARC_H):.0f} h arc: median "
          f"{np.median([v for v in rmss.values()]):.3f} m, worst {max(rmss.values()):.3f} m")

    # Wuhan's own numbers wherever they reach, ours beyond.
    spliced = 0
    for sat, d in track.items():
        f = full.get(sat)
        if f is None:
            continue
        m = np.array([f["t"][4] <= t <= f["t"][-5] and spanned(f["t"], t) for t in grid])
        if m.any():
            d["p"][m] = np.array([interp(f["t"], f["p"], t) for t in grid[m]])
            # The clock is interpolated LINEARLY, not by the 9-point rule used on positions: an
            # orbit is smooth by construction and a clock is not, and a high-order polynomial run
            # through a steered clock rings between its samples.
            ok = np.isfinite(f["c"])
            if ok.sum() >= 2:
                c = np.interp(grid[m], f["t"][ok], f["c"][ok], left=np.nan, right=np.nan)
                take = np.isfinite(c)
                idx = np.where(m)[0][take]
                d["c"][idx] = c[take]
            spliced = int(m.sum())
    print(f"  first {spliced * BDS_GRID / 3600:.0f} h of the track taken from the product itself, "
          f"the remaining {(len(grid) - spliced) * BDS_GRID / 3600:.0f} h integrated here")

    kinds = {sat: bds_kind(track[sat], bstamps[0]) for sat in track}
    for k in ("GEO", "IGSO", "MEO"):
        members = sorted(s for s in kinds if kinds[s] == k)
        if members:
            print(f"  {k:4}: {' '.join(members)}")

    # Who may be integrated past the product, and who only rides it.
    trust, held = {}, []
    fresh = max(d["t"][-1] for d in arc.values())
    for sat in sorted(track):
        f = full.get(sat)
        prod_end = f["t"][-5] if f is not None else -np.inf
        why = None
        if kinds[sat] == "GEO" and not BDS_INTEGRATE_GEO:
            why = "geostationary"
        elif rmss[sat] > BDS_MAX_ARC_RMS:
            why = f"arc fit {rmss[sat]:.0f} m"
        elif arc[sat]["t"][-1] < fresh - 7200:
            # The product stopped carrying it, which is what an analysis centre does around a
            # manoeuvre. Whatever the reason, its dynamics are not to be trusted forward.
            why = "dropped from the newest file"
        trust[sat] = prod_end if why else np.inf
        if why:
            held.append(f"{sat} ({why})")
    if held:
        print(f"  not integrated past the product: {', '.join(held)}")

    globals()["_SATS"] = track
    globals()["_KINDS"] = kinds
    globals()["_BDS_TRUST"] = trust
    jobs = [("BDS", sat, ts) for ts in bstamps
            for sat in sorted(track)
            if int(sat[1:]) in shipped and ts + 3600 <= trust[sat]]
    globals()["_BDS_TAIL"] = tail
    done, dropped, errs = {}, [], []
    with multiprocessing.Pool() as pool:
        for system, ts, sat, rec, err in pool.imap_unordered(_bds_fit_job, jobs, 8):
            done.setdefault(ts, [])
            if rec is None:
                dropped.append((sat, ts, err))
            else:
                done[ts].append((sat, rec))
                errs.append(err)
    if dropped:
        print(f"  dropped {len(dropped)} element sets that would not converge "
              f"(worst {max(d[2] for d in dropped):.0f} m)")
    blocks = [[r for _, r in sorted(done.get(ts, []))] for ts in bstamps]
    data = assemble(bstamps, blocks, CAPACITY["BDS"], RECLEN["BDS"])
    (out / "HW_PGNSS_BDS").write_bytes(data)
    print(f"BDS: {len(data)} bytes, {min(len(b) for b in blocks)}..{max(len(b) for b in blocks)} "
          f"satellites per block, worst-case error over its own slice: "
          f"median {np.median(errs):.2f} m, max {max(errs):.2f} m")
    return True


def _bds_fit_job(args):
    """One BeiDou element set, for the process pool — the GEO variant where the orbit calls for it."""
    system, sat, ts = args
    d = _SATS[sat]
    geo = _KINDS[sat] == "GEO"
    prop = (lambda el, t: propagate_bds(el, t, geo))
    seed = ((lambda p, v, toe, tow: seed_elements(*geo_frame(p, v), toe, tow, omega_e=BDS_OMEGA))
            if geo else
            (lambda p, v, toe, tow: seed_elements(p, v, toe, tow, omega_e=BDS_OMEGA)))
    tow = (int(ts) - BDT_OFFSET) % 604800
    el = fit(d, ts, tow, prop=prop, seed=seed, bounds=(BDS_LO, BDS_HI))
    err = _check(el, d, ts, tow, prop=prop)
    if err > MAX_ERROR_M:
        el = fit(d, ts, tow, half=1800, samples=13, prop=prop, seed=seed, bounds=(BDS_LO, BDS_HI))
        err = _check(el, d, ts, tow, prop=prop)
    if err > MAX_ERROR_M:
        return system, ts, sat, None, err
    af0, af1 = clock_fit(d, ts)
    idx = int(sat[1:]) - 1
    rec = enc_bds(idx, el, af0, af1, tow, tow, _BDS_TAIL.get(idx, b"\0\0\0\0"))
    return system, ts, sat, rec, err


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sp3", default=".scratch/code5d.sp3")
    ap.add_argument("--out", default=".scratch/pgnss-out")
    ap.add_argument("--keep", default=".scratch/hw2/satellite",
                    help="where the captured EXTRA/BDS/QZS are copied from")
    ap.add_argument("--start", default=None, help="first block, ISO UTC; default: now, floored")
    # The BeiDou inputs live in a directory whose name ENDS IN -in and can never be mistaken for a
    # place to write. It used to be `.scratch/pgnss-bds/`, which reads exactly like an output
    # directory; someone pointed --out at it, the rm -rf that preceded the build took the orbit
    # product, the Earth-orientation file and the gravity field with it, and the build then said
    # "falling back to the copy" and shipped the stale captured BeiDou we are trying to replace
    # (2026-08-30). The rename is half the fix; [require_bds_inputs] is the other half.
    ap.add_argument("--bds-sp3", default=".scratch/pgnss-bds-in/WUM0MGXNRT_*_02D_05M_ORB.SP3",
                    help="glob of Wuhan multi-GNSS files; the BeiDou orbit is built from these")
    ap.add_argument("--erp", default=".scratch/pgnss-bds-in/*ERP*",
                    help="glob of Earth-orientation files covering the arc and the window")
    ap.add_argument("--gfc", default=".scratch/pgnss-bds-in/EGM96.gfc")
    ap.add_argument("--nmax", type=int, default=12, help="geopotential degree/order")
    ap.add_argument("--no-bds", action="store_true",
                    help="deliberately skip generating BeiDou and copy the captured file instead; "
                         "without this, missing BeiDou inputs are a hard error, not a fallback")
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

    # Before any of the expensive work: does BeiDou have what it needs?
    if not args.no_bds:
        bds_paths, erp_paths = require_bds_inputs(args, stamps)
    else:
        bds_paths = erp_paths = []
        print("BDS: --no-bds given — BeiDou will be COPIED from the capture, not generated")

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rms_all = {"GPS": [], "GALILEO": []}

    # 2196 element sets, every one of them independent — so they are fitted across all cores. The
    # serial version took twenty-five minutes, which is long enough that a refresh becomes a chore
    # and chores do not get done every third day.
    # Galileo satellites whose eccentricity would reach a byte the capture never touches.
    #
    # E14 and E18 (GSAT0201/0202) sit in the wrong, highly elliptical orbits they were launched
    # into, e ≈ 0.168 — and a 32-bit count of 2⁻³³ needs its fourth byte past e = 1/512, where all
    # 18 satellites Huawei ships stay two orders of magnitude below. Huawei simply leaves those two
    # out, and so should we: the capture is the only evidence of what this band accepts, and having
    # just lost a walk to a byte pattern it had never been shown, two extra satellites are not worth
    # finding out whether that field is really 32 bits wide (白い熊, 2026-08-29).
    E_CEILING = 2 ** 24 / 2 ** 33
    wrong_orbit = []
    for sat in sorted(x for x in sats if x[0] == "E"):
        pv = state_at(sats[sat], stamps[0])
        if seed_elements(pv[0], pv[1], 0, 0)["e"] >= E_CEILING:
            wrong_orbit.append(sat)
    if wrong_orbit:
        print(f"Galileo: leaving out {', '.join(wrong_orbit)} — eccentric orbits the capture omits")

    globals()["_SATS"] = sats
    globals()["_TGD"] = read_tgd(pathlib.Path(args.keep))
    print(f"TGD: {len(_TGD['GPS'])} GPS and {len(_TGD['GALILEO'])} Galileo satellites carry one "
          f"in the capture; any we fit beyond those get 0")
    jobs = [(system, sat, ts)
            for system, prefix in (("GPS", "G"), ("GALILEO", "E"))
            for ts in stamps
            for sat in sorted(x for x in sats if x[0] == prefix and x not in wrong_orbit)]
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
        # Sub-epoch 0 sits on the UTC HOUR, not on the block stamp.
        #
        # The stamps are multiples of 7200 in GPS seconds, which is :59:42 in UTC — so writing the
        # state at the stamp itself puts every sub-epoch 59 min 42 s later than Huawei's. Theirs land
        # exactly on the UTC quarter-hour grid: block stamp 1471683600 (08:59:42 UTC) carries its
        # first state at 08:00:00 UTC to within 3.5 m (2026-08-29).
        hour = ((ts - LEAP) // 3600) * 3600 + LEAP
        for s in range(8):
            t = hour + 900 * s
            recs = []
            for sat in sorted(x for x in sats if x[0] == "R"):
                d = sats[sat]
                if not (d["t"][0] + 5 < t < d["t"][-1] - 5):
                    continue
                p, v = state_at(d, t)
                a = luni_solar(p, v, (state_at(d, t + 1)[1] - state_at(d, t - 1)[1]) / 2.0)
                tau = 0.0 if not np.isfinite(interp(d["t"], d["c"], t)) else float(interp(d["t"], d["c"], t))
                # tb is MOSCOW time-of-day in 900 s units — GLONASS runs on UTC+3, and the field
                # is defined against it. Ours computed it from GPS time-of-day and so ran about
                # three hours low. Huawei's 44 = 11:00 MSK = 08:00 UTC, matching its own state.
                tb = int(((t - LEAP + 3 * 3600) % 86400) // 900)
                recs.append(enc_glo(int(sat[1:]) - 1, tb, p, v, a, tau))
            subs.append(recs)
        gblocks.append(subs)
    data = assemble(glo_stamps, gblocks, CAPACITY["GLONASS"], RECLEN["GLONASS"], sub=8)
    (out / "HW_PGNSS_GLONASS").write_bytes(data)
    print(f"GLONASS: {len(data)} bytes, {len(gblocks[0][0])} satellites per sub-epoch")

    # QZSS is still Huawei's captured file. BeiDou is not, any more — see [build_bds] below.
    #
    # QZSS could be generated: JAXA's JGX0OPSULT carries GPS and the five QZSS satellites, 48 hours,
    # four times a day, on the same anonymous Wuhan mirror, and the propagator that stretches BeiDou
    # to 72 hours would stretch that too. It has not been worth it. QZSS is regional: its satellites
    # hold longitudes around 135 degrees east, which from Prague at 14 east are below the horizon at
    # every hour of every day. The file costs 28 kB and buys a constellation this band will never see
    # from here, so it is carried as captured rather than made.
    #
    # A copied file is shipped even once its 72-hour window has closed. For one build it was not, on
    # the reasoning that stale orbits are worse than none — and the band settled it: carrying the
    # stale pair the fix took about a minute, and with them removed and nothing else changed it went
    # back to about three (白い熊, 2026-08-29). The age is reported rather than acted on. Do not
    # re-add the expiry skip without a measurement that says otherwise.
    keep = pathlib.Path(args.keep)

    # ── BeiDou ────────────────────────────────────────────────────────────────────────────────────
    made_bds = False
    if not args.no_bds:
        made_bds = build_bds(args, stamps, out, keep, bds_paths, erp_paths)
        if not made_bds:
            sys.exit("\n  *** REFUSING TO BUILD: BeiDou generation failed (see above). ***\n"
                     "  Nothing has been substituted for it. Pass --no-bds to ship the capture.\n")

    for name in ("EXTRA", "BDS", "QZS"):
        if name == "BDS" and made_bds:
            continue
        src = keep / f"HW_PGNSS_{name}.bin"
        if not src.is_file():
            continue
        shutil.copy(src, out / f"HW_PGNSS_{name}")
        note = ""
        if name != "EXTRA":
            b = src.read_bytes()
            last = struct.unpack_from("<III", b, 12 * (BLOCKS - 1))[0]
            if last < stamps[0]:
                when = GPS_EPOCH + datetime.timedelta(seconds=last - LEAP)
                age = (stamps[0] - last) / 86400.0
                note = (f"\n  *** WARNING: this is a STALE {name} — its window closed "
                        f"{when:%Y-%m-%d %H:%M} UTC, {age:.1f} days ago. ***"
                        f"\n  It is shipped because an out-of-date orbit still measured better on "
                        f"the band than none;\n  see the note above the copy. It is not a "
                        f"prediction for this window.")
        print(f"{name}: copied unchanged from the capture ({src.stat().st_size} bytes){note}")


if __name__ == "__main__":
    main()
