#!/usr/bin/env python3
"""Reading `sequence_data` 700021 — the band's per-beat RR series.

One reader, imported by every script that needs it. It used to be copied into each of them, which is
how a fix to one left the other reporting different totals for the same file.

LAYOUT
    file header   9 bytes: 00, size as BE uint32, stream id as BE uint32 (0x000AAE75 = 700021)
    record        44-byte header: start/end epoch as BE uint32 at +0/+4, an LE echo of the start
                  at +0x24
    beats         (uint16 LE interval in ms, uint16 LE quality), repeated to the next header

THE PAGE STAMP
The band writes a page index into every 976th byte of this file: that byte is the page's own number,
not data (see HuaweiPagedFile.kt for the whole story). Measured 2026-08-27 on a 97789-byte capture:
100 stamps, 88 in a quality field's spare high byte and the rest in record headers. Two took the low
byte of a header's big-endian start, which left the record unfindable and handed its beats to the
one before it — that, not the band, was the "263 of 266" this tooling used to report. With the
stamps understood it reads 267 records, 266 of them self-consistent, and the quality column comes
back as exactly {100, 50, 0} with no impossible value left in it.

Headers are therefore found by the ECHO, which is the one copy of the start a stamp cannot also have
taken (they are 36 bytes apart, stamps are 976), and they must sit on the file's 4-byte grid: a
record header is 44 bytes and a beat is 4, so every header shares one residue. Without that grid
test the relaxed scan admits a mid-record offset whose echo happens to look like an epoch — one did,
at 82958, and its "beats" were a different structure read four bytes out of phase.

NO STAMP HAS EVER LANDED IN AN INTERVAL, and that is structural rather than lucky: beats sit at
offsets congruent to 1 (mod 4) and stamps at 0, so each one falls on the same byte of the same field
every time. The intervals every metric here is computed from were never touched.
"""
import struct

PAGE = 976
PLAUS = range(1_780_000_000, 1_800_000_000)


def stamp_in(b, off, n):
    """Where a page stamp falls inside b[off:off+n], or -1.

    Verified, never assumed: at a real page boundary the byte IS the index, so a site that does not
    match is one the stamp never reached — a file transferred in more than one window loses the
    alignment partway through, and repairing an unstamped byte would be inventing damage.
    """
    nxt = -(-off // PAGE) * PAGE
    if nxt >= off + n or nxt >= len(b):
        return -1
    return nxt - off if b[nxt] == (nxt // PAGE) & 0xFF else -1


def beats(path):
    """Every record in the file: {"s", "e", "beats": [(interval_ms, quality), ...]}.

    Returns the records and a dict of what the page stamps cost, so a caller can report it.
    """
    b = open(path, "rb").read()
    heads = []
    for o in range(0x21, len(b) - 0x2C):
        echo = struct.unpack_from("<I", b, o + 0x24)[0]
        declared = struct.unpack_from(">I", b, o)[0]
        if echo in PLAUS:
            # The echo is the authority: a start that disagrees with it was stamped.
            if declared != echo and stamp_in(b, o, 4) < 0:
                continue                    # not a header, just a plausible-looking number
            heads.append((o, echo, declared != echo))
        elif declared in PLAUS and stamp_in(b, o + 0x24, 4) >= 0:
            # The stamp took the echo instead; the declared start is then the intact copy.
            heads.append((o, declared, False))
    if not heads:
        return [], {"headers": 0, "beats": 0}

    grid = next((h[0] % 4 for h in heads if not h[2]), heads[0][0] % 4)
    heads = [h for h in heads if h[0] % 4 == grid]
    keep = [heads[0]]
    for h in heads[1:]:
        if h[0] - keep[-1][0] >= 0x2C:
            keep.append(h)

    out, mended_heads, mended_beats = [], 0, 0
    for i, (h, s, was_stamped) in enumerate(keep):
        e = struct.unpack_from(">I", b, h + 4)[0]
        stop = keep[i + 1][0] if i + 1 < len(keep) else len(b)
        if was_stamped:
            mended_heads += 1
        arr = []
        for p in range(h + 0x2C, stop - 3, 4):
            iv, q = struct.unpack_from("<HH", b, p)
            st = stamp_in(b, p, 4)
            if st in (0, 1):
                mended_beats += 1           # the interval itself is gone; the beat cannot be used
                continue
            if st in (2, 3):
                q = b[p + 2]                # quality is 0/50/100, so its high byte is spare
                mended_beats += 1
            arr.append((iv, q))
        out.append({"s": s, "e": e, "beats": arr})
    return out, {"headers": mended_heads, "beats": mended_beats}
