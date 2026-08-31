# The HUAWEI Band 11 Pro watch-face format

Everything established about the face package, and the one experiment that decided whether any of it
is useful. Written 2026-08-31 from three independent analyses of the 45 faces in 白い熊's library
plus a measurement on the band itself.

**The headline: an edited face installs.** The band does not verify the digest Huawei signed. That
was the single question the whole thing hung on, it could not be settled by reading, and it is now
settled by experiment.

## 1. The signature is not enforced — measured, not inferred

A face ships with a sidecar JSON that we send in the install announcement. It contains
`watchContentHash`, which **is** the SHA-256 of the `.bin` (verified on all 45, zero exceptions),
inside a blob signed with a 384-byte RSA-3072 `contentSign` we cannot forge. So Huawei's signature
does commit to the exact bytes. The question was only ever whether the *band* checks it.

The test: `earth` (`7184431733_2.1.1`), one byte flipped deep inside the resource blob, size
unchanged so nothing in the container is recomputed. The SHA-256 is the only difference between the
two files, and the sidecar was sent untouched.

| | bytes moved | band's answer |
| --- | --- | --- |
| pristine (control) | 456 303 B in 62 blocks | `wf/3 r=0` — installed |
| one byte changed | 456 303 B in 62 blocks | `wf/3 r=0` — installed |

The band asked for every byte of a file whose digest does not match the signed record, and kept it.

**The control is not optional.** A full band and a rejected digest were expected to look identical,
and the first two attempts at this test failed for reasons that had nothing to do with signatures —
see §6. Never run this class of test without a known-good install immediately beside it.

## 2. The container

The `.zip` in the library is our own capture wrapper: `<assetId>_<version>.bin`, the same name
`.json` (the signed sidecar), `preview.png`, `face.json`. The face itself is the `.bin`:

```
0   u16 LE   format version           (2 in all 45)
2   u16 LE   L1 = layout length
4   u32 LE   L2 = resource directory length
8   u32 LE   L3 = resource blob length
12  u32 LE   0                        (spare, 0 in all 45)
```

`16 + L1 + L2 + L3 == filesize` in **all 45 files**. No trailer, no padding, no checksum, nothing
encrypted or obfuscated. The directory is `L2/8` records of `u32 offset, u32 length`, contiguous and
exactly covering the blob.

Corroboration that this is the real format and not a coincidence: a 2022 third-party authoring
bundle (`.hwt`, `<author>Gumix</author>`) carries a `com.huawei.watchface` payload that parses
byte-for-byte as this container — and ships no signature file of any kind.

## 3. The layout

Section 1 is **protobuf**. Top level: repeated `Layer` (field 1), English name (2), store name (3).
Layer kinds `0/1/2/3` = background / clock / date row / interactive.

Eight element types are decoded: **TEXT, IMAGE, bitmap NUMBER, DIGIT GROUP, rotating HAND,
value-bound IMAGE, ARC, TEXT2**.

An element's payload is **the highest-numbered bytes field it has**. An earlier version of this
document said `type+3`, which matched every type in the survey — type 0 in field 3, type 2 in
field 5, type 7 in field 10 — and is still WRONG: MZ DIGICOLOR's battery is type 10 with its payload
in field **11**. The rule cost a build. Reading `type+1` moved the battery correctly and silently
left the weather text and its icon behind, because the loop simply never matched their field and had
nothing to say about it (白い熊, 2026-08-31). Take the maximum; do not compute it from the type.

- Colours are literal RGBA quads.
- Rectangles are **signed** — a negative reads as `18446744073709551608` if taken unsigned.
- The coordinate space is **286 × 482**; the picker thumbnail is **172 × 290**.
- Every AOD-visible element is stored **twice**, distinguished by a flag.
- Image references are **1-based strings**: `"00k"` means directory index `k − 1`. The maximum id
  equals the resource count in every face.

The layout round-trips losslessly: re-encoding all 45 reproduces them byte-identically.

## 4. The image codecs

Each resource has an 8-byte header: `u16 family, u16 pixel format, u16 width, u16 height`.

**Family `0x2345` — 21 of the 45 faces. Solved both directions.** Dword run-length: escape word
`0x23456789`, then a 4-byte value and a `u32` count *in words*. `0x8888` = BGRA (1 px/word),
`0xf565` = RGB565 (2 px/word). All **1394** resources decode exactly, and the re-encoder reproduces
all 1394 streams byte-for-byte — it is Huawei's own encoder, not an approximation.

**Family `0x6549` — the other 24 faces. It is QOI.** Op layout proved by byte accounting (1371 of
1371 exact) and by DIFF/LUMA histograms peaking exactly at QOI's ±1 single-channel and small-green
patterns. Rows are stride-padded to a multiple of 16 px. The `0xf565` variant is 16 bpp with a
3-byte `0x80` op and no literals (101 of 101 exact).

**Unsolved:** the 64-entry colour cache addressing. Stock QOI's hash is provably wrong here; a
65 536-tuple coefficient search and five alternative cache policies all failed. Probably avoidable —
the family is per *resource*, so an edited image can be written back as `0x2345` inside a QOI face.

No face mixes the two families.

## 5. Tap targets

There is **no "launch package X" string** anywhere: 177 distinct strings across all 45 faces, all
resource ids and names. Tap behaviour is not free-form and never will be.

But layer 3 holds `Widget` records, and a widget is a **hit region with a destination**:
`Widget.bounds` is where you press, `Widget.source` is an enum — 10 steps, 11 heart rate, 12
calories, 17 battery, 21 weather, 24 distance, 25 sleep, 28 alarm, 29 stopwatch, 36 music, among
others.

The evidence: 17 widgets draw nothing at all (pure hit regions); 104 have bounds more than twice
their largest visible element; `ALLDAY`'s three 98 × 42 corner boxes sit over labels painted into
the *background bitmap* rather than over elements. The negative control holds — `CNRbnd08` and
`MZ316` have **no layer 3 whatsoever** and show only time and date.

So moving a tap region, resizing it, or repointing it at a different built-in destination is an edit
to two protobuf fields.

## 6. What the band does at install time

- The announcement is `0x27/0x03` with asset id, version, `tag3=01`, screen size, and the signed
  store record as tag 8. Then `0x28/0x02` offers name/size/fileId, and `0x28/0x03` carries a SHA-256
  **we compute over the bytes we hold** — so an edited file simply declares its own and passes.
- Per-frame CRC16 is transport-level and also ours. Per-block: nothing.
- `tag3=02` **deletes**. There is no activate command; installing IS activating.
- **`wf/3 r=100007` is the refusal code.** Before 2026-08-31 every refusal was silence, which is why
  "the band is full" had to be inferred from a transfer that engaged and then moved no bytes.

### The same-session eviction trap

**The band will not accept an install in the same session as a delete.** Measured repeatedly: the
delete succeeds, the band reports the face gone and the slot free — eleven faces, its own figure
saying eighty-four free — and the install is refused with `100007`. The identical install over a
**fresh connection** succeeds immediately.

This is why `HuaweiSyncRunner.uploadWatchFace` now performs the eviction in its own session. The bug
survived because its symptom is byte-identical to the full band it was written to work around.

## 7. Building a face

Everything above is how the format is READ. This section is how one is written, and all of it comes
from building 相撲字時計 — a kanji clock in sumo brush lettering — out of MZ DIGICOLOR on
2026-08-31. The generator is `scripts/sumoji-build.py`, which is worth reading as the worked
example; this is the part that generalises.

### 7.1 What a value-bound element looks like

    element   1: index    2: type (2 = image list)    5: body
    body      1: image id, repeated once per value    2: {1:x, 2:y}    3: binding    6: flag

The band picks the image whose position in the repeated list matches the current value. So an
element is a slot, and the images are its alternatives.

**The bindings, as decoded from the library:**

| id | value | images | index base |
| --- | --- | --- | --- |
| 51 | month | 13 | **1-based** — index 1 is January |
| 52 | weekday | 8 | **1-based** — index 1 is Monday |
| 53 | weather | — | |
| 59 / 60 | hour tens / units | 3 / 10 | 0-based |
| 61 / 62 | minute tens / units | 6 / 10 | 0-based |
| 63 / 64 | second tens / units | 6 / 10 | 0-based |
| 70 / 71 | day-of-month tens / units | 4 / 10 | 0-based |

Month and weekday carry one image MORE than they have values and the band never asks for index 0.
Which way round they run is settled by the original artwork, not by guessing: in MZ DIGICOLOR
resource 033 is JAN and 055 is MON, and both lists repeat their first entry.

**The hour runs 1–12, not 0–11.** Do not assume this from a locale. It was settled by moving the
band's own clock to noon with the `huawei.time` action and reading the face back: 十二.

### 7.2 Draw order is the only way to express a value two digits wide

The band draws elements **in the order they appear in the layer**, and a tile either covers what is
under it or adds to it, depending only on whether its pixels are opaque:

- **opaque tile → replaces.** The later element wins outright.
- **fully transparent tile → adds.** Both stay visible.

This matters because a glyph that depends on BOTH digits cannot come from either binding alone.
Japanese numerals need exactly that: 30 minutes is 三十分, 13 is 十三分, 3 is 三分. The answer is two
elements at the SAME position, the units-bound one first and the tens-bound one over it, which makes
the tens digit an override:

    cell 1                          cell 2
    units: 〇一二…九   (drawn)      units: <n>分       (drawn)
    tens:  十/二十/三十 (covers)     tens:  分 alone    (covers, at tens = 0 only)

Right-align cell 1 and left-align cell 2 and the parts always meet at the seam, whichever are
present. The same structure builds the day: 一日, 十日, 二十日, 三十一日.

**Knockouts do NOT stack**, and this is the trap. Drawing the glyph as a hole in a dark mask is the
natural reading of a face like MZ DIGICOLOR, whose skin is a colour slab under a mask — but two
masks over one slab show only where BOTH are transparent, so 十 over 一 renders as their
intersection, which is nothing. Paint the glyph instead and scrub the slab.

The hour of 相撲字時計 uses the opposite choice deliberately: both its layers are transparent, so a
small 十 in the corner ADDS to a full-size units glyph rather than covering it. That is how eleven
and twelve fit in a row that shows one big character.

### 7.3 Elements can be appended

A face is not limited to the elements it shipped with. New ones can be added to a layer with any
binding, position and image count, and the band honours them: 相撲字時計's date row is six appended
elements. They were appended to the CLOCK layer even though the face HAS a date layer, because that
layer carries two complete sets of the four date elements distinguished by body field 6, whose
meaning is not known — rather than guess which set is drawn, the whole layer is blanked and the row
rebuilt beside the clock, where appending is already proved to work.

### 7.4 Readouts — steps, battery, and why copying beats adapting

A live number is a TEXT element inside a layer-3 widget, and **the element TYPE is part of the
contract**. Steps is a type-0 text element whose value selector is field 5 = 0. Setting that same
selector on this face's battery element — type 10, payload in field 11 — changed nothing at all and
went on reading 76%. Copy a whole element from a face that already shows what you want; do not adapt
one that shows something else.

Inside such an element: field 1 rect, field 2 RGBA, field 3 font id (**132 is the largest, 46 px**),
field 4 alignment (1 centres), field 5 the value selector.

**The band draws that number, so its width cannot be measured from here — only pinned.** A centred
readout grows outwards from the middle of its rect as the value gets longer, so anything placed
beside it must either keep clear of the widest plausible value or, better, narrow the rect until the
number cannot reach it. 相撲字時計 puts the weekday at x 200 and narrows the steps rect to 236 px
for exactly this reason. A preview cannot check it for you: ours substitutes a system font at the
right pixel size, so its positions are exact and its text widths are an approximation. The widget around it carries field 8, the
source enum from §5 — 10 steps, 17 battery.

Bitmap digits are a dead end for anything but a clock: 68/69 read `1,0` at two different step
counts, a seven-binding probe returned `0221031`, and 70/71 are day-of-month across 43 faces. There
is no steps-digit binding.

### 7.5 The container, when the image count changes

Adding images means `face.bin`'s directory grows one `(offset, length)` entry per new resource
before the packer can see them. Two things bite here:

- **The blob has a preamble** — eight bytes in every face examined — and directory offsets are
  relative to the START OF THE BLOB, not to the first resource. Dropping it shifts every offset by
  eight and produces a file the band accepts and draws as garbage.
- **`scripts/huawei-face-tool.py pack` refuses to write** unless an untouched unpack of the source
  reproduces the original byte for byte. That guard is what caught the preamble. Keep it.

### 7.6 The loop

    python3 scripts/sumoji-build.py ~/tmp/mz-digicolor ~/tmp/sumoji-work    # or your own generator
    python3 scripts/huawei-face-tool.py pack ~/tmp/sumoji-work/work <out> \
        --name <face name> --short <6 chars> --id <asset id>
    python3 scripts/sumoji-preview.py ~/tmp/look.png                        # before spending a upload

`sumoji-preview.py` composites the tiles the way the band does and turns a change from a
three-minute push-install-photograph cycle into a glance. Two rules make it honest rather than
reassuring: draw elements in the SAME ORDER the band does, and give each element its OWN x. Its
first version shared the minute line's cells with the date and drew the month last, which hid the
day's tens digit — a bug in the preview that would have read as a bug in the face.

Then install with the `huawei.watchface` action, remembering §6: a delete and an install cannot
share one session, and installing IS activating.

### 7.7 A new face, or a replacement?

`huawei-face-tool.py pack --id` forks the asset id, which is what makes a new face rather than an
edit of the original. Two builds sharing an id replace each other on the band; that is usually what
is wanted while iterating, and the reason to keep every version as its own ZIP in the library.

## 8. What is editable today

| | state |
| --- | --- |
| Layout: names, colours, geometry, which complication sits where | ready |
| Tap regions and their destinations (§5) | ready |
| Artwork on the 21 `0x2345` faces | ready — encoder is byte-exact |
| Artwork on the 24 QOI faces | needs the cache hash, or write the resource back as `0x2345` |
| A new clock built on an existing face's skeleton (§7) | done — 相撲字時計 |

The remaining risk is not cryptographic. It is that a face is a running program on a device we
cannot debug: a malformed layout is refused by the band or renders wrongly, and the only way to know
is to install it. Every face in the library has a pristine copy on disk, so that is recoverable.

## 9. Reproducing any of this

The 45 packages are at `.scratch/faces/*.zip` (gitignored — they are Huawei's bytes). Analysis
scripts and the decoders are under `.scratch/faceres/` and `.scratch/facesurvey/`.

**The long-form notes are NOT in this repository and not in `.scratch` either any more.** They quote
captured packets, which carry a hash of the Huawei account and the band's device id, and this repo
is public — so `face-format-notes.md`, `face-tooling-survey.md` and `face-install-constraints.md`
were moved on 2026-08-31 to `face-format-notes/` inside the private face library at
`~/〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro/`, beside the face
ZIPs and under the same rule. `.scratch` is not backed up; that directory is.

Everything in THIS document is free of identifiers, which is why it can carry the findings while
the workings live elsewhere.

Licence hygiene: Gadgetbridge (AGPL-3.0) was read for facts only and no code was copied.
`zhy8388608/Huawei_Watchface_Unpacker` carries no licence at all — field *names* were used as a
cross-check, nothing else.
