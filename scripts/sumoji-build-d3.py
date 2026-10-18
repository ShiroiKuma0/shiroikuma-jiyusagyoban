#!/usr/bin/env python3
"""Build 相撲字時計 for the HUAWEI WATCH D3 — layout C (白い熊, 2026-10-17).

The D3's screen is 408 x 480: the Band's height (482) and 43 % more width. Every line of the Band
face is limited by its WIDTH, so the extra width only turns into bigger glyphs where a line also
gets more height. Layout C keeps all five lines and gives the seconds' and the footer's height to
the hour and the minutes:

    line      Band 286 x 482        D3 408 x 480
    hour      146 high, 143 cells   163 high, 204 cells
    minutes    69                    87
    seconds    52                    47
    date       46, 40 per char       53, 58 per char
    gaps       14                     4   (the tiles' own margins keep ~10 px between the inks)
    footer     46                    46

Everything else is the Band build, unchanged and imported: the tiles, the hour's four-tile cell one
and its mark, 半 and 丁度, the fixed minute columns, the date's overrides. Its module constants are
re-set before anything is drawn, and its functions read them at call time.

What the D3 needs on top:

* **The skeleton is a Band face** (MZ DIGICOLOR), laid out for 286 px. Its top row (battery,
  weather), its always-on copy of the clock and its date layer are moved right by DX = 61, which
  centres them on the wider screen. The lines this face draws itself span the full 408.
* **The bezel offset is smaller.** The Band hides its first ~30 px behind a curved bezel, so its
  build moves the top row down 24 px; the D3's screen is flat, so 8 px.
* **The hour mark is re-derived, not re-searched.** On the Band its size and place were searched
  so that its knockout crosses 二's ink only where the 〇's ring is solid (sumoji-build.py's hour
  section). Every glyph here is the Band's glyph scaled by one factor, so the mark is scaled by the
  same factor about the glyph's own corner; the build then MEASURES the rule rather than trusting
  the scaling, and refuses to write a face that breaks it.
* **The picker thumbnail** (resource 001) is the face itself, drawn by the preview, shrunk into the
  thumbnail's 172 x 290 so the directory entry keeps its size.

The install needs nothing D3-specific: our app asks the device for its screen size before every
install (0x27/0x01) and announces whatever it answers.

    python3 scripts/sumoji-build-d3.py <unpacked MZ DIGICOLOR> <out-dir>
    python3 scripts/huawei-face-tool.py pack <out-dir>/work <pack-dir> --name '相撲字時計 D3' --short SUMOD3
"""
import importlib.util
import pathlib
import shutil
import struct
import sys

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


B = _load("sumoji_build", "sumoji-build.py")

# The Band's geometry, kept for the mark's scaling before it is overwritten.
BAND_CELL_W, BAND_HOUR_H = B.CELL_W, B.HOUR_H
BAND_MARK_W, BAND_MARK_XY = B.HOUR_MARK_W, B.HOUR_MARK_XY

W, H = 408, 480
DX = (W - 286) // 2               # 61: the Band skeleton's own elements, centred

# ── layout C ──────────────────────────────────────────────────────────────────────────────────────
B.DY = 8
# 4, not the Band's 14: every tile carries ~3 px of margin inside it, so the INK still has ~10 px
# between lines, and the 18 px this frees are what bring the seconds and the date back to size.
B.GAP = 4
B.HOUR_Y, B.HOUR_H = 64, 163
B.CELL_W = W // 2
B.CELL1_X, B.CELL2_X = 0, W // 2
B.MIN_Y, B.MIN_H = B.HOUR_Y + B.HOUR_H + B.GAP, 87
B.SEC_Y, B.SEC_H = B.MIN_Y + B.MIN_H + B.GAP, 47
B.DATE_Y, B.DATE_H = B.SEC_Y + B.SEC_H + B.GAP, 53
B.DATE_U = W // 7
B.DATE_X = (W - B.DATE_U * 7) // 2
B.MONTH_X, B.MONTH_W = B.DATE_X, B.DATE_U * 3
B.DAY1_X = B.MONTH_X + B.MONTH_W
B.DAY2_X = B.DAY1_X + B.DATE_U * 2
B.DAY_W = B.DATE_U * 2
B.BOTTOM_Y, B.LINE_H = B.DATE_Y + B.DATE_H + B.GAP, 46
B.WDAY_W = 46
# The steps count is centred in its rect and grows outwards; five digits of the largest font are
# about 140 px, so from a centre of 153 they reach x 223 — the weekday stands clear of that.
B.STEPS_W = 300
B.WDAY_X, B.WDAY_Y = 250, B.BOTTOM_Y
assert B.BOTTOM_Y + B.LINE_H <= H, "the footer runs off the screen"


def _glyph_box(w, h):
    """Where render() puts one centred hour glyph in a w x h cell: (x0, y0, scale)."""
    cells = [B.glyph(ch, 300) for ch in "〇"]
    cw, chh = max(c.width for c in cells), max(c.height for c in cells)
    k = min((w - 0) / 1 / cw, (h - 6) / chh)
    return (w - int(cw * k)) // 2, (h - int(chh * k)) // 2, k


bx, by, bk = _glyph_box(BAND_CELL_W, BAND_HOUR_H)
dx_, dy_, dk = _glyph_box(B.CELL_W, B.HOUR_H)
s = dk / bk
B.HOUR_MARK_W = round(BAND_MARK_W * s)
# The Band's mark sits 4 px ABOVE its glyph box (y 0 against a box at y 4); scaled, that lands at
# y -2 here, off the tile, so it is held at the tile's top edge — 2 px lower relative to the glyph.
B.HOUR_MARK_XY = (round(dx_ + (BAND_MARK_XY[0] - bx) * s), max(0, round(dy_ + (BAND_MARK_XY[1] - by) * s)))

# The appended elements were computed from the Band constants when the module loaded.
B.NEW = [
    ("hmark",  B.HOUR_TENS,  (B.CELL1_X + B.HOUR_MARK_XY[0], B.HOUR_Y + B.HOUR_MARK_XY[1]), 3),
    ("h1_top", B.HOUR_UNITS, (B.CELL1_X, B.HOUR_Y), 10),
    ("m2b_units", B.MIN_UNITS, (B.CELL2_X, B.MIN_Y), 10),
    ("s1_units", B.SEC_UNITS, (B.CELL1_X, B.SEC_Y), 10),
    ("s1_tens",  B.SEC_TENS,  (B.CELL1_X, B.SEC_Y), 6),
    ("s2_units", B.SEC_UNITS, (B.CELL2_X, B.SEC_Y), 10),
    ("s2_tens",  B.SEC_TENS,  (B.CELL2_X, B.SEC_Y), 6),
    ("month",    B.MONTH,     (B.MONTH_X, B.DATE_Y), 13),
    ("d1_units", B.DAY_UNITS, (B.DAY1_X, B.DATE_Y), 10),
    ("d1_tens",  B.DAY_TENS,  (B.DAY1_X, B.DATE_Y), 4),
    ("d2_units", B.DAY_UNITS, (B.DAY2_X, B.DATE_Y), 10),
    ("d2_tens",  B.DAY_TENS,  (B.DAY2_X, B.DATE_Y), 4),
    ("wday",     B.WEEKDAY,   (B.WDAY_X, B.WDAY_Y), 8),
]
B.PLAN = {
    8:  ("h1_units", B.HOUR_UNITS, (B.CELL1_X, B.HOUR_Y), 10),
    9:  ("h1_tens",  B.HOUR_TENS,  (B.CELL1_X, B.HOUR_Y), 3),
    10: ("h2_units", B.HOUR_UNITS, (B.CELL2_X, B.HOUR_Y), 10),
    11: ("h2_tens",  B.HOUR_TENS,  (B.CELL2_X, B.HOUR_Y), 3),
    0:  ("m1a_tens",  B.MIN_TENS,  (B.CELL1_X, B.MIN_Y), 6),
    1:  ("m1b_units", B.MIN_UNITS, (B.CELL1_X, B.MIN_Y), 10),
    2:  ("m1c_tens",  B.MIN_TENS,  (B.CELL1_X, B.MIN_Y), 6),
    3:  ("m2a_tens",  B.MIN_TENS,  (B.CELL2_X, B.MIN_Y), 6),
}


def check_mark(g):
    """The Band's rule, measured: at two o'clock the knockout must not eat into 二.

    The knockout tile (h1_top_2) is drawn over 二 at two and over the 〇 at twelve. Wherever it has
    alpha on 二's ink, two o'clock loses ink. Returns the number of 二 pixels it darkens by more
    than a rounding step, which must be zero.
    """
    knock = np.array(g["h1_top_2"])[:, :, 3].astype(int)
    two = np.array(g["h1_units_2"])[:, :, 1].astype(int)       # yellow's green channel = ink
    return int(((knock > 2) & (two > 8)).sum())


def shift_x(layout: bytes, dx: int) -> bytes:
    """Move the Band skeleton's own elements right by dx: the clock layer's always-on copy, the
    date layer, and every widget with what is inside it. The elements this face redraws (PLAN) are
    repositioned later by rewrite() and are skipped here."""
    P, Bd = B.parse, B.build

    def mv(v, fields):
        inner = P(v)
        if not inner or not all(isinstance(x, int) for _, _, x in inner):
            return v
        return Bd([(a, b_, x + dx if a in fields else x) for a, b_, x in inner])

    out = []
    for f, w, v in P(layout):
        if f != 1:
            out.append((f, w, v)); continue
        layer = P(v)
        kind = next((x for a, _, x in layer if a == 1), None)
        new = []
        for lf, lw, lv in layer:
            if kind in (1, 2) and lf == 5:                    # clock / date element
                elem = P(lv)
                idx = next((x for a, _, x in elem if a == 1), None)
                if kind == 1 and idx in B.PLAN:
                    new.append((lf, lw, lv)); continue
                ne = []
                for a, b_, x in elem:
                    if a == 5 and isinstance(x, bytes):
                        x = Bd([(c, d_, mv(y, {1}) if c == 2 and isinstance(y, bytes) else y)
                                for c, d_, y in P(x)])
                    ne.append((a, b_, x))
                lv = Bd(ne)
            elif kind == 3 and lf == 8:                        # widget
                ne = []
                for a, b_, x in P(lv):
                    if a == 5 and isinstance(x, bytes):
                        x = mv(x, {1})
                    elif a == 6 and isinstance(x, bytes):
                        sub = P(x)
                        et = next((y for c, _, y in sub if c == 2), None)
                        pf = B.payload_of(sub)
                        ns = []
                        for c, d_, y in sub:
                            if c == pf and isinstance(y, bytes):
                                body = []
                                for e, g_, z in P(y):
                                    if isinstance(z, bytes) and ((et == 2 and e == 2) or (et != 2 and e == 1)):
                                        z = mv(z, {1})
                                    body.append((e, g_, z))
                                y = Bd(body)
                            ns.append((c, d_, y))
                        x = Bd(ns)
                    ne.append((a, b_, x))
                lv = Bd(ne)
            new.append((lf, lw, lv))
        out.append((f, w, Bd(new)))
    return Bd(out)


def thumbnail(g, size):
    """The face itself, from the preview, fitted into the picker's resource."""
    Pv = _load("sumoji_preview", "sumoji-preview.py")
    Pv.B, Pv.W, Pv.H = B, W, H
    face = Pv.panel(g, Pv.LIB_WHEN, 76, 1220)
    tw, th = size
    k = min(tw / W, th / H)
    small = face.resize((round(W * k), round(H * k)), Image.LANCZOS)
    out = Image.new("RGBA", size, (0, 0, 0, 255))
    out.alpha_composite(small, ((tw - small.width) // 2, (th - small.height) // 2))
    return out


def main(src: pathlib.Path, out: pathlib.Path):
    g = B.glyphs()
    bad = check_mark(g)
    print(f"  hour mark {B.HOUR_MARK_W} px at {B.HOUR_MARK_XY} (Band {BAND_MARK_W} at {BAND_MARK_XY}, x{s:.3f}); "
          f"二 pixels eaten at two o'clock: {bad}")
    if bad:
        raise SystemExit("the mark's knockout crosses 二's ink — shrink or move the mark")

    data = (src / "face.bin").read_bytes()
    ver, l1, l2, l3, spare = struct.unpack_from("<HHIII", data, 0)
    shifted = shift_x(data[16:16 + l1], DX)
    tmp = out / "src-shifted"
    if tmp.exists():
        shutil.rmtree(tmp)
    shutil.copytree(src, tmp)
    (tmp / "face.bin").write_bytes(struct.pack("<HHIII", ver, len(shifted), l2, l3, spare)
                                   + shifted + data[16 + l1:])
    (tmp / "layout.protobuf").write_bytes(shifted)
    B.main(tmp, out)

    thumb = next((out / "work" / "images").glob("001_*.png"))
    w_, h_ = Image.open(thumb).size
    thumbnail(g, (w_, h_)).save(thumb)
    print(f"  thumbnail {thumb.name} redrawn from the face")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
