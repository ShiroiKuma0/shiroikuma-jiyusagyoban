#!/usr/bin/env python3
"""The whole face, three ways, so the weekday's font can be judged in place rather than on a sheet.

Column 2 is what was asked for — MKai2 for the day kanji alone. Columns 1 and 3 are the two
all-of-a-piece answers, because "the face in A-OTF vs MKai" reads both ways and the cheapest way to
resolve that is to draw all three.
"""
import datetime
import importlib.util
import pathlib
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("P", HERE / "sumoji-preview.py")
P = importlib.util.module_from_spec(spec)
spec.loader.exec_module(P)
B = P.B
KANTEI, MKAI = B.FONT, B.SUMO + "MKai2 HK Black 1.0.ttf"

VARIANTS = [("current — 勘亭流 throughout", KANTEI, KANTEI),
            ("day kanji in MKai2 HK Black", KANTEI, MKAI),
            ("MKai2 HK Black throughout", MKAI, MKAI)]
SAMPLES = [(datetime.datetime(2026, 8, 31, 9, 13), 76, 1220),
           (datetime.datetime(2026, 12, 31, 10, 0), 60, 12045)]

W, H, GAP, LAB = 286, 482, 22, 30
sheet = Image.new("RGB", (GAP + (W + GAP) * len(VARIANTS),
                          LAB + GAP + (H + GAP + 18) * len(SAMPLES)), (24, 24, 24))
d = ImageDraw.Draw(sheet)
try:
    f = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 15)
    fs = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13)
except OSError:
    f = fs = ImageFont.load_default()

for col, (label, font, wfont) in enumerate(VARIANTS):
    B.FONT, B.WDAY_FONT = font, wfont
    B._frame.clear()                       # or the second variant reads the first's cached frame
    g = B.glyphs()
    x = GAP + (W + GAP) * col
    d.text((x + W // 2, LAB // 2 + 4), label, font=f, fill=(255, 220, 0), anchor="mm")
    for r, (when, batt, steps) in enumerate(SAMPLES):
        y = LAB + GAP + (H + GAP + 18) * r
        p = P.panel(g, when, batt, steps)
        sheet.paste(p, (x, y), p)
        d.text((x, y + H + 4), when.strftime("%Y-%m-%d %a %H:%M"), font=fs, fill=(150, 150, 150))

out = pathlib.Path.home() / f"tmp/sumoji-font-compare_{datetime.datetime.now():%Y-%m-%d_%H-%M-%S}.png"
sheet.save(out)
print(out)
