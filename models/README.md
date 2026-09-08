# Models

Two files are required. They are **not committed** (see `.gitignore`) and are
verified by SHA-256 before inference starts.

| File | Purpose | License | Source |
|------|---------|---------|--------|
| `face_landmarker.task` | MediaPipe Face Landmarker (478 landmarks/face), used for face detection + geometric alignment. | Apache-2.0 | `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task` |
| `mobilefacenet.tflite` | MobileFaceNet face-embedding model (112×112 RGB input). | **Verify the license yourself** — it is an open conversion, not an official TensorFlow release. | `MOBILEFACENET_URL` (see below) or manual placement |

## How to get them

```bash
# face_landmarker.task (downloads + verifies against checksums.sha256)
scripts/fetch_models.sh

# mobilefacenet.tflite: either
#   a) drop the file into models/ yourself, then run scripts/fetch_models.sh
#      to record and verify its hash, or
#   b) point the script at a conversion you trust:
MOBILEFACENET_URL=https://your-mirror/mobilefacenet.tflite scripts/fetch_models.sh
```

The script **never fabricates a checksum**: it pins `face_landmarker.task`
automatically and asks you to explicitly approve the hash of any other file.

## Checksums

`checksums.sha256` follows the `sha256sum` format (`<hash>  <filename>`).
`scripts/checksums.sh` prints current hashes; the app validates loaded models
against this file via `model/ModelIntegrity`.
