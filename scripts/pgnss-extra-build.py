#!/usr/bin/env python3
"""Build `HW_PGNSS_EXTRA` — the 6248-byte almanac / ionosphere companion that Huawei Health
serves a HUAWEI Band 11 Pro alongside the predicted ephemeris.

The layout was reverse-engineered from two genuine Huawei vintages (2026-08-22 and
2026-08-25) and is documented in full below.  Everything load-bearing is rebuilt here from
public sources; the ~290 bytes that are still not understood are copied verbatim from a
captured reference file and are marked `COPIED` at every site.

    offset  size  contents
    0x0000     8  two counters                                    COPIED (meaning unknown)
    0x0008     8  GPS UTC parameter set: t_ot, WN_t, dtLS, WN_LSF, DN, dtLSF
    0x0010     8  Klobuchar alpha[4], beta[4] (signed bytes)
    0x0018     4  u32 = 1
    0x001c   100  GLONASS frequency-channel table, 25 x 4 B
    0x0080   176  zero pad
    0x0130   264  per-GPS-satellite 8-byte table                  COPIED (X field unexplained)
    0x0238   792  three 264-byte id-list blocks                   COPIED (semantics unproven)
    0x0550  1028  GPS almanac      header + 32 x 32 B
    0x0954   772  GLONASS almanac  header + 24 x 32 B
    0x0c58   624  Galileo almanac  header + 28 x 22 B
    0x0ec8   176  Galileo slack (capacity 36)
    0x0f78  2272  BeiDou almanac   header + 63 x 36 B
    0x1858    16  trailer: valid_from, valid_to, 0, leap seconds

SATELLITE NUMBERING — every constellation except GLONASS stores a 0-BASED index
(field = PRN - 1); GLONASS stores the true slot number 1..24.  This was settled against
external sources, not against our own decoder: the ESA GSSC almanac's SVID set minus one
is exactly Huawei's Galileo set, and propagating the two almanacs to a common instant
agrees to 27 km under `SVID-1` versus 45 120 km under `SVID`.  The same test on YUMA gives
GPS, and the IAC almanac reproduces Huawei's GLONASS channel/inclination per slot exactly.

SOURCES (all anonymous, no login)
    GPS almanac      https://www.navcen.uscg.gov/.../current_yuma.alm
    Galileo almanac  https://www.gsc-europa.eu/sites/default/files/sites/all/files/<date>.xml
    GLONASS almanac  ftp://ftp.glonass-iac.ru/MCC/ALMANAC/<yyyy>/MCCT_<yymmdd>.agl
    Klobuchar + UTC  https://igs.bkg.bund.de/root_ftp/IGS/BRDC/<yyyy>/<doy>/  (RINEX 3 header)
    BeiDou           fitted to the BeiDou broadcast ephemeris in that same RINEX file
"""
import argparse, datetime, math, os, re, struct, subprocess, sys
import xml.etree.ElementTree as ET

import numpy as np

PI = math.pi
OMEGA_E = 7.2921151467e-5
MU_GPS = 3.986005e14
MU = 3.986004418e14                      # Galileo / BeiDou / GLONASS
GPS_EPOCH = datetime.datetime(1980, 1, 6, tzinfo=datetime.timezone.utc)
UNIX_GPS = 315964800                     # unix seconds at the GPS epoch
BDS_WEEK_OFFSET = 1356                   # GPS week - BDS week
BDS_SECOND_OFFSET = 14                   # GPST - BDT, seconds
# BeiDou almanac delta-i is referenced to 0.30 semicircles for MEO/IGSO but to 0.00 for the
# geostationary satellites.  Measured, not assumed: evaluating Huawei's own captured GEO
# records with the 0.00 reference puts them on their published station longitudes (C01
# 140.02E vs 140.0, C03 110.55 vs 110.5, C04 160.12 vs 160.0), while the 0.30 reference puts
# them 5-16 degrees away.  With 0.30 the field also saturates -- a 5 deg inclination is
# 0.27 sc from the reference and the 16-bit 2^-19 field only reaches 0.0625 -- which silently
# clamps every GEO to i = 42.75 deg and throws it 20 000 km out.
BDS_GEO = set(range(1, 6)) | set(range(59, 64))


def bds_di_ref(prn):
    return 0.0 if prn in BDS_GEO else 0.30
SIZE = 6248

REFERENCE = ".scratch/huawei-reference/2026-08-25/HW_PGNSS_EXTRA.bin"
CACHE = ".scratch/pgnss-extra/src"


# ── time ──────────────────────────────────────────────────────────────────────────────────
def gps_now(leap=18):
    return datetime.datetime.now(datetime.timezone.utc).timestamp() - UNIX_GPS + leap


def gps_to_utc(g, leap=18):
    return GPS_EPOCH + datetime.timedelta(seconds=g - leap)


def na_from_date(d):
    """GLONASS N_A: day number inside the current four-year interval, 1-based.
    Verified against Huawei: N_A 967 == 2026-08-24."""
    start = datetime.date(d.year - (d.year - 2024) % 4, 1, 1)
    return (d - start).days + 1


# ── fetch ─────────────────────────────────────────────────────────────────────────────────
def grab(url, name, offline=False, sniff=None):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    plain = path[:-3] if path.endswith(".gz") else path
    if os.path.exists(plain) and os.path.getsize(plain) > 0:
        return plain
    if os.path.exists(path) and os.path.getsize(path) > 0:
        subprocess.run(["gunzip", "-f", "-k", path], check=True)
        return plain
    if offline:
        raise SystemExit(f"--offline but {path} is not cached")
    print(f"  fetching {url}", file=sys.stderr)
    # -f makes curl exit non-zero on an HTTP error instead of saving the error page, which
    # is exactly how a 404 once got cached as a "Galileo almanac".
    r = subprocess.run(["curl", "-sS", "-f", "-L", "--max-time", "300", "-A", "Mozilla/5.0",
                        "-o", path, url], capture_output=True, text=True)
    ok = r.returncode == 0 and os.path.exists(path) and os.path.getsize(path) > 0
    if ok and sniff is not None:
        with open(path, "rb") as fh:
            ok = sniff(fh.read(4096))
    if not ok:
        if os.path.exists(path):
            os.remove(path)
        raise SystemExit(f"fetch failed: {url}\n{r.stderr.strip()}")
    if path.endswith(".gz"):
        subprocess.run(["gunzip", "-f", "-k", path], check=True)
    return plain


# ── parsers ───────────────────────────────────────────────────────────────────────────────
def parse_yuma(path):
    """YUMA almanac -> {PRN: elements}.  Angles are radians, toa is seconds of week,
    `week` is the GPS week modulo 1024."""
    out = {}
    for blk in open(path).read().split("******")[1:]:
        g = dict(re.findall(r"^\s*([A-Za-z0-9()/ .]+?):\s+(\S+)\s*$", blk, re.M))
        if "ID" not in g:
            continue
        # YUMA carries the week mod 1024; lift it into the current 1024-week era.
        wk = int(g["week"])
        era = round((gps_now() / 604800 - wk) / 1024)
        out[int(g["ID"])] = dict(
            week=wk + 1024 * era, toa=float(g["Time of Applicability(s)"]),
            e=float(g["Eccentricity"]), i0=float(g["Orbital Inclination(rad)"]),
            omegadot=float(g["Rate of Right Ascen(r/s)"]), sqrtA=float(g["SQRT(A)  (m 1/2)"]),
            omega0=float(g["Right Ascen at Week(rad)"]), omega=float(g["Argument of Perigee(rad)"]),
            m0=float(g["Mean Anom(rad)"]), af0=float(g["Af0(s)"]), af1=float(g["Af1(s/s)"]),
            health=int(g["Health"]))
    return out


def parse_gssc(path):
    """ESA GSSC almanac XML -> {SVID: elements}.  Angles are already semicircles;
    aSqRoot is the offset from 5440.588203 m^(1/2)."""
    root = ET.parse(path).getroot()
    issue = root.find(".//issueDate").text
    tissue = datetime.datetime.strptime(issue[:19], "%Y-%m-%dT%H:%M:%S").replace(
        tzinfo=datetime.timezone.utc)
    out = {}
    for sv in root.findall(".//svAlmanac"):
        a = sv.find("almanac")
        f = lambda t: float(a.find(t).text)
        t0a = f("t0a")
        # wna in the XML is the 2-bit broadcast field; recover the full week from the
        # issue date, which is by construction within a few hours of t0a.
        gi = (tissue - GPS_EPOCH).total_seconds() + 18
        wk = round((gi - t0a) / 604800)
        health = max(int(sv.find(".//statusE5b").text), int(sv.find(".//statusE1B").text))
        out[int(sv.find("SVID").text)] = dict(
            week=wk, t0a=t0a, dsqrtA=f("aSqRoot"), e=f("ecc"), di=f("deltai"),
            omega0=f("omega0"), omegadot=f("omegaDot"), omega=f("w"), m0=f("m0"),
            af0=f("af0"), af1=f("af1"), health=health)
    return out


def parse_agl(path):
    """IAC GLONASS almanac (.agl) -> {slot: parameters}.  Already in exactly the
    parameterisation Huawei stores: lambda/di/omega/eps in semicircles, dT in seconds."""
    L = open(path).read().split("\n")
    out = {}
    i = 0
    while i + 2 < len(L):
        if L[i].strip() and len(L[i].split()) == 4:
            f, g = L[i + 1].split(), L[i + 2].split()
            d = datetime.date(int(f[5]), int(f[4]), int(f[3]))
            out[int(f[0])] = dict(chan=int(f[1]), NA=na_from_date(d), date=d,
                                  tlam=float(f[6]), tau=float(f[9]), lam=float(g[0]),
                                  di=float(g[1]), omega=float(g[2]), e=float(g[3]),
                                  dT=float(g[4]), dTdot=float(g[5]))
            i += 3
        else:
            i += 1
    return out


def parse_rinex_header(path):
    """-> (alpha[4], beta[4], utc dict).  Values are in SI; the raw integer fields are
    recovered by the caller with the ICD scale factors."""
    alpha = beta = None
    utc = {}
    for ln in open(path):
        if "END OF HEADER" in ln:
            break
        lab = ln[60:].strip()
        if lab == "IONOSPHERIC CORR" and ln[:4] == "GPSA" and alpha is None:
            alpha = [float(ln[5 + 12 * k:17 + 12 * k].replace("D", "E")) for k in range(4)]
        elif lab == "IONOSPHERIC CORR" and ln[:4] == "GPSB" and beta is None:
            beta = [float(ln[5 + 12 * k:17 + 12 * k].replace("D", "E")) for k in range(4)]
        elif lab == "TIME SYSTEM CORR" and ln[:4] == "GPUT":
            utc["t_ot"] = float(ln[38:45])
            utc["WN_t"] = int(ln[45:50])
        elif lab == "LEAP SECONDS":
            v = ln[:60].split()
            utc["dtLS"] = int(v[0])
            if len(v) >= 4:
                utc["dtLSF"], utc["WN_LSF"], utc["DN"] = int(v[1]), int(v[2]), int(v[3])
    if alpha is None or beta is None:
        raise SystemExit("RINEX header carries no GPSA/GPSB ionospheric correction")
    return alpha, beta, utc


def parse_rinex_bds(path):
    """BeiDou broadcast ephemeris -> {PRN: [record dicts]}, times in BDT seconds of week."""
    L = open(path).read().split("\n")
    out = {}
    i = 0
    while i < len(L):
        if re.match(r"^C\d\d \d{4} ", L[i]):
            prn = int(L[i][1:3])
            # RINEX 3 nav: 3 values on the epoch line at columns 23/42/61, then 4 values
            # per continuation line at 4/23/42/61.  Blank fields must still occupy a slot,
            # otherwise every field after a blank one silently shifts.
            vals = []
            for col in (23, 42, 61):
                t = L[i][col:col + 19].strip()
                vals.append(float(t.replace("D", "E")) if t else 0.0)
            for k in range(1, 8):
                ln = L[i + k] if i + k < len(L) else ""
                for col in (4, 23, 42, 61):
                    t = ln[col:col + 19].strip()
                    vals.append(float(t.replace("D", "E")) if t else 0.0)
            if len(vals) >= 27:
                out.setdefault(prn, []).append(dict(
                    af0=vals[0], af1=vals[1], af2=vals[2],
                    aode=vals[3], crs=vals[4], dn=vals[5], m0=vals[6],
                    cuc=vals[7], e=vals[8], cus=vals[9], sqrtA=vals[10],
                    toe=vals[11], cic=vals[12], omega0=vals[13], cis=vals[14],
                    i0=vals[15], crc=vals[16], omega=vals[17], omegadot=vals[18],
                    idot=vals[19], week=int(vals[21]),
                    toe_abs=int(vals[21]) * 604800 + vals[11]))
            i += 8
        else:
            i += 1
    return out


# ── propagation ───────────────────────────────────────────────────────────────────────────
def kepler(M, e):
    E = M
    for _ in range(60):
        E -= (E - e * math.sin(E) - M) / (1 - e * math.cos(E))
    return E


def alm_pos(el, tk, toa_sow, mu):
    """The plain broadcast-Kepler model the band applies to an almanac.

    `tk` is the SIGNED elapsed time since toa in seconds and may run past a week; `toa_sow`
    is the almanac reference time as seconds of week.  Omega must be formed as
    `(OmegaDot - OMEGA_E) * tk - OMEGA_E * toa`, which stays continuous as tk grows.  The
    algebraically "equivalent" `- OMEGA_E * (seconds of week of t)` is NOT equivalent across
    the week roll -- OMEGA_E * 604800 is 44.09 rad, not a multiple of 2*pi -- and silently
    threw satellites 4820 km out on any arc that straddled it."""
    A = abs(el["sqrtA"]) ** 2
    e = min(abs(el["e"]), 0.05)
    E = kepler(el["m0"] + math.sqrt(mu / A ** 3) * tk, e)
    v = math.atan2(math.sqrt(1 - e ** 2) * math.sin(E), math.cos(E) - e)
    u = v + el["omega"]
    r = A * (1 - e * math.cos(E))
    i = el["i0"]
    xp, yp = r * math.cos(u), r * math.sin(u)
    Om = el["omega0"] + (el["omegadot"] - OMEGA_E) * tk - OMEGA_E * toa_sow
    return np.array([xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om),
                     xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om),
                     yp * math.sin(i)])


def bds_eph_pos(r, t_bdt_sow):
    """Full BeiDou broadcast model, including the GEO frame rotation (BDS-SIS-ICD 5.2.4.12).
    Used only to make the truth points the almanac is fitted to."""
    A = r["sqrtA"] ** 2
    tk = (t_bdt_sow - r["toe"] + 302400) % 604800 - 302400
    n = math.sqrt(MU / A ** 3) + r["dn"]
    E = kepler(r["m0"] + n * tk, r["e"])
    v = math.atan2(math.sqrt(1 - r["e"] ** 2) * math.sin(E), math.cos(E) - r["e"])
    phi = v + r["omega"]
    s2, c2 = math.sin(2 * phi), math.cos(2 * phi)
    u = phi + r["cus"] * s2 + r["cuc"] * c2
    rad = A * (1 - r["e"] * math.cos(E)) + r["crs"] * s2 + r["crc"] * c2
    i = r["i0"] + r["idot"] * tk + r["cis"] * s2 + r["cic"] * c2
    xp, yp = rad * math.cos(u), rad * math.sin(u)
    geo = r["sqrtA"] ** 2 > 4.0e7 and abs(math.degrees(r["i0"])) < 10
    if geo:
        Om = r["omega0"] + r["omegadot"] * tk - OMEGA_E * r["toe"]
        xg = xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om)
        yg = xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om)
        zg = yp * math.sin(i)
        # Rz(omega_e*tk) . Rx(-5deg) in the ICD's transposed convention.  The signs were
        # settled by measurement, not by reading: with these two, consecutive broadcast
        # records agree to 2 m where they overlap and the satellite comes out nearly fixed
        # in ECEF (6.8 km per 300 s, against 922 km of inertial motion) -- which is what
        # "geostationary" means.  Either sign flipped gives ~21 800 km of disagreement.
        p, z = math.radians(5.0), -OMEGA_E * tk
        y1 = yg * math.cos(p) - zg * math.sin(p)
        z1 = yg * math.sin(p) + zg * math.cos(p)
        return np.array([xg * math.cos(z) - y1 * math.sin(z),
                         xg * math.sin(z) + y1 * math.cos(z), z1])
    Om = r["omega0"] + (r["omegadot"] - OMEGA_E) * tk - OMEGA_E * r["toe"]
    return np.array([xp * math.cos(Om) - yp * math.cos(i) * math.sin(Om),
                     xp * math.sin(Om) + yp * math.cos(i) * math.cos(Om), yp * math.sin(i)])


def alm_rms(el, tks, points, toa_sow, mu=MU):
    """3D RMS of an almanac element set against truth points, in metres.
    `tks` are signed seconds since toa, computed in absolute time by the caller."""
    d = [alm_pos(el, tk, toa_sow, mu) - p for tk, p in zip(tks, points)]
    return float(np.sqrt(np.mean(np.sum(np.array(d) ** 2, axis=1))))


def fit_almanac(tks, points, toa_sow, seed, mu=MU):
    """Least-squares fit of the seven plain-Kepler almanac parameters to ECEF truth points.
    `times_sow` are BDT/GPS seconds of week; the fit is what the band will actually evaluate,
    so any bias in our truth model shows up as a residual rather than hiding."""
    from scipy.optimize import least_squares
    keys = ["sqrtA", "e", "i0", "omega0", "omega", "m0", "omegadot"]
    x0 = np.array([seed[k] for k in keys], float)
    scale = np.array([1.0, 1e-3, 1e-2, 1e-2, 1e-2, 1e-2, 1e-11])

    def resid(x):
        el = dict(zip(keys, x))
        el["e"] = abs(el["e"])
        return np.concatenate([alm_pos(el, tk, toa_sow, mu) - p
                               for tk, p in zip(tks, points)])

    r = least_squares(resid, x0, x_scale=scale, method="lm", max_nfev=4000)
    el = dict(zip(keys, r.x))
    el["e"] = abs(el["e"])
    rms = float(np.sqrt(np.mean(r.fun.reshape(-1, 3) ** 2, axis=None) * 3))
    return el, rms


# ── encoding ──────────────────────────────────────────────────────────────────────────────
def sgn(v, bits):
    lo, hi = -(1 << (bits - 1)), (1 << (bits - 1)) - 1
    return max(lo, min(hi, int(round(v))))


def uns(v, bits):
    return max(0, min((1 << bits) - 1, int(round(v))))


def sc(rad):
    """radians -> semicircles wrapped into [-1, 1)"""
    return (rad / PI + 1.0) % 2.0 - 1.0


def build(epoch_gps, ref, yuma, gssc, agl, iono, utc, bds, log):
    b = bytearray(SIZE)

    # ---- 0x0000  COPIED: two counters, meaning unknown (A=(3,1) B=(5,2)) ----------------
    b[0x0000:0x0008] = ref[0x0000:0x0008]

    # ---- 0x0008  GPS UTC parameter set ---------------------------------------------------
    b[0x08] = uns(utc["t_ot"] / 4096, 8)
    b[0x09] = utc["WN_t"] & 0xFF
    b[0x0A] = uns(utc["dtLS"], 8)
    # WN_LSF / DN / dtLSF advertise the next leap second.  This RINEX header carries only the
    # current value, so the three bytes are COPIED; they encode "none pending" (137, 7, 18).
    b[0x0B] = utc.get("WN_LSF", ref[0x0B]) & 0xFF
    b[0x0C] = uns(utc.get("DN", ref[0x0C]), 8)
    b[0x0D] = uns(utc.get("dtLSF", ref[0x0D]), 8)
    b[0x0E:0x10] = b"\0\0"

    # ---- 0x0010  Klobuchar ---------------------------------------------------------------
    for k, s in enumerate((-30, -27, -24, -24)):
        b[0x10 + k] = sgn(iono[0][k] / 2.0 ** s, 8) & 0xFF
    for k, s in enumerate((11, 14, 16, 16)):
        b[0x14 + k] = sgn(iono[1][k] / 2.0 ** s, 8) & 0xFF

    struct.pack_into("<I", b, 0x18, 1)

    # ---- 0x001c  GLONASS frequency-channel table -----------------------------------------
    # word 0 holds the count; word n holds slot n's channel.  The 0x01c0 half-word and the
    # zeroed final word reproduce the reference exactly (their meaning is unknown).
    struct.pack_into("<BBH", b, 0x1C, 24, 0, 0x01C0)
    for n in range(1, 25):
        chan = agl[n]["chan"] if n in agl else 0
        struct.pack_into("<bBH", b, 0x1C + 4 * n, chan, n % 24, 0x01C0 if n < 24 else 0)

    # ---- 0x0130  COPIED: per-GPS-satellite 8-byte table.  Its `X` field (7..32, moves ----
    # ---- between vintages) is unexplained, so the whole 264-byte slot is carried over. ---
    b[0x0130:0x0238] = ref[0x0130:0x0238]
    # ---- 0x0238  COPIED: three 264-byte id-list blocks; byte-identical across both -------
    # ---- captured vintages, semantics unproven. -----------------------------------------
    b[0x0238:0x0550] = ref[0x0238:0x0550]

    # ---- 0x0550  GPS almanac -------------------------------------------------------------
    ref_wk = max(v["week"] for v in yuma.values())
    toa = max(v["toa"] for v in yuma.values())
    struct.pack_into("<BBH", b, 0x550, ref_wk & 0xFF, 32, 0)
    for prn in range(1, 33):
        p = 0x554 + (prn - 1) * 32
        a = yuma.get(prn)
        if a is None:
            continue
        struct.pack_into("<HHHhhH", b, p,
                         prn - 1,                                    # 0-BASED index
                         uns(a["e"] / 2 ** -21, 16),
                         uns(a["toa"] / 4096, 16),
                         sgn((a["i0"] / PI - 0.30) / 2 ** -19, 16),
                         sgn(a["omegadot"] / PI / 2 ** -38, 16),
                         0 if a["health"] == 0 else 255)
        struct.pack_into("<Iiii", b, p + 12,
                         uns(a["sqrtA"] / 2 ** -11, 32),
                         sgn(sc(a["omega0"]) / 2 ** -23, 32),
                         sgn(sc(a["omega"]) / 2 ** -23, 32),
                         sgn(sc(a["m0"]) / 2 ** -23, 32))
        struct.pack_into("<hh", b, p + 28,
                         sgn(a["af0"] / 2 ** -20, 16), sgn(a["af1"] / 2 ** -38, 16))
    log["gps"] = (len(yuma), ref_wk, toa)

    # ---- 0x0954  GLONASS almanac ---------------------------------------------------------
    struct.pack_into("<BBH", b, 0x954, 24, ref_wk & 0xFF, 0)
    for n in range(1, 25):
        g = agl.get(n)
        if g is None:
            continue
        p = 0x958 + (n - 1) * 32
        struct.pack_into("<HBB", b, p, g["NA"], n, g["chan"] & 0x1F)
        struct.pack_into("<iiii", b, p + 4,
                         sgn(g["lam"] / 2 ** -20, 32), sgn(g["tlam"] / 2 ** -5, 32),
                         sgn(g["di"] / 2 ** -20, 32), sgn(g["dT"] / 2 ** -9, 32))
        struct.pack_into("<bBHHh", b, p + 20,
                         sgn(g["dTdot"] / 2 ** -14, 8), 0,
                         uns(g["e"] / 2 ** -20, 16),
                         0,                       # unknown; zero in every captured record
                         sgn(g["tau"] / 2 ** -18, 16))
        struct.pack_into("<BBH", b, p + 28, 1, 1, 0)
    log["glo"] = (len(agl), agl[1]["date"] if 1 in agl else None)

    # ---- 0x0c58  Galileo almanac ---------------------------------------------------------
    sv = sorted(gssc)
    t0a = max(v["t0a"] for v in gssc.values())
    gwk = max(v["week"] for v in gssc.values())
    struct.pack_into("<BBHHH", b, 0xC58, len(sv), gwk & 0xFF, 1,
                     uns(t0a / 600, 16), 0)
    for k, s in enumerate(sv):
        a = gssc[s]
        struct.pack_into("<HhhhHHhhhhh", b, 0xC60 + k * 22,
                         s - 1,                                      # 0-BASED index
                         sgn(a["dsqrtA"] / 2 ** -9, 16),
                         sgn(a["di"] / 2 ** -14, 16),
                         sgn(a["omegadot"] / 2 ** -33, 16),
                         uns(a["health"], 16),
                         uns(a["e"] / 2 ** -16, 16),
                         sgn(a["omega0"] / 2 ** -15, 16),
                         sgn(a["omega"] / 2 ** -15, 16),
                         sgn(a["m0"] / 2 ** -15, 16),
                         sgn(a["af0"] / 2 ** -19, 16),
                         sgn(a["af1"] / 2 ** -38, 16))
    log["gal"] = (len(sv), gwk, t0a)

    # ---- 0x0f78  BeiDou almanac ----------------------------------------------------------
    bwk, btoa, recs, carried = bds
    struct.pack_into("<BBBB", b, 0xF78, 63, bwk & 0xFF, uns(btoa / 4096, 8), 0)
    for slot in range(63):
        el = recs.get(slot)
        if el is None:
            continue
        p = 0xF7C + slot * 36
        struct.pack_into("<BBH", b, p, slot, uns(btoa / 4096, 8), 0)
        struct.pack_into("<II", b, p + 4,
                         uns(el["sqrtA"] / 2 ** -11, 32), uns(el["e"] / 2 ** -21, 32))
        struct.pack_into("<iiii", b, p + 12,
                         sgn(sc(el["omega"]) / 2 ** -23, 32),
                         sgn(sc(el["m0"]) / 2 ** -23, 32),
                         sgn(sc(el["omega0"]) / 2 ** -23, 32),
                         sgn(el["omegadot"] / PI / 2 ** -38, 32))
        # +34 is an unidentified flags word (0x58 / 0x102 / 0xd8 in the captures); carry the
        # reference's value for this slot so we never invent one.
        flags = struct.unpack_from("<H", ref, 0xF7C + slot * 36 + 34)[0]
        struct.pack_into("<hhhH", b, p + 28,
                         sgn((el["i0"] / PI - bds_di_ref(slot + 1)) / 2 ** -19, 16),
                         sgn(el.get("af0", 0.0) / 2 ** -20, 16),
                         sgn(el.get("af1", 0.0) / 2 ** -38, 16),
                         flags)
    log["bds"] = (len(recs), bwk, btoa, carried)

    # ---- 0x1858  trailer -----------------------------------------------------------------
    struct.pack_into("<4I", b, 0x1858, int(epoch_gps), int(epoch_gps) + 604800, 0,
                     int(utc["dtLS"]))
    return bytes(b)


def lift_week(lsb, near):
    """Recover a full week number from its low byte, nearest to `near`."""
    return near + ((lsb - near) % 256 + 128) % 256 - 128


def decode_ref_bds(ref, slot):
    """Read one BeiDou almanac record out of a captured file (radians out)."""
    p = 0xF7C + slot * 36
    idx, toa, health = struct.unpack_from("<BBH", ref, p)
    sa, e = struct.unpack_from("<II", ref, p + 4)
    if sa == 0:
        return None
    w, m0, om0, od = struct.unpack_from("<iiii", ref, p + 12)
    di, a0, a1, fl = struct.unpack_from("<hhhH", ref, p + 28)
    return dict(toa=toa * 4096, sqrtA=sa * 2 ** -11, e=e * 2 ** -21,
                omega=w * 2 ** -23 * PI, m0=m0 * 2 ** -23 * PI, omega0=om0 * 2 ** -23 * PI,
                omegadot=od * 2 ** -38 * PI,
                i0=(bds_di_ref(slot + 1) + di * 2 ** -19) * PI,
                af0=a0 * 2 ** -20, af1=a1 * 2 ** -38)


def build_bds(nav, ref, epoch_gps, carry, log):
    """Fit the plain-Kepler almanac to the BeiDou broadcast ephemeris.

    Everything here is in ABSOLUTE BDT seconds (week * 604800 + seconds of week), because a
    broadcast file straddles the week roll and seconds-of-week arithmetic across it is a
    reliable way to put a satellite half an orbit out.

    The truth points come from the FULL broadcast model — harmonics, i-dot, and the GEO
    frame rotation — while the fit is of the seven-parameter model the band actually
    evaluates, so an error in either shows up as a residual instead of cancelling."""
    gps_sow = epoch_gps % 604800
    gps_week = int(epoch_gps // 604800)
    epoch_bdt = (gps_week - BDS_WEEK_OFFSET) * 604800 + gps_sow - BDS_SECOND_OFFSET

    # Place toa inside the arc the broadcast data actually covers.  A freshly published
    # RINEX day holds only the hours already elapsed, so toa normally lands a few hours
    # before the validity start; Huawei's own captures put it days earlier still.
    span = [r["toe_abs"] for rs in nav.values() for r in rs]
    if not span:
        raise SystemExit("no BeiDou broadcast ephemeris in the navigation file")
    centre = (min(span) + max(span)) / 2
    target = min(centre, epoch_bdt)
    # toa is transmitted as a single byte of 4096 s WITHIN THE WEEK, so it must be snapped
    # in seconds-of-week: 604800 is not a multiple of 4096, so snapping the absolute time
    # instead leaves up to 4096 s of error once the week is taken off (~900 km at MEO).
    bwk = int(target // 604800)
    btoa = min(round((target % 604800) / 4096) * 4096, 147 * 4096)
    btoa_abs = bwk * 604800 + btoa

    recs, resid, osc = {}, {}, []
    for prn, rs in sorted(nav.items()):
        slot = prn - 1                                  # 0-BASED index
        if not 0 <= slot < 63:
            continue
        rs = sorted(rs, key=lambda r: r["toe_abs"])
        near = min(rs, key=lambda r: abs(r["toe_abs"] - btoa_abs))
        n = math.sqrt(MU / near["sqrtA"] ** 6) + near["dn"]
        seed = dict(sqrtA=near["sqrtA"], e=near["e"], i0=near["i0"], omega0=near["omega0"],
                    omega=near["omega"], m0=near["m0"] + n * (btoa_abs - near["toe_abs"]),
                    omegadot=near["omegadot"], af0=near["af0"], af1=near["af1"])
        period = 2 * PI * math.sqrt(near["sqrtA"] ** 6 / MU)
        lo = max(btoa_abs - period / 2, min(r["toe_abs"] for r in rs) - 7200)
        hi = min(btoa_abs + period / 2, max(r["toe_abs"] for r in rs) + 7200)
        ts, ps = [], []
        for t in np.arange(lo, hi + 1, 300.0):
            r = min(rs, key=lambda r: abs(r["toe_abs"] - t))
            if abs(r["toe_abs"] - t) > 7200:
                continue
            ts.append(t - btoa_abs)            # signed seconds since toa, absolute
            ps.append(bds_eph_pos(r, t % 604800))
        # Score the seed and the fit with the SAME independent metric and keep the better;
        # a least-squares solve that wanders off is otherwise indistinguishable from a good
        # one until it reaches the band.
        best, best_rms, how = seed, (alm_rms(seed, ts, ps, btoa) if ts else 1e12), "osculating"
        if len(ts) >= 20:
            try:
                el, _ = fit_almanac(ts, ps, btoa, seed)
                el["af0"], el["af1"] = near["af0"], near["af1"]
                r = alm_rms(el, ts, ps, btoa)
                if r < best_rms:
                    best, best_rms, how = el, r, "fitted"
            except Exception as exc:                                   # pragma: no cover
                print(f"  BDS C{prn:02d} fit failed ({exc})", file=sys.stderr)
        if best_rms > 100e3:
            print(f"  BDS C{prn:02d} rejected: {best_rms/1000:.0f} km residual",
                  file=sys.stderr)
            continue
        recs[slot] = best
        resid[prn] = best_rms
        if how == "osculating":
            osc.append(prn)

    carried = []
    if carry:
        ref_bwk = lift_week(ref[0xF79], bwk)
        for slot in range(63):
            if slot in recs:
                continue
            old = decode_ref_bds(ref, slot)
            if old is None:
                continue
            # Re-reference the captured record to our own toa through the almanac's own
            # model.  This is an EXTRAPOLATION of somebody else's stale almanac, not an
            # independent source; see the report for its measured cost.
            dt = btoa_abs - (ref_bwk * 604800 + old["toa"])
            nn = math.sqrt(MU / old["sqrtA"] ** 6)
            new = dict(old)
            new["m0"] = old["m0"] + nn * dt
            new["omega0"] = old["omega0"] + old["omegadot"] * dt
            recs[slot] = new
            carried.append(slot + 1)
    log["bds_resid"] = resid
    log["bds_osculating"] = osc
    return bwk, btoa, recs, carried


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--epoch", default="now",
                    help="validity start: 'now' (default), full GPS seconds, or ISO UTC")
    ap.add_argument("--out", default="HW_PGNSS_EXTRA")
    ap.add_argument("--reference", default=REFERENCE,
                    help="captured file the unidentified regions are copied from")
    ap.add_argument("--offline", action="store_true", help="use only cached downloads")
    ap.add_argument("--no-carry-stale", action="store_true",
                    help="leave BeiDou slots we cannot source from public data at zero "
                         "instead of extrapolating the captured file's records")
    args = ap.parse_args()

    ref = open(args.reference, "rb").read()
    if len(ref) != SIZE:
        raise SystemExit(f"reference is {len(ref)} bytes, expected {SIZE}")

    if args.epoch == "now":
        epoch = math.floor(gps_now() / 3600) * 3600
    elif re.fullmatch(r"\d+", args.epoch):
        epoch = int(args.epoch)
    else:
        d = datetime.datetime.fromisoformat(args.epoch).replace(tzinfo=datetime.timezone.utc)
        epoch = (d - GPS_EPOCH).total_seconds() + 18
    epoch = int(epoch)
    now = datetime.datetime.now(datetime.timezone.utc)
    doy = now.timetuple().tm_yday

    print(f"validity {gps_to_utc(epoch)} .. {gps_to_utc(epoch + 604800)} UTC", file=sys.stderr)
    print("sources:", file=sys.stderr)
    yuma_p = grab("https://www.navcen.uscg.gov/sites/default/files/gps/almanac/current_yuma.alm",
                  "current_yuma.alm", args.offline,
                  sniff=lambda d: b"Time of Applicability" in d)
    brdc_p = grab(f"https://igs.bkg.bund.de/root_ftp/IGS/BRDC/{now.year}/{doy:03d}/"
                  f"BRDC00WRD_R_{now.year}{doy:03d}0000_01D_MN.rnx.gz",
                  f"brdc{doy:03d}.rnx.gz", args.offline,
                  sniff=lambda d: d[:2] == b"\x1f\x8b")
    gal_p = None
    for back in range(0, 10):
        d = (now - datetime.timedelta(days=back)).strftime("%Y-%m-%d")
        try:
            gal_p = grab("https://www.gsc-europa.eu/sites/default/files/sites/all/files/"
                         f"{d}.xml", f"galileo_{d}.xml", args.offline,
                         sniff=lambda x: b"<svAlmanac>" in x)
            break
        except SystemExit:
            continue
    if gal_p is None:
        raise SystemExit("no Galileo almanac XML found in the last 10 days")
    # The IAC posts .agl files irregularly, so list the directory and take the newest one
    # at or before today rather than probing dates one by one.
    agl_p, agl_name = None, None
    if not args.offline:
        lst = subprocess.run(["curl", "-sS", "-f", "--max-time", "120",
                              f"ftp://ftp.glonass-iac.ru/MCC/ALMANAC/{now.year}/"],
                             capture_output=True, text=True)
        names = sorted(re.findall(r"(MCCT_\d{6}\.agl)", lst.stdout))
        stamp = now.strftime("%y%m%d")
        names = [n for n in names if n[5:11] <= stamp]
        if names:
            agl_name = names[-1]
    if agl_name is None:
        cached = sorted(f for f in os.listdir(CACHE) if re.fullmatch(r"MCCT_\d{6}\.agl", f)) \
            if os.path.isdir(CACHE) else []
        if not cached:
            raise SystemExit("no IAC GLONASS almanac available and none cached")
        agl_name = cached[-1]
    agl_p = grab(f"ftp://ftp.glonass-iac.ru/MCC/ALMANAC/{now.year}/{agl_name}", agl_name,
                 args.offline,
                 sniff=lambda x: re.match(rb"\s*\d\d \d\d \d{4}", x) is not None)

    yuma = parse_yuma(yuma_p)
    gssc = parse_gssc(gal_p)
    agl = parse_agl(agl_p)
    alpha, beta, utc = parse_rinex_header(brdc_p)
    nav = parse_rinex_bds(brdc_p)
    # A RINEX day published mid-day holds only the hours already elapsed, which is too short
    # an arc to fit mean elements to.  Merge the previous day as well; absolute BDT seconds
    # make the week roll between them a non-issue.
    try:
        prev = now - datetime.timedelta(days=1)
        pdoy = prev.timetuple().tm_yday
        prev_p = grab(f"https://igs.bkg.bund.de/root_ftp/IGS/BRDC/{prev.year}/{pdoy:03d}/"
                      f"BRDC00WRD_R_{prev.year}{pdoy:03d}0000_01D_MN.rnx.gz",
                      f"brdc{pdoy:03d}.rnx.gz", args.offline,
                      sniff=lambda d: d[:2] == b"\x1f\x8b")
        for prn, rs in parse_rinex_bds(prev_p).items():
            seen = {r["toe_abs"] for r in nav.get(prn, [])}
            nav.setdefault(prn, []).extend(r for r in rs if r["toe_abs"] not in seen)
    except SystemExit as exc:
        print(f"  (previous day unavailable: {exc})", file=sys.stderr)

    log = {}
    bds = build_bds(nav, ref, epoch, not args.no_carry_stale, log)
    out = build(epoch, ref, yuma, gssc, agl, (alpha, beta), utc, bds, log)
    with open(args.out, "wb") as fh:
        fh.write(out)

    print(f"\nwrote {args.out}  {len(out)} bytes", file=sys.stderr)
    print(f"  GPS      {log['gps'][0]} sats, WNa {log['gps'][1]}, toa {log['gps'][2]:.0f}",
          file=sys.stderr)
    print(f"  GLONASS  {log['glo'][0]} slots, N_A date {log['glo'][1]}", file=sys.stderr)
    print(f"  Galileo  {log['gal'][0]} sats, WNa {log['gal'][1]}, t0a {log['gal'][2]:.0f}",
          file=sys.stderr)
    r = log["bds_resid"]
    print(f"  BeiDou   {log['bds'][0]} slots ({len(r)} fitted, {len(log['bds'][3])} carried "
          f"forward from the capture), BDT week {log['bds'][1]}, toa {log['bds'][2]}",
          file=sys.stderr)
    if r:
        print(f"           fit residual: median {np.median(list(r.values())):.1f} m, "
              f"max {max(r.values()):.1f} m", file=sys.stderr)
    if log["bds"][3]:
        print(f"           carried forward (NOT independently sourced): "
              f"{['C%02d' % s for s in log['bds'][3]]}", file=sys.stderr)


if __name__ == "__main__":
    main()
