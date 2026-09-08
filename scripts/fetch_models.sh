#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Attendance AI — model fetcher with SHA-256 verification
#
# Downloads the MediaPipe Face Landmarker task (Apache-2.0, verified) and,
# when a URL is provided, a MobileFaceNet TFLite conversion. Every download
# is checked against models/checksums.sha256; freshly downloaded files whose
# hash is not yet recorded are PRINTED so you can pin them deliberately.
#
#   MOBILEFACENET_URL=... ./scripts/fetch_models.sh
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${REPO_ROOT}/models"
ASSETS_DIR="${REPO_ROOT}/src/main/assets"
CHECKSUM_FILE="${MODELS_DIR}/checksums.sha256"

FACE_LANDMARKER_URL="${FACE_LANDMARKER_URL:-https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task}"
MOBILEFACENET_URL="${MOBILEFACENET_URL:-}"

mkdir -p "${MODELS_DIR}" "${ASSETS_DIR}"

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

expect_hash() {
  local file="$1" expected="$2"
  local got
  got="$(sha256_of "${file}")"
  if [[ "${got}" != "${expected}" ]]; then
    echo "ERROR: ${file} hash mismatch" >&2
    echo "  expected: ${expected}" >&2
    echo "  got:      ${got}" >&2
    return 1
  fi
  echo "OK:   ${file} (sha256 ${got})"
}

download() {
  local url="$1" dest="$2"
  echo "Downloading ${url}"
  curl -fL --retry 3 --max-time 300 -o "${dest}.part" "${url}"
  mv "${dest}.part" "${dest}"
}

known_hash() {
  local file="$1"
  local base
  base="$(basename "${file}")"
  [[ -f "${CHECKSUM_FILE}" ]] || return 1
  awk -v f="${base}" 'NF == 2 && $2 == f {print $1; exit}' "${CHECKSUM_FILE}"
}

# ---- face_landmarker.task (canonical URL, hash pinned in checksums file) ----
if [[ ! -f "${MODELS_DIR}/face_landmarker.task" ]]; then
  download "${FACE_LANDMARKER_URL}" "${MODELS_DIR}/face_landmarker.task"
fi
if expected="$(known_hash face_landmarker.task)"; then
  expect_hash "${MODELS_DIR}/face_landmarker.task" "${expected}"
else
  echo "NOTE: face_landmarker.task present but not pinned in ${CHECKSUM_FILE}"
  echo "      pin it with:     echo \"$(sha256_of "${MODELS_DIR}/face_landmarker.task")  face_landmarker.task\"" \
    ">> ${CHECKSUM_FILE}"
fi

# ---- mobilefacenet.tflite (requires you to accept its license) --------------
if [[ -f "${MODELS_DIR}/mobilefacenet.tflite" ]]; then
  if expected="$(known_hash mobilefacenet.tflite)"; then
    expect_hash "${MODELS_DIR}/mobilefacenet.tflite" "${expected}"
  else
    echo "NOTE: mobilefacenet.tflite present but unpinned; record its hash:"
    echo "      echo \"$(sha256_of "${MODELS_DIR}/mobilefacenet.tflite")  mobilefacenet.tflite\" >> ${CHECKSUM_FILE}"
  fi
elif [[ -n "${MOBILEFACENET_URL}" ]]; then
  download "${MOBILEFACENET_URL}" "${MODELS_DIR}/mobilefacenet.tflite"
  echo "Downloaded mobilefacenet.tflite."
  echo "VERIFY IT IS THE EXPECTED CONVERSION, then pin the hash:"
  echo "      echo \"$(sha256_of "${MODELS_DIR}/mobilefacenet.tflite")  mobilefacenet.tflite\" >> ${CHECKSUM_FILE}"
else
  echo "mobilefacenet.tflite not found and MOBILEFACENET_URL is empty."
  echo "Place the file manually in ${MODELS_DIR} (see models/README.md)."
fi

# ---- stage into app assets so Gradle bundles the models ----------------------
cp -f "${MODELS_DIR}"/face_landmarker.task "${ASSETS_DIR}"/face_landmarker.task 2>/dev/null || true
cp -f "${MODELS_DIR}"/mobilefacenet.tflite "${ASSETS_DIR}"/mobilefacenet.tflite 2>/dev/null || true
echo "Staged models into ${ASSETS_DIR}"
