#!/usr/bin/env python3
"""Vosk-teacher distillation: Arabic audio -> Vosk pseudo-text -> compact CTC student."""
import json, os, re, unicodedata
from pathlib import Path
import numpy as np
import tensorflow as tf
from datasets import Audio, load_dataset
from scipy.signal import resample_poly
from vosk import KaldiRecognizer, Model, SetLogLevel
ROOT=Path(__file__).resolve().parent
OUT=ROOT/"dist"; OUT.mkdir(parents=True,exist_ok=True)
SR=16000; FRAME=400; HOP=160; FFT=512; MEL=40; SECONDS=12
MAX_FRAMES=int(np.ceil(SECONDS*SR/HOP))
TRAIN=int(os.getenv("EVE_ASR_TRAIN_SAMPLES","600"))
VALID=int(os.getenv("EVE_ASR_VALID_SAMPLES","120"))
EPOCHS=int(os.getenv("EVE_ASR_EPOCHS","6")); BATCH=8; SEED=20261115
BLANK=0
def norm(s):
    s=unicodedata.normalize("NFKC",str(s or "")).replace("ـ"," ").lower()
    s="".join(c for c in s if unicodedata.category(c)!="Mn")
    return re.sub(r"\s+"," ",re.sub(r"[^\w\s]"," ",s,flags=re.UNICODE)).strip()
def charset():
    c=set(" ?0123456789abcdefghijklmnopqrstuvwxyz")
    for a,b in ((0x0600,0x06ff),(0x0750,0x077f),(0x08a0,0x08ff),(0xfb50,0xfdff),(0xfe70,0xfeff)):
        c.update(chr(i) for i in range(a,b+1))
    return [""]+sorted(c,key=ord)
CHARS=charset(); C2I={c:i for i,c in enumerate(CHARS) if c}
def encode(s): return [C2I.get(c,C2I[" "]) for c in norm(s)[:180]]
def hzmel(x): return 2595*np.log10(1+x/700)
def melhz(x): return 700*(10**(x/2595)-1)
def make_bank():
    pts=melhz(np.linspace(hzmel(0),hzmel(SR/2),MEL+2)); f=np.linspace(0,SR/2,FFT//2+1)
    bank=np.zeros((MEL,len(f)),np.float32)
    for i in range(MEL):
        l,m,r=pts[i:i+3]
        bank[i]=np.maximum(0,np.minimum((f-l)/max(m-l,1e-9),(r-f)/max(r-m,1e-9)))
    return bank
BANK=make_bank()
def mono16(info):
    x=np.asarray(info["array"],np.float32)
    if x.ndim==2: x=x.mean(axis=1)
    if x.ndim!=1 or not x.size or not np.all(np.isfinite(x)): raise ValueError("invalid audio")
    if np.max(np.abs(x))>1.5: x=x/32768
    rate=int(info["sampling_rate"])
    if rate!=SR:
        from math import gcd
        g=gcd(rate,SR); x=resample_poly(x,SR//g,rate//g)
    return np.clip(x[:SECONDS*SR],-1,1).astype(np.float32)
def feats(x):
    n=min(MAX_FRAMES,max(1,int(np.ceil(len(x)/HOP))))
    need=(n-1)*HOP+FRAME; x=np.pad(x,(0,max(0,need-len(x))))
    w=np.lib.stride_tricks.sliding_window_view(x,FRAME)[::HOP][:n]
    s=np.fft.rfft(w*np.hanning(FRAME)[None,:],n=FFT,axis=1)
    p=(np.abs(s)**2/FFT).astype(np.float32)
    z=np.log(np.maximum(p@BANK.T,1e-8)).astype(np.float32)
    z=(z-z.mean(0,keepdims=True))/np.maximum(z.std(0,keepdims=True),1e-3)
    out=np.zeros((MAX_FRAMES,MEL),np.float32); out[:n]=z
    return out,n
def transcribe(model,x):
    pcm=(x*32767).astype("<i2").tobytes()
    rec=KaldiRecognizer(model,SR); rec.AcceptWaveform(pcm)
    return norm(json.loads(rec.FinalResult()).get("text",""))
def stream(split,limit,shuffle=False):
    ds=load_dataset("google/fleurs","ar_eg",split=split,streaming=True)
    ds=ds.cast_column("audio",Audio(sampling_rate=SR))
    return ds.shuffle(seed=SEED,buffer_size=1500) if shuffle else ds.take(limit)
def collect_train(teacher):
    rows=[]; skipped=0; seen=0
    for seen,row in enumerate(stream("train",TRAIN,True),1):
        if seen>TRAIN: break
        try:
            x=mono16(row["audio"]); y=encode(transcribe(teacher,x)); f,n=feats(x)
            if not y or len(y)>=int(np.ceil(n/2)): skipped+=1; continue
            rows.append({"x":f,"n":n,"y":y})
        except Exception as e:
            skipped+=1
            if skipped<=5: print(f"TRAIN_SKIP {seen} {e!r}",flush=True)
        if seen%50==0: print(f"VOSK_LABELS {seen}/{TRAIN} usable={len(rows)} skipped={skipped}",flush=True)
    if len(rows)<max(50,TRAIN//3): raise RuntimeError(f"Too few Vosk-labelled clips: {len(rows)}")
    return rows,{"seen":seen,"usable":len(rows),"skipped":skipped}
def wer(refs,hyps):
    errors=words=0
    for a,b in zip(refs,hyps):
        a=norm(a).split(); b=norm(b).split(); prev=list(range(len(b)+1))
        for i,x in enumerate(a,1):
            cur=[i]
            for j,y in enumerate(b,1): cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
            prev=cur
        errors+=prev[-1]; words+=max(1,len(a))
    return errors/max(1,words)
def collect_valid(teacher):
    rows=[]; refs=[]; hyps=[]
    for i,row in enumerate(stream("validation",VALID),1):
        try:
            x=mono16(row["audio"]); ref=norm(row.get("transcription",""))
            if not ref: continue
            hyp=transcribe(teacher,x); f,n=feats(x)
            rows.append({"x":f,"n":n,"ref":ref}); refs.append(ref); hyps.append(hyp)
        except Exception as e: print(f"VALID_SKIP {i} {e!r}",flush=True)
    if len(rows)<max(20,VALID//3): raise RuntimeError(f"Too few held-out clips: {len(rows)}")
    return rows,wer(refs,hyps)
def model_new():
    inp=tf.keras.Input((MAX_FRAMES,MEL),name="log_mel")
    x=tf.keras.layers.Conv1D(96,11,strides=2,padding="same",activation="relu")(inp)
    x=tf.keras.layers.Bidirectional(tf.keras.layers.GRU(96,return_sequences=True))(x)
    x=tf.keras.layers.Bidirectional(tf.keras.layers.GRU(96,return_sequences=True))(x)
    return tf.keras.Model(inp,tf.keras.layers.Dense(len(CHARS),name="char_logits")(x))
def sparse_y(batch):
    ix=[]; vals=[]
    for r,row in enumerate(batch):
        for c,t in enumerate(row["y"]): ix.append((r,c)); vals.append(t)
    return tf.SparseTensor(np.asarray(ix,np.int64),np.asarray(vals,np.int32),
                           np.asarray([len(batch),max(len(r["y"]) for r in batch)],np.int64))
def decoder(logits,lengths):
    decoded,_=tf.nn.ctc_greedy_decoder(tf.transpose(logits,[1,0,2]),sequence_length=lengths,blank_index=BLANK)
    dense=tf.sparse.to_dense(decoded[0],default_value=BLANK).numpy()
    return [norm("".join(CHARS[int(t)] for t in row if 0<int(t)<len(CHARS))) for row in dense]
def train(training,validation):
    tf.keras.utils.set_random_seed(SEED); model=model_new()
    opt=tf.keras.optimizers.Adam(0.001,clipnorm=5.0)
    @tf.function
    def step(x,y,lens):
        with tf.GradientTape() as tape:
            logits=model(x,training=True)
            loss=tf.reduce_mean(tf.nn.ctc_loss(labels=y,logits=logits,label_length=None,
                logit_length=lens,logits_time_major=False,blank_index=BLANK))
        grads=tape.gradient(loss,model.trainable_variables); opt.apply_gradients(zip(grads,model.trainable_variables))
        return loss
    rng=np.random.default_rng(SEED)
    for ep in range(EPOCHS):
        order=rng.permutation(len(training)); losses=[]
        for start in range(0,len(order),BATCH):
            batch=[training[i] for i in order[start:start+BATCH]]
            x=np.stack([r["x"] for r in batch]); y=sparse_y(batch)
            lens=np.asarray([int(np.ceil(r["n"]/2)) for r in batch],np.int32)
            losses.append(float(step(x,y,lens).numpy()))
        print(f"STUDENT_EPOCH {ep+1}/{EPOCHS} ctc_loss={np.mean(losses):.4f}",flush=True)
    refs=[]; hyps=[]
    for start in range(0,len(validation),BATCH):
        batch=validation[start:start+BATCH]; x=np.stack([r["x"] for r in batch])
        lens=np.asarray([int(np.ceil(r["n"]/2)) for r in batch],np.int32)
        hyps.extend(decoder(model(x,training=False),lens)); refs.extend(r["ref"] for r in batch)
    score=wer(refs,hyps); model.save(OUT/"eve_vosk_asr_student.keras")
    converter=tf.lite.TFLiteConverter.from_keras_model(model); converter.optimizations=[tf.lite.Optimize.DEFAULT]
    data=converter.convert(); (OUT/"eve_vosk_asr_student.tflite").write_bytes(data)
    it=tf.lite.Interpreter(model_content=data); it.allocate_tensors()
    inp=it.get_input_details()[0]; out=it.get_output_details()[0]
    it.set_tensor(inp["index"],np.zeros((1,MAX_FRAMES,MEL),np.float32)); it.invoke()
    test=it.get_tensor(out["index"])
    if test.ndim!=3 or test.shape[-1]!=len(CHARS) or not np.all(np.isfinite(test)): raise RuntimeError("TFLite smoke test failed")
    meta={"model":"EVE-Vosk-Distilled-Arabic-CTC","teacher":"vosk-model-ar-mgb2-0.4",
      "teacher_usage":"training-only pseudo-transcription; no Vosk weights transferred",
      "dataset":"google/fleurs/ar_eg","dataset_license":"CC-BY-4.0","sample_rate":SR,
      "frame_length":FRAME,"frame_step":HOP,"fft_size":FFT,"mel_bins":MEL,
      "max_seconds":SECONDS,"max_frames":MAX_FRAMES,"charset":CHARS,"blank_id":BLANK,
      "training_samples":len(training),"validation_samples":len(validation),"student_wer":score,"tflite_bytes":len(data)}
    (OUT/"eve_vosk_asr_student.json").write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding="utf-8")
    return score,len(data)
def main():
    p=os.getenv("VOSK_MODEL_PATH","")
    if not p or not Path(p,"am","final.mdl").is_file(): raise RuntimeError("VOSK_MODEL_PATH must be the verified Arabic Vosk model")
    if TRAIN<100 or VALID<30: raise ValueError("Need >=100 training and >=30 held-out clips")
    SetLogLevel(-1); print("LOAD_VOSK_TEACHER "+p,flush=True); teacher=Model(p)
    train_rows,stats=collect_train(teacher); valid_rows,teacher_score=collect_valid(teacher)
    student_score,size=train(train_rows,valid_rows)
    report={"teacher":"vosk-model-ar-mgb2-0.4","dataset":"google/fleurs/ar_eg","dataset_license":"CC-BY-4.0",
      "train_stats":stats,"validation_samples":len(valid_rows),"teacher_validation_wer":teacher_score,
      "student_validation_wer":student_score,"student_beats_teacher":student_score<teacher_score,
      "replacement_status":"eligible_for_device_integration_review" if student_score<teacher_score else "not_eligible_keep_vosk_fallback",
      "student_tflite_bytes":size,"note":"Keep Vosk until unseen-speaker/dialect, noise, live microphone, Redmi 12 and offline device tests pass."}
    (OUT/"training_report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("ASR_DISTILLATION_REPORT "+json.dumps(report,ensure_ascii=False),flush=True)
if __name__=="__main__": main()
