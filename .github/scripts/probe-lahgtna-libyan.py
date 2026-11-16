#!/usr/bin/env python3
"""Small streaming probe for the Libyan subset of Lahgtna.

The script intentionally streams only a few dozen rows, transcribes them with the
current Vosk teacher model, and emits only aggregate metrics. It does not package
audio or transcripts.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np

from datasets import get_dataset_split_names, load_dataset

try:
    from vosk import KaldiRecognizer, Model
except Exception as exc:  # pragma: no cover - dependency guard
    raise SystemExit(f"Vosk Python package is required. Install with: pip install vosk. Details: {exc}")

DATASET = "oddadmix/dialectal-arabic-lahgtna-v2"
LIBYAN_LANGUAGE_KEYS = {"ly", "ly-lb", "lyly", "libyan", "libya", "libyan-arabic"}


def normalize_text(value):
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(value))
    s = s.replace("إ", "ا").replace("أ", "ا").replace("آ", "ا").replace("ٱ", "ا")
    s = s.replace("ى", "ي")
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return " ".join(s.split())


def is_libyan_language(value):
    if value is None:
        return False
    if isinstance(value, (list, tuple, set)):
        return any(is_libyan_language(v) for v in value)
    text = str(value).strip().lower().replace("_", "-")
    if not text:
        return False
    if text in LIBYAN_LANGUAGE_KEYS:
        return True
    return text.startswith("ly") or "libya" in text or "libyan" in text


def levenshtein_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def resample_audio(arr, sr):
    if sr == 16000:
        return arr.astype(np.float32)
    try:
        import librosa

        return librosa.resample(arr.astype(np.float32), orig_sr=sr, target_sr=16000)
    except Exception:
        try:
            from scipy import signal

            new_len = max(1, int(round(len(arr) * 16000 / sr)))
            return signal.resample(arr.astype(np.float32), new_len).astype(np.float32)
        except Exception as exc:  # pragma: no cover - explicit runtime error path
            raise RuntimeError(f"Unable to resample audio from {sr}Hz to 16000Hz: {exc}") from exc


def transcribe(model, audio):
    if not isinstance(audio, dict):
        raise TypeError(f"Expected audio to be a dict, got {type(audio).__name__}")
    if "array" not in audio:
        raise ValueError("Audio payload is missing the 'array' field")

    arr = np.asarray(audio["array"], dtype=np.float32)
    if arr.size == 0:
        raise ValueError("Audio array is empty")
    if arr.ndim == 2:
        arr = arr.mean(axis=1) if arr.shape[0] > arr.shape[1] else arr.mean(axis=0)
    if not np.isfinite(arr).all():
        raise ValueError("Audio array contains NaN/Inf values")

    sr = int(audio.get("sampling_rate", 16000))
    arr = resample_audio(arr, sr)
    pcm = (np.clip(arr, -1.0, 1.0) * 32767).astype("<i2").tobytes()

    recognizer = KaldiRecognizer(model, 16000)
    recognizer.AcceptWaveform(pcm)
    result = recognizer.FinalResult()
    try:
        payload = json.loads(result)
    except json.JSONDecodeError:
        payload = {}
    return (payload.get("text", "") or "").strip()


def run_split(split, model, limit):
    ds = load_dataset(DATASET, split=split, streaming=True)
    features = list(getattr(ds, "features", {}).keys()) if hasattr(ds, "features") else []
    required = {"audio", "transcript_text", "language"}
    missing = sorted(required - set(features))
    if missing:
        raise RuntimeError(f"{split} is missing required fields; got {features}; missing {missing}")

    tested = errors = words = skipped = 0
    for row in ds:
        if tested >= limit:
            break

        language = row.get("language")
        if not is_libyan_language(language):
            continue

        ref = row.get("transcript_text")
        audio = row.get("audio")
        if not ref or not isinstance(audio, dict) or "array" not in audio:
            skipped += 1
            continue

        try:
            hyp = transcribe(model, audio)
        except Exception as exc:
            skipped += 1
            print(f"WARN {split}: {type(exc).__name__}: {exc}", flush=True)
            continue

        ref_words = normalize_text(ref).split()
        hyp_words = normalize_text(hyp).split()
        if not ref_words:
            skipped += 1
            continue

        errors += levenshtein_distance(ref_words, hyp_words)
        words += len(ref_words)
        tested += 1

    return {
        "split": split,
        "tested": tested,
        "skipped": skipped,
        "teacher_wer": round(errors / max(1, words), 4),
        "reference_words": words,
    }


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: probe-lahgtna-libyan.py VOSK_MODEL_DIR")

    model_dir = Path(sys.argv[1])
    if not model_dir.exists():
        raise SystemExit(f"Vosk model directory does not exist: {model_dir}")

    model = Model(str(model_dir))
    try:
        available_splits = set(get_dataset_split_names(DATASET))
    except Exception:
        available_splits = {"train", "test"}

    splits = sorted(s for s in ("train", "test") if s in available_splits)
    if not splits:
        raise SystemExit(f"No supported dataset splits found for {DATASET}; available={sorted(available_splits)}")

    results = {split: run_split(split, model, 30) for split in splits}
    payload = {
        "dataset": DATASET,
        "mode": "streaming technical pilot; no audio/transcripts exported; no model training",
        "splits": results,
        "caveat": "WER is a small preliminary teacher baseline, not a student-model result; speaker independence is not verified.",
    }

    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if payload["splits"]["train"]["tested"] < 10 or payload["splits"].get("test", {}).get("tested", 0) < 10:
        raise SystemExit("FAIL: fewer than 10 Libyan clips decoded in one or more splits")
    print("PASS: Libyan streaming pilot completed")


if __name__ == "__main__":
    main()
