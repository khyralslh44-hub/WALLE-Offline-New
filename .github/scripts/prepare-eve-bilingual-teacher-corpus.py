#!/usr/bin/env python3
"""Prepare separate MSA-oriented Arabic and (when licensed) Libyan Vosk teacher sets.

Common Voice 18 Arabic is an open, read-speech Arabic corpus. It is MSA-oriented
but not guaranteed to be exclusively MSA; metadata is retained so this limitation
is explicit. No Egyptian FLEURS data is used here.
"""
import argparse, json, pathlib, re, wave
import numpy as np
from datasets import load_dataset
from vosk import Model, KaldiRecognizer

def norm(s):
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(s))
    s = re.sub(r"[إأآٱ]", "ا", s)
    s = re.sub(r"ى", "ي", s)
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return " ".join(s.split())

def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j-1] + (x != y)))
        prev = cur
    return prev[-1]

def to_pcm(audio_obj):
    audio = np.asarray(audio_obj["array"], dtype=np.float32)
    if audio.ndim == 2:
        audio = audio.mean(axis=1) if audio.shape[0] > audio.shape[1] else audio.mean(axis=0)
    sr = int(audio_obj.get("sampling_rate", 16000))
    if sr != 16000:
        import librosa
        audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
    audio = np.clip(audio, -1.0, 1.0)
    return (audio * 32767).astype("<i2").tobytes()

def transcribe(model, pcm):
    rec = KaldiRecognizer(model, 16000)
    rec.SetWords(True)
    rec.AcceptWaveform(pcm)
    return json.loads(rec.FinalResult()).get("text", "").strip()

def save_wav(path, pcm):
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(16000); wf.writeframes(pcm)

def process_split(ds, split, model, out, limit, max_wer, save_audio):
    rows, tested, accepted, rejected, skipped, errors, words = [], 0, 0, 0, 0, 0, 0
    audio_dir = out / "audio" / split
    if save_audio:
        audio_dir.mkdir(parents=True, exist_ok=True)
    for idx, item in enumerate(ds):
        if tested >= limit:
            break
        audio_obj = item.get("audio")
        ref = item.get("sentence")
        if not isinstance(audio_obj, dict) or "array" not in audio_obj or not ref:
            skipped += 1
            continue
        try:
            pcm = to_pcm(audio_obj)
            hyp = transcribe(model, pcm)
        except Exception as exc:
            skipped += 1
            print(f"WARN {split} row {idx}: {type(exc).__name__}: {exc}", flush=True)
            continue
        ref_words, hyp_words = norm(ref).split(), norm(hyp).split()
        dist = edit_distance(ref_words, hyp_words)
        wer = dist / max(1, len(ref_words))
        tested += 1; errors += dist; words += len(ref_words)
        repetitive = len(hyp_words) >= 4 and len(set(hyp_words)) / len(hyp_words) < 0.45
        good = bool(hyp) and len(hyp_words) >= 2 and wer <= max_wer and not repetitive
        if split == "train":
            if not good:
                rejected += 1
                continue
            name = f"cv18_ar_{idx:07d}.wav"
            save_wav(audio_dir / name, pcm)
            rows.append({"audio": f"audio/{split}/{name}", "text": hyp,
                         "reference": ref, "reference_wer": round(wer, 4),
                         "source": "MohamedRashad/common-voice-18-arabic",
                         "source_split": split, "language_target": "msa_oriented_arabic_read_speech"})
            accepted += 1
        else:
            rows.append({"reference": ref, "hypothesis": hyp, "reference_wer": round(wer, 4),
                         "source": "MohamedRashad/common-voice-18-arabic",
                         "source_split": split, "evaluation_only": True})
    out_file = out / f"{split}.jsonl"
    with out_file.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    return {"split": split, "tested": tested, "accepted": accepted,
            "rejected": rejected, "skipped": skipped,
            "teacher_wer": round(errors / max(1, words), 4),
            "records_written": len(rows), "manifest": out_file.name}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--train-limit", type=int, default=240)
    ap.add_argument("--eval-limit", type=int, default=80)
    ap.add_argument("--max-wer", type=float, default=0.55)
    args = ap.parse_args()
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = Model(args.model)

    # Public dataset card declares CC0-1.0. Train/validation/test remain separated.
    train = load_dataset("MohamedRashad/common-voice-18-arabic", split="train")
    valid = load_dataset("MohamedRashad/common-voice-18-arabic", split="validation")
    test = load_dataset("MohamedRashad/common-voice-18-arabic", split="test")
    required = {"audio", "sentence"}
    for name, ds in (("train", train), ("validation", valid), ("test", test)):
        missing = required.difference(ds.column_names)
        if missing:
            raise SystemExit(f"Common Voice {name} schema missing: {sorted(missing)}")

    msa = [
        process_split(train, "train", model, out, args.train_limit, args.max_wer, True),
        process_split(valid, "validation", model, out, args.eval_limit, args.max_wer, False),
        process_split(test, "test", model, out, args.eval_limit, args.max_wer, False),
    ]
    report = {
        "msa_source": "MohamedRashad/common-voice-18-arabic",
        "msa_source_license": "CC0-1.0 (dataset card)",
        "msa_scope": "Arabic read speech, MSA-oriented; not guaranteed exclusively MSA",
        "msa_splits": msa,
        "libyan_source": "oddadmix/dialectal-arabic-lahgtna-v2",
        "libyan_status": "blocked_pending_explicit_license",
        "libyan_reason": "The public dataset card identifies Libyan rows with language='ly' but does not declare a dataset license. Audio is not downloaded or used until reuse/training permission is explicit.",
        "egyptian_fleurs_used": False,
        "student_training": "not_started; this workflow prepares teacher targets and independent evaluation manifests only",
        "teacher": "vosk-model-ar-mgb2-0.4",
        "max_wer_filter": args.max_wer,
    }
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if msa[0]["accepted"] < 10:
        raise SystemExit("FAIL: fewer than 10 accepted Arabic training clips.")
    print("PASS: MSA-oriented Arabic teacher corpus prepared; Libyan data remains license-blocked; no student trained.")

if __name__ == "__main__":
    main()
