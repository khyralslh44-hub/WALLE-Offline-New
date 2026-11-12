# EVE — Libyan Arabic readiness report

Checked: 12 November 2026

## Changes to the Android source

- Expanded the conservative Libyan phrase adapter with everyday forms such as `شن تبي`, `شن تدير`, `شن درت`, `شن صار`, `ما نبيش`, `ما نحبش`, `ما عنديش`, `مفيش`, `منين`, and `وينك`.
- Routed the intent router through the same normalization front-end so the Libyan phrase layer is not bypassed by a separate normalizer.
- Added unit tests for the added phrases. Unsupported factual questions remain unsupported instead of being guessed.

These are reviewed rules and tests, not learned neural weights.

## Public data reviewed

| Resource | What it provides | Decision |
|---|---|---|
| Lisan-Libyan | About 51K annotated Libyan tokens; CC BY 4.0 | Download currently requires affiliation with a recognized company, university, or institution and an official institutional email; not accessed. |
| Alexandria LY | Multi-turn English–Libyan translation resource; CC BY-NC 4.0 | The published split statistics report **zero Libyan training and development turns**; Libya is test-only/zero-shot. Test examples are not training data. |
| Libyan sub-regional speech benchmark | 133.7 hours represented by segment manifests and cached embeddings; CC BY 4.0 for those data, not the underlying audio | Useful for regional speech identification, not text dialogue training. |
| Libyan restaurant-review sentiment corpus | 4,609 positive/negative Libyan reviews; CC BY 4.0 | Could support a narrow review-sentiment component, not a general conversational model. |
| AlexandriaX back-translated pairs (Libyan subset) | 13,587 Libyan responses in a 348,787-row synthetic English→dialect dataset; CC BY-NC 4.0 | Research-only candidate, not a safe general dialogue source: each row is a first-turn pair, source provenance is not retained, and source-corpus terms differ. The dataset card explicitly says to check underlying terms. |

### Additional research-only training candidate

A newer dataset card for `NAMAA-Space/alexandria-backtranslated-pairs` reports 13,587 Libyan rows within 348,787 pairs. The target-side sentences are described as authentic dialectal Arabic gathered from public corpora and the English side is machine back-translated. The dataset is CC BY-NC 4.0; it has no conversation structure, each row is a first turn, no per-row source provenance is retained, and the card warns that underlying source-corpus redistribution terms differ. It is therefore useful to investigate for non-commercial research, but I have not used it to train or package an app model without resolving those source terms. See https://huggingface.co/datasets/NAMAA-Space/alexandria-backtranslated-pairs (rows 215–222, 263–287, 335–345).

## Why a general trained model is not packaged yet

The closest Libyan lexical corpus is gated to institutional users. The public conversation set's accessible LY file is a held-out test split. The larger speech release does not redistribute its audio or transcripts. The restaurant data is domain-specific. None is a lawful and representative substitute for a broad Libyan dialogue training split.

We do not claim to have trained a general Libyan conversational model or Libyan speech recognizer. We will not silently repurpose a sentiment dataset, a dialect-identification benchmark, or copyrighted podcast recordings as general dialogue training data.

## Next technical steps when an authorized training split exists

1. Obtain a Libyan conversational training corpus with redistribution and model-training terms suitable for the intended app.
2. Normalize orthography while preserving original text; deduplicate by source and split by speaker/source before training.
3. Train a compact model or dialect adapter, quantize it, and record its exact packaged byte size.
4. Evaluate on held-out west/east/south Libyan examples and out-of-domain everyday dialogue; track intent accuracy, response adequacy, and unsupported-question rejection.
5. Integrate the trained asset into EVE's Android source build and verify offline runtime and Redmi 12 latency.

## References

- Lisan listing: https://sinalab.github.io/resources/index.html
- Lisan access form: https://docs.google.com/forms/d/e/1FAIpQLSdcbwoxQ3nyqQkYi-6OeY7lvDmI9B1tqUSslALC-AJX6WqQGw/viewform
- Alexandria: https://huggingface.co/datasets/UBC-NLP/alexandria
- Libyan regional speech benchmark: https://huggingface.co/datasets/NourZohdy/libyan-subregional-did
- Libyan restaurant reviews: https://github.com/Mansour-Essgaer/Libyan-Resturant
## Latest build and measured audio-model cost

The source revision `8ad23101130cbc1d60c4d2b52a5e586205f5f2b3` passed Android unit tests and the APK packaging/signature checks in GitHub Actions run `43191476180`. The build uploaded a Redmi 12 release artifact named `WALL-E-APK-Redmi12` with an artifact archive size of **427,827,610 bytes**. This is not an 80 MB build.

The main size contributor is the bundled Arabic Vosk model archive `vosk-model-ar-mgb2-0.4.zip`, measured by the build as **318 MB**. It is a general Arabic speech-recognition model, not a Libyan dialect dialogue model. The Vosk model list reports it at 318 MB and reports 16.40 WER on the MGB-2 development set. See https://alphacephei.com/vosk/models

The 5–10 MB target discussed for a future Libyan text component is separate from the speech-recognition model. It has **not** been built or merged. Removing the 318 MB Vosk asset would reduce the APK substantially but would also remove bundled open-vocabulary offline microphone transcription unless a tested replacement is integrated. Do not silently call a build without that asset a full voice-capable offline build.
