# Development log

## 0.6.15

- Capture or select an image, run local OCR for Chinese, English and Russian, and translate with local or cloud services.
- Add reviewed photo text to a conversation or save it as a standalone record. Preserve source origin and OCR confidence in archives and AI summaries; do not retain photo originals in the archive.
- Include a reproducible fetch step for pinned OCR models. Camera, OCR quality and concurrent microphone behavior require device validation.

## 0.6.14

- Query account balance once at startup when cloud mode and network are available; defer the first check until connectivity returns if necessary. Share balance across screens and allow manual refresh.
- Show a small orange wallet warning for confirmed insufficient funds or available balance below CNY 5 / USD 1. Do not infer low funds from a failed request.
- Rename the settings section to API balance.

## 0.6.13

- Query DeepSeek account balance via the official read-only endpoint when opening settings or refreshing. Only use credentials configured for the official API host; respect local mode.
- Display available, topped-up and granted balances with currency and update time.
- Tighten ASTROLAB / Interpreter footer typography.

## 0.6.12

- Adopt inset grouped settings rows, consistent typography and section footers.
- Refine Chinese interface copy and retain project attribution.

## 0.6.11

- Reorganize settings and simplify copy; add the ASTROLAB copyright, developer credit and repository link.
- Show an orange offline indicator in network mode and nonmodal, debounced bottom notifications for disconnect/reconnect.

## 0.6.10

- Detect disconnected networks and avoid unnecessary cloud requests while preserving local transcripts.
- Derive pending AI summary status from persisted session contents, so offline sessions can be summarized after reconnecting or restarting the app.
- Interpret untranslated source text directly during AI summarization; require explicit action for historical records and respect local-only mode.

## 0.6.9

- Localize sound labels into Russian or English using nearby dialogue context; exclude labels from TTS and summary inputs.
- Generate detailed chronological session notes with confirmed outcomes, concrete fees and terms, follow-up actions, timestamps and uncertainties. Chunk long transcripts before synthesis.
- Render readable summary sections and support copying notes. Cloud API still required for generation.

## 0.6.8

- Add a compact translucent quick input bar to live interpretation. Submit typed text without interrupting the microphone.
- Preserve typed origin in session history with a distinct teal caption color, separate from correction highlighting.
- Allow text-only conversations to continue when recording starts, retaining pending translation jobs.

## 0.6.7

- Add a live interpretation header toggle to rotate translated captions 180 degrees without altering source text or archives.
- Increase ordinary VAD silence endpoint to 550 ms and maximum speech duration to 8 s; experimental mixed mode retains its previous settings.
- Reject severely incomplete Chinese alternative hypotheses and retain Chinese script in Russian mixed transcripts.

## 0.6.6

- Prefer SenseVoice for Chinese speech and add a Chinese-priority mode.
- Keep experimental language segmentation out of automatic recognition.
- Reject repetitive punctuation and symbol-only recognition or translation results; mark failed segments.
- Twenty JVM tests passed. Chinese routing was compared on three synthetic speech samples on macOS; device accuracy remains unverified.

## 0.6.5

- Improve acoustic language boundaries and probe English-dominant segments independently.
- Experimental CED Tiny sound event captions; classification may be skipped while speech recognition is busy.
- Emphasize session date and weekday; show full date and time in session details.
- Eighteen JVM tests passed. Mixed speech and sound event accuracy still require device testing.

## 0.6.4

- Normalize uppercase English emitted by SenseVoice, retaining common technical acronyms and matching Whisper casing when available.
- Mixed-language recognition remains experimental.

## 0.6.3

- Swipe left on a session to reveal Delete; tap again to confirm. Swiping never deletes directly.

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
