#!/usr/bin/env python3
"""Recover the satellite assistance files Huawei Health serves the band, from a btsnoop capture.

We generate the band's predicted ephemeris ourselves, and it works — a fix in about 20 s when the set
is alive, against ~580 s with none. What we have never had is a WORKING REFERENCE to check our output
against. Every validation so far has been our generator against its own decoder, or against the orbit
product we fed it; never against a file known to produce a fast fix. That is how a byte we never wrote
(offset 77, constant 0xFF in all 1044 of Huawei's GPS records) went unnoticed for a day, and it is
why guesses about what else differs cannot be settled by argument (白い熊, 2026-08-29).

This turns a capture of Health doing **Workout → Workout settings → Location services → Satellite
updates → Update** into:

    HW_PGNSS_GPS, _GALILEO, _GLONASS, _BDS, _QZS, _EXTRA, HW_AGNSS_RTCM_33   the files themselves
    transcript.txt                                                          the whole exchange

The transcript matters as much as the files. The band drives this protocol — it asks, it picks, it
requests each block — so what Health does differently from us is visible only in the ORDER and the
answers, not in the payloads.

Usage:  huawei-extract-gnss.py <btsnoop.log> [more.log …] [outdir]

Several logs merge, exactly as for watch faces: a capture routinely loses a frame even when the phone
reports no drops, and two captures of the same transfer fill each other's holes. The capture must
include the session's HiChain handshake, since the control frames are encrypted and the key is
derived from it — so start capturing before the band connects, not just around the update.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / ".scratch/hw"))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / ".scratch/hw/_stub"))

import btsnoop as B  # noqa: E402  (path set above)
import huawei_crypto as hc  # noqa: E402
from phase0 import tlv_parse, unframe  # noqa: E402

# The two services, named as our own client names them (HuaweiCommands.kt:89-100).
SVC_ASK, SVC_FILES = 0x1F, 0x1C
ASK_NOTIFY, ASK_WHAT, ASK_READY = 0x01, 0x02, 0x03
F_LIST, F_PARAMS, F_PICK, F_BLOCK, F_DATA, F_DONE = 0x01, 0x02, 0x03, 0x04, 0x05, 0x06

CMD_NAMES = {
    (SVC_ASK, ASK_NOTIFY): "ASK/notify", (SVC_ASK, ASK_WHAT): "ASK/what",
    (SVC_ASK, ASK_READY): "ASK/ready",
    (SVC_FILES, F_LIST): "FILES/list", (SVC_FILES, F_PARAMS): "FILES/params",
    (SVC_FILES, F_PICK): "FILES/pick", (SVC_FILES, F_BLOCK): "FILES/block",
    (SVC_FILES, F_DATA): "FILES/data", (SVC_FILES, F_DONE): "FILES/done",
}


def frames(path):
    """Every frame in capture order, fragments reassembled, with the recovered keys.

    Lifted wholesale from huawei-extract-watchface.py: the fragmentation and key-recovery layers are
    service-agnostic, and the announcement is not the only thing that exceeds the 1022-byte limit.
    """
    streams, marks = B.acl_streams(path)
    ev = []
    for d, st in streams.items():
        for pos, f in B.scan_frames(bytes(st)):
            ev.append((B.stamp_for(marks[d], pos), d, f))
    ev.sort(key=lambda e: e[0])

    rec, raw = B.KeyRecovery(), []
    for ts, d, f in ev:
        try:
            sid, cid, payload, _ok = unframe(f)
            rec.feed(d, sid, cid, dict(tlv_parse(payload)), payload)
        except Exception:
            pass
        raw.append((ts, d, f))
    rec.finish()

    out, pend = [], None
    for ts, d, f in raw:
        slice_ = f[3]
        if slice_ == 0:
            svc, cmd, body = f[4], f[5], f[6:-2]
        elif slice_ == 1:
            pend = {"svc": f[5], "cmd": f[6], "buf": bytearray(f[7:-2])}
            continue
        elif slice_ in (2, 3) and pend:
            pend["buf"] += f[5:-2]
            if slice_ != 3:
                continue
            svc, cmd, body = pend["svc"], pend["cmd"], bytes(pend["buf"])
            pend = None
        else:
            continue
        out.append((ts, d, svc, cmd, bytes(body)))
    return out, rec


def decrypt(body, rec):
    """Decrypt an envelope, or hand back a raw payload untouched.

    Structural test, never "does it parse" — GNSS data frames are raw file bytes and a run of orbit
    parameters occasionally parses as a plausible TLV by pure coincidence, at which point decryption
    is attempted, fails, and a good frame is silently dropped. One hole is enough to ruin a file.
    """
    if not body or body[0] != 0x7C:
        return body
    try:
        tl = dict(tlv_parse(body))
    except Exception:
        return body
    if 126 not in tl or 125 not in tl:
        return body
    for k in rec.keys:
        try:
            return hc.decrypt_gcm(tl[126], k, tl[125])
        except Exception:
            pass
    return None


def show(tl):
    """TLVs as something readable — ASCII where it plainly is ASCII, hex otherwise."""
    bits = []
    for tag, val in sorted(tl.items()):
        try:
            text = val.decode("ascii")
            printable = text and all(" " <= c <= "~" for c in text)
        except Exception:
            printable = False
        bits.append(f"{tag}={text!r}" if printable else
                    f"{tag}=0x{val.hex()}" if len(val) <= 12 else f"{tag}=<{len(val)} B>")
    return " ".join(bits)


def crc16_xmodem(data):
    """The band's own checksum — CRC16-XMODEM, as the framing uses throughout."""
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def harvest(seq, rec, files, log):
    """Fold one capture into the shared file buffers and the transcript."""
    current, block_off, cursor = None, 0, 0
    blocks = {}
    for ts, d, svc, cmd, body in seq:
        if svc not in (SVC_ASK, SVC_FILES):
            continue
        pt = decrypt(body, rec)
        if pt is None:
            log.append(f"{ts:>12.3f} {'-->' if d else '<--'} "
                       f"{CMD_NAMES.get((svc, cmd), f'0x{svc:02x}/0x{cmd:02x}')}  [undecryptable]")
            continue

        # Data frames are RAW — one sequence byte then file bytes. They carry no offset of their own:
        # the offset comes from the FILES/block request that opened this run, and the sequence byte
        # restarts at 0 for every block. Parsing them as TLV yields confident nonsense.
        if svc == SVC_FILES and cmd == F_DATA:
            if current is None or len(pt) < 2:
                continue
            blocks[current] = blocks.get(current, 0) + 1
            data = pt[1:]
            buf = files[current]["buf"]
            n = min(len(data), len(buf) - cursor)
            if n > 0:
                buf[cursor:cursor + n] = data[:n]
                for i in range(cursor, cursor + n):
                    files[current]["have"][i] = 1
                cursor += n
            continue

        try:
            tl = dict(tlv_parse(pt))
        except Exception:
            continue
        log.append(f"{ts:>12.3f} {'-->' if d else '<--'} "
                   f"{CMD_NAMES.get((svc, cmd), f'0x{svc:02x}/0x{cmd:02x}')}  {show(tl)}")

        if svc == SVC_FILES and cmd == F_PICK:
            # TWO frames, and the second carries no name at all.
            #
            #   <-- FILES/pick  1='HW_AGNSS_RTCM_33'          the band names what it wants
            #   --> FILES/pick  2=0x00001ceb 3=0x8d9c         the companion answers size and CRC16
            #
            # Requiring tag 1 here — which the first version did — means the reply is skipped, no
            # buffer is ever allocated, and every data frame is then dropped for want of somewhere to
            # put it. That produced seven files of zero bytes from a capture that plainly contained
            # the transfer, which is exactly the failure this tool exists to avoid.
            if 1 in tl:
                name = tl[1].decode("ascii", "replace").strip("\x00")
                if name:
                    current = name
                    files.setdefault(current, {"buf": bytearray(), "have": bytearray(), "crc": None})
            if 2 in tl and len(tl[2]) == 4 and current:
                size = int.from_bytes(tl[2], "big")
                entry = files[current]
                if len(entry["buf"]) != size:
                    entry["buf"] = bytearray(size)
                    entry["have"] = bytearray(size)
                # The band is told this CRC before a byte moves, so it is the file's own checksum and
                # a free end-to-end check on everything this script reassembles.
                if 3 in tl:
                    entry["crc"] = int.from_bytes(tl[3], "big")
        elif svc == SVC_FILES and cmd == F_BLOCK and not d and 2 in tl and len(tl[2]) == 4:
            # Only the BAND's request, and only a four-byte tag.
            #
            # Both halves of this exchange use tag 2 for entirely different things:
            #
            #   <-- FILES/block  1='HW_AGNSS_RTCM_33' 2=<offset:4>  3=<length:4>
            #   --> FILES/block  2='<64 hex chars><file name>'      3=<offset:4>  127=<result>
            #
            # so reading tag 2 from whichever frame arrives parses a 96-byte token as an integer,
            # sets the write cursor to something astronomical, and silently drops every data frame
            # that follows. That is precisely what it did: 949 frames in, nothing out.
            block_off = int.from_bytes(tl[2], "big")
            cursor = block_off
        elif svc == SVC_FILES and cmd == F_DONE and current:
            entry = files[current]
            got = sum(entry["have"])
            log.append(f"{'':>12}     {current} complete: {got}/{len(entry['buf'])} bytes held, "
                       f"{blocks.get(current, 0)} data frames")
            current = None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    args = [pathlib.Path(a) for a in sys.argv[1:]]
    logs = [a for a in args if a.is_file()]
    if not logs:
        raise SystemExit("no readable log given")
    outdir = args[-1] if not args[-1].is_file() else pathlib.Path(".scratch/gnss-capture")
    outdir.mkdir(parents=True, exist_ok=True)

    files, log = {}, []
    for path in logs:
        log.append(f"===== {path.name} =====")
        seq, rec = frames(path)
        log.append(f"      {len(seq)} frames, {len(rec.keys)} session key(s) recovered")
        harvest(seq, rec, files, log)

    (outdir / "transcript.txt").write_text("\n".join(log) + "\n", encoding="utf-8")
    print(f"transcript -> {outdir / 'transcript.txt'}")
    if not files:
        print("no GNSS transfer found in these logs — was the capture running during Update?")
        return 1
    ok = 0
    for name, entry in sorted(files.items()):
        size, got = len(entry["buf"]), sum(entry["have"])
        if size and got == size:
            (outdir / name).write_bytes(bytes(entry["buf"]))
            want = entry.get("crc")
            seen = crc16_xmodem(bytes(entry["buf"]))
            mark = "complete" if want is None else (
                "complete, CRC ok" if seen == want else
                f"complete but CRC MISMATCH (band was told {want:#06x}, bytes give {seen:#06x})")
            print(f"  {name:<22} {size:>8} B  {mark}")
            ok += 1
        else:
            # Written anyway, with the holes named: a partial file is evidence even when it is not
            # usable, and merging a second capture is what fills it.
            (outdir / f"{name}.partial").write_bytes(bytes(entry["buf"]))
            print(f"  {name:<22} {size:>8} B  INCOMPLETE — {size - got} bytes missing "
                  f"(capture again and pass both logs)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
