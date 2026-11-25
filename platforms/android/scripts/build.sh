#!/usr/bin/env bash
# Build the Android JDK images.
# Usage: JOBS=4 bash platforms/android/scripts/build.sh [make targets...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/platforms/android/config/env.sh"

CONF="${CONF:-linux-aarch64-server-release}"
JOBS="${JOBS:-4}"
TARGETS=("$@")
if [ "${#TARGETS[@]}" -eq 0 ]; then
  TARGETS=(images)
fi

cd "$SRC_DIR"
echo "==> make CONF=$CONF JOBS=$JOBS ${TARGETS[*]}"
exec make CONF="$CONF" JOBS="$JOBS" "${TARGETS[@]}"
