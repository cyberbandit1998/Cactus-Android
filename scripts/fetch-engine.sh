#!/usr/bin/env bash
# Downloads the official Needle engine for Android (arm64) into native-engine/android-arm64/.
#
# Source: Hugging Face repo Cactus-Compute/needle3, folder android-arm64/ (this is what the upstream
# `needle download android-arm64` command fetches). The files are discovered through the Hugging Face
# API, not guessed, and checked against the SHA-256 the API publishes for large files.
#
#   scripts/fetch-engine.sh [--revision <commit-or-branch>] [--repo <owner/name>] [--folder <name>]
set -euo pipefail

REPO="Cactus-Compute/needle3"
FOLDER="android-arm64"
REVISION="main"
while [ $# -gt 0 ]; do
  case "$1" in
    --revision) REVISION="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --folder) FOLDER="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/native-engine/$FOLDER"
API="https://huggingface.co/api/models/$REPO"
mkdir -p "$DEST"

need() { command -v "$1" >/dev/null 2>&1 || { echo "error: '$1' is required" >&2; exit 1; }; }
need curl
need python3

echo "Engine source: $REPO @ $REVISION, folder $FOLDER"
COMMIT="$(curl -fsSL "$API/revision/$REVISION" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sha",""))' || true)"
[ -n "$COMMIT" ] && echo "Resolved commit: $COMMIT" && REVISION="$COMMIT"

list_files() { # prints "path<TAB>size<TAB>sha256-or-dash" for every file below $1
  curl -fsSL "$API/tree/$REVISION/$1" | python3 -c '
import json, sys
for e in json.load(sys.stdin):
    if e.get("type") == "file":
        lfs = e.get("lfs") or {}
        print("%s\t%s\t%s" % (e["path"], lfs.get("size") or e.get("size", 0), lfs.get("oid") or "-"))
    elif e.get("type") == "directory":
        print("DIR\t%s\t-" % e["path"])
'
}

walk() {
  list_files "$1" | while IFS=$'\t' read -r path size sha; do
    if [ "$path" = "DIR" ]; then walk "$size"; continue; fi
    name="$(basename "$path")"
    target="$DEST/$name"
    echo "  downloading $path ($size bytes)"
    curl -fL --retry 3 --retry-delay 2 -o "$target.part" "https://huggingface.co/$REPO/resolve/$REVISION/$path"
    if [ "$sha" != "-" ]; then
      actual="$(sha256sum "$target.part" | cut -d' ' -f1)"
      if [ "$actual" != "$sha" ]; then rm -f "$target.part"; echo "error: SHA-256 mismatch for $path" >&2; exit 1; fi
    fi
    mv "$target.part" "$target"
    case "$name" in needle|*.sh) chmod +x "$target" ;; esac
  done
}

walk "$FOLDER"

{
  echo "repo: $REPO"
  echo "folder: $FOLDER"
  echo "commit: ${COMMIT:-unknown}"
  echo "fetched: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$DEST/ENGINE_SOURCE.txt"

echo
echo "Files in $DEST:"
ls -la "$DEST"
if ! ls "$DEST"/*.a >/dev/null 2>&1; then
  echo >&2
  echo "error: no static library (*.a) was found in $FOLDER/. The upstream layout may have changed." >&2
  echo "Alternative: pip install cactus-needle && needle download android-arm64 --out native-engine" >&2
  exit 1
fi
echo "OK: engine library present."
