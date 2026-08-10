#!/usr/bin/env python3
"""Replay the fork's Room migrations against a real SQLite and compare the result to the schema
Room expects — the same comparison Room makes when it opens an existing database.

Room validates the live schema against its exported JSON at open time, and a mismatch as small as a
missing `DEFAULT NULL` makes it throw instead of opening. That failure only ever shows up on a device
holding the OLD database, so a clean build, a fresh install and the whole JVM suite can all pass while
every existing install crashes on start. This closes that gap without a device.

    python3 scripts/check-room-migration.py            # highest exported schema below current -> current
    python3 scripts/check-room-migration.py 22 27      # an explicit span

Exit status is 0 when the migrated schema matches, 1 when Room would refuse to open.
"""
from __future__ import annotations

import json
import pathlib
import re
import sqlite3
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCHEMAS = REPO / "app/schemas/com.opentasker.core.storage.AppDatabase"
MIGRATIONS = REPO / "app/src/main/java/com/opentasker/core/storage/DatabaseMigrations.kt"
DATABASE = REPO / "app/src/main/java/com/opentasker/core/storage/AppDatabase.kt"


def current_version() -> int:
    m = re.search(r"(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)", DATABASE.read_text(encoding="utf-8"))
    if not m:
        sys.exit(f"Could not read the schema version from {DATABASE}")
    return int(m.group(1))


def exported_versions() -> list[int]:
    return sorted(int(p.stem) for p in SCHEMAS.glob("*.json") if p.stem.isdigit())


def schema(version: int) -> dict:
    path = SCHEMAS / f"{version}.json"
    if not path.is_file():
        sys.exit(f"No exported schema for version {version} ({path} is missing). Build once and commit it.")
    return json.loads(path.read_text(encoding="utf-8"))["database"]


def create_statements(version: int) -> list[str]:
    out = []
    for entity in schema(version)["entities"]:
        table = entity["tableName"]
        out.append(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            out.append(index["createSql"].replace("${TABLE_NAME}", table))
    return out


def migration_statements(source: int, target: int) -> list[str]:
    """The execSQL literals inside MIGRATION_<source>_<target>, in order."""
    text = MIGRATIONS.read_text(encoding="utf-8")
    block = re.search(
        rf"val MIGRATION_{source}_{target} = object : Migration\({source}, {target}\) \{{(.*?)\n    \}}",
        text,
        re.S,
    )
    if not block:
        sys.exit(f"MIGRATION_{source}_{target} is not declared in {MIGRATIONS.name}")
    body = block.group(1)
    statements = []
    for call in re.finditer(r'db\.execSQL\(\s*(""".*?"""\.trimIndent\(\)|(?:"[^"]*"(?:\s*\+\s*)?)+)', body, re.S):
        raw = call.group(1)
        if raw.startswith('"""'):
            statements.append(re.sub(r'^"""|"""\.trimIndent\(\)$', "", raw).strip())
        else:
            statements.append("".join(re.findall(r'"([^"]*)"', raw)))
    if not statements:
        sys.exit(f"MIGRATION_{source}_{target} declares no execSQL statements — the parser needs updating")
    return statements


def live_schema(connection: sqlite3.Connection) -> dict:
    tables = {}
    query = (
        "SELECT name FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'"
    )
    for (table,) in connection.execute(query):
        columns = {}
        for _, name, ctype, notnull, default, _pk in connection.execute(f"PRAGMA table_info(`{table}`)"):
            columns[name] = ((ctype or "").upper() or None, bool(notnull), default)
        indices = set()
        for row in connection.execute(f"PRAGMA index_list(`{table}`)"):
            if not row[1].startswith("sqlite_autoindex"):
                indices.add(row[1])
        tables[table] = (columns, indices)
    return tables


def main() -> int:
    current = current_version()
    if len(sys.argv) == 3:
        source, target = int(sys.argv[1]), int(sys.argv[2])
    else:
        earlier = [v for v in exported_versions() if v < current]
        if not earlier:
            print(f"Only schema {current} is exported — nothing to migrate from.")
            return 0
        source, target = earlier[-1], current

    connection = sqlite3.connect(":memory:")
    for statement in create_statements(source):
        connection.execute(statement)

    for step in range(source, target):
        for statement in migration_statements(step, step + 1):
            try:
                connection.execute(statement)
            except sqlite3.Error as error:
                print(f"MIGRATION_{step}_{step + 1} failed on:\n    {statement}\n  {error}")
                return 1

    got = live_schema(connection)
    expected = schema(target)
    problems: list[str] = []
    for entity in expected["entities"]:
        table = entity["tableName"]
        if table not in got:
            problems.append(f"{table}: the migration never creates this table")
            continue
        columns, indices = got[table]
        for field in entity["fields"]:
            name = field["columnName"]
            if name not in columns:
                problems.append(f"{table}.{name}: column missing after migration")
                continue
            _, notnull, default = columns[name]
            if notnull != bool(field.get("notNull")):
                problems.append(f"{table}.{name}: notNull is {notnull}, Room expects {bool(field.get('notNull'))}")
            want = field.get("defaultValue")
            normalise = lambda value: None if value is None else str(value).strip()
            if normalise(default) != normalise(want):
                problems.append(f"{table}.{name}: default is {default!r}, Room expects {want!r}")
        for index in entity.get("indices", []):
            if index["name"] not in indices:
                problems.append(f"{table}: index {index['name']} missing after migration")

    for table in sorted(set(got) - {e["tableName"] for e in expected["entities"]}):
        problems.append(f"{table}: left behind by the migration but absent from schema {target}")

    if problems:
        print(f"{source} -> {target}: {len(problems)} mismatch(es). Room would refuse to open an existing database:")
        for problem in problems:
            print("  -", problem)
        return 1

    print(f"{source} -> {target}: the migration produces exactly the schema Room expects "
          f"({len(expected['entities'])} tables checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
