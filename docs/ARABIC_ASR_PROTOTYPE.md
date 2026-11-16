# EVE Arabic language stack — verified assessment and ASR prototype

Status: isolated research branch. No production speech engine has been replaced. No APK has yet been built or tested on Redmi 12.

## 1. Existing EVE Arabic language work (verified in this repository)

The project already contains several distinct Arabic layers:

- `OfflineArabicSpeech.kt`: Vosk-based local speech-to-text (ASR). It loads the bundled `vosk-model-ar-mgb2-0.4.zip`.
- `EveArabicTextUnderstanding.kt`: Arabic normalization, dialect aliases and conservative clitic/inflection variants.
- `EveTinyArabicBrain.kt`: a compact, deterministic intent classifier based on hashed Arabic character n-grams and class prototypes. It has curated examples and confidence/margin rejection. It is not an acoustic speech model and is not a general-purpose LLM.
- `EveArabicIntentRouter.kt`: dialogue intent/slot routing.
- `EveArabicSemanticComposer.kt`: bounded Arabic response composition grounded in EVE context; explicitly not a free-form generative LLM.
- Unit tests cover common intents, dialect normalization, uncertainty rejection, contextual replies, and grounding of vision claims.

This means the app's text-understanding and response layers are more developed than the current command-only `ArabicCommandEar` description suggests. The actual bottleneck for a small voice package is still the acoustic ASR model and its integration.

## 2. Current ASR baseline

The Android app currently bundles `vosk-model-ar-mgb2-0.4.zip` and loads it with Vosk/JNA. The model download script pins a SHA-256 and checks the expected Vosk model files before packaging. This is a real offline ASR path, but its model archive is too large for a 20 MB total target.

Do not remove this baseline until an alternative has passed comparative tests.

## 3. Candidate: official Moonshine Arabic Tiny Streaming

The official Moonshine model table currently lists Arabic Tiny Streaming as:
- 34 million parameters
- 15.5% WER on the official non-English streaming evaluation (400-clip macro)
- MIT license

The official table says these non-English streaming scores use its own evaluation panels and are not directly comparable with other benchmark harnesses. The candidate is worth a measured trial, but this WER does not establish broad dialect coverage or Redmi 12 performance.

Official model table: https://github.com/moonshine-ai/moonshine/blob/main/docs/models/available-models.md

## 4. Third-party quantized ONNX conversion

A third-party Arabic Moonshine ONNX conversion was previously identified with reported component sizes around 9.59 MB (encoder), 18.00 MB (decoder), and 1.44 MB (tokenizer), approximately 29 MB before app/runtime overhead. These are claims for that conversion, not official app-package size guarantees. Verify exact model files, hashes, tensor signatures, preprocessing, decoder loop, tokenizer, license chain, and output quality before integration.

Candidate card: https://huggingface.co/Sharjeelbaig/moonshine-streaming-tiny-ar-onnx

An independent Arabic benchmark repository lists `moonshine-streaming-tiny-ar` with substantially weaker aggregate results than the official 15.5% figure on its own open evaluation panel. Because datasets and evaluation protocols differ, these values must not be compared as if they were the same test. They do show why EVE needs its own fixed evaluation corpus.

Benchmark: https://github.com/kadirnar/arabic-arena

## 5. Integration decision

Do not replace Vosk yet. First create a separate ASR adapter and evaluate Moonshine in isolation. Preserve the existing EVE text brain and response pipeline; feed both engines the same audio corpus and compare transcripts and downstream EVE command success.

Before Android integration, confirm:
1. The ONNX model is a genuine compatible streaming Arabic model, with documented input/output tensor names and state handling.
2. The selected Android ONNX Runtime version supports the model's operators and ARM64 execution provider.
3. Streaming chunking, sample rate, normalization, tokenizer, and decoder state match the model's reference implementation.
4. Model files are pinned by immutable revision and SHA-256; no runtime network fetch is required.
5. License and redistribution terms are verified for every model/tokenizer asset.

## 6. Required EVE evaluation

Build one fixed, locally stored evaluation set with consented recordings:
- Modern Standard Arabic and Egyptian, Levantine, Gulf, and Maghrebi varieties.
- Multiple speakers and speaking rates.
- Quiet room, moderate background noise, and realistic phone-microphone recordings.
- Both EVE command phrases and ordinary conversational sentences.

Measure:
- WER and CER using a documented Arabic normalization policy.
- Exact intent accuracy and false-trigger rate after EVE's text brain.
- Cold-start time, streaming latency/real-time factor, peak RAM, CPU load, battery/thermal behavior.
- Downloaded model bytes, installed APK size, and extracted on-device storage.

Use identical audio, references, normalization, and hardware for Vosk and Moonshine. Publish per-dialect results, not only one aggregate.

## 7. Acceptance gates

- Airplane-mode test confirms ASR and the Arabic brain work with no network access.
- Existing vision, memory, personality, and game features remain intact.
- No claim that the total package is 20 MB unless measured APK and installed-storage sizes meet that target.
- No claim that Moonshine is more accurate than Vosk until the same EVE test set proves it.
- Redmi 12 performance must be measured on the physical device; CI build success is not device validation.

## 8. Isolated Moonshine candidate validation (trial branch)

The trial branch adds a separate CI workflow that resolves the candidate repository to an immutable Hugging Face commit, downloads the quantized encoder/decoder and tokenizer/config files, records SHA-256 hashes, and asks ONNX Runtime CPU to load both graphs and verify the documented tensor names.

This is deliberately an isolated gate. It does not alter `OfflineArabicSpeech.kt`, Gradle dependencies, APK assets, or the existing Vosk path. A successful structural check proves only that the files and graphs can be loaded; it does not prove end-to-end decoding, tokenizer compatibility in Android, Arabic conversational accuracy, real streaming, or Redmi 12 performance.

The candidate model card describes a 16 kHz mono phrase-recognition path and reports roughly 28 MB for quantized weights/tokenizer. Its sample decoder loop is autoregressive phrase decoding, so do not describe this ONNX conversion as verified live streaming. The next gate after structural validation is an actual audio inference test using the same fixed Arabic corpus for Vosk and Moonshine. Only after that should an Android adapter and APK-size/performance comparison be attempted.

## 9. Current outcome

The repository already has a bespoke lightweight Arabic text brain and semantic response layers. The next meaningful engineering task is a separate acoustic ASR prototype, not rewriting the existing text brain or claiming that a text intent classifier is speech recognition.
