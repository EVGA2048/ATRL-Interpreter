# Development log

## 0.6.2

- Add acoustic language probing and segmented transcription for Chinese, English and Russian code-switching.
- Keep recognized source text unchanged by cloud translation.
- Fifteen JVM regression tests passed.

## 0.6.1

- Show capture timestamps on each caption pair in live and saved sessions.

## 0.6.0

- Optional Russian streaming preview using T-one CTC; final recognition remains Whisper.
- Tap a word in a completed caption to edit its sentence. Re-translate edited source text and retain manual revisions in session history.
- Highlight inserted or replaced tokens; reject stale translation results.
- Thirteen JVM regression tests passed.

## 0.5.2

- Restore Whisper and VAD parameters from 0.4.1.
- Reject repetitive or implausible provisional hypotheses; retry abnormal final recognition and retain unresolved source text in the session.
- Preserve Russian technical phrases with Latin abbreviations and process Russian cloud translations in transcript order.
- Distinguish provisional local translation from cloud output.
- Eleven JVM regression tests passed.

## 0.5.1

- Refine navigation glyphs, translation editor and result actions.
- Compact capture controls and render a rolling history of microphone levels.
- Refine bilingual typography and group sessions by date.
- Debug build and eight JVM tests passed.

## 0.5.0

- SenseVoice INT8 assists Chinese–English code-switching. Russian remains on Whisper Base.
- Reduce Whisper tail padding and VAD endpoint delay; preheat ASR on opening the live page.
- Up to three concurrent live translation requests; captions update in place and TTS follows transcript order.
- Animate new provisional text, translated captions and the input meter.
- Local / online mode selector on translation and interpretation pages. Local mode disables DeepSeek requests and uses offline system voices.
- Debug build and eight JVM tests passed. Synthetic mixed-language audio reproduced the Whisper translation issue and SenseVoice retained both scripts. Android latency and mixed-speech accuracy await device testing.

## 0.4.1 — 2026-11-25

- API failures no longer trigger a fresh request for every queued segment: transient failures use a 15-second cooldown, HTTP 429 defaults to 30 seconds and respects numeric Retry-After (bounded to 1–300 seconds); authentication/configuration errors wait for settings save or configuration change.
- Only approved terms matching the current text or three preceding segments enter the cloud glossary prompt, at most 24 entries.
- Audio level updates limited to roughly 12.5 Hz; recognition overload and decoding warnings remain visible separately from normal recording status.
- Ignore typed translation results if text or target changed while the request was in flight.
- Four JVM tests cover the 60-second grouping boundary, confirmed and relevant term selection, credential changes and API retry intervals.
- Debug build and four JVM tests passed.

## 0.4.0

- Continuous chronological transcript, provisional gray text, paired local/cloud translations, fixed controls, scroll following and one-minute grouping based on audio timestamps.
- Whisper repeated decoding is opportunistic: preview work runs when the ASR queue is idle. Final VAD segments are decoded independently; previews may be skipped on slower hardware.
- Offline and cloud live translation run on independent serial workers.
- Grouped glossary with language/category filtering, searchable examples and confirmation workflow.
- Initial deployment on Huawei P30 Pro.

## 0.3.0

- Replaced demo homepage with functional tabs; removed repeated titles and debug captions.
- Upgraded to sherpa-onnx 2.0.3 and multilingual Whisper Base INT8 with zh/en/ru language candidates.
- Fixed target selector to display the resolved target language rather than the internal Auto mode.
- Installed on Huawei P30 Pro and launched.

## 0.2.0 / 0.1.0

- Initial Compose UI, microphone capture, encrypted credentials, local recognition, ML Kit, DeepSeek enhancement, session summaries, glossary, TTS and foreground microphone service.
