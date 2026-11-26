#!/usr/bin/env python3
"""Put back every name the phones have stored, after upstream's OpenTasker → RuleDeck rename.

usage: scripts/keep-stored-names.py [--report]

Upstream's rename (15667b5f8) is right for upstream: its app id changed, so a RuleDeck install is a new
app with nothing stored. The fork keeps its app id (`shiroikuma.jiyusagyoban`) and is updated in place
on three phones, so two kinds of name must NOT follow the rename:

1. **Text values the app stores or exchanges** — the database file, settings files, intent actions and
   extras, permissions, notification channel ids, wakelock and URI names. Renamed, the app would open
   an empty database beside the real one and stop hearing its own alarms.
   Listed in `scripts/stored-names/literals.txt`, as they were before the rename.

2. **The components the manifest declares** — Android keeps home-screen widgets, pinned shortcuts,
   quick-settings tiles, the accessibility service, the notification listener, the device admin and
   share targets by component name, and `scripts/bridge.py` and sister apps address some directly.
   Renamed, widgets and shortcuts vanish and every grant must be given again by hand, on each phone.
   Listed in `scripts/stored-names/components.txt`. Their classes stay in the old packages
   (`com.opentasker…`); the source file moves back there with everything it declares.

Everything else — packages, class names, build tooling, wording — follows upstream, so later syncs
merge normally. Run after `scripts/apply-upstream-rename.py` (or after a sync that brought renamed
names in); it is idempotent. --report only lists what it would do and what looks unhandled.
"""
import importlib.util, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("ren", ROOT / "scripts/apply-upstream-rename.py")
ren = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ren)

SRC_ROOTS = ("app/src", "core", "baselineprofile", "benchmark", "app/proguard-rules.pro", "app/baseline-prof.txt")
JAVA_MAIN = pathlib.Path("app/src/main/java")
OLD_NS, NEW_NS = "com.opentasker.app", "com.sysadmindoc.ruledeck"
MODS = r"(?:(?:public|internal|open|abstract|sealed|data|enum|annotation|value|inline|const|lateinit|suspend|tailrec|operator|infix|external|fun)\s+)*"
DECL = re.compile(r"^" + MODS + r"(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)", re.M)


def tracked(*roots):
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *roots],
                         capture_output=True, text=True, check=True, cwd=ROOT).stdout.split("\0")
    return [f for f in out if f and (ROOT / f).is_file()]


def read(f):
    return (ROOT / f).read_bytes().decode("utf-8", errors="surrogateescape")


def write(f, text):
    (ROOT / f).write_bytes(text.encode("utf-8", errors="surrogateescape"))


def lines(name):
    return [l for l in (ROOT / "scripts/stored-names" / name).read_text().split("\n") if l and not l.startswith("#")]


def new_fqcn(old):
    return ren.apply(old)


def split(fqcn):
    pkg, _, cls = fqcn.rpartition(".")
    return pkg, cls


def main():
    report = "--report" in sys.argv
    text_files = [f for f in tracked(*SRC_ROOTS) if re.search(r"\.(kt|kts|java|xml|pro|txt)$", f)]
    changed = set()

    def sub_all(pattern, repl, files=None, flags=0):
        n = 0
        rx = re.compile(pattern, flags)
        for f in files or text_files:
            if not (ROOT / f).is_file():
                continue
            t = read(f)
            u, k = rx.subn(repl, t)
            if k:
                n += k
                changed.add(f)
                if not report:
                    write(f, u)
        return n

    # ── 1. stored text values ────────────────────────────────────────────────────────────────────
    restored = 0
    for old in lines("literals.txt"):
        new = ren.apply(old)
        if new != old:
            restored += sub_all(re.escape('"' + new + '"'), lambda m, o=old: '"' + o + '"')
    print(f"stored text values put back: {restored} occurrences")

    # ── 2. the manifest's components ─────────────────────────────────────────────────────────────
    comps = [l.split("\t") for l in lines("components.txt")]
    aliases = [n for k, n in comps if k == "alias"]
    classes = [n for k, n in comps if k != "alias"]
    kotlin = [f for f in text_files if f.endswith(".kt")]
    moved, names = {}, {}                      # file → (newpkg, oldpkg) ; (newpkg, oldpkg) → exported names
    missing = []
    for old in classes:
        oldpkg, cls = split(old)
        newpkg = split(new_fqcn(old))[0]
        olddir, newdir = JAVA_MAIN / oldpkg.replace(".", "/"), JAVA_MAIN / newpkg.replace(".", "/")
        decl = re.compile(r"^" + MODS + r"class\s+" + re.escape(cls) + r"\b", re.M)
        found = None
        for d in (olddir, newdir):
            if (ROOT / d).is_dir():
                for p in sorted((ROOT / d).glob("*.kt")):
                    if decl.search(p.read_text(errors="surrogateescape")):
                        found = p.relative_to(ROOT)
                        break
            if found:
                break
        if not found:
            missing.append(old)
            continue
        moved[str(found)] = (newpkg, oldpkg)
    for f, (newpkg, oldpkg) in sorted(moved.items()):
        body = read(f)
        exported = {m.group(1) for m in DECL.finditer(body)}
        names.setdefault((newpkg, oldpkg), set()).update(exported)
        target = str(JAVA_MAIN / oldpkg.replace(".", "/") / pathlib.Path(f).name)
        body = re.sub(r"^package\s+" + re.escape(newpkg) + r"\s*$", "package " + oldpkg, body, count=1, flags=re.M)
        star = f"import {newpkg}.*"
        if not re.search(r"^" + re.escape(star) + r"$", body, re.M):
            body = re.sub(r"^(package\s+" + re.escape(oldpkg) + r")\s*$", lambda m: m.group(1) + "\n\n" + star, body, count=1, flags=re.M)
        if not report:
            if f != target:
                (ROOT / target).parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(["git", "mv", "-k", f, target], cwd=ROOT, check=False)
                if (ROOT / f).exists():
                    (ROOT / f).rename(ROOT / target)
            write(target, body)
    print(f"component source files in the old packages: {len(moved)} (classes listed: {len(classes)}, not found: {len(missing)})")
    for m in missing:
        print("   NOT FOUND:", m)

    # refresh the file list: paths moved
    text_files[:] = [f for f in tracked(*SRC_ROOTS) if re.search(r"\.(kt|kts|java|xml|pro|txt)$", f)]
    kotlin = [f for f in text_files if f.endswith(".kt")]
    for (newpkg, oldpkg), exported in sorted(names.items()):
        alt = "|".join(sorted(map(re.escape, exported), key=len, reverse=True))
        # fully qualified references, dotted and slashed (imports, manifest, XML, R8 rules, baseline profile)
        sub_all(r"(?<![\w.])" + re.escape(newpkg) + r"\.(" + alt + r")\b", lambda m, o=oldpkg: o + "." + m.group(1))
        sub_all(r"\b" + re.escape(newpkg.replace(".", "/")) + r"/(" + alt + r")\b", lambda m, o=oldpkg: o.replace(".", "/") + "/" + m.group(1))
        word = re.compile(r"\b(" + alt + r")\b")
        for f in kotlin:
            t = read(f)
            pkg = re.search(r"^package\s+([\w.]+)", t, re.M)
            if not pkg:
                continue
            add = []
            if pkg.group(1) == newpkg:
                body = re.sub(r"^(?:package|import)\s.*$", "", t, flags=re.M)
                used = {m.group(1) for m in word.finditer(body)}
                declared = {m.group(1) for m in DECL.finditer(t)}
                add = [f"import {oldpkg}.{n}" for n in sorted(used - declared)
                       if not re.search(r"^import\s+[\w.]+\." + re.escape(n) + r"\s*$", t, re.M)]
            if re.search(r"^import\s+" + re.escape(newpkg) + r"\.\*\s*$", t, re.M) and pkg.group(1) != oldpkg \
                    and not re.search(r"^import\s+" + re.escape(oldpkg) + r"\.\*\s*$", t, re.M):
                add.append(f"import {oldpkg}.*")
            if add:
                changed.add(f)
                if not report:
                    t = re.sub(r"^(package\s+[\w.]+)\s*$", lambda m: m.group(1) + "\n\n" + "\n".join(add), t, count=1, flags=re.M)
                    write(f, t)

    # the manifest: relative names resolve against the NEW namespace now — spell the kept ones out
    man = "app/src/main/AndroidManifest.xml"
    t = read(man)
    for old in classes + aliases:
        if old.startswith(OLD_NS + ".") and "." not in old[len(OLD_NS) + 1:]:
            short = "." + old[len(OLD_NS) + 1:]
            t = re.sub(r'(android:(?:name|targetActivity)=")' + re.escape(short) + '"', lambda m, o=old: m.group(1) + o + '"', t)
    for old in aliases:
        t = t.replace('"' + new_fqcn(old) + '"', '"' + old + '"')
    if not report:
        write(man, t)
    for old in aliases:
        sub_all(re.escape('"' + new_fqcn(old) + '"'), lambda m, o=old: '"' + o + '"')

    # ── 3. what is left to look at ───────────────────────────────────────────────────────────────
    left = {}
    lit = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
    for f in text_files:
        if "/src/main/" not in f or "/i18n/" in f or re.search(r"/values[^/]*/\w*strings\.xml$", f) or not (ROOT / f).is_file():
            continue
        for m in lit.finditer(read(f)):
            v = m.group(1)
            if re.search(r"rule[_-]?deck", v, re.I) and " " not in v and not re.fullmatch(r"[\w.$@/]*", v):
                left.setdefault(v, f)
    print(f"files changed: {len(changed)}")
    print(f"runtime text values still carrying the new name that are not plain identifiers or prose: {len(left)}")
    for v, f in sorted(left.items()):
        print("   ", v[:110], " <", f.split("/")[-1])


if __name__ == "__main__":
    main()
