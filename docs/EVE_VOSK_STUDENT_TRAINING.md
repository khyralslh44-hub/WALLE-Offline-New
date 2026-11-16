# EVE Arabic student training — Vosk teacher stage

This branch adds a reproducible **teacher pseudo-label** stage. It does not replace the Vosk runtime in Android.

## What it does
1. Downloads the pinned `vosk-model-ar-mgb2-0.4` archive and checks its SHA-256.
2. Runs Vosk on up to 120 Egyptian Arabic FLEURS training clips.
3. Uses the FLEURS reference transcript only as a quality filter (default WER <= 0.45).
4. Saves accepted audio as 16 kHz mono WAV and Vosk hypotheses as `train.jsonl`.
5. Uploads the corpus and quality report as a GitHub Actions artifact.

The student's targets are Vosk hypotheses. The human/reference transcript is not substituted as the target. This is pseudo-label distillation at the transcript level; Vosk's normal API does not expose its internal frame-level posterior distributions, so this is not logit-level knowledge distillation.

## Run
Open Actions → **EVE Arabic Vosk Teacher Corpus** → Run workflow. Defaults: 120 clips, max WER 0.45. Download artifact `EVE-Vosk-Arabic-Teacher-Corpus`.

## Important limits
- This workflow creates the teacher corpus; it does **not** claim a student model has been trained.
- A real acoustic student needs a compatible trainable architecture, GPU training, held-out evaluation, and Android export/benchmarking. Do not ship or switch the app until that stage passes.
- Vosk's own guidance distinguishes vocabulary/language-model adaptation from acoustic fine-tuning and notes Kaldi training is resource-intensive: https://alphacephei.com/vosk/adaptation
