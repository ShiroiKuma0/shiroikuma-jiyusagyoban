#!/usr/bin/env python3
"""Match values Huawei Health displays against the ten fields stored in rrisqi_data.bin.

    python3 scripts/rri-match.py <rrisqi.bin> "15:10=35" "16:02=41" ...

Eight of the ten fields are unnamed, and no amount of further data will name one: what is missing is
ground truth — a number Huawei Health DISPLAYS, with the time it belongs to. This is the tool that
turns such a capture into an answer, so that the capture is the only manual step.

Validated against the one datum already on record: Health showed 35 ms of HRV at 15:10 on
2026-08-22, and this reports that no stored field carries it — which is exactly the conclusion
docs/huawei-band-pairing.md reached by hand, so the tool agrees with the evidence that already
exists before being trusted with evidence that does not.

Pull a fresh file with the 健康 task バンドRR書類（Huawei） -- [727]; it lands in /sdcard/tmp.

Each pair is a local HH:MM(:SS) and the number Health showed for that window. Every field is scored
against every pair; a field survives only if it fits ALL of them, because one coincidence is what
already sank two candidates when there were three data points.
"""
import struct, sys, datetime, statistics as st
HEADER, RECORD, FIRST_FLOAT, FIELDS = 0x30, 0x42, 19, 10
PLAUSIBLE = range(1_600_000_000, 2_500_000_000)
def parse(b):
    out, off = [], HEADER
    while off + RECORD <= len(b):
        r = b[off:off+RECORD]; off += RECORD
        s, e = struct.unpack_from(">I", r, 1)[0], struct.unpack_from(">I", r, 5)[0]
        if s not in PLAUSIBLE or e not in PLAUSIBLE or e < s: break
        out.append({"s": s, "f": {i+1: struct.unpack_from(">f", r, FIRST_FLOAT+i*4)[0] for i in range(FIELDS)}})
    return out

w = parse(open(sys.argv[1], "rb").read())
pairs = []
for arg in sys.argv[2:]:
    t, _, v = arg.partition("=")
    parts = [int(p) for p in t.strip().split(":")]
    while len(parts) < 3: parts.append(0)
    pairs.append((parts, float(v)))
if not pairs:
    sys.exit("give at least one HH:MM=value pair")

TOL = 0.06  # 6 %, the tolerance the earlier exhaustive search used
survivors = {i: [] for i in range(1, FIELDS+1)}
for parts, want in pairs:
    # the window whose start is nearest that clock time, on any day in the file
    def dist(x):
        d = datetime.datetime.fromtimestamp(x["s"])
        return abs((d.hour*3600 + d.minute*60 + d.second) - (parts[0]*3600 + parts[1]*60 + parts[2]))
    hit = min(w, key=dist)
    when = datetime.datetime.fromtimestamp(hit["s"]).strftime("%Y-%m-%d %H:%M:%S")
    off_by = dist(hit)
    print(f"{parts[0]:02d}:{parts[1]:02d}:{parts[2]:02d} = {want}  -> window {when} "
          f"({off_by}s away, HR {60000/hit['f'][6]:.0f}, count {hit['f'][1]:.0f})")
    if off_by > 300:
        print("   WARNING: nearest window is more than 5 min away — check the time")
    for i in range(1, FIELDS+1):
        got = hit["f"][i]
        survivors[i].append(abs(got - want) <= TOL * max(abs(want), 1e-9))
        print(f"      f{i:<2} {got:>10.2f}  {'FITS' if survivors[i][-1] else ''}")

print("\n== fields fitting EVERY pair ==")
won = [i for i, ok in survivors.items() if all(ok)]
print("  " + (", ".join(f"f{i}" for i in won) if won else "none — no stored field is what Health displays"))
if len(pairs) < 3:
    print("  (with fewer than three pairs a fit is not yet evidence; one coincidence already"
          "\n   sank two candidates when there were three points)")
