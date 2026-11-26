#!/usr/bin/env python3
"""Send a command to 白い熊 自由作業盤's adb bridge, signed with the PC's key.

    bridge.py keygen                      make the PC's key (once; refuses to overwrite)
    bridge.py fingerprint                 the key's fingerprint, as the phone shows it
    bridge.py info   <target>             what the phone's bridge says about itself
    bridge.py offer  <target>             offer the public key; 白い熊 approves it on the phone
    bridge.py send   <target> <ACTION> [--path P] [--local FILE] [--task T] [--project P]
                                          [--extra NAME VALUE]...

<target>  an adb serial (RFCX61K7H3X, skhw:15555, 192.168.1.65:5555) or ssh:<host> (ssh:skhw).
<ACTION>  the last word of the action: IMPORT_BUNDLE, RUN_TASK, EXPORT_WORKSPACE, EXPORT_DATA,
          DELETE_ITEMS, SET_STARTUP_TASKS, GENGOSHIMA_IMPORT, GENGOSHIMA_TOKENS.
--path    the file ON THE PHONE (a bare name means /sdcard/tmp/<name>).
--local   the same file on the PC. Its SHA-256 goes into the signed part, so the phone refuses a
          file that was swapped after the push. Compulsory for a command that reads a file once
          the phone asks for signatures. This script does not push the file.
--extra   any other extra, by the last word of its name (GENERATE, START_TASKS, BOOT_START …).

What is signed (must match BridgeAuth.canonical in the app): one line each — "jsb-bridge-1", the
phone's bridge id, the full action, then every extra as name=value in name order, with %, CR and LF
in a value percent-escaped. The counter is this PC's clock in milliseconds; the phone accepts a
command only if it exceeds the last one it accepted.

The private key is ~/.config/shiroikuma-jiyusagyoban/bridge-key.pem (mode 600, in no repository).
Whoever has it commands the bridge of every phone that approved it.

The phone's answer is printed as `am broadcast` gives it. Exit status: 0 when result=-1 (OK).
"""
import base64, hashlib, os, pathlib, re, shlex, subprocess, sys, time

KEY = pathlib.Path.home() / ".config/shiroikuma-jiyusagyoban/bridge-key.pem"
COMPONENT = "shiroikuma.jiyusagyoban/com.sysadmindoc.ruledeck.core.transfer.WorkspaceTransferReceiver"
ACTION = "shiroikuma.jiyusagyoban.action."
EXTRA = "shiroikuma.jiyusagyoban.extra."
HEADER = "jsb-bridge-1"


def die(message):
    sys.exit(f"bridge.py: {message}")


def openssl(*args, data=None):
    return subprocess.run(["openssl", *args], input=data, check=True, capture_output=True).stdout


def public_der():
    if not KEY.is_file():
        die(f"no key at {KEY} — run: bridge.py keygen")
    return openssl("pkey", "-in", str(KEY), "-pubout", "-outform", "DER")


def fingerprint(der):
    h = hashlib.sha256(der).hexdigest()[:16]
    return " ".join(h[i:i + 4] for i in range(0, 16, 4))


def escape(value):
    return value.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")


def canonical(bridge_id, action, extras):
    lines = [HEADER, bridge_id, action] + [f"{k}={escape(v)}" for k, v in sorted(extras.items())]
    return "\n".join(lines) + "\n"


def sign(message):
    return base64.b64encode(openssl("dgst", "-sha256", "-sign", str(KEY), data=message.encode())).decode()


def run_on(target, line):
    """One shell line on the phone. adb joins its arguments, so the line travels as ONE argument."""
    if target.startswith("ssh:"):
        argv = ["ssh", target[4:], line]
    else:
        argv = ["adb", "-s", target, "shell", line]
    p = subprocess.run(argv, capture_output=True, text=True)
    return (p.stdout + p.stderr).strip()


def broadcast(target, action, extras, int_extras=("PROTOCOL",)):
    words = ["am", "broadcast", "-n", COMPONENT, "-a", action]
    for name, value in extras.items():
        short = name[len(EXTRA):]
        words += ["--ei" if short in int_extras else "--es", name, value]
    return run_on(target, " ".join(shlex.quote(w) for w in words))


def info(target):
    out = broadcast(target, ACTION + "BRIDGE_INFO", {EXTRA + "PROTOCOL": "1"})
    m = re.search(r'data="([^"]*)"', out)
    if not m or "id=" not in m.group(1):
        return None, out
    return dict(pair.split("=", 1) for pair in m.group(1).split()), out


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd = args.pop(0)

    if cmd == "keygen":
        if KEY.exists():
            die(f"{KEY} exists — a new key would lock this PC out of every phone that approved the old one")
        KEY.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(KEY.parent, 0o700)
        old = os.umask(0o077)
        try:
            openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(KEY))
        finally:
            os.umask(old)
        print(f"{KEY}\nfingerprint: {fingerprint(public_der())}")
        return 0

    if cmd == "fingerprint":
        print(fingerprint(public_der()))
        return 0

    if not args:
        die("which phone? give an adb serial or ssh:<host>")
    target = args.pop(0)

    if cmd == "info":
        facts, out = info(target)
        print(out if facts is None else " ".join(f"{k}={v}" for k, v in facts.items()))
        return 0 if facts else 1

    if cmd == "offer":
        der = public_der()
        out = broadcast(target, ACTION + "BRIDGE_OFFER_KEY",
                        {EXTRA + "PROTOCOL": "1", EXTRA + "KEY": base64.b64encode(der).decode()})
        print(out)
        print(f"this PC's fingerprint: {fingerprint(der)}")
        return 0 if "result=-1" in out else 1

    if cmd != "send":
        die(f"unknown command: {cmd}")
    if not args:
        die("which action?")
    action = ACTION + args.pop(0)
    extras = {EXTRA + "PROTOCOL": "1"}
    local = None
    while args:
        flag = args.pop(0)
        try:
            if flag == "--path":
                extras[EXTRA + "PATH"] = args.pop(0)
            elif flag == "--task":
                extras[EXTRA + "TASK"] = args.pop(0)
            elif flag == "--project":
                extras[EXTRA + "PROJECT"] = args.pop(0)
            elif flag == "--local":
                local = args.pop(0)
            elif flag == "--extra":
                name = args.pop(0)
                extras[EXTRA + name] = args.pop(0)
            else:
                die(f"unknown option: {flag}")
        except IndexError:
            die(f"{flag} needs a value")
    if local is not None:
        extras[EXTRA + "SHA256"] = hashlib.sha256(pathlib.Path(local).read_bytes()).hexdigest()

    facts, raw = info(target)
    if facts is None:
        # An app older than the gate (no BRIDGE_INFO): it takes the plain command.
        if "result=0" not in raw:
            die(f"the phone did not answer the bridge: {raw}")
    elif KEY.is_file():
        extras[EXTRA + "COUNTER"] = str(int(time.time() * 1000))
        extras[EXTRA + "SIG"] = sign(canonical(facts["id"], action, extras))
    elif facts.get("signed") == "true":
        die(f"this phone wants signed commands and there is no key at {KEY}")
    out = broadcast(target, action, extras)
    print(out)
    return 0 if "result=-1" in out else 1


sys.exit(main())
