#!/usr/bin/env python3
"""Composite the built glyph tiles into a picture of the face.

Two outputs, for two different readers:

  (default)          a sheet of four sample times side by side — the iteration tool, for judging a
                     change without spending a three-minute pack-upload-photograph cycle
  --library          ONE face at 290x494, which is the preview every archived version carries and
                     the only shape the band's face picker can display. Ours shipped the sheet for
                     a while, and the picker letterboxed a 1244x528 strip into a sliver a
                     centimetre tall (白い熊, 2026-09-01)
  --refresh-archives regenerate that preview for EVERY archived version, each rendered by its own
                     scripts out of its own ZIP

The last one matters because the versions do not share a layout — v1 has no date row, v2 has no
seconds, v3 has both — so a single renderer cannot draw them all. The archives were built to stand
alone; this is the thing that relies on it.

This exists because the only other way to see the clock is to pack it, upload it over Bluetooth and
photograph the band — a three-minute loop for a change worth one glance. It draws what the band
draws: the hour's two layers ADD (the tens mark over the units glyph, both transparent), while the
minute and second cells COVER (opaque tiles, so an override truly replaces rather than intersects).
"""
import argparse
import datetime
import importlib.util
import inspect
import io
import pathlib
import shutil
import zipfile
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
    (datetime.datetime(2026, 12, 31, 10, 0, 0), 76, 1220),      # 丁度, and the longest date
    (datetime.datetime(2026, 1, 1, 11, 30, 45), 60, 999),       # 半, and the shortest date
    (datetime.datetime(2026, 11, 10, 12, 5, 59), 30, 5012),     # a lone units digit, cell 1 blank
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
    # The minute line is five tiles and its own order — cell 1 is tens, units, tens, and cell 2 is
    # tens then units. Nothing crosses the seam: the tens word stays in cell 1 and the units word in
    # cell 2, which is what lets :30 read 半 and :00 read 丁度. Drawing cell 1's三十 last would put
    # it back at :30; drawing cell 2's units tile first would lose 丁度.
    mt, mu = divmod(minute, 10)
    im.alpha_composite(g[f"m1a_tens_{mt}"], (B.CELL1_X, B.MIN_Y))
    im.alpha_composite(g[f"m1b_units_{mu}"], (B.CELL1_X, B.MIN_Y))
    im.alpha_composite(g[f"m1c_tens_{mt}"], (B.CELL1_X, B.MIN_Y))
    im.alpha_composite(g[f"m2a_tens_{mt}"], (B.CELL2_X, B.MIN_Y))
    im.alpha_composite(g[f"m2b_units_{mu}"], (B.CELL2_X, B.MIN_Y))

    # The seconds keep the older two-cell scheme, where the digit hops into cell 1 when there is no
    # tens digit so that 七秒 stays centred. Units first, tens second: these tiles are OPAQUE, so
    # the later one wins — cell 1's tens glyph paints over its units digit, and cell 2's 秒-alone
    # tile covers "<units>秒" on a round ten. The other order renders 13 as "三 三秒".
    st, su = divmod(when.second, 10)
    im.alpha_composite(g[f"s1_units_{su}"], (B.CELL1_X, B.SEC_Y))
    im.alpha_composite(g[f"s1_tens_{st}"], (B.CELL1_X, B.SEC_Y))
    im.alpha_composite(g[f"s2_units_{su}"], (B.CELL2_X, B.SEC_Y))
    im.alpha_composite(g[f"s2_tens_{st}"], (B.CELL2_X, B.SEC_Y))
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


# The band's own library ships 290x494 for every captured face, and every stock face shows 10:08 —
# so ours do too, and the cards read as native beside them.
LIB_W, LIB_H = 290, 494
LIB_WHEN = datetime.datetime(2026, 10, 18, 10, 8, 36)
LIBRARY = pathlib.Path.home() / "〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro"


def library_preview(out, panel_fn=None, glyphs=None):
    """One face on a 290x494 ground — what the picker actually shows."""
    face = (panel_fn or panel)(glyphs if glyphs is not None else B.glyphs(), LIB_WHEN, 76, 7645)
    img = Image.new("RGB", (LIB_W, LIB_H), (0, 0, 0))
    img.paste(face, ((LIB_W - face.width) // 2, (LIB_H - face.height) // 2), face)
    img.save(out)
    return out


def refresh_archives(library=LIBRARY):
    """Rewrite preview.png in every archived version, using that version's OWN scripts.

    A version's `panel` signature is not stable — v1 took hour/minute/second separately, later ones
    take a datetime — so it is inspected rather than assumed.
    """
    done = []
    for z in sorted(pathlib.Path(library).glob("相撲字時計*.zip")):
        work = pathlib.Path("/tmp/sumoji-archive-preview") / z.stem
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir(parents=True)
        with zipfile.ZipFile(z) as zf:
            for name in ("sumoji-build.py", "sumoji-preview.py"):
                (work / name).write_bytes(zf.read(name))
        spec = importlib.util.spec_from_file_location(f"v{abs(hash(z))}", work / "sumoji-preview.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        g = mod.B.glyphs()
        if "when" in inspect.signature(mod.panel).parameters:
            face = mod.panel(g, LIB_WHEN, 76, 7645)
        else:
            face = mod.panel(g, LIB_WHEN.hour, LIB_WHEN.minute, LIB_WHEN.second, 76, 7645)
        img = Image.new("RGB", (LIB_W, LIB_H), (0, 0, 0))
        img.paste(face, ((LIB_W - face.width) // 2, (LIB_H - face.height) // 2), face)
        buf = io.BytesIO()
        img.save(buf, "PNG")
        tmp = z.with_suffix(".zip.new")
        with zipfile.ZipFile(z) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                dst.writestr(item, buf.getvalue() if item.filename == "preview.png"
                             else src.read(item.filename))
        tmp.replace(z)
        done.append(z.stem)
    return done


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out", nargs="?", help="where to write; defaults into ~/tmp with a stamp")
    ap.add_argument("--library", action="store_true", help="one face at 290x494, picker-shaped")
    ap.add_argument("--refresh-archives", action="store_true",
                    help="rewrite preview.png in every archived version, from its own scripts")
    a = ap.parse_args()
    if a.refresh_archives:
        for name in refresh_archives():
            print(f"  refreshed {name}")
    else:
        stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        kind = "library" if a.library else "sheet"
        out = pathlib.Path(a.out) if a.out else \
            pathlib.Path.home() / f"tmp/sumoji-{kind}_{stamp}.png"
        print(library_preview(out) if a.library else sheet(out))
