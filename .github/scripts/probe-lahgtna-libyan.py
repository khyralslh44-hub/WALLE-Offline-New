#!/usr/bin/env python3
"""Small, non-redistributive technical probe of the Libyan subset in Lahgtna.
Streams a limited number of rows, transcribes them with the current Vosk teacher,
and writes metrics only (no audio or transcripts are packaged).
"""
import json, re, sys
import numpy as np
from datasets import load_dataset
from vosk import Model, KaldiRecognizer

DATASET = "oddadmix/dialectal-arabic-lahgtna-v2"

def norm(s):
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(s))
    s = re.sub(r"[إأآٱ]", "ا", s)
    s = re.sub(r"ى", "ي", s)
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return " ".join(s.split())

def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j-1] + (x != y)))
        prev = cur
    return prev[-1]

def transcribe(model, audio):
    arr = np.asarray(audio["array"], dtype=np.float32)
    if arr.ndim == 2:
        arr = arr.mean(axis=1) if arr.shape[0] > arr.shape[1] else arr.mean(axis=0)
    sr = int(audio.get("sampling_rate", 16000))
    if sr != 16000:
        import librosa
        arr = librosa.resample(arr, orig_sr=sr, target_sr=16000)
    pcm = (np.clip(arr, -1, 1) * 32767).astype("<i2").tobytes()
    rec = KaldiRecognizer(model, 16000)
    rec.AcceptWaveform(pcm)
    return json.loads(rec.FinalResult()).get("text", "").strip()

def run_split(split, model, limit):
    ds = load_dataset(DATASET, split=split, streaming=True)
    features = list(ds.features.keys())
    required = {"audio", "transcript_text", "language"}
    if not required.issubset(features):
        raise RuntimeError(f"{split} missing required fields; got {features}")
    ds = ds.filter(lambda row: row.get("language") == "ly")
    tested = errors = words = skipped = 0
    for row in ds:
        if tested >= limit:
            break
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
        rw, hw = norm(ref).split(), norm(hyp).split()
        errors += distance(rw, hw)
        words += len(rw)
        tested += 1
    return {"split": split, "tested": tested, "skipped": skipped,
            "teacher_wer": round(errors / max(1, words), 4),
            "reference_words": words}

def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: probe-lahgtna-libyan.py VOSK_MODEL_DIR")
    model = Model(sys.argv[1])
    result = {
        "dataset": DATASET,
        "mode": "streaming technical pilot; no audio/transcripts exported; no model training",
        "train": run_split("train", model, 30),
        "test": run_split("test", model, 30),
        "caveat": "WER is a small preliminary teacher baseline, not a student-model result; speaker independence is not verified."
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["train"]["tested"] < 10 or result["test"]["tested"] < 10:
        raise SystemExit("FAIL: fewer than 10 Libyan clips decoded in one or more splits")
    print("PASS: Libyan streaming pilot completed")

if __name__ == "__main__":
    main()
