#!/usr/bin/env python3
"""The seven weekday kanji in every sumo font on disk, one row each, for choosing one.

Each row is measured in its OWN shared frame — the same treatment the face gives its glyphs, taken
from the pen origin and the baseline across all seven characters — so the comparison is between
typefaces rather than between accidents of how each character happens to be cropped.

Some of these are legacy Japanese fonts whose cmap is Shift-JIS only, with no Unicode table at all;
a character is looked up through cp932 for those, or the row would come out blank.
"""
import datetime
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

WDAY = "月火水木金土日"
DIR = pathlib.Path.home() / "〇/[06] 蔵書/[06][821] フォント/[06][821][03] 相撲フォント"
SIZE, CELL, PAD, LABEL = 150, 96, 10, 150
INK = (255, 255, 0)


def draw(f, ch, org):
    """One character on its own canvas, or None if the font drew nothing for it."""
    c = Image.new("L", (SIZE * 4, SIZE * 4), 0)
    ImageDraw.Draw(c).text((org, org), ch, fill=255, font=f, anchor="ls")
    return c if c.getbbox() else None


def strip(f, ch, org):
    """Try Unicode, then the cp932 detour.

    Coverage is decided by RENDERING rather than by reading the cmap. Two of these fonts have a
    cmap fontTools refuses outright — one is a format 12 table it calls corrupt — and FreeType
    draws from them perfectly well, so asking the file is both stricter and less informative than
    asking for the ink.
    """
    got = draw(f, ch, org)
    if got is not None:
        return got
    try:
        return draw(f, chr(int.from_bytes(ch.encode("cp932"), "big")), org)
    except UnicodeEncodeError:
        return None


def row(path):
    """Seven glyphs in one shared frame, or None if the font cannot draw them."""
    try:
        f = ImageFont.truetype(str(path), SIZE)
    except OSError:
        return None
    org = SIZE * 2
    blank = Image.new("L", (SIZE * 4, SIZE * 4), 0)
    strips, box = [], None
    for ch in WDAY:
        canvas = strip(f, ch, org)
        strips.append(canvas or blank)
        bb = canvas.getbbox() if canvas else None
        if bb:
            box = bb if box is None else (min(box[0], bb[0]), min(box[1], bb[1]),
                                          max(box[2], bb[2]), max(box[3], bb[3]))
    if box is None:
        return None
    w, h = box[2] - box[0], box[3] - box[1]
    k = min((CELL - 6) / w, (CELL - 6) / h)
    out = Image.new("RGBA", (CELL * len(WDAY), CELL), (0, 0, 0, 0))
    for i, canvas in enumerate(strips):
        g = canvas.crop(box).resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
        tile = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
        tile.paste(Image.new("RGBA", g.size, INK + (255,)),
                   ((CELL - g.width) // 2, (CELL - g.height) // 2), g)
        out.alpha_composite(tile, (i * CELL, 0))
    return out


def sheet(out_path):
    fonts = sorted(p for p in DIR.iterdir() if p.suffix.lower() in (".otf", ".ttf", ".ttc"))
    rows = [(p, row(p)) for p in fonts]
    rows = [(p, r) for p, r in rows if r is not None]
    W = LABEL + CELL * len(WDAY) + PAD * 2
    H = PAD + sum(CELL + PAD for _ in rows)
    sh = Image.new("RGB", (W, H), (18, 18, 18))
    d = ImageDraw.Draw(sh)
    try:
        lab = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 13)
    except OSError:
        lab = ImageFont.load_default()
    y = PAD
    for p, r in rows:
        sh.paste(r, (LABEL, y), r)
        name = p.name.rsplit(" ", 1)[0] if " " in p.name else p.stem
        d.text((PAD, y + CELL // 2), name[:26], font=lab, fill=(230, 230, 230), anchor="lm")
        y += CELL + PAD
    sh.save(out_path)
    return out_path, len(rows), len(fonts)


if __name__ == "__main__":
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    dst = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else \
        pathlib.Path.home() / f"tmp/sumo-weekday-fonts_{stamp}.png"
    path, ok, total = sheet(dst)
    print(f"{path}  ({ok} of {total} fonts drew all seven)")
