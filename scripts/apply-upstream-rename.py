#!/usr/bin/env python3
"""Apply upstream's OpenTasker → RuleDeck rename (15667b5f8, 2026-11-26) to this tree, mechanically.

usage: scripts/apply-upstream-rename.py [--check]

Upstream renamed the app in one commit: 949 files moved from `com.opentasker` to
`com.sysadmindoc.ruledeck`, and every mention of the old name was substituted. That commit is 99.6 %
explained by the fourteen literal substitutions in RULES (13 270 of 13 328 changed lines; measured
on the commit itself). So instead of merging 949 renames against a fork that touches 622 of those
files, the fork applies the SAME substitutions to its own tree — contents and paths — and the two
sides then agree line for line wherever they shared a line.

This script does only that mechanical half. What the phones have stored under the old name — the
database file, settings files, alarm actions, notification channels, and every component the
manifest declares — is put back afterwards by `scripts/keep-stored-names.py`.

--check lists what would change and writes nothing.
"""
import pathlib, subprocess, sys

# Most specific first. `com.opentasker.app` collapses into the new root: upstream moved MainActivity
# and the Application class up one level.
RULES = [
    ("com.opentasker.app", "com.sysadmindoc.ruledeck"), ("com/opentasker/app", "com/sysadmindoc/ruledeck"),
    ("com\\opentasker\\app", "com\\sysadmindoc\\ruledeck"), ("com_opentasker_app", "com_sysadmindoc_ruledeck"),
    ("com.opentasker", "com.sysadmindoc.ruledeck"), ("com/opentasker", "com/sysadmindoc/ruledeck"),
    ("com\\opentasker", "com\\sysadmindoc\\ruledeck"), ("com_opentasker", "com_sysadmindoc_ruledeck"),
    ("OpenTasker", "RuleDeck"), ("openTasker", "ruleDeck"), ("OPEN_TASKER", "RULEDECK"), ("OPENTASKER", "RULEDECK"),
    ("open_tasker", "ruledeck"), ("open-tasker", "ruledeck"), ("opentasker", "ruledeck"), ("Opentasker", "Ruledeck"),
]

# Never touched: the fork's own records and tooling, which speak about the upstream project by its
# old name on purpose (history), and this script and its sibling.
SKIP_PREFIXES = (".git/", ".claude/", ".scratch/", "CLAUDE.md", "CHANGELOG.md", "README.md", "docs/gengoshima.md",
                 "scripts/apply-upstream-rename.py", "scripts/keep-stored-names.py")


def apply(text):
    for old, new in RULES:
        text = text.replace(old, new)
    return text


def main():
    check = "--check" in sys.argv
    files = subprocess.run(["git", "ls-files", "-z"], capture_output=True, text=True, check=True).stdout.split("\0")
    changed, moved = 0, 0
    for f in files:
        if not f or f.startswith(SKIP_PREFIXES):
            continue
        p = pathlib.Path(f)
        if not p.is_file() or p.is_symlink():
            continue
        data = p.read_bytes()
        if b"\0" not in data[:8000]:
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                text = None
            if text is not None:
                new = apply(text)
                if new != text:
                    changed += 1
                    if not check:
                        p.write_bytes(new.encode("utf-8"))
        g = apply(f)
        if g != f:
            moved += 1
            if not check:
                pathlib.Path(g).parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(["git", "mv", "-k", f, g], check=True)
    print(f"{'would change' if check else 'changed'} {changed} files, {'would move' if check else 'moved'} {moved}")


if __name__ == "__main__":
    main()
