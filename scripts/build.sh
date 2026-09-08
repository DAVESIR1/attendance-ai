#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Attendance AI — one-shot build helper
#   scripts/build.sh [test]   (pass 'test' to also run the JVM unit tests)
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/env.sh
source "${REPO_ROOT}/scripts/env.sh"

./gradlew --no-daemon assembleRelease || ./gradlew --no-daemon assembleDebug

if [[ "${1:-}" == "test" ]]; then
  ./gradlew --no-daemon test
fi