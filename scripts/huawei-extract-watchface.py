#!/usr/bin/env python3
"""Extract installable watch faces from a btsnoop capture of Huawei Health installing one.

Huawei Health downloads a face as an encrypted `themeV2Cipher` package, decrypts it, uploads the
result to the band and DELETES the download. The bytes that actually go over the air therefore exist
nowhere on disk, and capturing the upload is the only way to obtain them. This script turns such a
capture into the two files `huawei.watchface` needs:

    <assetId>_<version>.bin     the face itself
    <assetId>_<version>.json    its store metadata, signed by Huawei

**Both are required.** The band will accept a file it was never told about, verify its digest and
acknowledge the transfer — and then discard it. The JSON carries `content` and `contentSign` from
Huawei's servers, so it cannot be fabricated: this only ever recovers faces already owned.

Every face is verified against the SHA-256 the phone itself sent the band before transferring it, so
a face that writes is a face that will install. One that does not verify is reported and skipped.

Usage:  huawei-extract-watchface.py <btsnoop.log> [more.log …] [outdir]

Several logs may be given, and their contents are MERGED. That is not a convenience: a single
capture routinely loses a frame or two even though the phone reports no drops and the stream parses
at 99.9%, and one missing 935-byte frame is enough for the band to refuse the whole face. Because
every data frame carries its own absolute offset, two captures of the SAME face fill each other's
holes exactly — so a face that will not verify is fixed by installing it once more rather than by
luck.

The capture must include the session's HiChain handshake (the control frames are encrypted and the
key is derived from it), so capture from before the band connects — not just around the install.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / ".scratch/hw"))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / ".scratch/hw/_stub"))

import hashlib

import btsnoop as B  # noqa: E402  (path set above)
import huawei_crypto as hc  # noqa: E402
from phase0 import tlv_parse, unframe  # noqa: E402

SVC_WATCHFACE, SVC_UPLOAD = 0x27, 0x28


def frames(path):
    """Every frame in true capture order, with fragments reassembled.

    Fragmentation is not optional here: the announcement carrying the metadata exceeds the band's
    1022-byte frame limit, so it always arrives split. A reader that ignores the slice byte sees the
    continuation as a frame on some absurd service and loses the metadata entirely.
    """
    streams, marks = B.acl_streams(path)
    ev = []
    for d, st in streams.items():
        for pos, f in B.scan_frames(bytes(st)):
            ev.append((B.stamp_for(marks[d], pos), d, f))
    ev.sort(key=lambda e: e[0])

    rec, raw = B.KeyRecovery(), []
    for _ts, d, f in ev:
        try:
            sid, cid, payload, _ok = unframe(f)
            rec.feed(d, sid, cid, dict(tlv_parse(payload)), payload)
        except Exception:
            pass
        raw.append((d, f))
    rec.finish()

    out, pend = [], None
    for d, f in raw:
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
        out.append((d, svc, cmd, bytes(body), rec))
    return out, rec


def decrypt(body, rec):
    try:
        tl = dict(tlv_parse(body))
    except Exception:
        return None
    if 126 not in tl or 125 not in tl:
        return body
    for k in rec.keys:
        try:
            return hc.decrypt_gcm(tl[126], k, tl[125])
        except Exception:
            pass
    return None


def harvest(seq, rec, meta, live, done, merged):
    """Fold one capture into the shared buffers."""
    for d, svc, cmd, body, _ in seq:
        pt = decrypt(body, rec)
        if pt is None:
            continue
        # Data frames are RAW, not TLV — parsing them as TLV yields plausible nonsense.
        if d and svc == SVC_UPLOAD and cmd == 0x06:
            if len(pt) <= 6:
                continue
            fid, off, data = pt[0], int.from_bytes(pt[2:6], "big"), pt[6:]
            f = live.get(fid)
            if f:
                n = min(len(data), len(f["buf"]) - off)
                if n > 0:
                    f["buf"][off:off + n] = data[:n]
                    for i in range(off, off + n):
                        f["have"][i] = 1
            continue
        try:
            tl = dict(tlv_parse(pt))
        except Exception:
            continue

        if d and svc == SVC_WATCHFACE and cmd == 0x03 and 8 in tl:
            meta[(tl[1].decode(), tl[2].decode())] = tl[8].decode("utf-8", "replace")
        elif d and svc == SVC_UPLOAD and cmd == 0x03 and 3 in tl:
            f = live.get(tl[1][0])
            if f:
                f["digest"] = tl[3]
        elif d and svc == SVC_UPLOAD and cmd == 0x02 and 1 in tl and 2 in tl:
            fid = tl[3][0]
            if fid in live:
                done.append(live.pop(fid))
            name = tl[1].decode("ascii", "replace")
            size = int.from_bytes(tl[2], "big")
            # Reuse the buffer if this face was seen in an earlier log: that IS the merge.
            key = (tl.get(5, b"").decode("ascii", "replace"), tl.get(6, b"").decode("ascii", "replace"))
            entry = merged.get(key)
            if entry is None or len(entry["buf"]) != size:
                entry = {"name": name, "asset": key[0], "version": key[1], "id": fid,
                         "buf": bytearray(size), "have": bytearray(size)}
                merged[key] = entry
            entry["id"] = fid
            live[fid] = entry


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    args = [pathlib.Path(a) for a in sys.argv[1:]]
    logs = [a for a in args if a.is_file()]
    if not logs:
        raise SystemExit("no readable log given")
    outdir = pathlib.Path(".") if args[-1].is_file() else args[-1]
    outdir.mkdir(parents=True, exist_ok=True)

    meta, live, done, merged = {}, {}, [], {}
    for log in logs:
        seq, rec = frames(log)
        harvest(seq, rec, meta, live, done, merged)
    done += list(live.values())

    faces = 0
    seen = set()
    for f in done:
        key = (f["asset"], f["version"])
        if not f["asset"] or not f["version"] or key in seen:
            continue                            # not a face, or already written from the merge
        seen.add(key)
        missing = f["have"].count(0)
        got = hashlib.sha256(bytes(f["buf"])).digest()
        want = f.get("digest")
        if missing:
            print(f"  SKIP {f['name']}: {missing:,} bytes never arrived "
                  f"({100 * missing / max(len(f['buf']), 1):.2f}%) — install it once more and pass both logs")
            continue
        if want and got != want:
            print(f"  SKIP {f['name']}: complete but the digest does not match — not the same file")
            continue
        if key not in meta:
            print(f"  SKIP {f['name']}: no metadata frame captured; the band would discard it")
            continue
        (outdir / f"{f['name']}.bin").write_bytes(bytes(f["buf"]))
        (outdir / f"{f['name']}.json").write_text(meta[key])
        print(f"  {f['name']}: {len(f['buf']):,} B verified -> {f['name']}.bin + .json")
        faces += 1
    print(f"\n{faces} face(s) extracted to {outdir}")
    if not faces:
        print("Nothing usable. Was the HiChain handshake inside the capture?")


if __name__ == "__main__":
    main()
