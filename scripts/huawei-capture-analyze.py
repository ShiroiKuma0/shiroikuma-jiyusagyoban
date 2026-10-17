#!/usr/bin/env python3
"""Decrypt one capture copy and show what happened between two moments — the live half of a session.

    huawei-capture-analyze.py <copy.log> [--from <unix s>] [--to <unix s>] [--utc-offset <s>] [--dump]

Run by `huawei-capture-session.py mark` after every step, on the copy it just took, with the window
since the previous mark; also usable by hand. It prints, per (direction, service, command), how many
frames the step caused and how many bytes, and whether they decrypted — so a step that produced
nothing, or produced frames we cannot read, is visible the moment it happens, not days later.
`--dump` also prints each decrypted frame's TLVs.

The keys come from the pairing inside the same log (see docs/huawei-band-pairing.md §12), so a copy
that no longer holds the handshake decrypts nothing; the summary says so.

The phone writes btsnoop timestamps in its LOCAL time (the Samsung, 2026-10-17: CEST, frames 7200 s
"ahead" of UTC), so `--utc-offset` is the phone's offset east of UTC in seconds (`date +%z`); it is
taken off every frame before the window is applied.

Run with the analysis venv: `.scratch/hw/venv/bin/python` (it has `cryptography`).
"""
import collections
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / ".scratch/hw3"))
sys.path.insert(0, str(ROOT / ".scratch/hw"))
sys.path.insert(0, str(ROOT / ".scratch/hw/_stub"))

import census  # noqa: E402  (path set above)
from phase0 import tlv_parse  # noqa: E402

# btsnoop timestamps are microseconds since 0000-01-01; this is 1970-01-01 on that scale.
BTSNOOP_UNIX_EPOCH_US = 0x00DCDDB30F2F8000


def unix(ts):
    return (ts - BTSNOOP_UNIX_EPOCH_US) / 1e6


def arg(name, default=None):
    if name in sys.argv:
        return float(sys.argv[sys.argv.index(name) + 1])
    return default


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    lo, hi = arg("--from", float("-inf")), arg("--to", float("inf"))
    offset = arg("--utc-offset", 0.0)
    dump = "--dump" in sys.argv

    seq, rec = census.load(path)
    rows = collections.OrderedDict()
    undecrypted = 0
    shown = 0
    for ts, d, svc, cmd, body in seq:
        t = unix(ts) - offset
        if not (lo < t <= hi):
            continue
        pt, was_enc = census.decrypt(body, rec)
        key = ("phone→dev" if d else "dev→phone", svc, cmd)
        r = rows.setdefault(key, {"n": 0, "bytes": 0, "bad": 0})
        r["n"] += 1
        r["bytes"] += len(body)
        if pt is None:
            r["bad"] += 1
            undecrypted += 1
        shown += 1
        if dump and pt is not None:
            try:
                tl = tlv_parse(pt)
                body_txt = " ".join(f"{t_}:{v.hex()[:64]}" for t_, v in tl)
            except Exception:
                body_txt = pt.hex()[:128]
            print(f"  {t:.1f}  {key[0]}  0x{svc:02X}/0x{cmd:02X}  {body_txt}")

    print(f"keys recovered: {len(rec.keys)} · frames in window: {shown} · undecryptable: {undecrypted}")
    if shown and len(rec.keys) == 0:
        print("!! no keys — this copy does not hold the pairing handshake (rotated away?)")
    for (direction, svc, cmd), r in rows.items():
        bad = f"  ({r['bad']} undecryptable)" if r["bad"] else ""
        print(f"  {direction}  0x{svc:02X}/0x{cmd:02X}  ×{r['n']:<4} {r['bytes']:>8} B{bad}")


if __name__ == "__main__":
    main()
