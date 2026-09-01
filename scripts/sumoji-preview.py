#!/usr/bin/env python3
"""Composite the built glyph tiles into a picture of the face, at chosen times.

This exists because the only other way to see the clock is to pack it, upload it over Bluetooth and
photograph the band — a three-minute loop for a change worth one glance. It draws what the band
draws: the hour's two layers ADD (the tens mark over the units glyph, both transparent), while the
minute and second cells COVER (opaque tiles, so an override truly replaces rather than intersects).
"""
import datetime
import importlib.util
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("sumoji_build", HERE / "sumoji-build.py")
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)

W, H = 286, 482

# Four dates chosen to exercise the row's shape rather than to look pretty: today, the longest date
# of the year (十二月三十一日, the only case that needs all eight character widths), the shortest
# (一月一日, where the day sits centred on its seam with a gap either side), and a round ten.
SAMPLES = [
    (datetime.datetime(2026, 9, 1, 3, 13, 7), 76, 1220),
    (datetime.datetime(2026, 12, 31, 10, 0, 0), 76, 1220),
    (datetime.datetime(2026, 1, 1, 11, 34, 45), 60, 999),
    (datetime.datetime(2026, 11, 10, 12, 59, 59), 30, 5012),
]


def panel(g, when, batt, steps):
    hour, minute = when.hour, when.minute
    im = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    # The hour is two cells again in v3, units-under-tens in each, exactly like the minutes.
    h12 = hour % 12 or 12                      # the band counts 1..12, confirmed against its own clock
    ht, hu = divmod(h12, 10)
    for x, cell in ((B.CELL1_X, "h1"), (B.CELL2_X, "h2")):
        im.alpha_composite(g[f"{cell}_units_{hu}"], (x, B.HOUR_Y))
        im.alpha_composite(g[f"{cell}_tens_{ht}"], (x, B.HOUR_Y))
    for y, tens, units, lo, hi in ((B.MIN_Y, *divmod(minute, 10), "m1", "m2"),
                                   (B.SEC_Y, *divmod(when.second, 10), "s1", "s2")):
        # Units first, tens second. These tiles are OPAQUE, so the later one wins, and that is the
        # whole trick of the two-cell line: cell 1 draws the units digit and the tens glyph paints
        # over it when there is one; cell 2 draws "<units>分" and the 分-alone tile covers it when
        # the minute is a round ten. Drawing them the other way round renders 13 as "三 三分".
        im.alpha_composite(g[f"{lo}_units_{units}"], (B.CELL1_X, y))
        im.alpha_composite(g[f"{lo}_tens_{tens}"], (B.CELL1_X, y))
        im.alpha_composite(g[f"{hi}_units_{units}"], (B.CELL2_X, y))
        im.alpha_composite(g[f"{hi}_tens_{tens}"], (B.CELL2_X, y))
    # The date row, drawn in the element order the band uses — month, then the day's two cells
    # units-before-tens, then the weekday. Each cell has its OWN x: sharing the minute line's cells
    # put the day under the month, which is what the first render of this did.
    dt, du = divmod(when.day, 10)
    im.alpha_composite(g[f"month_{when.month}"], (B.MONTH_X, B.DATE_Y))
    im.alpha_composite(g[f"d1_units_{du}"], (B.DAY1_X, B.DATE_Y))
    im.alpha_composite(g[f"d1_tens_{dt}"], (B.DAY1_X, B.DATE_Y))
    im.alpha_composite(g[f"d2_units_{du}"], (B.DAY2_X, B.DATE_Y))
    im.alpha_composite(g[f"d2_tens_{dt}"], (B.DAY2_X, B.DATE_Y))
    im.alpha_composite(g[f"wday_{when.weekday() + 1}"], (B.WDAY_X, B.WDAY_Y))
    d = ImageDraw.Draw(im)
    try:
        f_top = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 26)
        f_bot = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 42)
    except OSError:
        f_top = f_bot = ImageFont.load_default()
    y_top = 21 + B.DY
    d.text((12, y_top), f"{batt}%", font=f_top, fill=B.INK, anchor="lt")
    d.text((W - 12, y_top), "23°C", font=f_top, fill=B.INK, anchor="rt")
    d.text((3 + B.STEPS_W // 2, B.BOTTOM_Y + B.LINE_H // 2), f"{steps}", font=f_bot,
           fill=B.INK, anchor="mm")
    return im


def sheet(out):
    g = B.glyphs()
    pans = [panel(g, *t) for t in SAMPLES]
    gap, top, bot = 20, 12, 34
    sh = Image.new("RGB", (gap + (W + gap) * len(pans), H + top + bot), (26, 26, 26))
    d = ImageDraw.Draw(sh)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 17)
    except OSError:
        f = ImageFont.load_default()
    for i, (p, (when, _, _)) in enumerate(zip(pans, SAMPLES)):
        x = gap + (W + gap) * i
        sh.paste(p, (x, top), p)
        d.text((x, H + top + 8), when.strftime("%Y-%m-%d %a %H:%M"), font=f, fill=(255, 220, 0))
    sh.save(out)
    return out


if __name__ == "__main__":
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else \
        pathlib.Path.home() / f"tmp/sumoji-preview_{stamp}.png"
    print(sheet(out))
