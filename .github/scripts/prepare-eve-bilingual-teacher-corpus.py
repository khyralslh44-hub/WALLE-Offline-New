#!/usr/bin/env python3
"""Prepare separate Arabic read-speech and optional licensed Libyan Vosk targets.

Common Voice 18 Arabic is MSA-oriented read speech, not a guarantee of pure MSA.
Libyan audio is only accessed when the operator supplies a dataset ID and explicitly
confirms a permissive license verified with the dataset owner/source.
"""
import argparse, json, pathlib, re, wave
import numpy as np
from datasets import load_dataset
from vosk import Model, KaldiRecognizer

ALLOWED_LIBYAN_LICENSES = {"CC0-1.0", "CC-BY-4.0", "CC-BY-3.0", "Apache-2.0"}

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
    return (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2").tobytes()

def transcribe(model, pcm):
    rec = KaldiRecognizer(model, 16000)
    rec.SetWords(True)
    rec.AcceptWaveform(pcm)
    return json.loads(rec.FinalResult()).get("text", "").strip()

def save_wav(path, pcm):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(16000); wf.writeframes(pcm)

def process_split(ds, split, model, out, limit, max_wer, *,
                  text_field, source, language_target, save_audio,
                  language_field=None, language_value=None, stream_label=None):
    rows, tested, accepted, rejected, skipped, errors, words = [], 0, 0, 0, 0, 0, 0
    if language_field:
        ds = ds.filter(lambda x: x.get(language_field) == language_value)
    if stream_label:
        ds = ds.shuffle(seed=42, buffer_size=10000)
    for idx, item in enumerate(ds):
        if tested >= limit:
            break
        audio_obj, ref = item.get("audio"), item.get(text_field)
        if not isinstance(audio_obj, dict) or "array" not in audio_obj or not ref:
            skipped += 1
            continue
        try:
            pcm, hyp = to_pcm(audio_obj), None
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
        if split.endswith("_train"):
            if not good:
                rejected += 1
                continue
            name = f"{split}_{idx:07d}.wav"
            save_wav(out / "audio" / split / name, pcm)
            rows.append({"audio": f"audio/{split}/{name}", "text": hyp,
                         "reference": ref, "reference_wer": round(wer, 4),
                         "source": source, "source_split": split,
                         "language_target": language_target})
            accepted += 1
        else:
            row = {"reference": ref, "hypothesis": hyp, "reference_wer": round(wer, 4),
                   "source": source, "source_split": split, "evaluation_only": True}
            if save_audio:
                name = f"{split}_{idx:07d}.wav"
                save_wav(out / "audio" / split / name, pcm)
                row["audio"] = f"audio/{split}/{name}"
            rows.append(row)
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
    ap.add_argument("--libyan-dataset", default="")
    ap.add_argument("--libyan-license-confirmed", default="")
    args = ap.parse_args()
    out = pathlib.Path(args.out); out.mkdir(parents=True, exist_ok=True)
    model = Model(args.model)

    # Common Voice 18 Arabic: CC0 according to the dataset card. Keep held-out splits separate.
    cv_train = load_dataset("MohamedRashad/common-voice-18-arabic", split="train")
    cv_valid = load_dataset("MohamedRashad/common-voice-18-arabic", split="validation")
    cv_test = load_dataset("MohamedRashad/common-voice-18-arabic", split="test")
    for name, ds in (("train", cv_train), ("validation", cv_valid), ("test", cv_test)):
        missing = {"audio", "sentence"}.difference(ds.column_names)
        if missing:
            raise SystemExit(f"Common Voice {name} schema missing: {sorted(missing)}")
    msa = [
        process_split(cv_train, "msa_train", model, out, args.train_limit, args.max_wer,
          text_field="sentence", source="MohamedRashad/common-voice-18-arabic",
          language_target="msa_oriented_arabic_read_speech", save_audio=True),
        process_split(cv_valid, "msa_validation", model, out, args.eval_limit, args.max_wer,
          text_field="sentence", source="MohamedRashad/common-voice-18-arabic",
          language_target="msa_oriented_arabic_read_speech", save_audio=True),
        process_split(cv_test, "msa_test", model, out, args.eval_limit, args.max_wer,
          text_field="sentence", source="MohamedRashad/common-voice-18-arabic",
          language_target="msa_oriented_arabic_read_speech", save_audio=True),
    ]

    libyan = {"status": "blocked_pending_explicit_license", "train": None, "test": None}
    if args.libyan_dataset:
        if args.libyan_license_confirmed not in ALLOWED_LIBYAN_LICENSES:
            raise SystemExit("Libyan dataset supplied without an explicitly confirmed permissive license. No Libyan audio was processed.")
        ly_train = load_dataset(args.libyan_dataset, split="train", streaming=True)
        ly_test = load_dataset(args.libyan_dataset, split="test", streaming=True)
        for name, ds in (("Libyan train", ly_train), ("Libyan test", ly_test)):
            required = {"audio", "transcript_text", "language"}
            if not required.issubset(set(ds.features)):
                raise SystemExit(f"{name} schema must include audio, transcript_text, language")
        libyan = {
          "status": "processed_with_operator_confirmed_license",
          "dataset": args.libyan_dataset,
          "license": args.libyan_license_confirmed,
          "train": process_split(ly_train, "libyan_train", model, out, args.train_limit, args.max_wer,
            text_field="transcript_text", source=args.libyan_dataset,
            language_target="libyan_arabic", save_audio=True,
            language_field="language", language_value="ly", stream_label="shuffle"),
          "test": process_split(ly_test, "libyan_test", model, out, args.eval_limit, args.max_wer,
            text_field="transcript_text", source=args.libyan_dataset,
            language_target="libyan_arabic", save_audio=True,
            language_field="language", language_value="ly"),
        }

    report = {
      "msa_source": "MohamedRashad/common-voice-18-arabic",
      "msa_source_license": "CC0-1.0 (dataset card)",
      "msa_scope": "Arabic read speech, MSA-oriented; not guaranteed exclusively MSA",
      "msa_splits": msa,
      "libyan": libyan,
      "egyptian_fleurs_used": False,
      "teacher": "vosk-model-ar-mgb2-0.4",
      "student_training": "not_started; this workflow prepares teacher targets and held-out evaluation audio only",
      "max_wer_filter": args.max_wer,
    }
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if msa[0]["accepted"] < 10:
        raise SystemExit("FAIL: fewer than 10 accepted Arabic training clips.")
    if libyan.get("status") == "processed_with_operator_confirmed_license" and libyan["train"]["accepted"] < 10:
        raise SystemExit("FAIL: fewer than 10 accepted Libyan training clips.")
    print("PASS: Arabic read-speech corpus prepared. Libyan status is explicitly reported; no student has been trained.")

if __name__ == "__main__":
    main()
