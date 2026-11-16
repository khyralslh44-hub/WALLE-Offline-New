#!/usr/bin/env python3
"""Build a Vosk-teacher pseudo-label corpus for EVE Arabic student training.

FLEURS references are used ONLY to reject bad teacher hypotheses and report
quality. The student target remains the Vosk transcript (not the reference).
"""
import argparse, json, pathlib, re, subprocess, sys, wave
from datasets import load_dataset
from vosk import Model, KaldiRecognizer
import numpy as np

def norm(s):
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", str(s))
    s = re.sub(r"[إأآٱ]", "ا", s)
    s = re.sub(r"ى", "ي", s)
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return " ".join(s.split())

def edit_distance(a,b):
    prev=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        cur=[i]
        for j,y in enumerate(b,1):
            cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
        prev=cur
    return prev[-1]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--model",required=True)
    ap.add_argument("--out",required=True)
    ap.add_argument("--limit",type=int,default=120)
    ap.add_argument("--max-wer",type=float,default=0.45)
    args=ap.parse_args()
    out=pathlib.Path(args.out); audio_dir=out/"audio"; audio_dir.mkdir(parents=True,exist_ok=True)
    model=Model(args.model)
    ds=load_dataset("google/fleurs","ar_eg",split="train")
    rows=[]; rejected=0; tested=0; errors=0; words=0
    for idx,item in enumerate(ds):
        if tested>=args.limit: break
        obj=item.get("audio")
        ref=item.get("sentence")
        if not isinstance(obj,dict) or "array" not in obj or not ref: continue
        audio=np.asarray(obj["array"],dtype=np.float32)
        if audio.ndim==2: audio=audio.mean(axis=1) if audio.shape[0]>audio.shape[1] else audio.mean(axis=0)
        sr=int(obj.get("sampling_rate",16000))
        if sr!=16000:
            import librosa
            audio=librosa.resample(audio,orig_sr=sr,target_sr=16000)
        audio=np.clip(audio,-1,1)
        pcm=(audio*32767).astype("<i2").tobytes()
        rec=KaldiRecognizer(model,16000); rec.SetWords(True)
        rec.AcceptWaveform(pcm)
        result=json.loads(rec.FinalResult())
        hyp=result.get("text","")
        rw,hw=norm(ref).split(),norm(hyp).split()
        dist=edit_distance(rw,hw); wer=dist/max(1,len(rw))
        tested+=1; errors+=dist; words+=len(rw)
        # Reject empty, very short, repetitive and poor teacher outputs.
        toks=hw
        repetitive=(len(toks)>=4 and len(set(toks))/len(toks)<0.45)
        if not hyp.strip() or len(toks)<2 or wer>args.max_wer or repetitive:
            rejected+=1; continue
        name=f"fleurs_ar_eg_{idx:06d}.wav"; wav_path=audio_dir/name
        with wave.open(str(wav_path),"wb") as wf:
            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(16000); wf.writeframes(pcm)
        rows.append({"audio":f"audio/{name}","text":hyp,"teacher":"vosk-model-ar-mgb2-0.4","reference_wer":round(wer,4)})
        if len(rows)%10==0: print(f"accepted={len(rows)} tested={tested} rejected={rejected}",flush=True)
    manifest=out/"train.jsonl"
    with manifest.open("w",encoding="utf-8") as f:
        for row in rows: f.write(json.dumps(row,ensure_ascii=False)+"\n")
    report={"dataset":"google/fleurs:ar_eg/train","teacher":"vosk-model-ar-mgb2-0.4","tested":tested,"accepted":len(rows),"rejected":rejected,"teacher_wer_on_filtered_source":round(errors/max(1,words),4),"max_wer_filter":args.max_wer,"student_targets":"Vosk hypotheses; FLEURS references are only used for filtering"}
    (out/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if len(rows)<10: raise SystemExit("FAIL: fewer than 10 clean pseudo-labelled clips; do not train a student.")
    print("PASS: teacher pseudo-label corpus created. This is data preparation, not yet a trained student model.")

if __name__=="__main__": main()
