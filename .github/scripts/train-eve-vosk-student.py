#!/usr/bin/env python3
"""Fine-tune an Arabic CTC recognizer on Vosk pseudo-transcripts.

This is transcript-level teacher/student adaptation (not logit-level KD):
Vosk supplies targets; a pretrained Arabic acoustic model is the student.
Evaluation always uses held-out human reference text, never teacher hypotheses.
"""
import argparse, json, os, re
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from datasets import Dataset
from transformers import (
    AutoProcessor, AutoModelForCTC, Trainer, TrainingArguments
)

def norm(text):
    text = re.sub(r"[\\u064b-\\u065f\\u0670\\u0640]", "", str(text or ""))
    text = re.sub(r"[إأآٱ]", "ا", text)
    text = re.sub(r"ى", "ي", text)
    text = re.sub(r"[^\\w\\s]", " ", text, flags=re.UNICODE)
    return " ".join(text.split())

def read_jsonl(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows

def wer(refs, hyps):
    edits = words = 0
    def distance(a, b):
        prev = list(range(len(b)+1))
        for i, x in enumerate(a, 1):
            cur = [i]
            for j, y in enumerate(b, 1):
                cur.append(min(cur[-1]+1, prev[j]+1, prev[j-1]+(x != y)))
            prev = cur
        return prev[-1]
    for r, h in zip(refs, hyps):
        rw, hw = norm(r).split(), norm(h).split()
        edits += distance(rw, hw); words += len(rw)
    return edits / max(1, words)

class Collator:
    def __init__(self, processor):
        self.processor = processor
    def __call__(self, features):
        inputs = [{"input_values": f["input_values"]} for f in features]
        labels = [{"input_ids": f["labels"]} for f in features]
        batch = self.processor.pad(inputs, padding=True, return_tensors="pt")
        with self.processor.as_target_processor():
            label_batch = self.processor.pad(labels, padding=True, return_tensors="pt")
        batch["labels"] = label_batch["input_ids"].masked_fill(label_batch.attention_mask.ne(1), -100)
        return batch

def prepare(rows, root, processor, training):
    good = []
    for row in rows:
        audio_path = root / row.get("audio", "")
        text = row.get("text") if training else row.get("reference")
        if not audio_path.is_file() or not text:
            continue
        audio, sr = sf.read(audio_path, dtype="float32")
        if audio.ndim == 2:
            audio = audio.mean(axis=1)
        if sr != 16000:
            import librosa
            audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
        if len(audio) < 1600 or len(audio) > 16000 * 20:
            continue
        encoded = processor(audio, sampling_rate=16000)
        with processor.as_target_processor():
            labels = processor(text=norm(text)).input_ids
        if labels:
            good.append({"input_values": encoded.input_values[0], "labels": labels,
                         "reference": norm(row.get("reference", text))})
    return good

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-model", default="jonatasgrosman/wav2vec2-large-xlsr-53-arabic")
    ap.add_argument("--epochs", type=float, default=1.0)
    ap.add_argument("--batch-size", type=int, default=1)
    args = ap.parse_args()
    torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
    root, out = Path(args.corpus), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    train_rows = read_jsonl(root / "msa_train.jsonl")
    eval_rows = read_jsonl(root / "msa_test.jsonl")
    processor = AutoProcessor.from_pretrained(args.base_model)
    train = prepare(train_rows, root, processor, True)
    test = prepare(eval_rows, root, processor, False)
    if len(train) < 20 or len(test) < 10:
        raise SystemExit(f"Insufficient usable examples: train={len(train)} test={len(test)}")
    model = AutoModelForCTC.from_pretrained(args.base_model)
    model.freeze_feature_encoder()
    train_ds = Dataset.from_list(train)
    eval_ds = Dataset.from_list(test)
    def metrics(pred):
        logits = pred.predictions
        pred_ids = np.argmax(logits, axis=-1)
        hyp = processor.batch_decode(pred_ids)
        labels = pred.label_ids.copy()
        labels[labels == -100] = processor.tokenizer.pad_token_id
        refs = processor.batch_decode(labels, group_tokens=False)
        return {"wer": wer(refs, hyp)}
    training_args = TrainingArguments(
        output_dir=str(out / "checkpoints"), per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=1, gradient_accumulation_steps=4,
        learning_rate=1e-5, num_train_epochs=args.epochs, fp16=False,
        evaluation_strategy="epoch", save_strategy="epoch", logging_steps=10,
        report_to=[], dataloader_num_workers=0, gradient_checkpointing=True,
        group_by_length=False, remove_unused_columns=False,
    )
    trainer = Trainer(model=model, args=training_args, train_dataset=train_ds,
        eval_dataset=eval_ds, data_collator=Collator(processor), tokenizer=processor,
        compute_metrics=metrics)
    trainer.train()
    metrics_out = trainer.evaluate()
    model.save_pretrained(out / "student")
    processor.save_pretrained(out / "student")
    report = {
        "student": args.base_model,
        "training_method": "Vosk transcript pseudo-label fine-tuning; not logit-level distillation",
        "teacher": "vosk-model-ar-mgb2-0.4",
        "train_examples": len(train), "heldout_reference_examples": len(test),
        "heldout_metrics": metrics_out,
        "limitations": "MSA-oriented Common Voice only in this first run; Libyan is not included until its data pipeline and rights are verified. Not yet integrated into Android."
    }
    (out / "training_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
