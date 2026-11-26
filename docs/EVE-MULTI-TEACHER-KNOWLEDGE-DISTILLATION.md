# EVE Multi-Teacher Arabic ASR Knowledge Distillation

Status: design specification; no model has been trained by this document.

## Safety and branch policy
- Work only on `eve-moonshine-arabic-trial`.
- Never modify or merge into `main` without explicit approval.
- Keep the installed Vosk recognizer as the production fallback.
- A candidate student is not eligible for Android integration until it passes held-out evaluation and Redmi 12 tests.

## Why the previous pilot is not knowledge distillation
The previous `train-eve-vosk-student.py` initializes a compact Wav2Vec2 acoustic model from random weights and trains CTC against Vosk transcript pseudo-labels. It does not consume teacher logits. Its report explicitly describes this as pseudo-label supervised CTC, not logit-level KD. The 12-epoch run reached 100% WER and must not be shipped.

## Proposed teacher/student design
- Teacher A: an Arabic Wav2Vec2/XLS-R CTC checkpoint with accessible frame-level logits (initial candidate: `jonatasgrosman/wav2vec2-large-xlsr-53-arabic`).
- Teacher B: Whisper for independent transcript hypotheses and disagreement detection. Whisper text output alone is pseudo-labeling, not logit KD. Use Whisper logits only if the selected implementation exposes compatible token-level distributions and alignment.
- Teacher C: Vosk as an independent transcript baseline / fallback. Standard Vosk API text output is not frame-level logits and must not be described as logit KD.
- Student: compact Wav2Vec2 CTC using the same tokenizer/vocabulary and compatible feature-frame stride as the CTC teacher. Initialize the student using a documented compression/initialization strategy; do not randomly initialize and assume a few hundred clips will teach ASR from scratch.

## Training objective
For the CTC teacher/student pair, align frame lengths and vocabulary IDs, then optimize a weighted objective:
- supervised CTC loss against verified human transcripts where available;
- pseudo-label CTC loss only for teacher-consensus examples that pass confidence and disagreement filters;
- temperature-scaled KL divergence between teacher and student frame-level distributions on valid audio frames.
Mask padded frames and blank/pad positions correctly. Log each loss component independently. Do not mix incompatible tokenizers or compare unaligned logits.

Whisper/Vosk transcript agreement can select or weight examples, but transcript agreement is not a substitute for teacher logits. Keep uncertain examples for review rather than silently treating consensus as ground truth.

## Data policy and splits
- Keep MSA-oriented read speech and Libyan speech as separately reported domains.
- Preserve speaker-disjoint train/validation/test splits; never move test clips into training.
- Keep human references for evaluation only.
- Record dataset source, exact license, attribution requirements, and permitted redistribution before downloading, training, or publishing derived weights. A dataset's availability does not automatically grant model-weight redistribution rights.
- Use bounded streaming scans for large datasets; do not scan hundreds of gigabytes without an explicit cap.
- Do not upload private recordings or raw voice data as public artifacts.

## Compute plan
- GitHub Actions CPU runners may validate code and prepare manifests, but should not be assumed suitable for large-teacher logit training.
- Use a GPU runtime such as Colab only when a GPU is actually allocated. Save checkpoints and manifests to durable storage between sessions; free GPU availability and session duration are not guaranteed.
- Pin package versions and model revisions. Record GPU type, runtime, seed, and all hyperparameters.

## Acceptance gates
1. Unit test audio resampling, tokenizer IDs, padding masks, and frame alignment.
2. Run a tiny smoke test that computes finite CTC and KL losses and performs one optimizer step.
3. Compare teacher, student, and Vosk on the same untouched human-reference test set.
4. Report WER/CER by domain, speaker, and noise condition, plus confidence intervals where feasible.
5. Measure model weight size, peak RAM, inference latency, and real-time factor on Redmi 12.
6. Keep Vosk in production unless the student consistently beats it on representative held-out tests and passes on-device reliability checks.

## Required artifacts for every experiment
- immutable training configuration and source commit;
- dataset/license manifest and split counts;
- teacher model IDs/revisions and tokenizer hash;
- CTC, KD, validation losses by epoch;
- WER/CER for every baseline and student on identical test clips;
- model size, latency, memory, and quantization measurements;
- explicit PASS/FAIL decision and known limitations.
