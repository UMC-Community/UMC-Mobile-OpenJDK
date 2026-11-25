#!/usr/bin/env bash
# Fetch the Android upstream pinned in upstream/upstream.json (shallow clone).
# Usage: bash platforms/android/scripts/fetch-upstream.sh [ref]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/platforms/android/config/env.sh"

UPSTREAM_REPO="https://github.com/openjdk/jdk21u"
# Keep in sync with upstream/upstream.json
REF="${1:-jdk-21.0.14+3}"

if [ -d "$SRC_DIR/.git" ]; then
  echo "$SRC_DIR already exists; skipping clone (update it manually if needed)"
  exit 0
fi

mkdir -p "$(dirname "$SRC_DIR")"
echo "==> cloning $UPSTREAM_REPO @ $REF"
git clone --depth 1 --branch "$REF" "$UPSTREAM_REPO" "$SRC_DIR"
