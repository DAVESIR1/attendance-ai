#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Attendance AI — development environment (idempotent, source this file)
#
#   source scripts/env.sh
# ---------------------------------------------------------------------------
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Android SDK: local.properties first, fallback to a sane default.
if [[ -f "${REPO_ROOT}/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | head -1)"
fi
export ANDROID_HOME="${SDK_DIR:-${ANDROID_HOME:-${HOME}/Android/Sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"

# JDK: prefer a local .jdks install if JAVA_HOME is unset.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "${HOME}/.jdks/jdk-17.0.20.1+1" "${HOME}/.jdks/jdk-21.0.12.1+1"; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi
if [[ -x "${JAVA_HOME}/bin/java" ]]; then
  export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"
fi

echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME}"
cd "${REPO_ROOT}" || exit 1