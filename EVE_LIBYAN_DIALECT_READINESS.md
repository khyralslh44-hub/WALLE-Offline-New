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
| Alexandria LY | Multi-turn English–Libyan translation resource; CC BY-NC 4.0 | Current public LY directory exposes a test parquet, so it must not be used for training. |
| Libyan sub-regional speech benchmark | 133.7 hours represented by segment manifests and cached embeddings; CC BY 4.0 for those data, not the underlying audio | Useful for regional speech identification, not text dialogue training. |
| Libyan restaurant-review sentiment corpus | 4,609 positive/negative Libyan reviews; CC BY 4.0 | Could support a narrow review-sentiment component, not a general conversational model. |

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