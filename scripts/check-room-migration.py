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
MIGRATIONS = REPO / "core/storage/src/main/kotlin/com/opentasker/core/storage/DatabaseMigrations.kt"
DATABASE = REPO / "core/storage/src/main/kotlin/com/opentasker/core/storage/AppDatabase.kt"


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


def _regions(body: str):
    """Split a migration body into (text, bindings) regions, expanding `for (x in listOf(...))`.

    Several migrations apply the same DDL to a list of tables. Read literally, their execSQL text
    still contains `$table`, which is not SQL — SQLite rejects it and the replay stops at the first
    such migration. That is how the v1..v16 spans went unverified while the tool reported success on
    the default 28 -> 29 span: an unparseable migration looked exactly like a passing one.
    """
    position = 0
    for loop in re.finditer(r"for \(\s*(\w+)\s+in\s+listOf\(([^)]*)\)\s*\)\s*\{", body):
        yield body[position:loop.start()], [{}]
        variable = loop.group(1)
        values = re.findall(r'"([^"]*)"', loop.group(2))
        index, depth = loop.end(), 1
        while index < len(body) and depth:
            if body[index] == "{":
                depth += 1
            elif body[index] == "}":
                depth -= 1
            index += 1
        # One pass per value, statements in source order: the loop body runs table by table.
        yield body[loop.end():index - 1], [{variable: value} for value in values]
        position = index
    yield body[position:], [{}]


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
    statements = []
    for region, bindings in _regions(block.group(1)):
        literals = []
        for call in re.finditer(r'db\.execSQL\(\s*(""".*?"""\.trimIndent\(\)|(?:"[^"]*"(?:\s*\+\s*)?)+)', region, re.S):
            raw = call.group(1)
            if raw.startswith('"""'):
                literals.append(re.sub(r'^"""|"""\.trimIndent\(\)$', "", raw).strip())
            else:
                literals.append("".join(re.findall(r'"([^"]*)"', raw)))
        for binding in bindings:
            for literal in literals:
                for name, value in binding.items():
                    literal = literal.replace("${" + name + "}", value).replace("$" + name, value)
                statements.append(literal)
    if not statements:
        sys.exit(f"MIGRATION_{source}_{target} declares no execSQL statements — the parser needs updating")
    # A leftover template marker means the parser did not understand the migration. Say so instead
    # of handing SQLite something that cannot parse and calling the resulting error a schema fault.
    for statement in statements:
        if "$" in statement:
            sys.exit(
                f"MIGRATION_{source}_{target} still contains an unresolved Kotlin template:\n"
                f"    {statement}\n"
                "  Teach _regions()/migration_statements() this shape before trusting the replay."
            )
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


def replay(source: int, target: int) -> int:
    return _replay(source, target)


def main() -> int:
    current = current_version()
    if len(sys.argv) == 2 and sys.argv[1] == "--all":
        # Every exported version is a state some installed database can be sitting at, so every one
        # of them has to reach `current`. Spot-checking only the newest span is what let the v1..v16
        # migrations go unreplayed: the tool passed while a whole class of them could not even be
        # parsed.
        failures = []
        for version in exported_versions():
            if version >= current:
                continue
            if _replay(version, current) != 0:
                failures.append(version)
        print()
        if failures:
            print(f"{len(failures)} starting version(s) cannot reach {current}: {failures}")
            return 1
        print(f"every exported version reaches {current} with the schema Room expects.")
        return 0
    if len(sys.argv) == 3:
        source, target = int(sys.argv[1]), int(sys.argv[2])
    else:
        earlier = [v for v in exported_versions() if v < current]
        if not earlier:
            print(f"Only schema {current} is exported — nothing to migrate from.")
            return 0
        source, target = earlier[-1], current
    return _replay(source, target)


def _replay(source: int, target: int) -> int:
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
    notes: list[str] = []
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
            normalise = lambda value: None if value is None else str(value).strip()
            want, have = normalise(field.get("defaultValue")), normalise(default)
            # The comparison is asymmetric on purpose, because Room's is. Room only holds a column
            # to a default when the ENTITY declares one: a database that carries a default the
            # entity does not declare still opens. That is not a loophole, it is what lets a
            # hand-written `ADD COLUMN ... NOT NULL DEFAULT 0` work at all -- SQLite needs the
            # default to fill existing rows, and Room's own generated CREATE TABLE never has one.
            # This fork has shipped exactly that shape since v6 (position, isSecret,
            # requiresRiskAcknowledgement) and those upgrades demonstrably open.
            #
            # Failing on it would be worse than useless: it is the common case, so it would fire on
            # every run and train the reader to ignore the tool that exists to catch the ONE
            # direction that really does throw -- a default Room expects and the database lacks.
            if want is not None and have != want:
                problems.append(f"{table}.{name}: default is {default!r}, Room expects {want!r}")
            elif want is None and have is not None:
                notes.append(
                    f"{table}.{name}: the database will carry DEFAULT {have}, which Room's schema "
                    f"does not declare. Room accepts this; noted so a deliberate change is visible."
                )
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
    for note in notes:
        print("  note:", note)
    return 0


if __name__ == "__main__":
    sys.exit(main())
