#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
GRADLE_VERSION=8.9
GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_HOME="$GRADLE_USER_HOME_DIR/opentasker/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
GRADLE_ZIP="${TMPDIR:-/tmp}/opentasker-gradle-$GRADLE_VERSION.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  mkdir -p "$GRADLE_USER_HOME_DIR/opentasker"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$GRADLE_URL" -o "$GRADLE_ZIP"
  elif command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "\$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing -Uri '$GRADLE_URL' -OutFile '$GRADLE_ZIP'"
  else
    echo "ERROR: curl or powershell.exe is required to download Gradle." >&2
    exit 1
  fi

  rm -rf "$GRADLE_HOME"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_USER_HOME_DIR/opentasker"
  elif command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "\$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('$GRADLE_ZIP', '$GRADLE_USER_HOME_DIR/opentasker')"
  else
    echo "ERROR: unzip or powershell.exe is required to extract Gradle." >&2
    exit 1
  fi
  rm -f "$GRADLE_ZIP"
fi

cd "$APP_HOME"
exec "$GRADLE_BIN" "$@"
