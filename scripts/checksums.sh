#!/usr/bin/env bash
# Print SHA-256 of every model file in models/.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}" && sha256sum models/*