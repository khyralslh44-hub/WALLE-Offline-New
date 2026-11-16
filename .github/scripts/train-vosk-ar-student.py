#!/usr/bin/env python3
"""Build a lightweight Arabic speech student using Vosk as a teacher.

This is a starter distillation pipeline for a small, offline speech student:
- audio clips are transcribed by a Vosk Arabic teacher model;
- pseudo-labels are normalized and filtered;
- a compact MFCC-based Keras model is trained on a fixed vocabulary;
- the student is exported to TFLite for on-device use.

It is intentionally scoped to a lightweight, closed-vocabulary student and is not a
full end-to-end large ASR training pipeline.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

import numpy as np
import soundfile as sf
import tensorflow as tf
from scipy import signal

try:
    from vosk import Model, KaldiRecognizer
except Exception as exc:  # pragma: no cover
    raise SystemExit(f"Vosk Python package is required. Install with: pip install vosk. Details: {exc}")


def normalize_text(text: str) -> str:
    s = str(text).strip()
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", s)
    s = s.replace("إ", "ا").replace("أ", "ا").replace("آ", "ا").replace("ٱ", "ا")
    s = s.replace("ى", "ي")
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def load_audio(path: Path, target_sr: int = 16_000) -> np.ndarray:
    audio, sr = sf.read(path, dtype="float32", always_2d=False)
    if audio.ndim == 2:
        audio = audio.mean(axis=1)
    if sr != target_sr:
        num = max(1, int(round(len(audio) * target_sr / sr)))
        audio = signal.resample(audio, num)
    if audio.size == 0:
        raise ValueError(f"Audio is empty: {path}")
    return audio.astype(np.float32)


def mfcc_features(path: Path, target_sr: int = 16_000, n_mfcc: int = 26) -> np.ndarray:
    import librosa

    audio = load_audio(path, target_sr=target_sr)
    mfcc = librosa.feature.mfcc(
        y=audio,
        sr=target_sr,
        n_mfcc=n_mfcc,
        n_fft=400,
        hop_length=160,
        win_length=400,
    )
    feat = mfcc.mean(axis=1)
    return feat.astype(np.float32)


def transcribe_with_vosk(audio_path: Path, model_dir: Path, target_sr: int = 16_000) -> str:
    model = Model(str(model_dir))
    audio = load_audio(audio_path, target_sr=target_sr)
    recognizer = KaldiRecognizer(model, target_sr)
    chunks = np.asarray(audio, dtype=np.float32)
    chunk_size = 4000
    for start in range(0, len(chunks), chunk_size):
        chunk = chunks[start:start + chunk_size]
        recognizer.AcceptWaveform(chunk.tobytes())
    final = recognizer.FinalResult()
    try:
        payload = json.loads(final)
        text = payload.get("text", "")
    except json.JSONDecodeError:
        text = ""
    return normalize_text(text)


def collect_audio_files(dataset_root: Path) -> list[Path]:
    patterns = ["**/*.wav", "**/*.WAV", "**/*.flac", "**/*.mp3"]
    files: list[Path] = []
    for pattern in patterns:
        files.extend(dataset_root.glob(pattern))
    seen = set()
    ordered: list[Path] = []
    for path in sorted(files):
        resolved = path.resolve()
        if resolved not in seen:
            seen.add(resolved)
            ordered.append(path)
    return ordered


def iter_dataset(dataset_root: Path):
    data = []
    for audio_path in collect_audio_files(dataset_root):
        text_path = audio_path.with_suffix(".txt")
        if text_path.exists():
            transcript = normalize_text(text_path.read_text(encoding="utf-8"))
        else:
            transcript = ""
        data.append((audio_path, transcript))
    return data


def pseudo_label_dataset(dataset_root: Path, model_dir: Path) -> list[tuple[Path, str]]:
    labeled: list[tuple[Path, str]] = []
    for audio_path, existing in iter_dataset(dataset_root):
        transcript = existing
        if not transcript:
            transcript = transcribe_with_vosk(audio_path, model_dir)
        if not transcript:
            continue
        labeled.append((audio_path, transcript))
    return labeled


def build_vocab(texts: list[str], max_labels: int = 32, min_count: int = 2) -> tuple[dict[str, int], list[str]]:
    counts = Counter()
    for text in texts:
        for word in text.split():
            counts[word] += 1
    filtered = {word: count for word, count in counts.items() if count >= min_count}
    vocab = dict(sorted(filtered.items(), key=lambda kv: (-kv[1], kv[0]))[:max_labels])
    labels = [word for word in vocab]
    label_to_index = {word: idx for idx, word in enumerate(labels)}
    return label_to_index, labels


def encode_label(label: str, label_to_index: dict[str, int]) -> int:
    return label_to_index.get(label, 0)


def prepare_training_examples(label_to_index: dict[str, int], labeled: list[tuple[Path, str]], n_mfcc: int = 26):
    inputs = []
    targets = []
    for audio_path, transcript in labeled:
        words = transcript.split()
        if not words:
            continue
        # For a lightweight student, classify by the most salient token in the transcript.
        # This keeps the student small, deterministic, and aligned with a closed Arabic command vocabulary.
        chosen = max(words, key=lambda w: len(w))
        if chosen not in label_to_index:
            continue
        feat = mfcc_features(audio_path, n_mfcc=n_mfcc)
        inputs.append(feat.astype(np.float32))
        targets.append(label_to_index[chosen])
    if not inputs:
        raise ValueError("No valid examples were produced after pseudo-labeling. Check dataset quality or Vosk model compatibility.")
    X = np.stack(inputs, axis=0)
    y = np.asarray(targets, dtype=np.int32)
    return X, y


def build_student_model(num_classes: int, input_dim: int = 26):
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(input_dim,), name="mfcc"),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(num_classes, activation="softmax", name="class_logits"),
    ])
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss=tf.keras.losses.SparseCategoricalCrossentropy(),
        metrics=[tf.keras.metrics.SparseCategoricalAccuracy(name="acc")],
    )
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a compact Arabic speech student using Vosk as a teacher.")
    parser.add_argument("--dataset-root", type=Path, required=True, help="Directory containing audio files and optional .txt transcripts.")
    parser.add_argument("--model-dir", type=Path, required=True, help="Unpacked Vosk Arabic model directory.")
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--output-dir", type=Path, default=Path("dist/vosk-ar-student"))
    parser.add_argument("--max-labels", type=int, default=32)
    parser.add_argument("--min-count", type=int, default=2)
    args = parser.parse_args()

    if not args.dataset_root.exists():
        raise SystemExit(f"Dataset directory does not exist: {args.dataset_root}")
    if not args.model_dir.exists():
        raise SystemExit(f"Vosk model directory does not exist: {args.model_dir}")

    labeled = pseudo_label_dataset(args.dataset_root, args.model_dir)
    if not labeled:
        raise SystemExit(
            "No usable examples were pseudo-labeled. Provide WAV/FLAC audio files or pre-written .txt files aligned to them."
        )

    texts = [transcript for _, transcript in labeled]
    label_to_index, labels = build_vocab(texts, max_labels=args.max_labels, min_count=args.min_count)
    if not labels:
        raise SystemExit("Not enough repeated words to build a closed vocabulary. Add more Arabic audio or lower --min-count.")

    X, y = prepare_training_examples(label_to_index, labeled, n_mfcc=26)
    model = build_student_model(len(labels), input_dim=X.shape[1])
    history = model.fit(X, y, epochs=args.epochs, batch_size=args.batch_size, validation_split=0.1, verbose=2)

    out_dir = args.output_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    (out_dir / "arabic_vosk_student.tflite").write_bytes(tflite_model)

    metadata = {
        "student_type": "compact-arabic-mfcc-classifier",
        "teacher": "vosk-arabic",
        "teacher_model_dir": str(args.model_dir),
        "labels": labels,
        "training_examples": int(len(X)),
        "last_epoch_loss": float(history.history["loss"][-1]),
        "last_epoch_acc": float(history.history["acc"][-1]),
        "max_labels": args.max_labels,
        "min_count": args.min_count,
        "notes": "This is a small prototype trained from Vosk pseudo labels; not a full general-purpose Arabic ASR system.",
    }
    (out_dir / "arabic_vosk_student.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "trained", "labels": labels, "examples": int(len(X)), "output_dir": str(out_dir)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
