#!/usr/bin/env python3
"""Run real Arabic speech inference and WER on public Arabic evaluation clips."""
import json, pathlib, re, sys, time
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from datasets import load_dataset
from scipy.signal import resample_poly

root = pathlib.Path(sys.argv[1])
revision = (root / "revision.txt").read_text().strip()
encoder_path = root / "encoder_model_quantized.onnx"
decoder_path = root / "decoder_model_quantized.onnx"
tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
config = json.loads((root / "config.json").read_text())
bos = int(config.get("decoder_start_token_id", config.get("bos_token_id", 1)))
eos = int(config.get("eos_token_id", 2))
pad = int(config.get("pad_token_id", 0))

opts = ort.SessionOptions()
opts.intra_op_num_threads = 2
opts.inter_op_num_threads = 1
encoder = ort.InferenceSession(str(encoder_path), sess_options=opts, providers=["CPUExecutionProvider"])
decoder = ort.InferenceSession(str(decoder_path), sess_options=opts, providers=["CPUExecutionProvider"])

def norm(s):
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(s))
    s = re.sub(r"[إأآٱ]", "ا", s)
    s = re.sub(r"ى", "ي", s)
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return " ".join(s.split())

def transcribe(audio, sr):
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim == 2:
        audio = audio.mean(axis=1) if audio.shape[0] > audio.shape[1] else audio.mean(axis=0)
    if sr != 16000:
        from math import gcd
        g = gcd(int(sr), 16000)
        audio = resample_poly(audio, 16000 // g, int(sr) // g).astype(np.float32)
    if audio.size == 0:
        return ""
    rem = audio.size % 80
    if rem:
        audio = np.pad(audio, (0, 80-rem))
    hidden = encoder.run(None, {"input_values": audio[None, :]})[0]
    ids = [bos]
    limit = min(128, max(12, int(len(audio) / 16000 * 8) + 8))
    for _ in range(limit):
        logits = decoder.run(None, {
            "input_ids": np.asarray([ids], dtype=np.int64),
            "encoder_hidden_states": hidden
        })[0]
        nxt = int(np.argmax(logits[0, -1]))
        if nxt in (pad, eos):
            break
        ids.append(nxt)
    return tokenizer.decode(ids[1:], skip_special_tokens=True)

def edit_distance(a, b):
    prev = list(range(len(b)+1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(cur[-1]+1, prev[j]+1, prev[j-1]+(x != y)))
        prev = cur
    return prev[-1]

print("Loading public Arabic FLEURS Egyptian Arabic test split")
ds = load_dataset("google/fleurs", "ar_eg", split="test")
if len(ds) == 0:
    raise SystemExit("FAIL: Arabic evaluation split is empty.")
print("Dataset columns:", ds.column_names)
rows = []
for i in range(min(8, len(ds))):
    item = ds[i]
    audio_obj = item.get("audio")
    if not isinstance(audio_obj, dict) or "array" not in audio_obj:
        continue
    reference = next((item[k] for k in ("sentence", "text", "transcription", "transcript") if isinstance(item.get(k), str)), None)
    if not reference:
        continue
    started = time.perf_counter()
    hypothesis = transcribe(audio_obj["array"], audio_obj.get("sampling_rate", 16000))
    elapsed = time.perf_counter() - started
    ref_words, hyp_words = norm(reference).split(), norm(hypothesis).split()
    errors = edit_distance(ref_words, hyp_words)
    rows.append((reference, hypothesis, errors, max(1, len(ref_words)), elapsed))
    print(json.dumps({"sample": i, "reference": reference, "hypothesis": hypothesis, "wer": errors/max(1,len(ref_words)), "seconds": round(elapsed,3)}, ensure_ascii=False))
if len(rows) < 3:
    raise SystemExit(f"FAIL: only {len(rows)} usable audio/reference pairs; expected at least 3.")
total_err = sum(x[2] for x in rows)
total_words = sum(x[3] for x in rows)
wer = total_err / total_words
print(json.dumps({"revision": revision, "samples": len(rows), "aggregate_wer": round(wer,4), "mean_inference_seconds": round(sum(x[4] for x in rows)/len(rows),3)}, ensure_ascii=False))
if not np.isfinite(wer):
    raise SystemExit("FAIL: WER is not finite.")
print("PASS: end-to-end Arabic audio inference completed.")
