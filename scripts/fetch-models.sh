#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs app/src/main/assets/asr
tmp=$(mktemp -d)
base=https://github.com/k2-fsa/sherpa-onnx/releases/download
curl -fL "$base/v2.0.3/sherpa-onnx-2.0.3.aar" -o app/libs/sherpa-onnx-2.0.3.aar
curl -fL "$base/asr-models/sherpa-onnx-whisper-base.tar.bz2" -o "$tmp/whisper.tar.bz2"
tar -xjf "$tmp/whisper.tar.bz2" -C "$tmp"
cp "$tmp"/sherpa-onnx-whisper-base/base-{encoder.int8.onnx,decoder.int8.onnx,tokens.txt} app/src/main/assets/asr/
curl -fL "$base/asr-models/silero_vad.onnx" -o app/src/main/assets/asr/silero_vad.onnx
echo "Temporary files: $tmp"

shasum -a 256 -c scripts/models.sha256
