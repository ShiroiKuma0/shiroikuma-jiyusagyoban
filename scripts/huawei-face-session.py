#!/usr/bin/env python3
"""Drive a watch-face capture session against the rooted phone running Huawei Health.

A capture session is a turn-taking loop: `arm`, 白い熊 installs one face, `grab`. Doing it one face
at a time is not fussiness — the phone's Bluetooth log ROTATES, and when it does the session's
HiChain handshake goes with it, taking the key that decrypts every unprocessed face. So each face is
verified while its key is still recoverable, and the log is copied off after every one.

    arm                 note where the log stands; confirm a handshake is present
    grab <slot>         pull the log, extract + verify, screenshot the phone
    pack <slot> <name>  crop the preview and build <name>.zip
    status              what has been captured so far

Faces land in ~/〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro.
**That directory holds personal identifiers** — the store record carries a Huawei account hash
(`huid`) and the band's `deviceId` — so it must never be committed anywhere public.
"""
import json
import pathlib
import shutil
import subprocess
import sys
import time

ARCHIVE = pathlib.Path.home() / "〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro"
WORK = pathlib.Path(__file__).resolve().parent.parent / ".scratch/hw2/faces"
LOG = "/data/log/bt/btsnoop_hci.log"
STATE = WORK / "session.json"


def adb(*args, binary=False):
    """Run adb against the ONE usb device; refuse if that is ambiguous."""
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    serials = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
    usb = [s for s in serials if ":" not in s]
    if len(usb) != 1:
        raise SystemExit(f"expected exactly one USB device, found {usb or 'none'}")
    r = subprocess.run(["adb", "-s", usb[0], *args], capture_output=True)
    if not binary and r.returncode != 0:
        raise SystemExit(r.stderr.decode(errors="replace").strip())
    return r.stdout if binary else r.stdout.decode(errors="replace")


def load_state():
    return json.loads(STATE.read_text()) if STATE.is_file() else {"faces": {}}


def save_state(s):
    WORK.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(s, ensure_ascii=False, indent=2))


def log_size():
    return int(adb("shell", f"su -c 'stat -c %s {LOG}'").strip())


def cmd_arm():
    WORK.mkdir(parents=True, exist_ok=True)
    s = load_state()
    size = log_size()
    s["armed_at"] = size
    save_state(s)
    print(f"armed at {size:,} bytes · {len(s['faces'])} face(s) captured so far")
    print("READY — install ONE face, leave Health on that face's page, then say done.")


def cmd_grab(slot):
    s = load_state()
    WORK.mkdir(parents=True, exist_ok=True)
    stage = WORK / slot
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    # The whole log, not a delta: the handshake sits before this face and the control frames cannot
    # be decrypted without it. Keeping a copy per face also survives a rotation mid-session.
    adb("shell", f"su -c 'cp {LOG} /sdcard/facelog.log'")
    adb("pull", "/sdcard/facelog.log", str(stage / "capture.log"))
    adb("shell", "su -c 'rm -f /sdcard/facelog.log'")

    shot = stage / "screen.png"
    shot.write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    extractor = pathlib.Path(__file__).resolve().parent / "huawei-extract-watchface.py"
    r = subprocess.run(
        [sys.executable, str(extractor), str(stage / "capture.log"), str(stage)],
        capture_output=True, text=True,
    )
    print(r.stdout.strip().splitlines()[-4:] and "\n".join(r.stdout.strip().splitlines()[-4:]))
    if r.returncode != 0:
        print(r.stderr.strip()[-400:])

    faces = sorted(p.stem for p in stage.glob("*.bin"))
    known = {f["asset_version"] for f in s["faces"].values()}
    fresh = [f for f in faces if f not in known]
    s["faces"].setdefault(slot, {})
    if not fresh:
        print(f"\nNO NEW FACE verified in {slot} — redo this one.")
        print(f"   (verified in the log: {faces or 'none'})")
        s["faces"].pop(slot, None)
    else:
        s["faces"][slot] = {"asset_version": fresh[-1], "packed": False}
        print(f"\n{slot}: {fresh[-1]} verified · screenshot {shot}")
    save_state(s)


def cmd_pack(slot, name, box=None):
    from PIL import Image

    s = load_state()
    entry = s["faces"].get(slot) or {}
    av = entry.get("asset_version")
    if not av:
        raise SystemExit(f"{slot} has no verified face — grab it first")
    stage = WORK / slot
    ARCHIVE.mkdir(parents=True, exist_ok=True)

    preview = stage / "preview.png"
    img = Image.open(stage / "screen.png")
    crop = box or s.get("crop")
    if not crop:
        raise SystemExit("no crop box yet — set one with `crop L T R B` after checking a screenshot")
    img.crop(tuple(crop)).save(preview)

    # The filename is sanitised for the filesystem, which is lossy; face.json keeps the real one.
    safe = "".join("_" if c in '/\\:*?"<>|' else c for c in name).strip() or av
    (stage / "face.json").write_text(json.dumps({
        "name": name,
        "assetId": av.split("_")[0],
        "version": av.split("_", 1)[1],
        "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }, ensure_ascii=False, indent=2))

    import zipfile
    dest = ARCHIVE / f"{safe}.zip"
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for f in (f"{av}.bin", f"{av}.json", "preview.png", "face.json"):
            z.write(stage / f, f)
    entry.update({"packed": True, "name": name, "zip": str(dest)})
    save_state(s)
    print(f"{dest.name}: {dest.stat().st_size:,} B  ({name})")


def cmd_status():
    s = load_state()
    if not s.get("faces"):
        print("nothing captured yet")
        return
    for slot, f in sorted(s["faces"].items()):
        mark = "packed" if f.get("packed") else "captured"
        print(f"  {slot:<8} {f['asset_version']:<24} {mark:<9} {f.get('name', '')}")
    print(f"\n{sum(1 for f in s['faces'].values() if f.get('packed'))} packed of {len(s['faces'])}")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd, *rest = sys.argv[1:]
    if cmd == "arm":
        cmd_arm()
    elif cmd == "grab":
        cmd_grab(rest[0])
    elif cmd == "pack":
        cmd_pack(rest[0], rest[1], [int(x) for x in rest[2:6]] or None)
    elif cmd == "crop":
        s = load_state(); s["crop"] = [int(x) for x in rest[:4]]; save_state(s)
        print("crop box set:", s["crop"])
    elif cmd == "status":
        cmd_status()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
