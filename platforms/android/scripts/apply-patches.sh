#!/usr/bin/env bash
# Apply platforms/android/patches/*.patch in filename order to the upstream tree.
# The source tree must be a clean, pinned checkout (no local modifications).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/platforms/android/config/env.sh"

PATCH_DIR="$ROOT/platforms/android/patches"
cd "$SRC_DIR"

if [ -n "$(git status --porcelain)" ]; then
  echo "error: the source tree has uncommitted changes; run git reset --hard && git clean -fdx first" >&2
  exit 1
fi

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
if [ "${#patches[@]}" -eq 0 ]; then
  echo "no patches to apply"
  exit 0
fi

for p in "${patches[@]}"; do
  echo "==> applying $(basename "$p")"
  git apply --whitespace=fix "$p"
done

echo "done: ${#patches[@]} patch(es) applied"
