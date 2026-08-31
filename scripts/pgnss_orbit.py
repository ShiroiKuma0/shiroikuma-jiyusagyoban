#!/usr/bin/env python3
"""Numerical orbit propagation, used to stretch a 48-hour orbit product to the 72 hours the band wants.

WHY THIS EXISTS
`pgnss-build.py` fits its element sets to a precise orbit that already spans the whole window. For
GPS, Galileo and GLONASS that orbit is free: CODE publishes a five-day prediction. For BeiDou nothing
free reaches beyond 48 hours — every BeiDou-bearing product on every anonymous mirror is `_02D_`,
24 hours observed plus 24 predicted, and CODE's own five-day file says in its header that it is
"derived from CODE Ultra-Rapid GRE orbits": G, R and E, no C (checked again 2026-08-30). So the last
day of a BeiDou window has to be predicted here, from an arc that stops short of it.

The method is Montenbruck et al., NAVIGATION 68(1):199-215 (2021): fit an epoch state plus a handful
of solar-radiation-pressure coefficients to a couple of days of precise positions used as
pseudo-observations, then integrate that fitted state forward with a modest force model.

WHY THE INTEGRATION IS IN ECEF, WHICH IS UNUSUAL
Orbit integrators normally work in an inertial frame, which drags in precession, nutation, polar
motion and the whole Earth-orientation apparatus — a great deal of code whose errors are invisible
until they are metres of orbit. None of it is needed here, because BOTH ends of this pipeline are
Earth-fixed: the SP3 that is fitted is ITRF, and the records the band reads are ITRF. Integrating in
the rotating frame — central term plus geopotential, which is STATIC in ECEF, plus centrifugal and
Coriolis — means the frame chain never has to be built at all. This is exactly the form GLONASS
broadcasts its own ephemeris in, and for the same reason.

What is given up is bounded and measured. Omitting polar motion means our "ITRF" is really the
terrestrial intermediate frame, a fixed rotation of at most 0.3" away; that offset CANCELS, because
the same convention converts the truth in and the answer out, and only its drift over the window
survives — about a metre. Taking the Earth's rotation rate as constant leaves a slow z-rotation of
the same order. Precession and nutation do not enter the dynamics at all in this frame; they enter
only through the direction of the Sun and the Moon, where an arcminute is harmless.

WHAT IS MODELLED
* Geopotential to degree/order `NMAX` (EGM96, unnormalised, Cunningham recursion) — static in ECEF.
* Centrifugal and Coriolis, from the frame's own rotation.
* Sun and Moon as point masses, from a low-precision analytic ephemeris.
* Solid Earth tide, degree 2, nominal Love number.
* Relativistic (Schwarzschild) correction.
* Solar radiation pressure, ECOM-1 in the D/Y/B frame with a conical shadow — FITTED, not modelled,
  because the satellite's area, mass and reflectivity are not published for any of these craft.

Every one of those has a self-check in `selftest()` that does not go through the fit:
run `python3 scripts/pgnss_orbit.py` and it grades itself against closed forms and JPL Horizons.
"""
import math
import pathlib

import numpy as np

# ── constants ─────────────────────────────────────────────────────────────────────────────────────
GM_EARTH = 3.986004418e14          # m^3/s^2, IERS/IGS conventional value
GM_SUN = 1.32712440018e20
GM_MOON = 4.9028000661e12
RE = 6378136.3                     # m, EGM96 reference radius (its own header)
OMEGA_ERA = 7.292115146706979e-5   # rad/s, d(ERA)/d(UT1) = 2*pi*1.00273781191135448/86400
OMEGA_E = OMEGA_ERA                # rad/s — NOT the IERS "nominal mean" 7.292115e-5
#: The two differ by 1.47e-12 rad/s, two parts in 1e8, which sounds like nothing and is not. In the
#: rotating frame the rate enters the Coriolis term, so the error is 2*dw*v = 9e-9 m/s^2, and an
#: unmodelled constant acceleration grows as t^2: 0.2 m over a 2-hour arc, 1.9 m over 6 hours, 30 m
#: over 24. Fitting a 24-hour arc with the nominal value plateaued at exactly 30 m no matter how many
#: iterations, degrees of geopotential or ECOM coefficients were thrown at it (measured 2026-08-30).
#: d(ERA)/dt = 2*pi*1.00273781191135448/86400 is the rate the terrestrial frame actually turns at.
C_LIGHT = 299792458.0
AU = 1.49597870700e11
R_SUN = 6.957e8
R_EARTH_SHADOW = 6378137.0
K2_LOVE = 0.30
P_SUN = 4.56e-6                    # N/m^2 at 1 AU — only sets the scale of the fitted coefficients

GPS_EPOCH_JD = 2444244.5           # 1980-01-06 00:00:00 UTC
LEAP = 18                          # GPS - UTC
TT_MINUS_GPS = 51.184              # TT = TAI + 32.184 = GPS + 19 + 32.184

NMAX_DEFAULT = 12
DEG = math.pi / 180.0
ARCSEC = DEG / 3600.0


# ── the gravity field ─────────────────────────────────────────────────────────────────────────────
def _unnormalise(n, m):
    """Multiply a fully-normalised coefficient by this to get the unnormalised one.

    N = sqrt( (n-m)! (2n+1) (2 - delta_0m) / (n+m)! ).  Checked against two values whose unnormalised
    magnitudes are common knowledge: C20 -> -1.0826e-3 (that is -J2) and C22 -> 1.5746e-6.
    """
    f = math.sqrt(math.factorial(n - m) * (2 * n + 1) * (2 if m else 1) / math.factorial(n + m))
    return f


def read_gfc(path, nmax=NMAX_DEFAULT):
    """EGM96 (or any ICGEM .gfc) -> unnormalised C, S arrays of shape (nmax+1, nmax+1)."""
    C = np.zeros((nmax + 1, nmax + 1))
    S = np.zeros((nmax + 1, nmax + 1))
    for line in open(path, errors="replace"):
        if not line.startswith("gfc"):
            continue
        f = line.split()
        n, m = int(f[1]), int(f[2])
        if n > nmax:
            continue
        k = _unnormalise(n, m)
        C[n, m] = float(f[3].replace("D", "e")) * k
        S[n, m] = float(f[4].replace("D", "e")) * k
    C[0, 0] = 1.0
    return C, S


def grav_accel(r, C, S, nmax):
    """Geopotential acceleration in ECEF for a batch of positions, r of shape (K, 3).

    Cunningham's recursion (Montenbruck & Gill, Satellite Orbits, sect. 3.2.4) on the unnormalised
    coefficients. Safe in double precision to well past the degree used here.
    """
    x, y, z = r[:, 0], r[:, 1], r[:, 2]
    r2 = x * x + y * y + z * z
    rho = RE * RE / r2
    x0, y0, z0 = RE * x / r2, RE * y / r2, RE * z / r2

    N = nmax + 2
    V = [[None] * (N + 1) for _ in range(N + 1)]
    W = [[None] * (N + 1) for _ in range(N + 1)]
    V[0][0] = RE / np.sqrt(r2)
    W[0][0] = np.zeros_like(x)
    for m in range(0, N):
        if m > 0:
            V[m][m] = (2 * m - 1) * (x0 * V[m - 1][m - 1] - y0 * W[m - 1][m - 1])
            W[m][m] = (2 * m - 1) * (x0 * W[m - 1][m - 1] + y0 * V[m - 1][m - 1])
        if m + 1 <= N:
            V[m + 1][m] = (2 * m + 1) * z0 * V[m][m]
            W[m + 1][m] = (2 * m + 1) * z0 * W[m][m]
        for n in range(m + 2, N + 1):
            V[n][m] = ((2 * n - 1) * z0 * V[n - 1][m] - (n + m - 1) * rho * V[n - 2][m]) / (n - m)
            W[n][m] = ((2 * n - 1) * z0 * W[n - 1][m] - (n + m - 1) * rho * W[n - 2][m]) / (n - m)

    ax = np.zeros_like(x)
    ay = np.zeros_like(x)
    az = np.zeros_like(x)
    for n in range(0, nmax + 1):
        for m in range(0, n + 1):
            c, s = C[n, m], S[n, m]
            if c == 0.0 and s == 0.0:
                continue
            if m == 0:
                ax -= c * V[n + 1][1]
                ay -= c * W[n + 1][1]
            else:
                f = (n - m + 2) * (n - m + 1)
                ax += 0.5 * ((-c * V[n + 1][m + 1] - s * W[n + 1][m + 1])
                             + f * (c * V[n + 1][m - 1] + s * W[n + 1][m - 1]))
                ay += 0.5 * ((-c * W[n + 1][m + 1] + s * V[n + 1][m + 1])
                             + f * (-c * W[n + 1][m - 1] + s * V[n + 1][m - 1]))
            az += (n - m + 1) * (-c * V[n + 1][m] - s * W[n + 1][m])
    k = GM_EARTH / (RE * RE)
    return np.stack([ax, ay, az], axis=1) * k


# ── Sun and Moon, Earth-fixed ─────────────────────────────────────────────────────────────────────
def _tt_centuries(t_gps):
    return (GPS_EPOCH_JD + (t_gps + TT_MINUS_GPS) / 86400.0 - 2451545.0) / 36525.0


def gmst(t_gps):
    """Greenwich mean sidereal time, radians. UT1 is approximated by UTC (6 ms today, 0.03 m)."""
    jd_ut1 = GPS_EPOCH_JD + (t_gps - LEAP) / 86400.0
    tu = (jd_ut1 - 2451545.0) / 36525.0
    frac = (jd_ut1 + 0.5) % 1.0
    s = (67310.54841 + (876600 * 3600 + 8640184.812866) * tu
         + 0.093104 * tu * tu - 6.2e-6 * tu ** 3)
    return ((s % 86400.0) / 86400.0 * 2 * math.pi) % (2 * math.pi) + 0.0 * frac


def _ecl_to_ecef(lon, lat, dist, t_gps, j2000=False):
    """Ecliptic spherical -> ECEF cartesian, via the obliquity of date and GMST.

    [j2000] says the longitude is referred to the FIXED mean equinox of J2000 rather than to the
    equinox of date, in which case the general precession in longitude is added first. Getting this
    wrong is worth 1341 arcseconds today — 0.37 degrees of Sun and Moon direction — and it is not
    guessable from the formulae: both truncated series below are J2000-referred, which is visible
    only in the difference between their mean-longitude RATE and the of-date rate (the Moon's
    481267.88088 - 1.3972 per century is the of-date rate less the precession). Measured against JPL
    Horizons before and after (.scratch/pgnss-bds/frametest.py).
    """
    T = _tt_centuries(t_gps)
    if j2000:
        lon = lon + (5029.0966 * T + 1.11113 * T * T) * ARCSEC
    eps = (84381.448 - 46.8150 * T - 0.00059 * T * T + 0.001813 * T ** 3) * ARCSEC
    cl, sl = math.cos(lon), math.sin(lon)
    cb, sb = math.cos(lat), math.sin(lat)
    xe = dist * cb * cl
    ye = dist * (cb * sl * math.cos(eps) - sb * math.sin(eps))
    ze = dist * (cb * sl * math.sin(eps) + sb * math.cos(eps))
    th = gmst(t_gps)
    return np.array([xe * math.cos(th) + ye * math.sin(th),
                     -xe * math.sin(th) + ye * math.cos(th),
                     ze])


def sun_ecef(t_gps):
    """Solar ephemeris, the Astronomical Almanac's low-precision form — equinox OF DATE, 0.01 deg.

    Not Montenbruck & Gill's: theirs is referred to J2000 and, as transcribed here, ran 314 arcsec
    slow in mean longitude against JPL Horizons at every epoch tested. This one was checked against
    Horizons instead of trusted.
    """
    n = GPS_EPOCH_JD + (t_gps + TT_MINUS_GPS) / 86400.0 - 2451545.0
    L = (280.460 + 0.9856474 * n) * DEG
    g = (357.528 + 0.9856003 * n) * DEG
    lon = L + (1.915 * math.sin(g) + 0.020 * math.sin(2 * g)) * DEG
    dist = (1.00014 - 0.01671 * math.cos(g) - 0.00014 * math.cos(2 * g)) * AU
    return _ecl_to_ecef(lon, 0.0, dist, t_gps, j2000=False)


def moon_ecef(t_gps):
    """Truncated lunar series (Montenbruck & Gill sect. 3.3.2), J2000 equinox.

    Measured against JPL Horizons over the window this is used on: better than 90 arcsec in
    direction and 300 km in range, which is what `moon_check` in the self-test re-measures.
    """
    T = _tt_centuries(t_gps)
    L0 = (218.31617 + 481267.88088 * T - 1.3972 * T) * DEG
    l = (134.96292 + 477198.86753 * T) * DEG
    lp = (357.52543 + 35999.04944 * T) * DEG
    F = (93.27283 + 483202.01873 * T) * DEG
    D = (297.85027 + 445267.11135 * T) * DEG
    s = math.sin
    c = math.cos
    dl = (22640 * s(l) + 769 * s(2 * l) - 4586 * s(l - 2 * D) + 2370 * s(2 * D)
          - 668 * s(lp) - 412 * s(2 * F) - 212 * s(2 * l - 2 * D) - 206 * s(l + lp - 2 * D)
          + 192 * s(l + 2 * D) - 165 * s(lp - 2 * D) + 148 * s(l - lp) - 125 * s(D)
          - 110 * s(l + lp) - 55 * s(2 * F - 2 * D))
    lon = L0 + dl * ARCSEC
    arg = F + (dl + 412 * s(2 * F) + 541 * s(lp)) * ARCSEC
    lat = (18520 * s(arg) - 526 * s(F - 2 * D) + 44 * s(l + F - 2 * D) - 31 * s(-l + F - 2 * D)
           - 25 * s(-2 * l + F) - 23 * s(lp + F - 2 * D) + 21 * s(-l + F)
           + 11 * s(-lp + F - 2 * D)) * ARCSEC
    dist = (385000e3 - 20905e3 * c(l) - 3699e3 * c(2 * D - l) - 2956e3 * c(2 * D)
            - 570e3 * c(2 * l) + 246e3 * c(2 * l - 2 * D) - 205e3 * c(lp - 2 * D)
            - 171e3 * c(l + 2 * D) - 152e3 * c(l + lp - 2 * D))
    return _ecl_to_ecef(lon, lat, dist, t_gps, j2000=True)


def third_body(r, s, gm):
    """Point-mass perturbation: the direct pull minus the pull on the Earth itself."""
    d = s[None, :] - r
    dn = np.linalg.norm(d, axis=1)[:, None]
    sn = np.linalg.norm(s)
    return gm * (d / dn ** 3 - s[None, :] / sn ** 3)


def solid_tide_dcs(s_sun, s_moon):
    """Degree-2 solid-Earth-tide corrections to the UNNORMALISED C2m, S2m (IERS Conventions 6.6)."""
    dC = np.zeros(3)
    dS = np.zeros(3)
    for s, gm in ((s_sun, GM_SUN), (s_moon, GM_MOON)):
        rj = np.linalg.norm(s)
        sphi = s[2] / rj
        lam = math.atan2(s[1], s[0])
        cphi2 = max(0.0, 1.0 - sphi * sphi)
        cphi = math.sqrt(cphi2)
        p = (0.5 * (3 * sphi * sphi - 1) * math.sqrt(5.0),
             3 * sphi * cphi * math.sqrt(10.0 / 6.0),
             3 * cphi2 * math.sqrt(10.0 / 24.0))
        k = (K2_LOVE / 5.0) * (gm / GM_EARTH) * (RE / rj) ** 3
        for m in range(3):
            dC[m] += k * p[m] * math.cos(m * lam)
            dS[m] += k * p[m] * math.sin(m * lam)
    for m in range(3):
        f = _unnormalise(2, m)
        dC[m] *= f
        dS[m] *= f
    return dC, dS


# ── solar radiation pressure ──────────────────────────────────────────────────────────────────────
def shadow(r, s):
    """Conical-shadow illumination fraction in [0, 1], for a batch of positions."""
    d = s[None, :] - r
    dn = np.linalg.norm(d, axis=1)
    rn = np.linalg.norm(r, axis=1)
    a = np.arcsin(np.clip(R_SUN / dn, -1, 1))                 # apparent radius of the Sun
    b = np.arcsin(np.clip(R_EARTH_SHADOW / rn, -1, 1))        # apparent radius of the Earth
    cosc = np.clip(np.einsum("ij,ij->i", -r, d) / (rn * dn), -1, 1)
    c = np.arccos(cosc)                                       # Sun-to-Earth separation as seen
    nu = np.ones_like(c)
    nu[c < b - a] = 0.0
    pen = (c < a + b) & (c >= np.abs(b - a))
    if pen.any():
        A, B, Cc = a[pen], b[pen], c[pen]
        x = (Cc * Cc + A * A - B * B) / (2 * Cc)
        yv = np.sqrt(np.maximum(A * A - x * x, 0.0))
        area = (A * A * np.arccos(np.clip(x / A, -1, 1))
                + B * B * np.arccos(np.clip((Cc - x) / B, -1, 1)) - Cc * yv)
        nu[pen] = np.clip(1.0 - area / (math.pi * A * A), 0.0, 1.0)
    ann = (c < b - a) & (b < a)          # annular: Earth entirely inside the solar disc
    if ann.any():
        nu[ann] = 1.0 - (b[ann] / a[ann]) ** 2
    return nu


def dyb_frame(r, v_inertial, s):
    """The ECOM axes and the argument of latitude, for a batch.

    e_D points from the satellite to the Sun, e_Y along the solar-panel axis, e_B completes it.
    u is measured from the ascending node — identical in ECEF and inertial, because the two frames
    share a z-axis and differ only by a rotation about it.
    """
    d = s[None, :] - r
    eD = d / np.linalg.norm(d, axis=1)[:, None]
    eR = r / np.linalg.norm(r, axis=1)[:, None]
    y = np.cross(eD, eR)
    yn = np.linalg.norm(y, axis=1)[:, None]
    eY = y / np.where(yn == 0, 1.0, yn)
    eB = np.cross(eD, eY)
    # The argument of latitude, by a formula that survives a GEO.
    #
    # The obvious one, u = atan2(z / sin i, r . n), divides by the sine of the inclination, and a
    # BeiDou geostationary satellite flies at a fraction of a degree: sin i goes to zero, the node
    # itself stops being defined, and u becomes noise that the once-per-revolution coefficients then
    # fit. One of the four came out of the arc fit 68.8 km wrong that way. Projecting r onto the node
    # and onto (h x n) instead divides by nothing, and when the node degenerates any fixed reference
    # in the plane will do — the coefficients are empirical, so the phase origin is arbitrary.
    h = np.cross(r, v_inertial)
    hn = np.linalg.norm(h, axis=1)[:, None]
    hhat = h / np.where(hn == 0, 1.0, hn)
    n = np.stack([-h[:, 1], h[:, 0], np.zeros_like(h[:, 0])], axis=1)
    nn = np.linalg.norm(n, axis=1)[:, None]
    degenerate = (nn[:, 0] < 1e-9 * np.linalg.norm(h, axis=1))
    n = np.where(degenerate[:, None], np.array([1.0, 0.0, 0.0]), n / np.where(nn == 0, 1.0, nn))
    m = np.cross(hhat, n)
    u = np.arctan2(np.einsum("ij,ij->i", r, m), np.einsum("ij,ij->i", r, n))
    return eD, eY, eB, u


# ── the terrestrial frame ─────────────────────────────────────────────────────────────────────────
class Frame:
    """ITRF <-> the frame the integration actually runs in, and that frame's rotation rate.

    THE FRAME IS NOT ITRF. It is the terrestrial intermediate frame, whose z-axis is the Earth's
    actual rotation axis; ITRF's z-axis misses it by the polar motion, 0.2-0.3 arcseconds today.
    That sounds ignorable and is the single largest error in this file if it is ignored, because the
    rotating-frame equations single out the rotation axis: writing them about ITRF's z instead of the
    real one leaves a spurious Coriolis term

        |2 dw x v| = 2 * OMEGA_E * 1.95e-6 * 3070 = 8.8e-7 m/s^2

    which is seven times solar radiation pressure. Measured, with no fitting anywhere: a state read
    off CODE's own five-day prediction and propagated with everything else modelled diverged from it
    by 1.19 m in 30 minutes and 82 m in 4 hours, an implied constant acceleration of 7.4e-7 m/s^2 for
    G01 and 1.0e-6 for G05 (2026-08-30). A fit cannot absorb it: a tilt of the frame is a symmetry of
    neither the centrifugal term, the Coriolis term, nor the flattening.

    The rate is d(ERA)/dt corrected by the length of day, which is a 1e-8 relative effect and worth
    tens of metres over three days.

    Both come from an Earth-orientation file. CODE publishes a 21-day PREDICTED one beside the orbit
    prediction, so this needs nothing that is not already being downloaded. Without one the class
    falls back to a zero pole and a nominal rate, and says so.
    """

    def __init__(self, erp=None):
        """[erp] is one Earth-orientation file or a list of them; later rows win where they overlap."""
        self.mjd = self.xp = self.yp = self.lod = None
        if erp:
            paths = [erp] if isinstance(erp, (str, pathlib.Path)) else list(erp)
            seen = {}
            for path in paths:
              for line in open(path, errors="replace"):
                f = line.split()
                if len(f) < 5:
                    continue
                try:
                    mjd = float(f[0])
                    if not 40000 < mjd < 90000:
                        continue
                    seen[mjd] = (float(f[1]) * 1e-6, float(f[2]) * 1e-6, float(f[4]) * 1e-7)
                except ValueError:
                    continue
            if seen:
                a = np.array([(m,) + v for m, v in sorted(seen.items())])
                self.mjd, self.xp, self.yp, self.lod = a[:, 0], a[:, 1], a[:, 2], a[:, 3]

    def available(self):
        return self.mjd is not None

    def span(self):
        return (self.mjd[0], self.mjd[-1]) if self.available() else (None, None)

    def covers(self, t0, t1, margin=0.0):
        """Does the Earth-orientation series span [t0, t1] in GPS seconds?

        Asked before anything is integrated. Outside the series `np.interp` does not fail, it
        CLAMPS to the end value — so a window that runs past the file gets yesterday's pole held
        constant and no complaint. That is the quiet-wrong-answer shape this whole file is trying
        to avoid.
        """
        if not self.available():
            return False
        to_mjd = lambda t: (GPS_EPOCH_JD - 2400000.5) + (t - LEAP) / 86400.0
        return bool(self.mjd[0] - margin <= to_mjd(t0) and to_mjd(t1) <= self.mjd[-1] + margin)

    def _pole(self, t):
        """Polar motion (xp, yp) in radians at GPS second [t]."""
        if not self.available():
            return 0.0, 0.0
        mjd = (GPS_EPOCH_JD - 2400000.5) + (t - LEAP) / 86400.0
        xp = float(np.interp(mjd, self.mjd, self.xp)) * ARCSEC
        yp = float(np.interp(mjd, self.mjd, self.yp)) * ARCSEC
        return xp, yp

    def omega(self, t):
        if not self.available():
            return OMEGA_ERA
        mjd = (GPS_EPOCH_JD - 2400000.5) + (t - LEAP) / 86400.0
        lod = float(np.interp(mjd, self.mjd, self.lod))
        return OMEGA_ERA * (1.0 - lod / 86400.0)

    def to_tirs(self, r, t):
        """W(t) r, with W = R3(-s')R2(xp)R1(yp) to first order in the pole coordinates."""
        xp, yp = self._pole(t)
        r = np.atleast_2d(r)
        return np.stack([r[:, 0] - xp * r[:, 2],
                         r[:, 1] + yp * r[:, 2],
                         xp * r[:, 0] - yp * r[:, 1] + r[:, 2]], axis=1)

    def to_itrs(self, r, t):
        xp, yp = self._pole(t)
        r = np.atleast_2d(r)
        return np.stack([r[:, 0] + xp * r[:, 2],
                         r[:, 1] - yp * r[:, 2],
                         -xp * r[:, 0] + yp * r[:, 1] + r[:, 2]], axis=1)


# ── the equation of motion ────────────────────────────────────────────────────────────────────────
class Force:
    """The force model, with everything that depends only on time cached per step."""

    def __init__(self, C, S, nmax=NMAX_DEFAULT, srp=True, tide=True, relativity=True,
                 third=True, omega=OMEGA_E, frame=None):
        self.C, self.S, self.nmax = C, S, nmax
        self.srp, self.tide, self.relativity, self.third = srp, tide, relativity, third
        self.omega = omega
        self.frame = frame
        self._t = None

    def _at(self, t):
        if self._t == t:
            return
        self._t = t
        self.sun = sun_ecef(t)
        self.moon = moon_ecef(t)
        if self.tide:
            dC, dS = solid_tide_dcs(self.sun, self.moon)
            self.C2 = self.C.copy()
            self.S2 = self.S.copy()
            self.C2[2, :3] += dC
            self.S2[2, :3] += dS
        else:
            self.C2, self.S2 = self.C, self.S

    def accel(self, t, r, v, p_srp):
        """p_srp: (K, 5) = D0, Y0, B0, Bc, Bs in m/s^2 at 1 AU. r, v: (K, 3), ECEF."""
        self._at(t)
        a = grav_accel(r, self.C2, self.S2, self.nmax)
        w = self.frame.omega(t) if self.frame is not None else self.omega
        a[:, 0] += w * w * r[:, 0] + 2 * w * v[:, 1]
        a[:, 1] += w * w * r[:, 1] - 2 * w * v[:, 0]
        if self.third:
            a += third_body(r, self.sun, GM_SUN) + third_body(r, self.moon, GM_MOON)
        vi = v.copy()
        vi[:, 0] -= w * r[:, 1]
        vi[:, 1] += w * r[:, 0]
        if self.relativity:
            rn = np.linalg.norm(r, axis=1)[:, None]
            v2 = np.einsum("ij,ij->i", vi, vi)[:, None]
            rv = np.einsum("ij,ij->i", r, vi)[:, None]
            a += (GM_EARTH / (C_LIGHT ** 2 * rn ** 3)) * ((4 * GM_EARTH / rn - v2) * r + 4 * rv * vi)
        if self.srp and p_srp is not None:
            eD, eY, eB, u = dyb_frame(r, vi, self.sun)
            nu = shadow(r, self.sun)
            scale = nu * (AU / np.linalg.norm(self.sun - r, axis=1)) ** 2
            cu, su = np.cos(u), np.sin(u)
            n = p_srp.shape[1]
            D = p_srp[:, 0]
            Y = p_srp[:, 1] if n > 1 else 0.0
            B = p_srp[:, 2] if n > 2 else 0.0
            if n > 4:                                   # ECOM-1: B gains its once-per-revolution pair
                B = B + p_srp[:, 3] * cu + p_srp[:, 4] * su
            if n > 8:                                   # the full nine, D and Y gaining theirs too
                D = D + p_srp[:, 5] * cu + p_srp[:, 6] * su
                Y = Y + p_srp[:, 7] * cu + p_srp[:, 8] * su
            a += scale[:, None] * D[:, None] * eD
            if n > 1:
                a += scale[:, None] * Y[:, None] * eY
            if n > 2:
                a += scale[:, None] * B[:, None] * eB
        return a


def rhs(t, y, force, p_srp):
    k = y.shape[0] // 6
    st = y.reshape(k, 6)
    a = force.accel(t, st[:, :3], st[:, 3:], p_srp)
    return np.concatenate([st[:, 3:], a], axis=1).ravel()


def propagate(y0, t0, t_eval, force, p_srp, rtol=1e-11, atol=1e-4, max_step=600.0):
    """Integrate a batch of states. y0: (K, 6) ECEF. Returns (len(t_eval), K, 3) positions.

    Epochs before and after t0 are integrated as two runs from the common epoch, and an epoch AT t0
    is answered from y0 without integrating — scipy hands back a bare list rather than an array when
    an interval contains no steps, which is how that case announced itself.
    """
    from scipy.integrate import solve_ivp
    te = np.asarray(t_eval, float)
    out = np.empty((len(te), y0.shape[0], 3))
    at = te == t0
    if at.any():
        out[at] = y0[None, :, :3]
    for mask, rev in ((te < t0, True), (te > t0, False)):
        if not mask.any():
            continue
        tt = np.sort(te[mask])
        tt = tt[::-1] if rev else tt
        sol = solve_ivp(rhs, (t0, tt[-1]), y0.ravel(), method="DOP853", t_eval=tt,
                        args=(force, p_srp), rtol=rtol, atol=atol, max_step=max_step)
        if not sol.success:
            raise RuntimeError(sol.message)
        ys = np.asarray(sol.y).T.reshape(len(tt), y0.shape[0], 6)[:, :, :3]
        order = np.argsort(te[mask])
        got = ys[::-1] if rev else ys
        buf = np.empty_like(got)
        buf[order] = got
        out[mask] = buf
    return out


# ── the fit ───────────────────────────────────────────────────────────────────────────────────────
#: Position, velocity, then five ECOM-1 coefficients (D0, Y0, B0, Bc, Bs). The steps are the
#: finite-difference increments; each is small enough to stay in the linear regime and large enough
#: to clear the integrator's own tolerance (1e-4 m) by orders of magnitude — 1 nm/s^2 of SRP moves a
#: satellite 0.2 m over six hours, which is a thousand times the integration noise.
#: How many radiation-pressure coefficients to estimate. 3 is D0/Y0/B0, 5 adds B's once-per-
#: revolution pair (ECOM-1 as Montenbruck et al. use it), 9 gives D and Y theirs as well. More
#: coefficients always fit the arc better and do not always PREDICT better, which is what matters
#: here, so the number is a measured choice — see NSRP_DEFAULT.
STEP_STATE = [1.0, 1.0, 1.0, 1e-3, 1e-3, 1e-3]
#: THREE, which fits the arc worse and predicts it better — the only thing that matters here.
#: Measured on a 48-hour arc ending 2026-08-26, graded against orbits observed afterwards, median
#: 3D error at +72 h (2026-08-30):
#:
#:      coefficients   arc rms    GPS      GLONASS   Galileo   BeiDou MEO/IGSO
#:      9              1.2 m      59 m     27 m      29 m      43 m
#:      3              2.2 m      25 m     11 m      13 m      19 m
#:
#: The extra six coefficients buy a factor of two on the arc and cost a factor of two to three on
#: the prediction, because the solver spends them on large cancelling once-per-revolution terms that
#: are only cancelling INSIDE the arc. Montenbruck et al. estimate three and say so plainly; this is
#: the measurement that agrees with them.
NSRP_DEFAULT = 3
SRP0_FULL = (-1e-7, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)


def fit_arc(t_obs, p_obs, t0, y0, force, nsrp=NSRP_DEFAULT, verbose=False, max_nfev=40):
    """Fit 6 epoch-state + 5 ECOM parameters to precise positions used as pseudo-observations.

    The Jacobian columns are integrated as ONE batch alongside a nominal trajectory, so every column
    shares the integrator's adaptive step sequence. A column differenced against an independently
    stepped integration carries that integration's own step-control difference, which at these
    tolerances is the size of the derivative being measured.

    The solver is scipy's trust-region least squares and NOT a hand-rolled Levenberg-Marquardt. The
    hand-rolled one, with a fixed damping of 1e-3, converged LINEARLY at about a factor of two per
    iteration and stalled: 30 m on a 24-hour GPS arc that scipy takes to 0.2 m. Orbit fits have
    genuinely near-degenerate parameter directions — a fixed multiple of diag(J'J) damps exactly
    those into immobility — and the stall looked so much like a missing force that it cost an
    afternoon of ablating the force model for a defect that was in the optimiser (2026-08-30).
    """
    from scipy.optimize import least_squares
    npar = 6 + nsrp
    step = np.array(STEP_STATE + [1e-9] * nsrp)
    x0 = np.concatenate([np.asarray(y0, float), np.array(SRP0_FULL[:nsrp], float)])

    def fun(x):
        pos = propagate(x[None, :6], t0, t_obs, force, x[None, 6:])[:, 0, :]
        return (pos - p_obs).ravel()

    def jac(x):
        pert = np.tile(x, (npar + 1, 1))
        for k in range(npar):
            pert[k + 1, k] += step[k]
        pos = propagate(pert[:, :6], t0, t_obs, force, pert[:, 6:])
        return np.stack([(pos[:, k + 1, :] - pos[:, 0, :]).ravel() / step[k]
                         for k in range(npar)], axis=1)

    sol = least_squares(fun, x0, jac=jac, x_scale=step, method="trf",
                        xtol=1e-14, ftol=1e-14, gtol=1e-14, max_nfev=max_nfev)
    rms = math.sqrt(float(np.mean(sol.fun ** 2)) * 3)
    if verbose:
        print(f"    {sol.nfev} evaluations, 3D rms {rms:.4f} m, status {sol.status}")
    return sol.x, rms


# ── self-test ─────────────────────────────────────────────────────────────────────────────────────
def selftest(gfc=".scratch/pgnss-bds/EGM96.gfc", horizons=True):
    """Check each piece against something that is not this file. Returns True if all of them pass.

        python3 scripts/pgnss_orbit.py [path/to/EGM96.gfc]

    Every check here is one that has actually caught something. The Horizons comparison found both
    analytic ephemerides referred to the equinox of J2000 while the code was treating them as of
    date — 1341 arcseconds, which is the difference between a Sun that is where the Sun is and one
    that is a third of a degree away. It needs the network; without it that check is skipped and
    said to be skipped, rather than silently passing.
    """
    ok = True

    def report(name, passed, detail=""):
        nonlocal ok
        ok = ok and passed
        print(f"  {'ok  ' if passed else 'FAIL'}  {name}{('  ' + detail) if detail else ''}")

    r = np.array([[2.0e7, 1.0e7, 1.5e7], [-3.0e7, 5.0e6, -2.0e7]])
    rn = np.linalg.norm(r, axis=1)[:, None]

    C = np.zeros((13, 13))
    S = np.zeros((13, 13))
    C[0, 0] = 1.0
    e = np.max(np.abs(grav_accel(r, C, S, 12) + GM_EARTH * r / rn ** 3) / (GM_EARTH / rn ** 2))
    report("point-mass gravity reproduces -GM r/r^3", e < 1e-12, f"rel {e:.1e}")

    J2 = 1.0826266e-3
    C2 = C.copy()
    C2[2, 0] = -J2
    got = grav_accel(r, C2, S, 12)
    x, y, z = r[:, 0], r[:, 1], r[:, 2]
    rr = rn[:, 0]
    k = 1.5 * J2 * GM_EARTH * RE ** 2 / rr ** 5
    want = np.stack([-GM_EARTH * x / rr ** 3 + k * x * (5 * z * z / rr ** 2 - 1),
                     -GM_EARTH * y / rr ** 3 + k * y * (5 * z * z / rr ** 2 - 1),
                     -GM_EARTH * z / rr ** 3 + k * z * (5 * z * z / rr ** 2 - 3)], axis=1)
    e = np.max(np.abs(got - want)) / np.max(np.abs(want))
    report("J2 reproduces its closed form", e < 1e-12, f"rel {e:.1e}")

    if pathlib.Path(gfc).is_file():
        Cg, Sg = read_gfc(gfc, 8)
        good = abs(Cg[2, 0] + 1.0826266e-3) < 2e-9 and abs(Cg[2, 2] - 1.57446e-6) < 2e-10
        report("EGM96 unnormalises to the known C20 and C22", good,
               f"C20 {Cg[2,0]:+.7e} C22 {Cg[2,2]:+.7e}")
    else:
        print(f"  skip  no gravity file at {gfc}")

    import datetime as _dt
    t = (_dt.datetime(2026, 8, 30, 12, tzinfo=_dt.timezone.utc)
         - _dt.datetime(1980, 1, 6, tzinfo=_dt.timezone.utc)).total_seconds() + LEAP
    s = sun_ecef(t)
    lon = math.degrees(math.atan2(s[1], s[0]))
    report("the Sun is over Greenwich at noon UTC", abs(lon) < 1.0, f"sub-solar longitude {lon:+.2f} deg")

    a0 = 2.6561e7
    v0 = math.sqrt(GM_EARTH / a0)
    f = Force(C, S, nmax=0, srp=False, tide=False, relativity=False, third=False, omega=0.0)
    y0 = np.array([[a0, 0, 0, 0, v0 * math.cos(0.96), v0 * math.sin(0.96)]])
    T = 2 * math.pi * math.sqrt(a0 ** 3 / GM_EARTH)
    d = float(np.linalg.norm(propagate(y0, 0.0, [20 * T], f, None)[0, 0] - y0[0, :3]))
    report("twenty Kepler revolutions close on themselves", d < 0.05, f"{d*1e3:.3f} mm")

    # The rotating-frame terms, against the same orbit written down in closed form and turned.
    inc, ecc = math.radians(55.0), 0.01
    n = math.sqrt(GM_EARTH / a0 ** 3)
    R = np.array([[1, 0, 0], [0, math.cos(inc), -math.sin(inc)], [0, math.sin(inc), math.cos(inc)]])

    def ecef(tt):
        M = n * tt
        E = M
        for _ in range(60):
            E -= (E - ecc * math.sin(E) - M) / (1 - ecc * math.cos(E))
        nu = 2 * math.atan2(math.sqrt(1 + ecc) * math.sin(E / 2), math.sqrt(1 - ecc) * math.cos(E / 2))
        rr_ = a0 * (1 - ecc * math.cos(E))
        h = math.sqrt(GM_EARTH * a0 * (1 - ecc ** 2))
        ri = R @ np.array([rr_ * math.cos(nu), rr_ * math.sin(nu), 0.0])
        vi = R @ np.array([-GM_EARTH / h * math.sin(nu), GM_EARTH / h * (ecc + math.cos(nu)), 0.0])
        th = OMEGA_E * tt
        Rz = np.array([[math.cos(th), math.sin(th), 0], [-math.sin(th), math.cos(th), 0], [0, 0, 1]])
        re = Rz @ ri
        return re, Rz @ vi - np.cross(np.array([0, 0, OMEGA_E]), re)

    f = Force(C, S, nmax=0, srp=False, tide=False, relativity=False, third=False, omega=OMEGA_E)
    r0, vv0 = ecef(0.0)
    d = float(np.linalg.norm(propagate(np.concatenate([r0, vv0])[None, :], 0.0, [72 * 3600.0], f,
                                       None)[0, 0] - ecef(72 * 3600.0)[0]))
    report("centrifugal and Coriolis reproduce a turned Kepler orbit over 72 h", d < 1e-3,
           f"{d*1e6:.1f} um")

    if horizons:
        try:
            import json
            import urllib.request

            def jpl(body, jd):
                u = ("https://ssd.jpl.nasa.gov/api/horizons.api?format=json&COMMAND='%s'"
                     "&OBJ_DATA='NO'&MAKE_EPHEM='YES'&EPHEM_TYPE='VECTORS'&CENTER='500@399'"
                     "&VEC_TABLE='1'&REF_PLANE='ECLIPTIC'&REF_SYSTEM='ICRF'&OUT_UNITS='KM-S'"
                     "&TLIST='%.8f'&TLIST_TYPE='JD'&TIME_TYPE='TT'" % (body, jd))
                txt = json.load(urllib.request.urlopen(u, timeout=60))["result"]
                blk = txt.split("$$SOE")[1].split("$$EOE")[0]
                for line in blk.splitlines():
                    if line.strip().startswith("X ="):
                        g = line.replace("X =", "").replace("Y =", "").replace("Z =", "").split()
                        return np.array([float(g[0]), float(g[1]), float(g[2])]) * 1e3
                raise RuntimeError("no vector in the reply")

            for label, fn, body, tol in (("Sun", sun_ecef, "10", 120.0),
                                         ("Moon", moon_ecef, "301", 300.0)):
                worst = 0.0
                for hours in (0, 48, 120):
                    tt = t + hours * 3600
                    T_ = _tt_centuries(tt)
                    eps = (84381.448 - 46.8150 * T_) * ARCSEC
                    v = fn(tt)
                    th = gmst(tt)
                    xe = v[0] * math.cos(th) - v[1] * math.sin(th)
                    ye = v[0] * math.sin(th) + v[1] * math.cos(th)
                    mine = np.array([xe, ye * math.cos(eps) + v[2] * math.sin(eps),
                                     -ye * math.sin(eps) + v[2] * math.cos(eps)])
                    pa = (5029.0966 * T_ + 1.11113 * T_ * T_) * ARCSEC
                    cp, sp_ = math.cos(pa), math.sin(pa)
                    mine = np.array([mine[0] * cp + mine[1] * sp_, -mine[0] * sp_ + mine[1] * cp,
                                     mine[2]])
                    ref = jpl(body, GPS_EPOCH_JD + (tt + TT_MINUS_GPS) / 86400.0)
                    ang = math.degrees(math.acos(np.clip(
                        float(mine @ ref) / np.linalg.norm(mine) / np.linalg.norm(ref), -1, 1))) * 3600
                    worst = max(worst, ang)
                report(f"the {label} agrees with JPL Horizons", worst < tol,
                       f"worst {worst:.0f} arcsec of {tol:.0f}")
        except Exception as exc:
            print(f"  skip  JPL Horizons unreachable ({type(exc).__name__})")

    print("\nself-test", "PASSED" if ok else "FAILED")
    return ok


if __name__ == "__main__":
    import sys as _sys
    _sys.exit(0 if selftest(*(_sys.argv[1:2] or [])) else 1)
