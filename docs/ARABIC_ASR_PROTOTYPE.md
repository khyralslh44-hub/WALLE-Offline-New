# EVE Arabic ASR prototype — engineering decision

Status: research/prototype only. This branch does not replace the stable EVE speech engine.

## Current baseline

The current Android app bundles `vosk-model-ar-mgb2-0.4.zip` (318 MB) and loads it with Vosk/JNA in `OfflineArabicSpeech.kt`. The upstream Vosk model table reports 16.40 WER on MGB-2 dev. The model is much too large for the requested compact package, but it is currently the stronger documented general-Arabic baseline.

## Candidate: Moonshine Streaming Tiny Arabic (INT8 ONNX)

- Model card: https://huggingface.co/Sharjeelbaig/moonshine-streaming-tiny-ar-onnx
- Base model: `moonshine-ai/moonshine-streaming-tiny-ar`
- License stated by candidate card: MIT (verify upstream license and redistribution terms before shipping).
- 27 million parameters; Arabic vocabulary 12,288; mono 16 kHz input.
- Quantized encoder: 9.59 MB; quantized decoder: 18.00 MB; tokenizer: 1.44 MB; total about 28 MB.
- The candidate card's claimed 15–25 ms phrase latency is not independently verified on Redmi 12.
- It is not yet proven more accurate than Vosk for EVE's everyday conversational Arabic. Do not present it as better until it passes the same recorded test set.

## Important quality warning

A separate Arabic ASR benchmark listing reports Moonshine Streaming Tiny Arabic with an aggregate WER of about 50.08 on its open evaluation set. That figure is dataset-dependent and must not be compared directly with Vosk's MGB-2 number, but it is a warning that this tiny model may be unsuitable for unrestricted Arabic dictation. It may still be useful for short, constrained EVE commands after local evaluation.

## Integration plan

1. Keep the existing Vosk engine available as a fallback during experiments.
2. Add ONNX Runtime Android only in this prototype branch.
3. Implement the candidate's actual encoder/decoder contract and Arabic tokenizer; do not assume a generic Whisper or sherpa API is compatible.
4. Add a model-download/build step with pinned revision, SHA-256 checksums, and exact file-size checks. Do not check large model binaries into Git.
5. Build a repeatable Arabic evaluation set covering MSA plus Egyptian, Levantine, Gulf, and Maghrebi speech, male/female voices, quiet/noisy recordings, and EVE's real command phrases.
6. Measure WER/CER, command success, cold-start time, real-time factor, peak RAM, APK size, and battery/thermal behavior on Redmi 12.
7. Only replace the default engine if the candidate is demonstrably acceptable. Otherwise keep Vosk or use a two-tier design (tiny command recognizer + larger optional general transcription model).

## Acceptance gates

- Offline test succeeds with airplane mode enabled and no network permission/path used by ASR.
- No existing vision models or EVE features are removed.
- No claim of a 20 MB total package: the published quantized candidate is about 28 MB before runtime/tokenizer overhead.
- Report measured results, not estimates, before calling the prototype complete.

## Sources checked

- Vosk model list: https://alphacephei.com/vosk/models
- Moonshine Arabic INT8 ONNX model card: https://huggingface.co/Sharjeelbaig/moonshine-streaming-tiny-ar-onnx
- Arabic ASR benchmark: https://github.com/kadirnar/arabic-arena
