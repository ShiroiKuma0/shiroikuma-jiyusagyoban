#!/usr/bin/env python3
"""Build 相撲字時計 — a sumo-lettering kanji clock — out of MZ DIGICOLOR.

The band draws a face from fixed slots — two images for the hour, two for the minute, and further
slots for the month, the day and the weekday — each bound to one value and holding an image per
alternative. There is no text renderer and no font on the band, so a kanji clock has to arrive as
pictures. This paints them, in 勘亭流 — the brush lettering of sumo banners — and rewrites
MZ DIGICOLOR's layout to point at them.

Three lines: the hour, the minute, and the date as 八月三十一日月. A seconds line lived where the
date is now and was dropped for it — seconds on a watch face are read by nobody and cost the row
that carries the only information the clock could not otherwise show.

## The glyphs are painted, not knocked out

An earlier version was SUBTRACTIVE, because MZ DIGICOLOR is: a coloured slab of "all segments lit"
sits under the digits and each digit image is a dark mask covering the segments that should be off.
Drawing a kanji as a hole in that mask worked and got the skins' recolouring for free.

It also could not stack. Two masks over one slab show only where BOTH are transparent — their
intersection — so hour 11, drawn as 十 over 一, rendered as neither. Now every tile is painted
yellow on opaque black and the skins are scrubbed, which costs the recolouring and buys the only
thing that matters: a later tile genuinely REPLACES an earlier one.

## Sixty minutes out of two slots

`dt.numkanji` builds a numeral as a tens part plus a units part, dropping `〇十` and collapsing
`一十` to `十` — exactly two parts, which is exactly what the band offers. The alignment does the
work: the tens cell is right-aligned and the units cell left-aligned, so the two always meet at the
seam whichever parts are present.

    min 59   [    五十][九分    ]
    min 13   [      十][三分    ]
    min  3   [        ][三分    ]   tens tile is clear
    min 30   [    三十][分      ]   units tile covered by the 分-alone tile

Both cells draw units first and tens second, and the tiles are opaque, so the later one wins. That
is what makes the round tens work: cell 1's tens glyph paints over its units digit, and cell 2's
"分 alone" tile paints over "<units>分". Carrying 分 inside the units glyph rather than in a third
slot is what keeps the string from opening a hole when the units digit is zero.

## The hour is one glyph and a corner mark

The hour reads at a glance, so it gets the whole width as a single character rather than two half
ones — 一 through 九 and 十, each centred and as large as the row allows. Eleven and twelve cannot
be one character, so the tens slot becomes a small 十 in the top-left corner: the hour's own glyph
stays full size and the mark says "and ten more". Both hour layers are TRANSPARENT rather than
opaque, so the mark adds to the glyph instead of covering it — the opposite of the minute cells,
and the reason the two lines are built differently.

Ten is 十 in the units slot, at full size like every other hour. It was 時 for a while, nudged
aside to dodge the corner mark; 十 needs no nudge, because its bar sits at mid-height and its
stroke runs down the centre, leaving the corner clear.

The band counts 1–12, not 0–11. That was not assumed: the face showed 十 at 12:50, and the only way
to settle it was to stand the band's clock at noon with `huawei.time` and read it. It said 十二.

## The date

八月三十一日, in seven character widths, with the weekday moved down beside the steps count.

The row is limited by its WIDTH alone, so every character taken out of it is width the rest keep,
and it went through three shapes on that principle — all three 白い熊's, on 2026-08-31:

    八月三十一日（月）   ten widths    29 px    the natural way to write it
    八月三十一日月      eight         35 px    brackets dropped, weekday told apart by colour
    八月三十一日 + 月   seven         40 px    weekday moved to the bottom row entirely

The middle step is worth keeping in mind even though it is gone: the brackets bought only
separation, and painting the weekday WHITE against the date's yellow bought the same separation for
nothing. Once it moved to the bottom row it no longer needed distinguishing at all and went back to
yellow — but it did take a font of its own. 勘亭流's 月 closes almost to a solid block at 46 px, and
standing alone in the corner it has no context to help it, so the weekday alone is drawn in
MKai2 HK Black. That is why there are two font constants.

The day reuses the minute line's override exactly. The month and the weekday need no override —
one image each — but both are 1-BASED, with an image list one longer than their range; the original
artwork settles which way round they run, 033 being JAN and 055 MON.

## Sizing

Every glyph is measured into one shared frame, taken from the pen origin and the baseline across
the whole repertoire, so 一 and 十 and 分 all sit on the same centre line rather than each being
cropped to its own ink. Getting this wrong bottom-aligned the hour's 一 and looked broken. The date
is measured in a frame of its OWN, or the 曜日 characters would shrink every hour and minute glyph
to fit letters those lines never draw.

The frame is not what limits the size. Each line is limited by its own row — the hour and the date
by width, the minute by height until it took the 8 px the date did not need, which put it at 71 px
against a 143 px cell and therefore at ITS width limit too. So all three lines now sit at a
ceiling, the rows fill the face exactly from 78 to 482, and there is no slack anywhere left to
find.
"""
import pathlib
import struct
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

SUMO = "/home/shiroikuma/〇/[06] 蔵書/[06][821] フォント/[06][821][03] 相撲フォント/"
FONT = SUMO + "A-OTF 勘亭流 Std Ultra 1.001.otf"

# The weekday may take a face of its own. It stands alone on the bottom row rather than inside a
# line of context, and 勘亭流's 月 closes up almost to a solid block at that size — so the glyph
# that most needs an open counter is the one furthest from help.
WDAY_FONT = SUMO + "MKai2 HK Black 1.0.ttf"
DIG = "〇一二三四五六七八九"
TENS = ["", "十", "二十", "三十", "四十", "五十"]

# The two lines, extended to cover the slab completely.
#
# The skin is opaque in three bands — y 40..255 behind the hour, y 269..363 behind the minutes, and
# y 408..443 for the MUSIC bar. The old digits only reached y 69 and y 298, so the top of each band
# showed through as the yellow bars 白い熊 asked to be rid of. Starting each line at the top of its
# band covers them with the same masks that draw the kanji, and needs no change to the skins.
# The band clips the top of the face.
#
# Worn, the first thirty-odd pixels are behind the bezel: the battery and weather row sat at y 21
# and came out sliced in half (白い熊, 2026-08-31). Everything moves down by DY, and the whole skin
# is erased rather than partly covered — with the clock painted on black the skin contributes
# nothing but the coloured bands we were at pains to hide, so removing it outright is simpler and
# leaves no edge to leak.
DY = 24                           # how far the top row and the widgets move down
# Three lines now. The gap between the hour and the minutes was 18 px and 白い熊 asked for 40 % less,
# so it is 11 — used between every pair, which keeps the stack even.
# Asymmetric hour cells, and no padding anywhere it can be avoided.
#
# The hour's two layers ADD rather than cover — the tiles are transparent with only the glyph
# painted — so the units digit survives the tens digit instead of being painted over by it. That is
# what makes eleven and twelve correct at last: ten paints 十 over nothing, eleven paints 十 and 一
# side by side. It costs the symmetry, because the tens cell only ever holds 十 and can be narrow
# while the units cell takes the rest.
# The slack redistributed. 31 px were going spare — 26 below the steps and 5 above the hour — and it is
# split across all three lines rather than banked into the hour alone, so every glyph grows by about a
# tenth. The hour keeps the largest share because it is the line being read from across a room. The
# three lines and the steps now fill the face exactly, 78 to 482.
#
# The minutes took another 8 px when the seconds became the date, which needs less height than
# it does width. That put the minute line at its own ceiling — see DATE_H below.
HOUR_Y, HOUR_H = 78, 239
MARK_X, MARK_Y, MARK_W = 2, 2, 70
# 70, down from 75, and the line does not shrink: the minutes are limited by their WIDTH —
# two characters in a 143 px cell — from 69 px of height upwards. The 5 px above that were
# doing nothing, so they went to the date when the weekday left it.
MIN_Y, MIN_H = HOUR_Y + HOUR_H, 70
CELL_W = 143                      # two cells, side by side, spanning the whole 286
CELL1_X, CELL2_X = 0, 143

# ── the date row, where the seconds used to be ────────────────────────────────────────────────
#
# 八月三十一日 — the weekday used to end this line and now lives on the bottom row instead.
#
# It went through three shapes, each one buying width. 八月三十一日（月）needs ten character widths
# and gives a 29 px glyph. Dropping the brackets and telling the weekday apart by colour instead —
# white against the date's yellow — needs eight, and gives 35. Moving the weekday out altogether
# needs seven, and gives 40. Each step was 白い熊's (2026-08-31), and each was right: the row is
# limited by its width alone, so a character removed is width every remaining character keeps.
#
# The worst case is 十二月三十一日 plus the weekday — three characters of month, four of day, one of
# weekday — so the row is laid out for that and never has to reflow:
#
#     [   八月][  三十][一日  ][月]      3 + 2 + 2 + 1, at 35 px each
#     [ 十二月][  三十][一日  ][木]      the longest date of the year
#     [   一月][      ][一日  ][金]      the shortest; the day sits centred on its seam
#
# Months one to ten leave the leftmost width empty, which puts the line about half a character
# right of centre for ten months of the year. Squeezing 十一月 and 十二月 into two widths would
# centre it always, at the cost of two months rendering a third smaller — the asymmetry is the
# quieter fault, so it is the one kept.
# 41 px, not the 49 the seconds had: the date is limited by its WIDTH — eight characters
# across 286 — so any height past the point where the glyph stops growing is wasted. The 8 px
# that frees go to the minutes, which is the last of them: at 75 the minute glyph reaches
# 71 px, and 71 is half of the 143 its cell has. The line cannot use another pixel of height.
DATE_Y, DATE_H = MIN_Y + MIN_H, 46
DATE_U = 40                       # one character width; 7 of them, centred in the 286
DATE_X = (286 - DATE_U * 7) // 2
MONTH_X, MONTH_W = DATE_X, DATE_U * 3
DAY1_X, DAY2_X, DAY_W = MONTH_X + MONTH_W, MONTH_X + MONTH_W + DATE_U * 2, DATE_U * 2

# The weekday sits on the BOTTOM row now, beside the steps count, which never spans its line
# (白い熊, 2026-08-31). Taking it out of the date leaves seven character widths there instead of
# eight, and 286/7 is 40 px against 35 — the date grew by a seventh for free. It is yellow again:
# down here it is nothing like the date, so it needs no colour to say so.
#
# It is NOT hard against the right edge, where it first went: that read as two separate things
# rather than one line. It sits close enough to the number to be read with it — and the steps rect
# narrows to match, so the pair stays a pair. The clearance is set by the widest count the band is
# likely to show: five digits of font 132 come to roughly 140 px, which from a centre of 121 reaches
# x 191, ten short of the weekday.
WDAY_W = 46
WDAY_X, WDAY_Y = 200, 436
STEPS_W = 236                     # narrowed from the full 280, so the number cannot drift right


INK = (255, 255, 0, 255)          # 時間's own yellow

# Every character the clock can draw. The whole repertoire is measured together, once, so that all
# glyphs share one vertical frame.
# 時 is no longer drawn anywhere, so it is out of the frame too — though it was worth
# measuring rather than assuming: it set the frame's top edge by ONE pixel, and taking
# it out grows the glyphs by 0.4 %. The size never came from the character set. Every
# line is limited by the HEIGHT of its own tile — the hour's width would allow 274 px
# and its tile only allowed 220 — so the real gain is below, in the layout.
REPERTOIRE = "〇一二三四五六七八九十分"

# The date is measured in its OWN frame. Sharing the clock's would drag every hour and
# minute glyph down to fit 曜日 characters they never draw.
DATE_REP = "〇一二三四五六七八九十月日火水木金土"
WDAY = "月火水木金土日"                # index 1 is Monday, proved by the original artwork


def numk(n):
    """1-12 as 時間's own numeral: 一…九, 十, 十一, 十二."""
    return DIG[n] if n < 10 else "十" + ("" if n == 10 else DIG[n - 10])
DIGITS_LATIN = "0123456789"
_frame = {}


def _measure(size, rep=None, font=None):
    """
    One shared frame for the whole repertoire, measured from the PEN ORIGIN and the BASELINE.

    Glyphs must be placed by their em box, not by their ink. Aligning ink bottoms put 一 — a single
    horizontal stroke sitting in the middle of its square — down on the floor beside 十, which is
    not where the character lives (白い熊, 2026-08-31: "the kanji sits with the line going through
    the centre vertically").

    So every character is drawn against a common origin and the frame is the union of what they all
    paint, in both axes. Each glyph then keeps its true position inside the square, every one is the
    same size, and nothing can clip — 勘亭流 overshoots its metrics in every direction and the frame
    simply includes the overshoot.
    """
    rep, font = rep or REPERTOIRE, font or FONT
    key = (size, rep, font)              # the font is part of the key, or a second face reads the
    if key in _frame:                    # first one's cached frame and every glyph comes out wrong
        return _frame[key]
    f = ImageFont.truetype(font, size)
    org = size * 2                                # the pen origin, with room on every side
    left, right, top, bot = 10 ** 9, -10 ** 9, 10 ** 9, -10 ** 9
    for ch in rep:
        c = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(c).text((org, org), ch, fill=255, font=f, anchor="ls")
        bb = c.getbbox()
        if bb is None:
            continue
        left, right = min(left, bb[0] - org), max(right, bb[2] - org)
        top, bot = min(top, bb[1] - org), max(bot, bb[3] - org)
    _frame[key] = (f, org, left, right, top, bot)
    return _frame[key]


def glyph(ch, size, rep=None, font=None):
    """One character on a strip in the shared frame — same box for every character."""
    f, org, left, right, top, bot = _measure(size, rep, font)
    c = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(c).text((org, org), ch, fill=255, font=f, anchor="ls")
    return c.crop((org + left, org + top, org + right, org + bot))


def render(text, w, h, align="centre", pad=6, chars=None, valign="centre", vpad=3,
           rep=None, solid=True, colour=None, font=None):
    """
    A black tile with `text` painted on it, as large as fits. FULLY OPAQUE, and that is the point.

    The first attempt drew these as knockouts — black with the glyph cut out — so the skin's colour
    would show through. That works for a single layer and fails the moment two are stacked: the
    upper tile's hole reveals the LOWER TILE'S BLACK rather than the skin, so 十 painted over 一
    showed only where the two strokes crossed. Stacking is what lets a tens-bound element override a
    units-bound one, which is the only way two per-digit slots can spell an hour that depends on
    both digits — so the glyph is painted, and the colour is fixed here rather than by the skin.

    `chars` fixes the scale as though the tile held that many characters, so every tile on a line is
    drawn at one size. `valign` places the shared frame, not the ink — see [_measure].
    """
    img = Image.new("RGBA", (w, h), (0, 0, 0, 255) if solid else (0, 0, 0, 0))
    if not text:
        return img
    SIZE = 300
    cells = [glyph(ch, SIZE, rep, font) for ch in text]
    cw, chh = max(c.width for c in cells), max(c.height for c in cells)
    k = min((w - pad * 2) / (chars or len(text)) / cw, (h - vpad * 2) / chh)
    cw2, chh2 = int(cw * k), int(chh * k)
    total = cw2 * len(text)
    x0 = {"centre": (w - total) // 2, "right": w - pad - total, "left": pad}[align]
    y0 = {"centre": (h - chh2) // 2, "top": vpad, "bottom": h - vpad - chh2}[valign]
    ink = Image.new("RGBA", (w, h), colour or INK)
    mask = Image.new("L", (w, h), 0)
    for i, c in enumerate(cells):
        g = c.resize((max(1, int(c.width * k)), max(1, int(c.height * k))), Image.LANCZOS)
        mask.paste(g, (x0 + i * cw2 + (cw2 - g.width) // 2, y0), g)
    img.paste(ink, (0, 0), mask)
    return img


def _shift_right(img, width, dx):
    """Place a narrower tile inside a full-width one, flush right."""
    out = Image.new("RGBA", (width, img.height), (0, 0, 0, 0))
    out.alpha_composite(img, (dx, 0))
    return out


def clear(w, h):
    """Fully transparent — lets the opaque tile underneath stand, unchanged."""
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


# The bottom band, in face coordinates.
#
# The skin paints a yellow bar with the word MUSIC across y 408..443 and the date row draws over it
# — both baked in, both inside the same eighty pixels. 白い熊 wants the steps count there with the
# band's battery under it, and two legible lines do not fit alongside the date, so the date goes.
# Stated plainly because it was a judgement call rather than a request.
BOTTOM_Y = 436
LINE_H = 46


def scrub_skin(im):
    """Erase the skin completely. See the note above DY."""
    return Image.new("RGBA", im.size, (0, 0, 0, 0))


def glyphs():
    """
    Every image the clock needs.

    ## Two cells, four elements, and why that is what it takes

    The band gives one element per DIGIT — hour tens, hour units — and a glyph that depends on both
    cannot come from either alone. 白い熊's hour spec needs exactly that: cell one is the hour
    numeral when the tens digit is zero and 十 when it is one; cell two is 時 up to ten and the
    units numeral at eleven and twelve.

    So each cell gets TWO elements at the same position: the units-bound one underneath, and the
    tens-bound one on top, either fully transparent (leave what is below) or opaque (replace it).
    That makes the tens digit an override, which is exactly the missing dependency.

        hour   cell 1                    cell 2
        0-9    units: 〇一…九            tens: 時, opaque  ->  九時, 〇時
        10     tens:  十, opaque         units: 時         ->  十時
        11-12  tens:  十, opaque         units: 一 / 二    ->  十一, 十二

    The same trick puts 〇 back at zero on both lines, which two slots could not do.
    """
    out = {}
    hh = HOUR_H
    mw, mh = CELL_W, MIN_H

    # ── the hour: one big centred glyph, with 十 as a small mark over its top-left corner ──
    #
    # Additive, so nothing covers anything: the mark is painted on top of the digit and both stay
    # legible. The units digit is zero only at ten, and that image carries 時 — so ten reads 十時,
    # eleven 十一, twelve 十二, and one to nine are a single character filling the face.
    # Ten's units digit is zero, and that image carries 十 — the hour's own character, full size and
    # centred like every other hour's. It was 時 for a while, nudged right to dodge the corner mark,
    # because 時 has ink in its own top-left. 十 does not: its bar sits at mid-height and its stroke
    # runs down the centre, so the mark has clear space and the glyph can stay where it belongs.
    out["h1_units_0"] = render("十", 286, hh, "centre", chars=1, solid=False)
    for u in range(1, 10):
        out[f"h1_units_{u}"] = render(DIG[u], 286, hh, "centre", chars=1, solid=False)
    out["h1_tens_0"] = clear(MARK_W, MARK_W)
    for t in (1, 2):
        out[f"h1_tens_{t}"] = render("十", MARK_W, MARK_W, "centre", chars=1, solid=False, vpad=2)

    # The second hour pair is retired — two layers are all this needs.
    for u in range(10):
        out[f"h2_units_{u}"] = clear(1, 1)
    for t in range(3):
        out[f"h2_tens_{t}"] = clear(1, 1)

    # ── minutes, cell 1: the units numeral, or the tens word painted over it ──
    for u in range(10):
        out[f"m1_units_{u}"] = render(DIG[u], mw, mh, "right", chars=2)
    out["m1_tens_0"] = clear(mw, mh)
    for t in range(1, 6):
        out[f"m1_tens_{t}"] = render(TENS[t], mw, mh, "right", chars=2)

    # ── minutes, cell 2: 分, or the units numeral and 分 once the tens digit is set ──
    for u in range(10):
        out[f"m2_units_{u}"] = render(("" if u == 0 else DIG[u]) + "分", mw, mh, "left", chars=2)
    out["m2_tens_0"] = render("分", mw, mh, "left", chars=2)        # cell 1 already carries the numeral
    for t in range(1, 6):
        out[f"m2_tens_{t}"] = clear(mw, mh)

    # The date row is retired by pointing every one of its images at nothing. Kept at the sizes
    # the elements actually had, rather than one shared 1x1, in case the band sizes anything
    # from the resource header rather than from the element.
    # ── the date: 八月三十一日月, four bindings across eight character widths ──
    #
    # The day uses the minutes' two-cell override, unchanged: cell 1 draws the units digit and the
    # tens word paints over it; cell 2 draws "<units>日" and the 日-alone tile paints over that when
    # the day is a round ten. So 一日, 十日, 二十日 and 三十一日 all come out of two slots.
    #
    # The month and the weekday need no override — one image each, chosen directly — but both are
    # 1-BASED: their image lists carry one entry more than they have values and the band never asks
    # for index 0. The original artwork proves which way round they run (033 is JAN, 055 is MON), so
    # index 1 is January and Monday, and index 0 is filled with a copy rather than left blank.
    dw, dh = DAY_W, DATE_H
    for m in range(1, 13):
        out[f"month_{m}"] = render(numk(m) + "月", MONTH_W, dh, "right", pad=0, chars=3,
                                   rep=DATE_REP)
    out["month_0"] = out["month_1"]

    for u in range(10):
        out[f"d1_units_{u}"] = render(DIG[u], dw, dh, "right", pad=0, chars=2, rep=DATE_REP)
    out["d1_tens_0"] = clear(dw, dh)
    for t in range(1, 4):
        out[f"d1_tens_{t}"] = render(TENS[t], dw, dh, "right", pad=0, chars=2, rep=DATE_REP)

    for u in range(10):
        out[f"d2_units_{u}"] = render(("" if u == 0 else DIG[u]) + "日", dw, dh, "left", pad=0,
                                      chars=2, rep=DATE_REP)
    out["d2_tens_0"] = render("日", dw, dh, "left", pad=0, chars=2, rep=DATE_REP)
    for t in range(1, 4):
        out[f"d2_tens_{t}"] = clear(dw, dh)

    for i, ch in enumerate(WDAY, start=1):
        out[f"wday_{i}"] = render(ch, WDAY_W, LINE_H, "centre", pad=0, chars=1, rep=DATE_REP,
                                  font=WDAY_FONT)
    out["wday_0"] = out["wday_1"]

    out["date_wide"] = clear(94, 24)
    out["date_digit"] = clear(21, 28)
    return out


# ── protobuf, only as much as is needed ────────────────────────────────────────────────────────

def _v(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s
        if not x & 0x80: return r, i
        s += 7


def _enc(n):
    o = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        o.append(b | (0x80 if n else 0))
        if not n: return bytes(o)


def parse(b):
    """[(field, wire, value)] — value is bytes for wire 2, int otherwise. Order preserved."""
    out, i = [], 0
    while i < len(b):
        k, i = _v(b, i)
        f, w = k >> 3, k & 7
        if w == 2:
            n, i = _v(b, i); out.append((f, w, b[i:i + n])); i += n
        elif w == 0:
            x, i = _v(b, i); out.append((f, w, x))
        elif w == 5:
            out.append((f, w, b[i:i + 4])); i += 4
        elif w == 1:
            out.append((f, w, b[i:i + 8])); i += 8
        else:
            raise ValueError(f"wire {w}")
    return out


def build(items):
    o = bytearray()
    for f, w, v in items:
        o += _enc((f << 3) | w)
        if w == 2:
            o += _enc(len(v)) + v
        elif w == 0:
            o += _enc(v)
        else:
            o += v
    return bytes(o)


# ── the edit ───────────────────────────────────────────────────────────────────────────────────

# Which element gets what. Keyed by (binding, aod-flag, x) because the layer holds two renderings —
# the ordinary one and an always-on copy that shows HH:MM where the ordinary shows MM:SS. Only the
# ordinary one is touched; the AOD copy keeps the seven-segment digits and goes on working.
# Which element becomes what.
#
# Keyed by the element's INDEX, because bindings are being reassigned and positions moved — the old
# identity of an element says nothing about its new job. Each entry is
# (image-set prefix, new binding, new position, how many values that binding takes).
#
# DRAW ORDER IS LOad-BEARING. Within a cell the units-bound element must be drawn first and the
# tens-bound one over it, and the band draws elements in the order they appear. The pairs are
# therefore (8,9), (10,11), (0,1) and (2,3) — units first in every one. Elements 10 and 11 have
# their bindings SWAPPED from the original for exactly this reason.
#
# Elements 4-7 are the always-on-display copies and are left alone: they keep the seven-segment
# digits, so AOD still tells the time.
HOUR_UNITS, HOUR_TENS = 60, 59
MIN_UNITS, MIN_TENS = 62, 61

PLAN = {
    8:  ("h1_units", HOUR_UNITS, (0, HOUR_Y), 10),
    9:  ("h1_tens",  HOUR_TENS,  (MARK_X, HOUR_Y + MARK_Y), 3),
    10: ("h2_units", HOUR_UNITS, (CELL2_X, HOUR_Y), 10),
    11: ("h2_tens",  HOUR_TENS,  (CELL2_X, HOUR_Y), 3),
    0:  ("m1_units", MIN_UNITS,  (CELL1_X, MIN_Y), 10),
    1:  ("m1_tens",  MIN_TENS,   (CELL1_X, MIN_Y), 6),
    2:  ("m2_units", MIN_UNITS,  (CELL2_X, MIN_Y), 10),
    3:  ("m2_tens",  MIN_TENS,   (CELL2_X, MIN_Y), 6),
}


# The date row is six elements the face does not have in its clock layer, so they are APPENDED.
#
# The date HAS its own layer, with these four bindings already wired — but that layer carries two
# complete sets of elements distinguished by a field whose meaning is not known, and only one of
# them is drawn. Rather than guess which, the whole date layer stays blanked and the row is rebuilt
# here beside the clock, where appended elements are already proved to work.
#
# Draw order is load-bearing in the day's two cells exactly as it is in the minutes': units first,
# tens over it.
MONTH, DAY_UNITS, DAY_TENS, WEEKDAY = 51, 71, 70, 52
NEW = [
    ("month",    MONTH,     (MONTH_X, DATE_Y), 13),
    ("d1_units", DAY_UNITS, (DAY1_X, DATE_Y), 10),
    ("d1_tens",  DAY_TENS,  (DAY1_X, DATE_Y), 4),
    ("d2_units", DAY_UNITS, (DAY2_X, DATE_Y), 10),
    ("d2_tens",  DAY_TENS,  (DAY2_X, DATE_Y), 4),
    ("wday",     WEEKDAY,   (WDAY_X, WDAY_Y), 8),
]


def element(index, prefix, binding, pos, count, ids, template):
    """One clock element, shaped like the ones already in the layer."""
    body = build([(1, 2, ids[f"{prefix}_{i}"].encode()) for i in range(count)]
                 + [(2, 2, build([(1, 0, pos[0]), (2, 0, pos[1])])),
                    (3, 0, binding), (6, 0, 0)])
    return build([(1, 0, index), (2, 0, 2), (5, 2, body)])


def rewrite(layout: bytes, ids: dict) -> bytes:
    """Give the eight clock elements their new images, bindings and positions."""
    top = parse(layout)
    out, touched = [], []
    for f, w, v in top:
        if f != 1:
            out.append((f, w, v)); continue
        layer = parse(v)
        if next((x for ff, ww, x in layer if ff == 1), None) != 1:
            out.append((f, w, v)); continue
        new_layer = []
        for lf, lw, lv in layer:
            if lf != 5:
                new_layer.append((lf, lw, lv)); continue
            elem = parse(lv)
            index = next((x for ef, ew, x in elem if ef == 1), None)
            if index not in PLAN:
                new_layer.append((lf, lw, lv)); continue
            prefix, binding, pos, count = PLAN[index]
            group = [ids[f"{prefix}_{i}"] for i in range(count)]
            np = [(1, 2, g.encode()) for g in group]
            body = next(x for ef, ew, x in elem if ef == 5)
            for pf, pw, pv in parse(body):
                if pf == 1:
                    continue
                if pf == 2:
                    pv = build([(1, 0, pos[0]), (2, 0, pos[1])])
                if pf == 3:
                    pv = binding
                np.append((pf, pw, pv))
            new_elem = [(ef, ew, build(np) if ef == 5 else ev) for ef, ew, ev in elem]
            new_layer.append((lf, lw, build(new_elem)))
            touched.append(f"elem {index:>2} -> {prefix:<9} binding {binding} at {pos}, {count} images")
        nxt = max((next((x for a, b_, x in parse(lv) if a == 1), -1)
                   for lf, lw, lv in new_layer if lf == 5), default=-1) + 1
        for i, (prefix, binding, pos, count) in enumerate(NEW):
            new_layer.append((5, 2, element(nxt + i, prefix, binding, pos, count, ids, None)))
            touched.append(f"elem {nxt + i:>2} += {prefix:<9} binding {binding} at {pos}, "
                           f"{count} images")
        out.append((f, w, build(new_layer)))
    for t in touched:
        print("   ", t)
    if len(touched) != len(PLAN) + len(NEW):
        raise SystemExit(f"expected {len(PLAN) + len(NEW)} elements, got {len(touched)}")
    return build(out)


# Widget sources, from the enum decoded across the 45 faces.
SRC_STEPS, SRC_BATTERY = 10, 17
YELLOW_RGBA = [(1, 0, 255), (2, 0, 255), (3, 0, 0), (4, 0, 255)]


def rect(x, y, w, h):
    return build([(1, 0, x), (2, 0, y), (3, 0, w), (4, 0, h)])


# A genuine steps readout, lifted whole from Fluo Energy.
#
# `field 5 = 0` means steps in a TYPE 0 text element. Setting it on this face's battery element —
# which is type 10, with its payload in field 11 — did nothing but keep showing 76% (白い熊,
# 2026-08-31). The element type is part of the contract, not a detail, so the whole thing is copied
# rather than adapted: wrapper `1: index, 2: 0, 3: payload`, and inside it the font (148), the
# centring (4: 1), the selector (5: 0) and the two fields nobody has decoded (7, 18).
STEPS_TEMPLATE = bytes.fromhex(
    "0a09086b10aa031848201b"          # 1: rect  {x:107, y:426, w:72, h:27}
    "120c08dd0110dd0118dd0120ff01"    # 2: colour {221,221,221,255}
    "188401"                          # 3: 132   the largest text the format offers
    "2001"                            # 4: 1     centred
    "2800"                            # 5: 0     steps
    "3800"                            # 7: 0
    "900100"                          # 18: 0
)


def readout(index, x, y, w, h):
    """The steps count, centred, in 時間's yellow."""
    inner = []
    for f, w_, val in parse(STEPS_TEMPLATE):
        if f == 1:
            val = rect(x, y, w, h)
        elif f == 2:
            val = build(YELLOW_RGBA)
        inner.append((f, w_, val))
    elem = build([(1, 0, 0), (2, 0, 0), (3, 2, build(inner))])
    return build([(1, 0, index), (2, 0, 0), (5, 2, rect(x, y, w, h)),
                  (6, 2, elem), (7, 2, b""), (8, 0, SRC_STEPS)])


def payload_of(sub):
    """
    A sub-element's payload field, which is NOT a fixed offset from its type.

    Measured across the corpus: type 0 keeps its payload in field 3, type 2 in field 5, type 7 in
    field 10 — all type+3 — but MZ DIGICOLOR's battery is type 10 with its payload in field 11.
    Assuming type+1 moved the battery correctly and silently left the weather text and its icon
    behind (白い熊, 2026-08-31). Taking the highest-numbered bytes field is what actually holds for
    every element in the library.
    """
    cand = [a for a, w, v in sub if w == 2 and isinstance(v, bytes) and a >= 3]
    return max(cand) if cand else None


def _shift(v, dy):
    """Add dy to field 2 of a {1:x, 2:y, ...} group, if that is what this is."""
    inner = parse(v)
    if not inner or not all(isinstance(x, int) for _, _, x in inner):
        return v
    return build([(a, b_, (x + dy) if a == 2 else x) for a, b_, x in inner])


def edit_widget(wid: bytes, dy: int, yellow: bool) -> bytes:
    """Move a widget and everything inside it down by [dy], and recolour its text."""
    out = []
    for f, w, v in parse(wid):
        if f == 5 and isinstance(v, bytes):                 # the widget's own bounds
            v = _shift(v, dy)
        elif f == 6 and isinstance(v, bytes):
            sub = parse(v)
            et = next((x for a, b_, x in sub if a == 2), None)
            pf = payload_of(sub)
            new_sub = []
            for a, b_, x in sub:
                if a == pf and isinstance(x, bytes):
                    body = []
                    for c, d_, y in parse(x):
                        if et == 2 and c == 2 and isinstance(y, bytes):
                            y = _shift(y, dy)               # image list: {1:x, 2:y}
                        elif et != 2 and c == 1 and isinstance(y, bytes):
                            y = _shift(y, dy)               # text: {1:x, 2:y, 3:w, 4:h}
                        elif et != 2 and c == 2 and yellow and isinstance(y, bytes):
                            y = build(YELLOW_RGBA)          # text colour
                        body.append((c, d_, y))
                    x = build(body)
                new_sub.append((a, b_, x))
            v = build(new_sub)
        out.append((f, w, v))
    return build(out)


def bottom(layout: bytes, ids: dict) -> bytes:
    """Move the top row clear of the bezel, make it yellow, retire the date, add the steps line."""
    top = parse(layout)
    out = []

    for f, w, v in top:
        if f != 1:
            out.append((f, w, v)); continue
        layer = parse(v)
        kind = next((x for a, b_, x in layer if a == 1), None)
        if kind == 2:                          # the date layer: every image points at nothing
            new_layer = []
            for lf, lw, lv in layer:
                if lf != 5:
                    new_layer.append((lf, lw, lv)); continue
                elem = parse(lv)
                body = next((x for a, b_, x in elem if a == 5), None)
                if body is None:
                    new_layer.append((lf, lw, lv)); continue
                pb = parse(body)
                n = sum(1 for a, b_, _ in pb if a == 1)
                first = next(x for a, b_, x in pb if a == 1).decode()
                blank = ids["date_digit"] if first in {f"{i:03d}" for i in range(45, 55)} \
                    else ids["date_wide"]
                np = [(1, 2, blank.encode())] * n + [(a, b_, x) for a, b_, x in pb if a != 1]
                new_layer.append((lf, lw, build([(a, b_, build(np) if a == 5 else x)
                                                 for a, b_, x in elem])))
            out.append((f, w, build(new_layer)))
        elif kind == 3:                        # the widget layer
            new_layer, idx = [], -1
            for lf, lw, lv in layer:
                if lf == 8:
                    idx = max(idx, next((x for a, b_, x in parse(lv) if a == 1), 0))
                    lv = edit_widget(lv, DY, yellow=True)
                new_layer.append((lf, lw, lv))
            new_layer.append((8, 2, readout(idx + 1, 3, BOTTOM_Y, STEPS_W, LINE_H)))
            out.append((f, w, build(new_layer)))
        else:
            out.append((f, w, v))
    print(f"    top row moved down {DY}px and recoloured; date retired; "
          f"steps centred at y {BOTTOM_Y}, weekday beside it at x {WDAY_X}")
    return build(out)


def main(src: pathlib.Path, out: pathlib.Path):
    data = (src / "face.bin").read_bytes()
    ver, l1, l2, l3, spare = struct.unpack_from("<HHIII", data, 0)
    layout, table, blob = data[16:16 + l1], data[16 + l1:16 + l1 + l2], data[16 + l1 + l2:]
    count = len(table) // 8

    # The new images go on the end, so every existing id keeps its meaning.
    imgs = glyphs()
    images_dir = out / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    ids = {}
    for i, (name, im) in enumerate(sorted(imgs.items())):
        rid = count + i + 1
        ids[name] = f"{rid:03d}"
        im.save(images_dir / f"{rid:03d}_{im.width}x{im.height}.png")

    new_layout = bottom(rewrite(layout, ids), ids)

    # The skins keep their ids; only their bottom band is erased.
    from PIL import Image as _Image
    for skin in ("076", "077", "078"):
        f = next((src / "images").glob(f"{skin}_*.png"))
        scrub_skin(_Image.open(f).convert("RGBA")).save(images_dir / f.name)
    print(f"  layout {len(layout)} -> {len(new_layout)} B, {len(imgs)} new images "
          f"(ids {count + 1:03d}-{count + len(imgs):03d})")

    # Copy the unpacked tree forward, then let the shared tool do the packing so the codec and the
    # round-trip guard are the ones already proved byte-exact.
    import shutil
    work = out / "work"
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(src, work)
    (work / "layout.protobuf").write_bytes(new_layout)
    for f in images_dir.glob("*.png"):
        shutil.copy(f, work / "images" / f.name)
    # face.bin has to grow a directory entry per new image before the packer will see them.
    new_table = bytearray(table)
    new_blob = bytearray(blob)
    for name in sorted(imgs):
        rid = int(ids[name])
        im = imgs[name]
        # A placeholder the packer will overwrite from the PNG; only its size has to be right.
        body = struct.pack("<HHHH", 0x2345, 0x8888, im.width, im.height)
        new_table += struct.pack("<II", len(new_blob), len(body))
        new_blob += body
    (work / "face.bin").write_bytes(
        struct.pack("<HHIII", ver, len(new_layout), len(new_table), len(new_blob), spare)
        + new_layout + bytes(new_table) + bytes(new_blob))
    print(f"  wrote the working tree to {work}")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
