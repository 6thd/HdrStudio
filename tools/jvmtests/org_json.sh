#!/usr/bin/env bash
# Builds the org.json jar used by the JVM tests, pinned to one source commit.
#   org.json (github.com/stleary/JSON-java) tag 20240303 = Maven org.json:json:20240303
#   commit f9b5587c87aaf02e4a5ed1991d48d6c05993624a (git object hash: the content pin)
# The Android SDK's android.jar contains org.json as stubs only; the tests put this jar first.
# usage: tools/jvmtests/org_json.sh <work_dir>   -> prints the jar path
# Offline: ORG_JSON_SRC=<a JSON-java checkout at that commit> skips the fetch (the commit is still checked).
set -euo pipefail
VER=20240303
COMMIT=f9b5587c87aaf02e4a5ed1991d48d6c05993624a
W=$(mkdir -p "$1/org-json" && cd "$1/org-json" && pwd)
JAR="$W/org-json-$VER.jar"
if [ -f "$JAR" ] && [ -f "$W/COMMIT" ] && [ "$(cat "$W/COMMIT")" = "$COMMIT" ]; then echo "$JAR"; exit 0; fi
SRC=${ORG_JSON_SRC:-$W/src}
if [ -z "${ORG_JSON_SRC:-}" ]; then
  rm -rf "$SRC"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$VER" https://github.com/stleary/JSON-java.git "$SRC" >&2
fi
GOT=$(git -C "$SRC" rev-parse HEAD)
[ "$GOT" = "$COMMIT" ] || { echo "org.json: commit $GOT, expected $COMMIT" >&2; exit 1; }
rm -rf "$W/classes" && mkdir -p "$W/classes"
javac --release 11 -nowarn -encoding UTF-8 -d "$W/classes" $(find "$SRC/src/main/java/org/json" -name '*.java' | LC_ALL=C sort) >&2
(cd "$W/classes" && find org -type f | LC_ALL=C sort > ../list && jar --create --file "$JAR" --date=2024-03-03T00:00:00Z @../list) >&2
echo "$COMMIT" > "$W/COMMIT"
echo "$JAR"
