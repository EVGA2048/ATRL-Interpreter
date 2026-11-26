# Third-party components

The following components are downloaded by scripts/fetch-models.sh; model and runtime binaries are not stored in this repository.

- sherpa-onnx 2.0.3: https://github.com/k2-fsa/sherpa-onnx — Apache-2.0.
- Whisper Base INT8: OpenAI, https://github.com/openai/whisper — MIT; ONNX conversion and quantization by sherpa-onnx.
- SenseVoiceSmall INT8: FunAudioLLM / FunASR, Alibaba Group, https://huggingface.co/FunAudioLLM/SenseVoiceSmall — FunASR Model License; converted by sherpa-onnx.
- Silero VAD: https://github.com/snakers4/silero-vad — MIT.
- T-one: T-Software DC, https://github.com/voicekit-team/T-one — Apache-2.0.
- CED-Tiny INT8: Xiaomi, https://huggingface.co/mispeech/ced-tiny — upstream model card declares Apache-2.0. Training code at https://github.com/RicherMans/CED is GPL-3.0 and is not included. Converted by sherpa-onnx.
- AudioSet labels: https://research.google.com/audioset/download.html.
- ONNX Runtime: https://github.com/microsoft/onnxruntime — MIT; runtime license and third-party notices must be included in binary redistribution.
- Google ML Kit Translate: https://developers.google.com/ml-kit/terms.

See ../MODELS.md for pinned model artifacts and checksums. Interpreter's own source license remains undecided.
