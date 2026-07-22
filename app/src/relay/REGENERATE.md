# Regenerating the share-relay fixtures

The share-app feature (per-target relay APKs, see `core/share/relay/`) ships four **fixed** binary
fixtures under `app/src/main/assets/relay/`:

| Asset | What it is |
| --- | --- |
| `classes.dex` | The compiled relay logic — `RelayActivity` from `app/src/relay/java/…`. Package-independent (no `R`/resource refs), so it is byte-identical for every generated relay. |
| `AndroidManifest.tmpl` | The binary `AndroidManifest.xml` aapt2 produced from `app/src/relay/AndroidManifest.xml`. At runtime `RelayManifestTemplate` rebuilds only its string pool to substitute the per-relay package, label, and target package. |
| `resources.arsc` | The fixed resource table (one `mipmap/ic` entry = `0x7f010000` → `res/mipmap/ic.png`). Never patched. |
| `ic_placeholder.png` | Fallback icon; the builder normally overwrites `res/mipmap/ic.png` in the zip with the chosen icon. |

These are committed (not Gradle-generated) so `buildFork` never depends on invoking `aapt2`/`d8`.
Regenerate them **only** when `RelayActivity.java` or `app/src/relay/AndroidManifest.xml` changes:

```bash
SDK=~/android-sdk; BT="$SDK/build-tools/36.0.0"; JAR="$SDK/platforms/android-35/android.jar"
JAVAC=/usr/lib/jvm/java-21-openjdk-amd64/bin/javac
SRC=app/src/relay; OUT=app/src/main/assets/relay
tmp=$(mktemp -d)

# 1. classes.dex  (pure Java -> d8; no kotlin-stdlib, min-api 26)
"$JAVAC" -source 8 -target 8 -bootclasspath "$JAR" -d "$tmp/classes" \
    "$SRC/java/com/opentasker/relay/RelayActivity.java"
"$BT/d8" --min-api 26 --release --lib "$JAR" --output "$tmp/dex" \
    "$tmp/classes/com/opentasker/relay/RelayActivity.class"
cp "$tmp/dex/classes.dex" "$OUT/classes.dex"

# 2 + 3 + 4. binary manifest template + resources.arsc + placeholder icon
"$BT/aapt2" compile --dir "$SRC/res" -o "$tmp/compiled.zip"
"$BT/aapt2" link -o "$tmp/ref.apk" --manifest "$SRC/AndroidManifest.xml" -I "$JAR" "$tmp/compiled.zip"
( cd "$tmp" && unzip -oq ref.apk AndroidManifest.xml resources.arsc res/mipmap/ic.png )
cp "$tmp/AndroidManifest.xml" "$OUT/AndroidManifest.tmpl"
cp "$tmp/resources.arsc"      "$OUT/resources.arsc"
cp "$tmp/res/mipmap/ic.png"   "$OUT/ic_placeholder.png"
rm -rf "$tmp"
```

After regenerating, confirm the manifest template still carries the three placeholder strings
`com.opentasker.relay`, `Relay Placeholder`, `PLACEHOLDER_TARGET_PKG` (RelayManifestTemplate substitutes
those exact values) and that the icon resource id in `resources.arsc` is still `0x7f010000`
(`aapt2 dump resources` — hardcoded in `RelayManifestTemplate` as the icon ref). If the id changes,
update `RelayManifestTemplate`.
