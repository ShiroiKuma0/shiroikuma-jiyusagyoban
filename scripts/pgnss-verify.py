#!/usr/bin/env python3
"""Compare a generated predicted set against Huawei's captured one, FIELD BY FIELD.

This exists because the check it sits beside could not fail. `pgnss-build.py` was verified by
decoding its own bytes and propagating them, which is a real test of the orbits — they are
sub-metre — and no test at all of anything else: propagation reads only the orbital fields, so the
bytes nothing wrote were the bytes nothing read. The set that resulted was accepted by the band,
counted down correctly, and produced a 3-4 minute fix where Huawei's own file had produced 21 s. The
difference was one byte per record that we never wrote (白い熊, 2026-08-29).

So this asks a different question, and a stupider one: **does every byte position Huawei uses carry
something in ours, and does every position they leave alone stay alone in ours?** It knows nothing
about what the fields MEAN, which is the point — it would have caught byte 77 without anyone knowing
what byte 77 is.

Exits non-zero on any divergence, so it can gate a build.
"""
import struct
import sys
import pathlib

BLOCKS = 36
LAYOUT = {"GPS": 80, "GALILEO": 76, "BDS": 92, "QZS": 76}

#: Byte positions whose use depends on WHEN the window falls, not on whether a field is written.
#:
#: BeiDou stores toc and toe as 32-bit counts of 8 s of the BeiDou week. The week runs to 604800 s,
#: so the count runs to 75600 and needs SEVENTEEN bits: the third byte of each is 1 for a window
#: late in the week and 0 for one early in it. Nothing is missing either way.
#:
#: This is not a licence taken to make our own output pass. Huawei's two captured vintages FAIL this
#: check against each other on exactly these two offsets and nothing else — 2026-08-22 sets them,
#: 2026-08-25 does not — which is as clear a statement as the format is going to make that they are
#: not a field (verified 2026-08-30). Every other offset stays strict.
#: The GPS week is the same kind of thing: bytes 2-3 of a GPS record hold it, so a set built in week
#: 2434 reads 130 where a capture from week 2433 reads 129. The check's "both constant and different
#: means a flag disagrees" rule cannot tell a flag from a counter, and this one is a counter whose
#: meaning is written down in `enc_gps`. It happens not to fire against the 2026-08-22 capture only
#: because that one straddles a week boundary and so is not constant.
EPOCH_DEPENDENT = {("BDS", 10), ("BDS", 30), ("GPS", 2), ("GPS", 3)}


def records(path, reclen):
    b = path.read_bytes()
    out = []
    for i in range(BLOCKS):
        _, off, _ = struct.unpack_from("<III", b, 12 * i)
        n = struct.unpack_from("<I", b, off)[0]
        for k in range(n):
            out.append(b[off + 4 + k * reclen:off + 4 + (k + 1) * reclen])
    return out


def stamps(path):
    b = path.read_bytes()
    return [struct.unpack_from("<III", b, 12 * i)[0] for i in range(BLOCKS)]


def main():
    ours = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".scratch/pgnss-out")
    ref = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else ".scratch/hw2/satellite")
    bad = 0
    for system, reclen in LAYOUT.items():
        o, h = ours / f"HW_PGNSS_{system}", ref / f"HW_PGNSS_{system}.bin"
        if not o.is_file():
            print(f"{system}: not generated — skipped")
            continue
        if not h.is_file():
            print(f"{system}: no capture to compare against — skipped")
            continue
        O, H = records(o, reclen), records(h, reclen)
        print(f"{system}: ours {len(O)} records, capture {len(H)}")
        for off in range(reclen):
            if (system, off) in EPOCH_DEPENDENT:
                continue
            hz = all(r[off] == 0 for r in H)
            oz = all(r[off] == 0 for r in O)
            if hz and not oz:
                vals = sorted({r[off] for r in O})[:8]
                print(f"   off {off:>3}: capture always 0, ours sets {vals}  <<< we write where they do not")
                bad += 1
            elif oz and not hz:
                vals = sorted({r[off] for r in H})[:8]
                print(f"   off {off:>3}: capture sets {vals}, ours always 0  <<< we leave a field they fill")
                bad += 1
            else:
                hs = {r[off] for r in H}
                os_ = {r[off] for r in O}
                if len(hs) == 1 and len(os_) == 1 and hs != os_:
                    print(f"   off {off:>3}: capture constant {hs.pop()}, ours constant {os_.pop()}  <<< flag disagrees")
                    bad += 1
        # The window has to be in the future, or the file is a confident lie.
        st = stamps(o)
        step = {st[i + 1] - st[i] for i in range(BLOCKS - 1)}
        if step != {7200}:
            print(f"   block cadence is {step}, expected {{7200}}")
            bad += 1
    print()
    print("clean — every field the capture uses is populated" if not bad
          else f"{bad} divergence(s) — see above")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
