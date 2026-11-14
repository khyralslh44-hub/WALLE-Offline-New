# EVE: Teacher → Student Distillation (Qwen3)

This is a real supervised response-distillation path for the **same Qwen3-0.6B family used by the current Android runtime**.

## Model roles

- Teacher: `Qwen/Qwen3-4B-Instruct-2507`, loaded in 4-bit only in the cloud GPU session.
- Student: `Qwen/Qwen3-0.6B`, initialized from pretrained weights then adapted with LoRA on the teacher's EVE-specific Arabic responses.
- Export: merged student checkpoint converted to LiteRT-LM `.litertlm` using the INT4 weight-only recipe `weight_only_wi4_afp32`.

The teacher is never included in the Android app and is not used at inference time. The distillation examples are synthetic and project-specific. No personal conversations, face images, private memories, or user files are uploaded to the teacher.

## Run on Kaggle

Open `eve-model/notebooks/kaggle_distill_eve.ipynb`, choose GPU T4, enable Internet, and run all cells. The notebook refuses to continue unless CUDA is available; it must not train on CPU.

The end-to-end script performs:
1. Teacher generation of Arabic examples across identity, mood, emotion, vision uncertainty, local memory, curiosity, planning, autonomy, reasoning, games, privacy, voice, offline behavior, and correction.
2. A deterministic train/validation split.
3. LoRA SFT of Qwen3-0.6B using assistant-only loss.
4. LoRA merge into a Hugging Face student checkpoint.
5. LiteRT-LM export with INT4 weight-only quantization.
6. LiteRT-LM command-line inference smoke test and a SHA-256 manifest.

Expected outputs under `/kaggle/working/eve-distillation/`:
- `teacher_distilled_eve.jsonl`
- `training_metrics.json`
- `student_evaluation.json`
- `eve_qwen3_0.6b_distilled_hf/`
- `eve_qwen3_0.6b_distilled_litert/`
- `distillation_manifest.json`
- `litert_smoke_test.txt`

The notebook then saves these as a Kaggle output artifact.

## Important acceptance criteria

A successful notebook submission is **not** a completed training run. Accept the new student only when:
- Kaggle logs confirm CUDA GPU allocation and LoRA training completion.
- `training_metrics.json` contains finite train and validation loss.
- `student_evaluation.json` is reviewed for truthful, natural Arabic and non-repetitive responses.
- A non-empty `.litertlm` file is exported and `litert-lm run` smoke test succeeds.
- The SHA-256 in the manifest matches the downloaded file.
- The replacement model is integrated and tested in Android before claiming phone readiness.

This is persona/behavior response distillation, not from-scratch pretraining. General knowledge remains bounded by the pretrained 0.6B base model and should not be described as equivalent to the 4B teacher.

## Install training/export dependencies

```bash
pip install -U "transformers>=4.55,<5" "peft>=0.17" "accelerate>=1.10" "bitsandbytes>=0.46" "litert-torch>=0.10" "litert-lm>=0.19" safetensors
```
