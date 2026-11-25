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
sensevoice=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09
curl -fL "$base/asr-models/$sensevoice.tar.bz2" -o "$tmp/sensevoice.tar.bz2"
tar -xjf "$tmp/sensevoice.tar.bz2" -C "$tmp" "$sensevoice/model.int8.onnx" "$sensevoice/tokens.txt"
cp "$tmp/$sensevoice/model.int8.onnx" app/src/main/assets/asr/sensevoice.int8.onnx
cp "$tmp/$sensevoice/tokens.txt" app/src/main/assets/asr/sensevoice-tokens.txt

tone=sherpa-onnx-streaming-t-one-russian-2025-09-08
curl -fL "$base/asr-models/$tone.tar.bz2" -o "$tmp/tone.tar.bz2"
tar -xjf "$tmp/tone.tar.bz2" -C "$tmp" "$tone/model.onnx" "$tone/tokens.txt"
cp "$tmp/$tone/model.onnx" app/src/main/assets/asr/tone-ru.onnx
cp "$tmp/$tone/tokens.txt" app/src/main/assets/asr/tone-ru-tokens.txt
ced=sherpa-onnx-ced-tiny-audio-tagging-2024-04-19
curl -fL "$base/audio-tagging-models/$ced.tar.bz2" -o "$tmp/ced.tar.bz2"
tar -xjf "$tmp/ced.tar.bz2" -C "$tmp" "$ced/model.int8.onnx" "$ced/class_labels_indices.csv"
cp "$tmp/$ced/model.int8.onnx" app/src/main/assets/asr/ced-tiny.int8.onnx
cp "$tmp/$ced/class_labels_indices.csv" app/src/main/assets/asr/ced-labels.csv
shasum -a 256 -c scripts/models.sha256
