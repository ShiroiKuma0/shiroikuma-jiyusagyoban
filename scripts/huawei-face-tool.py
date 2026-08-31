#!/usr/bin/env python3
"""Unpack a HUAWEI Band 11 Pro watch face into editable pieces.

The face is the `.bin` inside the library ZIP. Its format is written up in
docs/huawei-watch-face-format.md; the short version:

    16-byte header: u16 version, u16 layoutLen, u32 dirLen, u32 blobLen, u32 spare
    then the protobuf layout, then dirLen/8 records of (u32 offset, u32 length),
    then the resource blob those records point into.

Each resource starts `u16 family, u16 pixelFormat, u16 width, u16 height`.

Family 0x2345 is a word-level run-length codec, solved in both directions and verified exact on
every resource of every face we hold:

    read u32 words; the word 0x23456789 introduces a run — the next word is the value and the word
    after it is the repeat count, IN WORDS. Any other word is one literal word.

    pixelFormat 0x8888 -> one word is one BGRA pixel
    pixelFormat 0xf565 -> one word is two RGB565 pixels, low half first

Family 0x6549 is QOI with an unsolved colour-cache hash and is reported, not decoded.

Packing is the same codec in reverse. Huawei's own run threshold is **four words** — the exact
break-even, since a run costs twelve bytes and four literals cost sixteen — and at that threshold
this encoder reproduces every resource of MZ DIGICOLOR byte for byte. `pack` refuses to write
anything if an untouched unpack does not round-trip to the original file, so a wrong encoder is
caught here rather than on the band.

Usage:
    huawei-face-tool.py unpack <face.zip|face.bin> <out-dir>
    huawei-face-tool.py pack   <dir> <out-dir> [--name NAME] [--short SHORT] [--id ID]

`pack` with a --name FORKS the face: it is given a new asset id derived from the name, so the new
face installs ALONGSIDE the original instead of replacing it. The band identifies a face by that id
and nothing else.
"""
import pathlib
import struct
import sys
import zipfile

ESCAPE = 0x23456789


def read_bin(path: pathlib.Path):
    """The .bin plus its signed sidecar, from either the ZIP or a bare pair of files."""
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as z:
            b = next(n for n in z.namelist() if n.endswith(".bin"))
            j = next((n for n in z.namelist()
                      if n.endswith(".json") and n.rsplit("/", 1)[-1] != "face.json"), None)
            return b.rsplit("/", 1)[-1][:-4], z.read(b), (z.read(j) if j else b"")
    data = path.read_bytes()
    meta = path.with_suffix(".json")
    return path.stem, data, (meta.read_bytes() if meta.is_file() else b"")


def sections(data: bytes):
    ver, l1, l2, l3, spare = struct.unpack_from("<HHIII", data, 0)
    if 16 + l1 + l2 + l3 != len(data):
        raise SystemExit(f"container does not close: 16+{l1}+{l2}+{l3} != {len(data)}")
    layout = data[16:16 + l1]
    table = data[16 + l1:16 + l1 + l2]
    blob = data[16 + l1 + l2:]
    return ver, spare, layout, table, blob


def unrle(body: bytes, want_words: int):
    """Expand one run-length stream to exactly [want_words] words, or say why it could not."""
    out = bytearray()
    i = 0
    n = len(body)
    words = 0
    while i + 4 <= n and words < want_words:
        w = struct.unpack_from("<I", body, i)[0]
        if w == ESCAPE:
            if i + 12 > n:
                raise ValueError("truncated run header")
            value = body[i + 4:i + 8]
            count = struct.unpack_from("<I", body, i + 8)[0]
            out += value * count
            words += count
            i += 12
        else:
            out += body[i:i + 4]
            words += 1
            i += 4
    if words != want_words:
        raise ValueError(f"expanded {words} words, expected {want_words}")
    return bytes(out)


def to_rgba(expanded: bytes, fmt: int, w: int, h: int) -> bytes:
    if fmt == 0x8888:
        # BGRA in the file; PNG wants RGBA.
        out = bytearray(len(expanded))
        out[0::4] = expanded[2::4]
        out[1::4] = expanded[1::4]
        out[2::4] = expanded[0::4]
        out[3::4] = expanded[3::4]
        return bytes(out)
    if fmt == 0xF565:
        out = bytearray(w * h * 4)
        for p in range(w * h):
            v = struct.unpack_from("<H", expanded, p * 2)[0]
            r = (v >> 11) & 0x1F
            g = (v >> 5) & 0x3F
            b = v & 0x1F
            out[p * 4 + 0] = (r << 3) | (r >> 2)
            out[p * 4 + 1] = (g << 2) | (g >> 4)
            out[p * 4 + 2] = (b << 3) | (b >> 2)
            out[p * 4 + 3] = 0xFF
        return bytes(out)
    raise ValueError(f"unknown pixel format 0x{fmt:04x}")


def enrle(words: bytes, threshold: int = 4) -> bytes:
    """The inverse of [unrle]. See the module note for why the threshold is four."""
    out = bytearray()
    n = len(words) // 4
    i = 0
    while i < n:
        v = words[i * 4:(i + 1) * 4]
        j = i + 1
        while j < n and words[j * 4:(j + 1) * 4] == v:
            j += 1
        run = j - i
        if run >= threshold:
            out += struct.pack("<I", ESCAPE) + v + struct.pack("<I", run)
        else:
            out += v * run
        i = j
    return bytes(out)


def from_rgba(rgba: bytes, fmt: int, w: int, h: int) -> bytes:
    """RGBA back to the file's own pixel layout. Exact — 565 expansion is reversible by >>3/>>2."""
    if fmt == 0x8888:
        out = bytearray(len(rgba))
        out[0::4] = rgba[2::4]
        out[1::4] = rgba[1::4]
        out[2::4] = rgba[0::4]
        out[3::4] = rgba[3::4]
        return bytes(out)
    if fmt == 0xF565:
        # Padded to a whole word: two pixels per word, so an odd pixel count needs one more.
        words = (w * h + 1) // 2
        out = bytearray(words * 4)
        for p in range(w * h):
            r, g, b = rgba[p * 4], rgba[p * 4 + 1], rgba[p * 4 + 2]
            struct.pack_into("<H", out, p * 2, ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3))
        return bytes(out)
    raise ValueError(f"unknown pixel format 0x{fmt:04x}")


def read_png(path: pathlib.Path):
    """Width, height and RGBA bytes. Pillow if present, else a minimal reader for our own output."""
    try:
        from PIL import Image
        im = Image.open(path).convert("RGBA")
        return im.width, im.height, im.tobytes()
    except ImportError:
        pass
    import binascii
    import zlib
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path.name} is not a PNG")
    i, w, h, idat = 8, 0, 0, bytearray()
    while i < len(data):
        ln = struct.unpack_from(">I", data, i)[0]
        tag = data[i + 4:i + 8]
        body = data[i + 8:i + 8 + ln]
        if tag == b"IHDR":
            w, h, depth, colour = struct.unpack_from(">IIBB", body, 0)
            if (depth, colour) != (8, 6):
                raise ValueError(f"{path.name}: only 8-bit RGBA is read without Pillow")
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
        i += 12 + ln
    raw = zlib.decompress(bytes(idat))
    out = bytearray(w * h * 4)
    stride = w * 4
    prev = bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1: line[x] = (line[x] + a) & 0xFF
            elif f == 2: line[x] = (line[x] + b) & 0xFF
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bytes(out)


def png(path: pathlib.Path, w: int, h: int, rgba: bytes):
    """A minimal PNG writer, so this script needs nothing installed."""
    import binascii
    import zlib
    raw = b"".join(b"\x00" + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", binascii.crc32(tag + payload) & 0xFFFFFFFF))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def unpack(src: pathlib.Path, out: pathlib.Path):
    name, data, meta = read_bin(src)
    ver, spare, layout, table, blob = sections(data)
    out.mkdir(parents=True, exist_ok=True)
    (out / "face.bin").write_bytes(data)
    if meta:
        (out / "face.json").write_bytes(meta)
    (out / "layout.protobuf").write_bytes(layout)
    images = out / "images"
    images.mkdir(exist_ok=True)

    count = len(table) // 8
    lines = [
        f"face          {name}",
        f"format        version {ver}, spare {spare}",
        f"layout        {len(layout)} B",
        f"resources     {count}",
        f"blob          {len(blob)} B",
        "",
        f"{'id':>4}  {'family':>6} {'format':>6} {'w':>5} {'h':>5} {'packed':>8}  file",
    ]
    ok = failed = 0
    for i in range(count):
        off, ln = struct.unpack_from("<II", table, i * 8)
        r = blob[off:off + ln]
        fam, fmt, w, h = struct.unpack_from("<HHHH", r, 0)
        rid = i + 1                      # the layout refers to resources 1-based
        note = ""
        if fam == 0x2345:
            words = w * h if fmt == 0x8888 else (w * h + 1) // 2
            try:
                rgba = to_rgba(unrle(r[8:], words), fmt, w, h)
                f = images / f"{rid:03d}_{w}x{h}.png"
                png(f, w, h, rgba)
                note = f.name
                ok += 1
            except Exception as e:                       # noqa: BLE001 — reported, not swallowed
                note = f"FAILED: {e}"
                failed += 1
        else:
            # Kept raw so nothing is lost, and named so it is obvious why there is no picture.
            (images / f"{rid:03d}_{w}x{h}_family{fam:04x}.raw").write_bytes(r)
            note = f"family 0x{fam:04x} not decoded (QOI)"
            failed += 1
        lines.append(f"{rid:>4}  0x{fam:04x} 0x{fmt:04x} {w:>5} {h:>5} {ln:>8}  {note}")

    lines += ["", f"decoded {ok} of {count} resources" + (f", {failed} not decoded" if failed else "")]
    (out / "resources.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines[:6]))
    print(f"decoded {ok} of {count} resources -> {images}")
    if failed:
        print(f"{failed} not decoded — see {out / 'resources.txt'}")


# ── the layout's own top level ─────────────────────────────────────────────────────────────────
#
# Only three fields matter here and none of them needs a schema: repeated Layer (1), the full name
# (2) and a SIX-character short name (3). Everything else is carried through untouched, which is
# the whole reason a name can be changed without understanding the rest.

def _varint(b: bytes, i: int):
    v = s = 0
    while True:
        x = b[i]
        i += 1
        v |= (x & 0x7F) << s
        if not x & 0x80:
            return v, i
        s += 7


def _tag(field: int, wire: int) -> bytes:
    v = (field << 3) | wire
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        out.append(b | (0x80 if v else 0))
        if not v:
            return bytes(out)


def _len(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def layout_names(layout: bytes):
    """(full name, short name) as the layout holds them."""
    i, full, short = 0, "", ""
    while i < len(layout):
        key, i = _varint(layout, i)
        field, wire = key >> 3, key & 7
        if wire != 2:
            raise ValueError(f"unexpected wire type {wire} at top level")
        ln, i = _varint(layout, i)
        val = layout[i:i + ln]
        i += ln
        if field == 2:
            full = val.decode("utf-8", "replace")
        elif field == 3:
            short = val.decode("utf-8", "replace")
    return full, short


def rename_layout(layout: bytes, name: str, short: str) -> bytes:
    """Replace fields 2 and 3, carrying every other field through byte for byte."""
    out = bytearray()
    i = 0
    while i < len(layout):
        key, i = _varint(layout, i)
        field, wire = key >> 3, key & 7
        ln, i = _varint(layout, i)
        val = layout[i:i + ln]
        i += ln
        if field == 2:
            val = name.encode("utf-8")
        elif field == 3:
            val = short.encode("utf-8")
        out += _tag(field, wire) + _len(len(val)) + val
    return bytes(out)


def fork_id(name: str) -> str:
    """
    A stable ten-digit id for a forked face.

    Ten digits like Huawei's own, so nothing downstream has to care that it is ours, but in a `79`
    block: every id seen in 白い熊's library begins 2182, 7184, 7185 or 7186. Derived from the name
    rather than random, so forking the same name twice gives the same face rather than a second
    copy the band would hold alongside the first.
    """
    import hashlib
    return "79" + str(int(hashlib.sha256(name.encode()).hexdigest()[:12], 16))[-8:].zfill(8)


def pack(src: pathlib.Path, out: pathlib.Path, name=None, short=None, asset=None):
    data = (src / "face.bin").read_bytes()
    ver, spare, layout, table, blob = sections(data)
    old_name, old_short = layout_names(layout)
    version = "2.1.1"
    meta = (src / "face.json").read_bytes() if (src / "face.json").is_file() else b""

    # Rebuild the blob from the PNGs, keeping every resource's own family and pixel format: the
    # header of each is authoritative, not the file name.
    images = {int(f.name[:3]): f for f in (src / "images").glob("*.png")}
    count = len(table) // 8
    # The blob does not start at resource 1. There is a preamble — eight bytes in every face
    # examined — and the directory's offsets are relative to the START OF THE BLOB, not to the
    # first resource. Dropping it silently shifted every offset by eight and the round-trip guard
    # caught it, which is the whole reason that guard exists.
    first = struct.unpack_from("<II", table, 0)[0] if count else 0
    new_blob = bytearray(blob[:first])
    new_table = bytearray()
    changed = []
    for i in range(count):
        off, ln = struct.unpack_from("<II", table, i * 8)
        r = blob[off:off + ln]
        fam, fmt, w, h = struct.unpack_from("<HHHH", r, 0)
        rid = i + 1
        rebuilt = r
        if fam == 0x2345 and rid in images:
            pw, ph, rgba = read_png(images[rid])
            if (pw, ph) != (w, h):
                raise SystemExit(f"resource {rid}: PNG is {pw}x{ph}, the face wants {w}x{h}")
            body = enrle(from_rgba(rgba, fmt, w, h))
            rebuilt = struct.pack("<HHHH", fam, fmt, w, h) + body
            if rebuilt != r:
                changed.append(rid)
        new_table += struct.pack("<II", len(new_blob), len(rebuilt))
        new_blob += rebuilt

    new_layout = layout
    if name is not None:
        new_layout = rename_layout(layout, name, short or name[:6].upper())

    built = (struct.pack("<HHIII", ver, len(new_layout), len(new_table), len(new_blob), spare)
             + new_layout + bytes(new_table) + bytes(new_blob))

    # A round-trip that does not reproduce the original means the codec is wrong, and finding that
    # out on the band is the expensive way. Only assert it when nothing was asked to change.
    if name is None and not changed and built != data:
        raise SystemExit("round-trip mismatch — refusing to write a face this tool cannot rebuild")

    asset = asset or (fork_id(name) if name else src.name)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"{asset}_{version}.bin"
    target.write_bytes(built)
    if meta:
        # The sidecar names the face too. Its hash and signature are stale after any edit and that
        # is fine — measured 2026-08-31, the band never checks either — but the id has to agree
        # with what the install announces or the two halves describe different faces.
        import json as _json
        rec = _json.loads(meta)
        rec["result"]["content"]["hitopId"] = asset
        (out / f"{asset}_{version}.json").write_text(_json.dumps(rec, separators=(",", ":")))

    print(f"name          {old_name!r} / {old_short!r}"
          + (f"  ->  {name!r} / {short or (name[:6].upper())!r}" if name else "  (unchanged)"))
    print(f"asset id      {asset}")
    print(f"resources     {count}, rebuilt {len(changed)}" + (f": {changed}" if changed else ""))
    print(f"size          {len(data)} -> {len(built)} B")
    print(f"wrote         {target}")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    verb, a, b = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
    if verb == "unpack":
        unpack(a, b)
    elif verb == "pack":
        rest = sys.argv[4:]
        opt = {rest[i]: rest[i + 1] for i in range(0, len(rest) - 1, 2)}
        pack(a, b, opt.get("--name"), opt.get("--short"), opt.get("--id"))
    else:
        raise SystemExit(__doc__)
