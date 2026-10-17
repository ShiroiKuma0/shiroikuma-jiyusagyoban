#!/usr/bin/env python3
"""Capture Huawei Health talking to a new device (the Watch D3), step by step, on the rooted phone.

A session is a run of steps — pair, measure blood pressure, record an ECG, sync … — taken and
analysed one at a time, as they happen: every frame has to be attributable to the step that caused
it, and every step is decrypted and summarised the moment it is marked. The log is append-only, so a step is
attributed by a MARK: the log's size and a full copy, taken just before the step and again after.
The decoder then reads only the bytes between two marks.

Two failure modes this guards against, both learned on the Band 11 Pro:

* **Rotation.** At about 24 MB the log becomes `btsnoop_hci.log.last` and a new one starts. The
  HiChain handshake goes with the old file, and with it the key for every later frame. `watch` copies
  the old file the moment it rotates and says so loudly — then reconnect the device in Health, which
  writes a fresh handshake into the new log.
* **A snoop that is not on.** `check` refuses to start unless `dumpsys bluetooth_manager` reports a
  FULL snoop log, because a session against a silent log has to be done again.

    check                    snoop FULL? root? log present? — run before pairing
    watch                    guard against rotation (leave running in its own terminal)
    mark <label>             copy the log, record where it stands, and decrypt + summarise
                             everything since the previous mark (huawei-capture-analyze.py)
    status                   the marks so far, with the bytes each step added
    shot <label>             screenshot the phone (each display of the Fold) into the capture dir

Output goes to `.scratch/d3/capture/` (gitignored). **It holds personal identifiers** — the Huawei
account hash and the device id travel in the decrypted frames — so it is never committed anywhere.
"""
import json
import os
import pathlib
import subprocess
import sys
import time

SERIAL = "R3CW70FPPDV"          # the rooted Galaxy Z Fold5 that runs Huawei Health
LOG = "/data/log/bt/btsnoop_hci.log"
OUT = pathlib.Path(__file__).resolve().parent.parent / ".scratch/d3/capture"
MARKS = OUT / "marks.json"
ROOT = pathlib.Path(__file__).resolve().parent.parent
VENV_PY = ROOT / ".scratch/hw/venv/bin/python"
ANALYZE = ROOT / "scripts/huawei-capture-analyze.py"


def adb(*args, binary=False, check=True):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def su(cmd, binary=False, check=True):
    return adb("shell", f"su -c '{cmd}'", binary=binary, check=check)


def size(path=LOG):
    out = su(f"stat -c %s {path}", check=False).strip()
    return int(out) if out.isdigit() else None


def mtime(path):
    out = su(f"stat -c %Y {path}", check=False).strip()
    return int(out) if out.isdigit() else None


def utc_offset(zone):
    """`+0200` → 7200. The log's timestamps are the phone's local time, not UTC."""
    if len(zone) != 5 or zone[0] not in "+-" or not zone[1:].isdigit():
        return 0
    sec = int(zone[1:3]) * 3600 + int(zone[3:5]) * 60
    return sec if zone[0] == "+" else -sec


def stamp():
    return time.strftime("%Y-%m-%d_%H-%M-%S")


def load():
    return json.loads(MARKS.read_text()) if MARKS.is_file() else {"marks": [], "rotations": []}


def save(state):
    OUT.mkdir(parents=True, exist_ok=True)
    MARKS.write_text(json.dumps(state, ensure_ascii=False, indent=2))


def cmd_check():
    devices = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    if SERIAL not in devices:
        raise SystemExit(f"the rooted phone ({SERIAL}) is not connected over USB")
    if su("id", check=False).find("uid=0") < 0:
        raise SystemExit("no root on the phone — `su` did not give uid 0")
    snoop = adb("shell", "dumpsys bluetooth_manager").lower()
    lines = [l.strip() for l in snoop.splitlines() if "snoop" in l]
    print("\n".join(lines) or "(dumpsys says nothing about snoop)")
    if not any("full" in l for l in lines):
        raise SystemExit("snoop is NOT full — Developer options → Bluetooth HCI snoop log → Enabled, "
                         "then Bluetooth off and on, then check again")
    s = size()
    print(f"log {LOG}: {s if s is not None else 'absent'} bytes")
    pkg = adb("shell", "pm list packages com.huawei.health", check=False).strip()
    print(f"Huawei Health: {pkg or 'NOT INSTALLED'}")
    print("ready" if s is not None and pkg else "not ready")


def cmd_mark(label):
    OUT.mkdir(parents=True, exist_ok=True)
    s = size()
    if s is None:
        raise SystemExit("no log on the phone")
    name = f"{stamp()}_{''.join(c if c.isalnum() or c in '-_' else '_' for c in label)}.log"
    # The PHONE's clock, because the log's timestamps are the phone's: a PC clock a few seconds off
    # would put a step's first frames in the previous step.
    phone_now, _, zone = adb("shell", "date '+%s %z'").strip().partition(" ")
    (OUT / name).write_bytes(su(f"cat {LOG}", binary=True))
    state = load()
    previous = state["marks"][-1]["size"] if state["marks"] else None
    since = state["marks"][-1].get("epoch") if state["marks"] else None
    epoch = int(phone_now) if phone_now.isdigit() else int(time.time())
    state["marks"].append({"label": label, "at": stamp(), "epoch": epoch, "size": s, "copy": name})
    save(state)
    grew = f", +{s - previous} B since the last mark" if previous is not None and s >= previous else ""
    print(f"marked '{label}' at {s} B{grew} → {name}")
    if previous is not None and s < previous:
        print("!! the log is SMALLER than at the last mark — it rotated. Check `watch`'s copy, and "
              "reconnect the device in Health before the next step.")
    if since is not None and os.access(VENV_PY, os.X_OK):
        print(f"--- what '{label}' caused ---")
        subprocess.run([str(VENV_PY), "-I", str(ANALYZE), str(OUT / name),
                        "--from", str(since), "--to", str(epoch + 2), "--utc-offset", str(utc_offset(zone))])


def cmd_watch():
    OUT.mkdir(parents=True, exist_ok=True)
    last = mtime(LOG + ".last")
    print(f"watching for rotation (Ctrl-C to stop); log at {size()} B")
    while True:
        now = mtime(LOG + ".last")
        if now is not None and now != last:
            name = f"rotated_{stamp()}.log"
            (OUT / name).write_bytes(su(f"cat {LOG}.last", binary=True))
            state = load()
            state["rotations"].append({"at": stamp(), "copy": name})
            save(state)
            print(f"\a!! {time.strftime('%H:%M:%S')} ROTATION — old log saved as {name}. "
                  "Disconnect and reconnect the device in Huawei Health NOW, so the new log gets a handshake.")
            last = now
        s = size()
        if s is not None and s > 22 * 1024 * 1024:
            print(f"   {time.strftime('%H:%M:%S')} log at {s // (1024 * 1024)} MB — rotation is close (~24 MB)")
        time.sleep(5)


def displays():
    out = adb("shell", "dumpsys SurfaceFlinger --display-id", check=False)
    return [l.split()[1] for l in out.splitlines() if l.startswith("Display ")]


def cmd_shot(label):
    """One PNG per display: the Fold has two, and screencap without -d picks one at random. The
    PNG is written on the phone and pulled, so screencap's warnings never end up inside the image."""
    OUT.mkdir(parents=True, exist_ok=True)
    base = f"shot_{stamp()}_{''.join(c if c.isalnum() or c in '-_' else '_' for c in label)}"
    for i, d in enumerate(displays() or [None]):
        remote = f"/sdcard/tmp/.capture_shot_{i}.png"
        adb("shell", f"screencap -p {'-d ' + d if d else ''} {remote}")
        data = adb("exec-out", f"cat {remote}", binary=True)
        adb("shell", f"rm -f {remote}")
        path = OUT / f"{base}_d{i}.png"
        path.write_bytes(data)
        print(f"{path}  ({len(data)} B)")


def cmd_status():
    state = load()
    prev = None
    for m in state["marks"]:
        grew = "" if prev is None else f"  (+{m['size'] - prev} B)"
        print(f"{m['at']}  {m['size']:>10} B{grew}  {m['label']}")
        prev = m["size"]
    for r in state["rotations"]:
        print(f"{r['at']}  ROTATION  {r['copy']}")
    if not state["marks"] and not state["rotations"]:
        print("nothing captured yet")


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    cmd = argv[1]
    if cmd == "check":
        cmd_check()
    elif cmd == "mark" and len(argv) >= 3:
        cmd_mark(" ".join(argv[2:]))
    elif cmd == "watch":
        cmd_watch()
    elif cmd == "shot" and len(argv) >= 3:
        cmd_shot(" ".join(argv[2:]))
    elif cmd == "status":
        cmd_status()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
