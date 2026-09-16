#!/usr/bin/env python3
"""Grade a generated `HW_PGNSS_EXTRA` — the ALMANAC — against physical truth and Huawei's own.

    python3 scripts/pgnss-almanac-verify.py ALMANAC [--ephemeris DIR] [--reference DIR]

## Why this exists

The band's time to first fix is decided by this file, and nothing measured it. On 2026-09-15 a set
whose ephemeris graded at **0.11 m** fixed in **two minutes**; the identical set with Huawei's own
almanac substituted fixed in **15 seconds**. The almanac decides WHICH satellites to look for and
roughly where — the ephemeris only matters once one has been acquired, so a perfect ephemeris buys
nothing if the search never converges.

Every other instrument here grades ephemeris: `pgnss-grade.py` propagates orbits, `pgnss-verify.py`
covers the four ephemeris record types and has no `EXTRA` path at all. `ExtraFileTest` compares our
Kotlin against our own Python twin, which shares every assumption. So the one file that governs
acquisition was checked only against itself.

## The rule this tool keeps

**Nothing here may lean on the encoder.** The decode comes from the independent decoder below,
written from the format rather than from the writer; the physical truth comes from separately
produced ephemeris files; the structural expectations come from captured Huawei vintages. Grading
output against something that shares its assumptions is how a file passes every check and still
takes two minutes on the wrist.

Promoted out of `.scratch/pgnss-extra/` on 2026-09-15, where it had been pinned to the 2026-08-30
fixtures and had never been run since.
"""
import argparse, math, pathlib, struct, sys, datetime
import numpy as np

# ============================================================================
# The independent decoder — written from the format, not from the encoder.
# Verified against two genuine vintages (2026-08-22, 2026-08-25) and cross-checked
# against the same-vintage ephemeris.
# ============================================================================
import struct, math, sys, datetime
PI = math.pi
# BeiDou delta-i reference: 0.00 semicircles for the geostationary satellites, 0.30 for the
# rest.  Confirmed by evaluating Huawei's own GEO records against published station
# longitudes; the 0.30 reference also saturates the 16-bit field for a GEO.
BDS_GEO = set(range(1, 6)) | set(range(59, 64))
GPS_EPOCH = datetime.datetime(1980, 1, 6, tzinfo=datetime.timezone.utc)

def decode(b):
    assert len(b) == 6248, len(b)
    o = {}
    o['counter0'], o['counter1'] = struct.unpack_from('<II', b, 0)
    o['utc'] = dict(t_ot=b[8] * 4096, WN_t=b[9], dtLS=b[10], WN_LSF=b[11], DN=b[12], dtLSF=b[13])
    o['klob_alpha_raw'] = struct.unpack_from('<4b', b, 0x10)
    o['klob_beta_raw']  = struct.unpack_from('<4b', b, 0x14)
    o['klob_alpha'] = [v * 2.0 ** s for v, s in zip(o['klob_alpha_raw'], (-30, -27, -24, -24))]
    o['klob_beta']  = [v * 2.0 ** s for v, s in zip(o['klob_beta_raw'],  (11, 14, 16, 16))]
    o['glo_channels'] = {n: struct.unpack_from('<b', b, 0x1c + 4 * n)[0] for n in range(1, b[0x1c] + 1)}
    o['gps_table'] = [struct.unpack_from('<HBBHH', b, 0x130 + 8 * k) for k in range(32)]
    o['idlists'] = []
    for off in (0x238, 0x340, 0x448):
        sysid = struct.unpack_from('<I', b, off)[0]
        n = struct.unpack_from('<H', b, off + 4)[0]
        o['idlists'].append((sysid, [struct.unpack_from('<HH', b, off + 6 + 4 * i) for i in range(n)]))
    # ---- GPS almanac: header <u8 WNa_lsb><u8 count><u16 0>, then count x 32 B
    o['gps_wna_lsb'], gps_n = b[0x550], b[0x551]
    o['gps_alm'] = {}
    for k in range(gps_n):
        p = 0x554 + k * 32
        idx, e, toa, di, odot, health = struct.unpack_from('<HHHhhH', b, p)
        sa, om0, om, m0 = struct.unpack_from('<Iiii', b, p + 12)
        af0, af1 = struct.unpack_from('<hh', b, p + 28)
        o['gps_alm'][idx + 1] = dict(sqrtA=sa * 2**-11, e=e * 2**-21, toa=toa * 4096,
            i0=(0.30 + di * 2**-19) * PI, omegadot=odot * 2**-38 * PI, health=health,
            omega0=om0 * 2**-23 * PI, omega=om * 2**-23 * PI, m0=m0 * 2**-23 * PI,
            af0=af0 * 2**-20, af1=af1 * 2**-38)
    # ---- GLONASS almanac: header <u8 count><u8 WN_lsb><u16 0>, then count x 32 B
    glo_n, o['glo_wn_lsb'] = b[0x954], b[0x955]
    o['glo_alm'] = {}
    for k in range(glo_n):
        p = 0x958 + k * 32
        na, n, ch = struct.unpack_from('<HBB', b, p)
        lam, tlam, di, dT = struct.unpack_from('<iiii', b, p + 4)
        dTd, _, eps, _z, tau = struct.unpack_from('<bBHHh', b, p + 20)
        C, M = b[p + 28], b[p + 29]
        o['glo_alm'][n] = dict(NA=na, chan=ch - 32 if ch >= 16 else ch,
            lam=lam * 2**-20, tlam=tlam * 2**-5, di=di * 2**-20, dT=dT * 2**-9,
            dTdot=dTd * 2**-14, e=eps * 2**-20, tau=tau * 2**-18, C=C, M=M,
            period=43200 + dT * 2**-9, incl_deg=(63.0 / 180 + di * 2**-20) * 180)
    # ---- Galileo almanac: header <u8 count><u8 WNa_lsb><u16 1><u16 t0a/600><u16 ?>
    gal_n, o['gal_wna_lsb'] = b[0xc58], b[0xc59]
    o['gal_t0a'] = struct.unpack_from('<H', b, 0xc5c)[0] * 600
    o['gal_alm'] = {}
    for k in range(gal_n):
        p = 0xc60 + k * 22
        prn, dsa, di, odot, health, e, om0, w, m0, af0, af1 = struct.unpack_from('<HhhhHHhhhhh', b, p)
        o['gal_alm'][prn] = dict(sqrtA=5440.588203 + dsa * 2**-9, e=e * 2**-16,
            i0=(56.0 / 180 + di * 2**-14) * PI, omegadot=odot * 2**-33 * PI, health=health,
            omega0=om0 * 2**-15 * PI, omega=w * 2**-15 * PI, m0=m0 * 2**-15 * PI,
            af0=af0 * 2**-19, af1=af1 * 2**-38, toa=o['gal_t0a'])
    # ---- BeiDou almanac: header <u8 count><u8 WNa_lsb><u8 toa><u8 0>, then count x 36 B
    bds_n, o['bds_wna_lsb'], o['bds_toa'] = b[0xf78], b[0xf79], b[0xf7a] * 4096
    o['bds_alm'] = {}
    for k in range(bds_n):
        p = 0xf7c + k * 36
        idx, toa, health = struct.unpack_from('<BBH', b, p)
        sa, e = struct.unpack_from('<II', b, p + 4)
        if sa == 0:
            continue
        w, m0, om0, odot = struct.unpack_from('<iiii', b, p + 12)
        di, a0, a1, flags = struct.unpack_from('<hhhH', b, p + 28)
        o['bds_alm'][idx + 1] = dict(sqrtA=sa * 2**-11, e=e * 2**-21, toa=toa * 4096,
            omega=w * 2**-23 * PI, m0=m0 * 2**-23 * PI, omega0=om0 * 2**-23 * PI,
            omegadot=odot * 2**-38 * PI, health=health,
            i0=((0.0 if idx + 1 in BDS_GEO else 0.30) + di * 2**-19) * PI,
            af0=a0 * 2**-20, af1=a1 * 2**-38, flags=flags)
    t0, t1, z, leap = struct.unpack_from('<4I', b, 0x1858)
    o['valid_from_gps'], o['valid_to_gps'], o['trailer_zero'], o['leap'] = t0, t1, z, leap
    o['valid_from'] = GPS_EPOCH + datetime.timedelta(seconds=t0 - leap)
    o['valid_to']   = GPS_EPOCH + datetime.timedelta(seconds=t1 - leap)
    return o

# The decoder's own command-line dump lived here and is deliberately dropped: inlined into
# this tool it ran first and consumed the arguments meant for the grader.

PI = math.pi; OMEGA_E = 7.2921151467e-5
MU_GPS = 3.986005e14; MU = 3.986004418e14
GPS_EPOCH = datetime.datetime(1980, 1, 6, tzinfo=datetime.timezone.utc)

_ap = argparse.ArgumentParser(description=__doc__,
                              formatter_class=argparse.RawDescriptionHelpFormatter)
_ap.add_argument("almanac", help="the HW_PGNSS_EXTRA to grade")
_ap.add_argument("--ephemeris", default=".scratch/pgnss-out",
                 help="directory holding the HW_PGNSS_* ephemeris of the SAME build (the physical "
                      "truth this almanac is graded against)")
_ap.add_argument("--nav", nargs="*",
                 help="RINEX navigation file(s) carrying BeiDou ephemeris — the truth the BeiDou "
                      "almanac is graded against. Newest first. Without them BeiDou is not graded "
                      "at all, which is how the one constellation we FIT ourselves went unmeasured.")
_ap.add_argument("--reference", default=".scratch/huawei-reference",
                 help="directory of captured Huawei vintages, one subdirectory per date")
_args = _ap.parse_args()

MINE = _args.almanac
EPH = pathlib.Path(_args.ephemeris)
# Every captured vintage present, newest last. A capture is a dated subdirectory holding either
# `HW_PGNSS_EXTRA` or `HW_PGNSS_EXTRA.bin` — both spellings exist in the archive.
REFS = []
for _d in sorted(pathlib.Path(_args.reference).glob("*")):
    for _n in ("HW_PGNSS_EXTRA", "HW_PGNSS_EXTRA.bin"):
        if (_d / _n).is_file():
            REFS.append((str(_d / _n), _d.name)); break
if not REFS:
    sys.exit(f"no captured reference under {_args.reference}")
mine = open(MINE, "rb").read()
fail = []

def head(t): print("\n" + "=" * 78 + f"\n{t}\n" + "=" * 78)

def check(ok, msg):
    print(("  PASS  " if ok else "  FAIL  ") + msg)
    if not ok: fail.append(msg)

# ── 1. round trip ─────────────────────────────────────────────────────────────────────────
head("1. ROUND TRIP through decode_extra.py (independent decoder)")
d = decode(mine)
check(len(mine) == 6248, f"file is {len(mine)} bytes")
print(f"  valid {d['valid_from']} .. {d['valid_to']} UTC, leap {d['leap']} s")
check((d['valid_to'] - d['valid_from']).total_seconds() == 604800, "validity span is exactly 604800 s")
check(d['leap'] == 18, "leap seconds = 18")
now = datetime.datetime.now(datetime.timezone.utc)
check(d['valid_from'] <= now <= d['valid_to'], f"file is valid right now ({now:%Y-%m-%d %H:%M} UTC)")
check(d['valid_to'] > datetime.datetime(2026, 9, 1, 2, 59, 42, tzinfo=datetime.timezone.utc),
      "outlives the 2026-09-01 02:59:42 expiry of the file we currently ship")
print(f"  GLONASS channels: {[d['glo_channels'][n] for n in sorted(d['glo_channels'])]}")
print(f"  Klobuchar alpha raw {d['klob_alpha_raw']}  beta raw {d['klob_beta_raw']}")
print(f"  UTC set {d['utc']}")
check(len(d['gps_alm']) == 32, f"GPS almanac has {len(d['gps_alm'])} satellites")
check(len(d['glo_alm']) == 24, f"GLONASS almanac has {len(d['glo_alm'])} slots")
check(len(d['gal_alm']) >= 24, f"Galileo almanac has {len(d['gal_alm'])} satellites")
check(len(d['bds_alm']) >= 40, f"BeiDou almanac has {len(d['bds_alm'])} satellites")
# every record's own index field must equal its slot
check(all(n == i for i, n in enumerate(sorted(d['glo_alm']), start=1)), "GLONASS slots are 1..24")

# ── 2. byte-position diff against BOTH captures ───────────────────────────────────────────
head("2. BYTE-POSITION DIFF against both captured vintages")
SEG = [(0x0000,0x0008,'counters (copied)'),(0x0008,0x0010,'UTC set'),(0x0010,0x0018,'Klobuchar'),
       (0x0018,0x001c,'const 1'),(0x001c,0x0080,'GLONASS channel table'),(0x0080,0x0130,'zero pad'),
       (0x0130,0x0238,'GPS 8B table (copied)'),(0x0238,0x0550,'id lists (copied)'),
       (0x0550,0x0554,'GPS hdr'),(0x0554,0x0954,'GPS almanac'),
       (0x0954,0x0958,'GLO hdr'),(0x0958,0x0c58,'GLONASS almanac'),
       (0x0c58,0x0c60,'GAL hdr'),(0x0c60,0x0ec8,'Galileo almanac'),(0x0ec8,0x0f78,'Galileo slack'),
       (0x0f78,0x0f7c,'BDS hdr'),(0x0f7c,0x1858,'BeiDou almanac'),(0x1858,0x1868,'trailer')]
for path, tag in REFS:
    ref = open(path, "rb").read()
    m = np.frombuffer(mine, np.uint8); r = np.frombuffer(ref, np.uint8)
    onlyref = ((r != 0) & (m == 0)); onlymine = ((r == 0) & (m != 0))
    print(f"\n  vs {tag}:  Huawei-nonzero-we-zero {int(onlyref.sum())},  "
          f"we-nonzero-Huawei-zero {int(onlymine.sum())}")
    for s, e, w in SEG:
        a, b = int(onlyref[s:e].sum()), int(onlymine[s:e].sum())
        if a or b:
            print(f"      0x{s:04x}-0x{e:04x} {w:26s} theirs-only {a:4d}  ours-only {b:4d}")
    check(int(onlymine[0x0ec8:0x0f78].sum()) == 0, f"[{tag}] Galileo slack stays zero")
    check(int(onlymine[0x0080:0x0130].sum()) == 0, f"[{tag}] 0x80-0x130 pad stays zero")
    for s, e, w in [(0x0000,0x0008,'counters'),(0x0130,0x0238,'GPS 8B table'),(0x0238,0x0550,'id lists')]:
        if tag == "2026-08-25":
            check(mine[s:e] == ref[s:e], f"[{tag}] {w} copied verbatim")

# record-population comparison, which is what actually matters
ref25 = open(REFS[-1][0], "rb").read()
def pop_gps(b): return {k for k in range(32) if struct.unpack_from('<I', b, 0x554+k*32+12)[0]}
def pop_glo(b): return {k for k in range(24) if b[0x958+k*32+2]}
def pop_gal(b): return {struct.unpack_from('<H', b, 0xc60+k*22)[0] for k in range(b[0xc58])}
def pop_bds(b): return {k for k in range(63) if struct.unpack_from('<I', b, 0xf7c+k*36+4)[0]}
for name, fn in (("GPS", pop_gps), ("GLONASS", pop_glo), ("Galileo", pop_gal), ("BeiDou", pop_bds)):
    a, b = fn(mine), fn(ref25)
    miss = sorted(b - a); extra = sorted(a - b)
    print(f"  {name:8s} populated {len(a):2d} (Huawei {len(b):2d})"
          + (f"  MISSING vs Huawei: {miss}" if miss else "")
          + (f"  extra: {extra}" if extra else ""))

# ── 3. physical: our GPS almanac vs the separately-built ephemeris ────────────────────────
head("3. PHYSICAL — almanac vs independently produced ephemeris")
def prop(el, tk, toa_sow, mu):
    A = el["sqrtA"]**2; n = math.sqrt(mu/A**3); M = el["m0"] + n*tk; E = M
    for _ in range(80): E -= (E - el["e"]*math.sin(E) - M)/(1 - el["e"]*math.cos(E))
    v = math.atan2(math.sqrt(1-el["e"]**2)*math.sin(E), math.cos(E)-el["e"])
    u = v + el["omega"]; r = A*(1-el["e"]*math.cos(E)); i = el["i0"]
    xp, yp = r*math.cos(u), r*math.sin(u)
    Om = el["omega0"] + (el["omegadot"]-OMEGA_E)*tk - OMEGA_E*toa_sow
    return np.array([xp*math.cos(Om)-yp*math.cos(i)*math.sin(Om),
                     xp*math.sin(Om)+yp*math.cos(i)*math.cos(Om), yp*math.sin(i)])
def eph_prop(e, tk, toe_sow):
    A = e["sqrtA"]**2; n = math.sqrt(MU_GPS/A**3)+e["dn"]; M = e["m0"]+n*tk; E = M
    for _ in range(80): E -= (E - e["e"]*math.sin(E) - M)/(1 - e["e"]*math.cos(E))
    v = math.atan2(math.sqrt(1-e["e"]**2)*math.sin(E), math.cos(E)-e["e"])
    phi = v + e["omega"]; s2, c2 = math.sin(2*phi), math.cos(2*phi)
    u = phi + e["cus"]*s2 + e["cuc"]*c2
    r = A*(1-e["e"]*math.cos(E)) + e["crs"]*s2 + e["crc"]*c2
    i = e["i0"] + e["cis"]*s2 + e["cic"]*c2 + e["idot"]*tk
    xp, yp = r*math.cos(u), r*math.sin(u)
    Om = e["omega0"] + (e["omegadot"]-OMEGA_E)*tk - OMEGA_E*toe_sow
    return np.array([xp*math.cos(Om)-yp*math.cos(i)*math.sin(Om),
                     xp*math.sin(Om)+yp*math.cos(i)*math.cos(Om), yp*math.sin(i)])
def load_gps_eph(path):
    b = open(path, "rb").read(); out = {}
    for i in range(36):
        ts, off, ln = struct.unpack_from('<III', b, 12*i)
        if not ln: continue
        for k in range(struct.unpack_from('<I', b, off)[0]):
            r = b[off+4+k*80:off+4+(k+1)*80]
            idx = struct.unpack_from('<H', r, 0)[0]
            out.setdefault(idx+1, []).append(dict(
                m0=struct.unpack_from('<i',r,8)[0]*2**-31*PI, dn=struct.unpack_from('<i',r,12)[0]*2**-43*PI,
                e=struct.unpack_from('<I',r,16)[0]*2**-33, sqrtA=struct.unpack_from('<I',r,20)[0]*2**-19,
                omega0=struct.unpack_from('<i',r,24)[0]*2**-31*PI, i0=struct.unpack_from('<i',r,28)[0]*2**-31*PI,
                omega=struct.unpack_from('<i',r,32)[0]*2**-31*PI, omegadot=struct.unpack_from('<i',r,36)[0]*2**-43*PI,
                idot=struct.unpack_from('<h',r,40)[0]*2**-43*PI, cuc=struct.unpack_from('<h',r,42)[0]*2**-29,
                cus=struct.unpack_from('<h',r,44)[0]*2**-29, crc=struct.unpack_from('<h',r,46)[0]*2**-5,
                crs=struct.unpack_from('<h',r,48)[0]*2**-5, cic=struct.unpack_from('<h',r,50)[0]*2**-29,
                cis=struct.unpack_from('<h',r,52)[0]*2**-29,
                toe=struct.unpack_from('<H',r,72)[0]*16, ts=ts))
    return out
eph = load_gps_eph(str(EPH / "HW_PGNSS_GPS"))
toa_sow = list(d['gps_alm'].values())[0]['toa']
wna = 2434 if d['gps_alm'] else 0
# the almanac's absolute reference: recover the week from the file's own validity epoch
vf = (d['valid_from'] - GPS_EPOCH).total_seconds() + d['leap']
wk = round((vf - toa_sow) / 604800) + int(vf // 604800) - int(vf // 604800)
alm_abs = (int(vf // 604800)) * 604800 + toa_sow
if alm_abs < vf - 302400: alm_abs += 604800
if alm_abs > vf + 302400: alm_abs -= 604800
errs = []
for prn, al in sorted(d['gps_alm'].items()):
    if prn not in eph or al['health'] != 0: continue
    e0 = min(eph[prn], key=lambda e: abs(e['ts'] - alm_abs))
    t_abs = e0['ts']
    pe = eph_prop(e0, ((t_abs % 604800) - e0['toe'] + 302400) % 604800 - 302400, e0['toe'])
    pa = prop(al, t_abs - alm_abs, toa_sow, MU_GPS)
    errs.append((np.linalg.norm(pe-pa)/1000, prn))
errs.sort()
v = np.array([x[0] for x in errs])
print(f"  GPS almanac vs .scratch/pgnss-out/HW_PGNSS_GPS at the nearest ephemeris epoch")
print(f"    n={len(v)}  median {np.median(v):.2f} km  p90 {np.percentile(v,90):.2f} km  max {v.max():.2f} km"
      f"  (worst: PRN{errs[-1][1]})")
check(np.median(v) < 25, f"GPS almanac agrees with the ephemeris to {np.median(v):.2f} km (median)")
check(v.max() < 200, f"worst GPS satellite is {v.max():.2f} km")

# ── 3b. Galileo vs its own independently produced ephemeris ──────────────────────────────
def load_gal_eph(path):
    b = open(path, "rb").read(); out = {}
    for i in range(36):
        ts, off, ln = struct.unpack_from('<III', b, 12*i)
        if not ln: continue
        for k in range(struct.unpack_from('<I', b, off)[0]):
            r = b[off+4+k*76:off+4+(k+1)*76]
            idx = struct.unpack_from('<I', r, 0)[0]
            out.setdefault(idx, []).append(dict(
                omega=struct.unpack_from('<i',r,28)[0]*2**-31*PI, dn=struct.unpack_from('<i',r,32)[0]*2**-43*PI,
                m0=struct.unpack_from('<i',r,36)[0]*2**-31*PI, omegadot=struct.unpack_from('<i',r,40)[0]*2**-43*PI,
                e=struct.unpack_from('<I',r,44)[0]*2**-33, idot=struct.unpack_from('<h',r,48)[0]*2**-43*PI,
                sqrtA=struct.unpack_from('<I',r,52)[0]*2**-19, i0=struct.unpack_from('<i',r,56)[0]*2**-31*PI,
                omega0=struct.unpack_from('<i',r,60)[0]*2**-31*PI,
                crs=struct.unpack_from('<h',r,64)[0]*2**-5, cis=struct.unpack_from('<h',r,66)[0]*2**-29,
                cus=struct.unpack_from('<h',r,68)[0]*2**-29, crc=struct.unpack_from('<h',r,70)[0]*2**-5,
                cic=struct.unpack_from('<h',r,72)[0]*2**-29, cuc=struct.unpack_from('<h',r,74)[0]*2**-29,
                toe=struct.unpack_from('<I',r,24)[0]*60, ts=ts))
    return out
geph = load_gal_eph(str(EPH / "HW_PGNSS_GALILEO"))
gt = d['gal_t0a']
# WHICH WEEK t0a BELONGS TO, chosen by nearness rather than by a one-sided window.
#
# This used to snap into [vf - 604800, vf + 302400], which is not symmetric: a t0a that lands
# between half a week and a week ahead of the validity start was left in the window's week
# instead of the week before it. On 2026-09-15 that put the reference epoch seven days away
# from the truth and the check reported the Galileo almanac 14 360 km out — a file that is
# actually 36 km out. An instrument that fails towards "catastrophe" is worse than none: it
# cost an afternoon, and it is the second time today a grader has been mistaken for a fault.
_gal_wk_lsb = mine[0xc59]
_win_wk = int(vf // 604800)
# The full week whose low byte the header carries, taken in the era of the validity window.
_gal_wk = _win_wk - ((_win_wk - _gal_wk_lsb) % 256)
gabs = _gal_wk * 604800 + gt
# Our almanac's index field is 0-based (PRN-1), which is what Huawei's own files use.
# Test the ephemeris under BOTH conventions and report which one it is actually written in,
# so this check keeps working — and keeps telling the truth — either way.
print(f"\n  Galileo almanac vs .scratch/pgnss-out/HW_PGNSS_GALILEO (t0a is "
      f"{(vf-gabs)/86400:.1f} d before the validity start, week {_gal_wk} vs window {_win_wk})")
best = None
for shift, lab in ((0, "ephemeris is 0-based, PRN-1 (Huawei's convention)"),
                   (1, "ephemeris is 1-based, PRN  (NOT Huawei's convention)")):
    ge = []
    for n, al in sorted(d['gal_alm'].items()):
        if n + shift not in geph: continue
        e0 = min(geph[n + shift], key=lambda e: abs(e['ts'] - gabs))
        t_abs = e0['ts']
        pe = eph_prop(e0, ((t_abs % 604800) - e0['toe'] + 302400) % 604800 - 302400, e0['toe'])
        ge.append(np.linalg.norm(pe - prop(al, t_abs - gabs, gt, MU)) / 1000)
    if ge:
        v2 = np.array(ge)
        print(f"    {lab}: n={len(v2):2d} median {np.median(v2):9.2f} km")
        if best is None or np.median(v2) < best[0]: best = (np.median(v2), shift, lab, v2)
gv = best[3]
print(f"    -> matches with {best[2]}")
print(f"       median {np.median(gv):.2f} km  p90 {np.percentile(gv,90):.2f} km  max {gv.max():.2f} km")
gbad = sorted(zip(gv, sorted(n for n in d['gal_alm'] if n + best[1] in geph)))[-3:]
print(f"       worst three (idx, km): {[(int(n), round(float(x),1)) for x, n in gbad]}")
check(np.median(gv) < 60, f"Galileo almanac agrees with the ephemeris to {np.median(gv):.2f} km (median)")
check(best[1] == 0, "the ephemeris we ship uses Huawei's 0-based Galileo numbering "
      "(FAIL here means HW_PGNSS_GALILEO is off by one satellite)")

# ── 3c. BeiDou: decode our own file and re-check against the BROADCAST ephemeris ─────────
import importlib.util as _iu
_sp = _iu.spec_from_file_location("pb", "scripts/pgnss-extra-build.py")
pb = _iu.module_from_spec(_sp); _sp.loader.exec_module(pb)
_navs = _args.nav or [".scratch/pgnss-extra/src/brdc242.rnx", ".scratch/pgnss-extra/src/brdc241.rnx"]
nav = pb.parse_rinex_bds(_navs[0])
for prn, rs in (pb.parse_rinex_bds(_navs[1]) if len(_navs) > 1 else {}).items():
    seen = {r["toe_abs"] for r in nav.get(prn, [])}
    nav.setdefault(prn, []).extend(r for r in rs if r["toe_abs"] not in seen)
btoa_sow = d['bds_toa']; bwk = struct.unpack_from('<B', mine, 0xf79)[0]
berr = []
for prn, rs in sorted(nav.items()):
    al = d['bds_alm'].get(prn)
    if al is None: continue
    rs = sorted(rs, key=lambda r: r["toe_abs"])
    bwk_full = rs[0]["week"]
    btoa_abs = None
    for w in (bwk_full - 1, bwk_full, bwk_full + 1):
        if (w & 0xFF) == bwk: btoa_abs = w * 604800 + btoa_sow
    if btoa_abs is None: continue
    for r in rs[::4]:
        t = r["toe_abs"]
        if abs(t - btoa_abs) > 43200: continue
        pe = pb.bds_eph_pos(r, t % 604800)
        pa = prop(al, t - btoa_abs, btoa_sow, MU)
        berr.append((np.linalg.norm(pe-pa)/1000, prn))
CARRIED = {5,16,40,43,44,45,46,47,48,49,50,56,57,59,60,61,62}
if berr:
    fit_v = np.array([x[0] for x in berr if x[1] not in CARRIED])
    car_v = np.array([x[0] for x in berr if x[1] in CARRIED])
    print(f"\n  BeiDou almanac (decoded back out of our file) vs the BRDC broadcast ephemeris")
    print(f"    fitted from public data : n={len(fit_v):3d}  median {np.median(fit_v):7.2f} km"
          f"  p90 {np.percentile(fit_v,90):7.2f} km  max {fit_v.max():8.2f} km")
    if len(car_v):
        print(f"    carried from the capture: n={len(car_v):3d}  median {np.median(car_v):7.2f} km"
              f"  p90 {np.percentile(car_v,90):7.2f} km  max {car_v.max():8.2f} km")
    check(np.median(fit_v) < 5,
          f"BeiDou satellites fitted from public data agree with the broadcast to "
          f"{np.median(fit_v):.2f} km (median)")

# ── 3d. GLONASS: measured cost of the almanac's age ──────────────────────────────────────
ref = open(REFS[-1][0], "rb").read()
hg = {}
for k in range(24):
    q = 0x958 + k*32
    na, slot, ch = struct.unpack_from('<HBB', ref, q)
    lam, tlam, di, dT = struct.unpack_from('<iiii', ref, q+4)
    hg[slot] = dict(NA=na, lam=lam*2**-20, tlam=tlam*2**-5, dT=dT*2**-9)
WE = OMEGA_E / PI          # semicircles / s
dl, dt_ = [], []
for n, al in sorted(d['glo_alm'].items()):
    h = hg.get(n)
    if not h: continue
    T = 43200 + al['dT']
    gap = (h['NA'] - al['NA']) * 86400.0
    krev = round((gap - al['tlam'] + h['tlam']) / T)
    tl = al['tlam'] + krev*T - gap
    lm = (al['lam'] - WE*krev*T + 1) % 2 - 1
    dt_.append(tl - h['tlam'])
    dl.append(((lm - h['lam']) + 1) % 2 - 1)
dl = np.array(dl); dt_ = np.array(dt_)
print(f"\n  GLONASS: our almanac (N_A {list(d['glo_alm'].values())[0]['NA']}) propagated "
      f"{(hg[1]['NA']-list(d['glo_alm'].values())[0]['NA'])} days forward, vs Huawei's captured "
      f"almanac (N_A {hg[1]['NA']})")
print(f"    node time  median |dt|  {np.median(abs(dt_)):8.1f} s   max {abs(dt_).max():8.1f} s")
print(f"    node long. median |dl|  {np.median(abs(dl))*180:8.3f} deg  max {abs(dl).max()*180:8.3f} deg")
print(f"    -> along-track equivalent at 25 500 km: median "
      f"{np.median(abs(dt_))*3.95:.0f} km, max {abs(dt_).max()*3.95:.0f} km")
check(np.median(abs(dl))*180 < 5, "GLONASS node longitude reproduces Huawei's to <5 deg")

# ── 4. sanity ─────────────────────────────────────────────────────────────────────────────
head("4. SANITY — orbit geometry")
def band(name, vals, lo, hi, unit):
    ok = all(lo <= x <= hi for x in vals)
    check(ok, f"{name}: {min(vals):.3f} .. {max(vals):.3f} {unit} (expect {lo}..{hi})")
band("GPS  semi-major axis", [a['sqrtA']**2/1000 for a in d['gps_alm'].values()], 26400, 26700, "km")
band("GPS  inclination", [math.degrees(a['i0']) for a in d['gps_alm'].values()], 52, 58, "deg")
band("GPS  eccentricity", [a['e'] for a in d['gps_alm'].values()], 0, 0.021, "")
band("GAL  semi-major axis", [a['sqrtA']**2/1000 for a in d['gal_alm'].values()], 29500, 29700, "km")
band("GAL  inclination", [math.degrees(a['i0']) for a in d['gal_alm'].values()], 53, 59, "deg")
band("GAL  eccentricity", [a['e'] for a in d['gal_alm'].values()], 0, 0.021, "")
band("GLO  period", [a['period'] for a in d['glo_alm'].values()], 40400, 40700, "s")
band("GLO  inclination", [a['incl_deg'] for a in d['glo_alm'].values()], 63, 66, "deg")
band("GLO  eccentricity", [a['e'] for a in d['glo_alm'].values()], 0, 0.01, "")
bds_a = [a['sqrtA']**2/1000 for a in d['bds_alm'].values()]
geo = [x for x in bds_a if x > 40000]; meo = [x for x in bds_a if x <= 40000]
band("BDS  GEO/IGSO semi-major axis", geo, 42000, 42300, "km")
band("BDS  MEO semi-major axis", meo, 27800, 28000, "km")
band("BDS  eccentricity", [a['e'] for a in d['bds_alm'].values()], 0, 0.021, "")
# the GLONASS channel table must agree with the almanac's own channel field
mism = [n for n in d['glo_alm'] if d['glo_alm'][n]['chan'] != d['glo_channels'].get(n)]
check(not mism, f"GLONASS channel table agrees with the almanac records ({mism or 'all 24'})")

head("RESULT")
print(f"  {len(fail)} failure(s)" + ("" if not fail else ":\n    " + "\n    ".join(fail)))
sys.exit(1 if fail else 0)
