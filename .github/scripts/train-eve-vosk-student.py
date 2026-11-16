#!/usr/bin/env python3
"""Train a compact Arabic CTC student from Vosk pseudo-transcripts.

The compact acoustic network is initialized from scratch; Vosk hypotheses are
transcript targets. This is NOT logit-level knowledge distillation. Evaluation
uses held-out human references and must gate any Android replacement.
"""
import argparse, json, os, re
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from datasets import Dataset
from transformers import (
    AutoProcessor, Trainer, TrainingArguments,
    Wav2Vec2Config, Wav2Vec2ForCTC,
)

def norm(text):
    text = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(text or ""))
    text = re.sub(r"[إأآٱ]", "ا", text)
    text = re.sub(r"ى", "ي", text)
    text = re.sub(r"[^\w\s]", " ", text, flags=re.UNICODE)
    return " ".join(text.split())

def read_jsonl(path):
    return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]

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
    ap.add_argument("--tokenizer-model", default="jonatasgrosman/wav2vec2-large-xlsr-53-arabic")
    ap.add_argument("--epochs", type=float, default=12.0)
    ap.add_argument("--batch-size", type=int, default=2)
    ap.add_argument("--hidden-size", type=int, default=256)
    ap.add_argument("--layers", type=int, default=4)
    ap.add_argument("--heads", type=int, default=4)
    args = ap.parse_args()
    if args.hidden_size % args.heads:
        raise SystemExit("--hidden-size must be divisible by --heads")
    torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
    root, out = Path(args.corpus), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    train_rows = read_jsonl(root / "msa_train.jsonl")
    eval_rows = read_jsonl(root / "msa_test.jsonl")
    processor = AutoProcessor.from_pretrained(args.tokenizer_model)
    train = prepare(train_rows, root, processor, True)
    test = prepare(eval_rows, root, processor, False)
    if len(train) < 20 or len(test) < 10:
        raise SystemExit(f"Insufficient usable examples: train={len(train)} test={len(test)}")

    # Compact Wav2Vec2: 7 narrow convolution stages + a small 4-layer encoder.
    # This is deliberately independent of the large XLS-R acoustic weights.
    config = Wav2Vec2Config(
        vocab_size=len(processor.tokenizer),
        hidden_size=args.hidden_size,
        num_hidden_layers=args.layers,
        num_attention_heads=args.heads,
        intermediate_size=args.hidden_size * 4,
        conv_dim=(128, 128, 128, 128, 128, 128, 128),
        conv_stride=(5, 2, 2, 2, 2, 2, 2),
        conv_kernel=(10, 3, 3, 3, 3, 2, 2),
        feat_proj_dropout=0.0,
        hidden_dropout=0.1,
        attention_dropout=0.1,
        final_dropout=0.1,
        layerdrop=0.05,
        ctc_loss_reduction="mean",
        pad_token_id=processor.tokenizer.pad_token_id,
        bos_token_id=processor.tokenizer.bos_token_id,
        eos_token_id=processor.tokenizer.eos_token_id,
    )
    model = Wav2Vec2ForCTC(config)
    params = sum(p.numel() for p in model.parameters())
    train_ds, eval_ds = Dataset.from_list(train), Dataset.from_list(test)

    def metrics(pred):
        pred_ids = np.argmax(pred.predictions, axis=-1)
        hyp = processor.batch_decode(pred_ids)
        labels = pred.label_ids.copy()
        labels[labels == -100] = processor.tokenizer.pad_token_id
        refs = processor.batch_decode(labels, group_tokens=False)
        return {"wer": wer(refs, hyp)}

    training_args = TrainingArguments(
        output_dir=str(out / "checkpoints"),
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=2,
        learning_rate=3e-4,
        warmup_ratio=0.1,
        weight_decay=0.01,
        num_train_epochs=args.epochs,
        fp16=False,
        evaluation_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        logging_steps=10,
        report_to=[],
        dataloader_num_workers=0,
        group_by_length=False,
        remove_unused_columns=False,
    )
    trainer = Trainer(
        model=model, args=training_args, train_dataset=train_ds,
        eval_dataset=eval_ds, data_collator=Collator(processor),
        tokenizer=processor, compute_metrics=metrics,
    )
    trainer.train()
    measured = trainer.evaluate()
    student_dir = out / "student"
    model.save_pretrained(student_dir, safe_serialization=True)
    processor.save_pretrained(student_dir)
    weight_bytes = sum(p.stat().st_size for p in student_dir.glob("*.safetensors"))
    report = {
        "student_architecture": "compact_wav2vec2_ctc",
        "initialization": "random acoustic weights; Arabic tokenizer/processor reused",
        "training_method": "Vosk transcript pseudo-label supervised CTC training; NOT logit-level knowledge distillation",
        "teacher": "vosk-model-ar-mgb2-0.4",
        "parameters": params,
        "parameter_count_millions": round(params / 1e6, 3),
        "safetensors_weight_bytes": weight_bytes,
        "safetensors_weight_mib": round(weight_bytes / (1024**2), 2),
        "train_examples": len(train),
        "heldout_human_reference_examples": len(test),
        "heldout_metrics": measured,
        "limitations": "Pilot corpus is small and MSA-oriented. Libyan speech is not included. This model is not integrated into Android and must not replace Vosk until it passes broader held-out and on-device tests."
    }
    (out / "training_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
