#!/usr/bin/env python3
"""Build 相撲字時計 — a sumo-lettering kanji clock — out of MZ DIGICOLOR.

The band draws a face from fixed slots — two images for the hour, two for the minute, and further
slots for the month, the day and the weekday — each bound to one value and holding an image per
alternative. There is no text renderer and no font on the band, so a kanji clock has to arrive as
pictures. This paints them, in 勘亭流 — the brush lettering of sumo banners — and rewrites
MZ DIGICOLOR's layout to point at them.

Four lines and a footer: the hour, the minute, the second, the date, and a steps count with the
weekday beside it. The seconds were dropped once to make room for the date and came back when the
hour went from one full-width glyph to two half ones — a single glyph needs 239 px of row and a
pair needs 146, and the ninety pixels that frees is the seconds line (白い熊, 2026-09-01).

## The glyphs are painted, not knocked out

An earlier version was SUBTRACTIVE, because MZ DIGICOLOR is: a coloured slab of "all segments lit"
sits under the digits and each digit image is a dark mask covering the segments that should be off.
Drawing a kanji as a hole in that mask worked and got the skins' recolouring for free.

It also could not stack. Two masks over one slab show only where BOTH are transparent — their
intersection — so hour 11, drawn as 十 over 一, rendered as neither. Now every tile is painted
yellow on opaque black and the skins are scrubbed, which costs the recolouring and buys the only
thing that matters: a later tile genuinely REPLACES an earlier one.

## Sixty minutes out of five tiles

The minute line is FIXED-COLUMN: the tens word right-aligned in cell 1, the units word left-aligned
in cell 2, and nothing ever crosses the seam. :30 is 半 and :00 is 丁度 (白い熊, 2026-09-05).

    min 59   [    五十][九分    ]
    min 13   [      十][三分    ]
    min  5   [        ][五分    ]   cell 1 blank, so the reading sits half a character right
    min 10   [      十][分      ]
    min 30   [        ][半      ]   the whole reading is one glyph
    min  0   [        ][丁度    ]

**The earlier version let the units digit HOP into cell 1** whenever the tens digit was zero, which
put 五分 in the middle of the row rather than half a character right of it. The hop is also the
reason :00 could only ever read 〇分, and that is worth writing down because it looks like a missing
feature rather than an impossibility.

A tile is chosen by ONE value and covers what is under it, so the row is a decision list: at every
pixel, the topmost opaque tile wins. Ask for 丁度 while the digit still hops, and the column that
holds 分 at :05 must hold 度 at :00, 分 at :10 and 五 at :15 — dark, lit, lit, dark. That is an
exclusive-or of the two digits, and no decision list computes one, whatever it is given to work
with. Take the hop away and the column stops depending on both digits at once; 半 and 丁度 are then
ordinary alternatives of a tens-bound tile.

Five tiles, drawn in this order:

    cell 1                                    cell 2
    tens   三十                (drawn)        tens   丁度 / 分 / 半      (drawn)
    units  black at :x0        (covers)       units  <n>分 at :xn        (covers)
    tens   十/二十/四十/五十   (covers)
           blank at t=0 and t=3

Cell 1's middle tile is a black square rather than a glyph: its whole job is to take 三十 away at
:30 so 半 can stand alone. It fires at :00 as well — a units-bound tile cannot tell :00 from :30 —
and that costs nothing only because cell 1 is blank at :00 in this layout anyway. Under the old
centred one it held the 〇, which is the other half of why the hop had to go.

## The hour is two cells, and twelve is 〇時

一時 through 九時, then 十時, 十一時, 〇時 — two characters wide, in the same two cells the minutes
use. Cell two is 時 at every hour; cell one carries the numeral, and eleven takes a 十 in its corner
rather than a cell of its own.

**Twelve reads 〇時, and that is what shapes the whole hour.** The band gives the hour as two
bindings, so hours 1-9 share one tens image and 10/11/12 share the other, while hour 1 shares its
UNITS image with 11 and hour 2 with 12. Ten owns its units image alone. That leaves exactly one
bespoke glyph for the three of them — whatever the tens tile paints — and this face spends it on
twelve's 〇. Ten then takes its 十 back through its own units image, and eleven can take back only
hour ONE's, which is 一. So eleven is 一時 with a mark, not because a mark is prettier but because
the bindings allow nothing else (白い熊 chose it from the three-案 sheet, 2026-09-06).

    hour    cell 1                                        cell 2
    1-9     units 二…九, or the top tile's 一 at one       tens 時, opaque   ->  三時
    10      top tile 十, opaque                            units 時          ->  十時
    11      top tile 一 + the corner 十 showing through     units 時          ->  十一時
    12      tens 〇, opaque                                 units 時          ->  〇時

Four tiles in cell one, in this order: the numerals 二…九 underneath, the tens 〇 over them, the
mark, and a units tile on top holding 十 (ten), the punched 一 (one and eleven) and the knockout
(two and twelve).

**The mark's size is set by hour TWO, not by the corner.** It is tens-bound, so it is drawn at ten,
eleven and twelve alike. Ten wipes it with its own opaque 十. Twelve has to erase it, and the only
image that tells twelve from eleven is the units one at index 2 — which the band also draws over 二
at two o'clock. So the mark may cross 二's ink only where the 〇's ring is SOLID, because there the
ring paints the pixel itself and nothing needs erasing. That one rule is the whole ceiling: 48 px of
ink with the ring untouched, 52 with it 3 px heavier, and no more until +7 (白い熊 picked +3).

**Neither mask is a shape.** A hard-edged knockout leaves a ghost 十 on the ring at twelve — black
eating the ring's soft edge on one side, the mark's own edge hardening it on the other. Both are
alphas computed per pixel from what the tiles actually paint:

    knockout   k = 1 - C / ((1-m)*C + m*255)      restores the ring exactly
    一 tile    t = 1 - m*255 / ((1-m)*C + m*255)  leaves the mark exactly, on black

where C is what the 〇 tile paints there and m is the mark's alpha. Checked against a render with no
machinery in it: hours 1, 2, 10 and 11 come out bit-for-bit identical and twelve is off by 1 of 255.

**The ring is thickened INWARD** — drawn 3 px smaller and grown back — so it keeps its outer size.
Growing it where it stood pushed 20 px of it flat against the screen's left edge.

**The mark is built with STRAIGHT alpha**, yellow everywhere with alpha from the glyph. `render`
pastes ink through a mask, which darkens the RGB of its soft edge as well as its alpha; over black —
every other tile here — that is invisible, but over the 〇's ink it dulled the ring by a quarter
wherever the mark's edge crossed it.

**A previous version made the hour ONE full-width glyph**, with a small 十 in the top-left corner
for eleven and twelve, and its two layers transparent so the mark ADDED instead of covering. It read
beautifully at 242 px and it is recorded here because the trade is worth knowing rather than
rediscovering: a single glyph needs a 239 px row, a pair needs 146, and that difference is a whole
line. The seconds were worth more than the size.

The band counts 1-12, not 0-11. That was not assumed: the face showed 十 at 12:50, and the only way
to settle it was to stand the band's clock at noon with `huawei.time`. It said 十二.

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

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

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
# v3: the hour is TWO cells again, so its row needs only the height of one 143 px cell instead of
# 239 px for a single full-width glyph. That frees about ninety pixels, which is what buys the
# seconds line back (白い熊, 2026-09-01).
#
# Every line now sits at its WIDTH ceiling, so the leftover height is spacing rather than size:
# the hour caps at 143 px per glyph (two across 286), the minutes and seconds at 65 (two cells of
# two characters), the date at 40 (seven characters). Giving any of them more height changes
# nothing, which is why the remainder is spent on the gaps between them instead.
HOUR_Y, HOUR_H = 78, 146
GAP = 14
CELL_W = 143                      # two cells, side by side, spanning the whole 286
CELL1_X, CELL2_X = 0, 143
MIN_Y, MIN_H = HOUR_Y + HOUR_H + GAP, 69

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
SEC_Y, SEC_H = MIN_Y + MIN_H + GAP, 52     # deliberately below the minutes: a
                                           # seconds line that matches them competes
DATE_Y, DATE_H = SEC_Y + SEC_H + GAP, 46
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
REPERTOIRE = "〇一二三四五六七八九十時分秒"

# The date is measured in its OWN frame. Sharing the clock's would drag every hour and
# minute glyph down to fit 曜日 characters they never draw.
DATE_REP = "〇一二三四五六七八九十月日火水木金土"

# 丁度 and 半 get a frame of their own for the same reason, and it is worth the two lines because
# the numbers are so small they look like nothing. 度 overshoots the clock's frame by 7 px at the
# top and 半 by 4 at both ends, out of 282 — fold them in and EVERY hour, minute and second glyph
# shrinks by up to 4 % to make room for three characters those lines never draw. They can afford
# their own frame because nothing stands beside them: 半 IS the reading, and 丁度 shares a frame
# with itself. The lone 分 of :10 and :20 keeps the clock's frame, so it stays the same size as the
# 分 of :15 one minute later.
MIN_REP = REPERTOIRE + "丁度半"
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


# ── the hour's twelfth, and the machinery it takes ───────────────────────────────────────────────
#
# The mark is a 66 px tile at (9, 0) of cell one, which puts 52 x 51 px of ink above the left of the
# 一. Both numbers were searched, not chosen: it is the largest 十 whose knockout crosses 二's ink
# only where the ring is solid. See the hour section of this file's docstring for the rule.
HOUR_MARK_W, HOUR_MARK_XY, RING_FAT = 66, (9, 0), 3


def ink(ch, w, h, fat=0):
    """One glyph's ink with nothing behind it.

    `fat` gives it a heavier stroke at the SAME size: the glyph is drawn that many pixels smaller
    and then grown back. Growing it where it stands pushes it past the cell — 20 px of the 〇's ring
    came out flat against the screen's left edge that way (白い熊 spotted the cut, 2026-09-06).
    """
    g = render(ch, w, h, "centre", pad=fat, chars=1, solid=False, vpad=max(3, fat))
    if not fat:
        return g
    a = np.array(Image.fromarray(np.array(g)[:, :, 3]).filter(ImageFilter.MaxFilter(2 * fat + 1)))
    out = np.zeros((h, w, 4), np.uint8)
    out[:, :, 0], out[:, :, 1], out[:, :, 3] = INK[0], INK[1], a
    return Image.fromarray(out)


def opaque(img):
    """An ink image stood on the face's black, which is what an hour tile is."""
    t = Image.new("RGBA", img.size, (0, 0, 0, 255))
    t.alpha_composite(img)
    return t


def hour_mark():
    """The 十 that says eleven, with STRAIGHT alpha — yellow everywhere, alpha from the glyph.

    render() pastes its ink through a mask, so a soft pixel comes out with darkened RGB as well as
    reduced alpha. Over black that is the same picture; over the 〇's ink it is not, and it dulled
    the ring by a quarter wherever the mark's edge crossed it.
    """
    a = np.array(render("十", HOUR_MARK_W, HOUR_MARK_W, "centre", chars=1,
                        solid=False, vpad=2))[:, :, 3]
    o = np.zeros((HOUR_MARK_W, HOUR_MARK_W, 4), np.uint8)
    o[:, :, 0], o[:, :, 1], o[:, :, 3] = INK[0], INK[1], a
    return Image.fromarray(o)


def hour_masks():
    """The two tiles that leave the mark showing at eleven and nowhere else.

    Not shapes — alphas, computed per pixel from what the tiles actually paint, or the mark's soft
    edge and the ring's fight and leave a ghost 十 on the 〇 at twelve:

        knockout   k = 1 - C / ((1-m)*C + m*255)      restores the ring exactly
        一 tile    t = 1 - m*255 / ((1-m)*C + m*255)  leaves the mark exactly, on black

    C is what the 〇 tile paints at that pixel, m the mark's alpha. Where the ring is solid k is 0,
    which is what lets the mark cross 二 at all: nothing is erased there, so nothing is taken out of
    二 at two o'clock either.
    """
    w, (mx, my) = HOUR_MARK_W, HOUR_MARK_XY
    m = np.zeros((HOUR_H, CELL_W))
    m[my:my + w, mx:mx + w] = np.array(hour_mark())[:, :, 3] / 255.0
    C = np.array(opaque(ink("〇", CELL_W, HOUR_H, RING_FAT)))[:, :, 0].astype(np.float64)
    Cd = (1 - m) * C + m * 255.0
    ok = Cd > 0
    k = np.clip(np.where(ok, 1 - C / np.where(ok, Cd, 1), 0), 0, 1)
    t = np.clip(np.where(ok, 1 - m * 255.0 / np.where(ok, Cd, 1), 1), 0, 1)

    knock = Image.new("RGBA", (CELL_W, HOUR_H), (0, 0, 0, 0))
    knock.putalpha(Image.fromarray(np.round(k * 255).astype(np.uint8)))
    punched = np.array(opaque(ink("一", CELL_W, HOUR_H)))
    punched[:, :, 3] = np.round(np.minimum(punched[:, :, 3] / 255.0, t) * 255).astype(np.uint8)
    return knock, Image.fromarray(punched)


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

    # ── the hour: two cells, 一時 … 十時, 十一時, 〇時 ──
    #
    # Cell one is four tiles and cell two is two, and the order is the whole trick. See the hour
    # section of the module docstring for why twelve gets the tens tile and eleven gets a mark.
    #
    #     cell 1, bottom to top                        cell 2
    #     units  二…九            (drawn)              units  時   (drawn)
    #     tens   〇, opaque       (covers)             tens   時   (covers, hours one to nine)
    #     mark   十               (adds)
    #     units  十 / 一 / knockout                    ten, then one and eleven, then two and twelve
    hw = CELL_W
    knock, punched = hour_masks()
    for u in range(10):                                        # 二…九 underneath; one and eleven
        out[f"h1_units_{u}"] = render(DIG[u], hw, hh, "centre", pad=0, chars=1) if u >= 2 \
            else clear(hw, hh)                                 # come from the top tile instead
    out["h1_tens_0"] = clear(hw, hh)
    out["h1_tens_1"] = opaque(ink("〇", hw, hh, RING_FAT))     # twelve, and only twelve, keeps it
    out["h1_tens_2"] = clear(hw, hh)                           # 24-hour mode; the band never asks

    out["hmark_0"] = clear(HOUR_MARK_W, HOUR_MARK_W)
    out["hmark_1"] = hour_mark()                               # drawn at ten, eleven and twelve
    out["hmark_2"] = clear(HOUR_MARK_W, HOUR_MARK_W)

    out["h1_top_0"] = render("十", hw, hh, "centre", pad=0, chars=1)   # ten: also wipes the mark
    out["h1_top_1"] = punched                                          # one and eleven
    out["h1_top_2"] = knock                                            # two and twelve
    for u in range(3, 10):
        out[f"h1_top_{u}"] = clear(hw, hh)

    for u in range(3):                                         # cell two is 時 at every hour now
        out[f"h2_units_{u}"] = render("時", hw, hh, "centre", pad=0, chars=1)
    for u in range(3, 10):
        out[f"h2_units_{u}"] = clear(hw, hh)                    # always covered
    out["h2_tens_0"] = render("時", hw, hh, "centre", pad=0, chars=1)   # hours one to nine
    for t in (1, 2):
        out[f"h2_tens_{t}"] = clear(hw, hh)

    # ── minutes, cell 1: the tens word, and the blank that clears it at :30 ──
    #
    # Bottom: 三十, which only :31-:39 ever keep. Middle: an opaque black square at units zero —
    # the tile that takes 三十 away so 半 can stand alone. Top: the tens word for every other tens
    # digit, blank at zero, and CLEAR at three so the two tiles below it decide.
    for t in range(6):
        out[f"m1a_tens_{t}"] = render(TENS[3], mw, mh, "right", chars=2) if t == 3 \
            else clear(mw, mh)
    out["m1b_units_0"] = render("", mw, mh, "right", chars=2)         # opaque black, no glyph
    for u in range(1, 10):
        out[f"m1b_units_{u}"] = clear(mw, mh)
    for t in range(6):
        out[f"m1c_tens_{t}"] = clear(mw, mh) if t == 3 \
            else render(TENS[t], mw, mh, "right", chars=2)

    # ── minutes, cell 2: the units word, and the two readings that replace it ──
    #
    # The tens tile carries what a round ten reads as — 丁度 at zero, 半 at thirty, a lone 分
    # otherwise — and the units tile covers it with "<n>分" the moment the units digit is not zero.
    for t in range(6):
        special = {0: "丁度", 3: "半"}.get(t)
        out[f"m2a_tens_{t}"] = render(special or "分", mw, mh, "left", chars=2,
                                      rep=MIN_REP if special else None)
    out["m2b_units_0"] = clear(mw, mh)
    for u in range(1, 10):
        out[f"m2b_units_{u}"] = render(DIG[u] + "分", mw, mh, "left", chars=2)

    # The date row is retired by pointing every one of its images at nothing. Kept at the sizes
    # the elements actually had, rather than one shared 1x1, in case the band sizes anything
    # from the resource header rather than from the element.
    # ── seconds: the minutes' two-cell scheme again, ending in 秒 ──
    sw, sh = CELL_W, SEC_H
    for u in range(10):
        out[f"s1_units_{u}"] = render(DIG[u], sw, sh, "right", chars=2)
    out["s1_tens_0"] = clear(sw, sh)
    for t in range(1, 6):
        out[f"s1_tens_{t}"] = render(TENS[t], sw, sh, "right", chars=2)
    for u in range(10):
        out[f"s2_units_{u}"] = render(("" if u == 0 else DIG[u]) + "秒", sw, sh, "left", chars=2)
    out["s2_tens_0"] = render("秒", sw, sh, "left", chars=2)
    for t in range(1, 6):
        out[f"s2_tens_{t}"] = clear(sw, sh)

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
    8:  ("h1_units", HOUR_UNITS, (CELL1_X, HOUR_Y), 10),
    9:  ("h1_tens",  HOUR_TENS,  (CELL1_X, HOUR_Y), 3),
    10: ("h2_units", HOUR_UNITS, (CELL2_X, HOUR_Y), 10),
    11: ("h2_tens",  HOUR_TENS,  (CELL2_X, HOUR_Y), 3),
    0:  ("m1a_tens",  MIN_TENS,  (CELL1_X, MIN_Y), 6),
    1:  ("m1b_units", MIN_UNITS, (CELL1_X, MIN_Y), 10),
    2:  ("m1c_tens",  MIN_TENS,  (CELL1_X, MIN_Y), 6),
    3:  ("m2a_tens",  MIN_TENS,  (CELL2_X, MIN_Y), 6),
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
SEC_UNITS, SEC_TENS = 64, 63
MONTH, DAY_UNITS, DAY_TENS, WEEKDAY = 51, 71, 70, 52
NEW = [
    # Cell one of the hour needs FOUR tiles and the layer offers two, so the mark and the tile above
    # it are appended. Appended elements are drawn last, in this list's order, which is exactly the
    # order they need: the 〇 (element 9) first, then the mark over it, then the tile that shows the
    # mark at eleven and takes it away at ten and twelve.
    ("hmark",  HOUR_TENS,  (CELL1_X + HOUR_MARK_XY[0], HOUR_Y + HOUR_MARK_XY[1]), 3),
    ("h1_top", HOUR_UNITS, (CELL1_X, HOUR_Y), 10),
    # The minute line needs FIVE tiles and the layer only offers four in place, so cell 2's units
    # tile is appended. It has to be drawn after cell 2's tens tile, and appended elements come
    # last, which is exactly the order wanted.
    ("m2b_units", MIN_UNITS, (CELL2_X, MIN_Y), 10),
    ("s1_units", SEC_UNITS, (CELL1_X, SEC_Y), 10),
    ("s1_tens",  SEC_TENS,  (CELL1_X, SEC_Y), 6),
    ("s2_units", SEC_UNITS, (CELL2_X, SEC_Y), 10),
    ("s2_tens",  SEC_TENS,  (CELL2_X, SEC_Y), 6),
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
