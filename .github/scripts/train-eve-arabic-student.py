#!/usr/bin/env python3
"""Train EVE's compact Arabic CTC student using frame-level teacher-logit distillation."""
import argparse, json, os, random, re, time
from pathlib import Path
import librosa, numpy as np, torch
import torch.nn as nn
import torch.nn.functional as F
from datasets import load_dataset
from transformers import AutoModelForCTC, AutoProcessor

TEACHER = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"
LY = "oddadmix/dialectal-arabic-lahgtna-v2"
MSA = "MohamedRashad/common-voice-18-arabic"
SR, NMEL = 16000, 80

def norm(s):
    s = re.sub(r"\[[^\]]*\]|<[^>]*>", " ", str(s or ""))
    s = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", s)
    s = re.sub(r"[إأآٱ]", "ا", s).replace("ى", "ي")
    return re.sub(r"\s+", " ", re.sub(r"[ـ،؛؟.!,:;\"'()\[\]{}<>…]", " ", s)).strip()

def edit(a, b):
    p = list(range(len(b)+1))
    for i,x in enumerate(a,1):
        q=[i]
        for j,y in enumerate(b,1):
            q.append(min(q[-1]+1,p[j]+1,p[j-1]+(x!=y)))
        p=q
    return p[-1]

def metric(refs, hyps):
    we=wt=ce=ct=0
    for r,h in zip(refs,hyps):
        rw,hw=norm(r).split(),norm(h).split()
        rc,hc=list(norm(r).replace(" ","")),list(norm(h).replace(" ",""))
        we+=edit(rw,hw); wt+=len(rw); ce+=edit(rc,hc); ct+=len(rc)
    return {"utterances":len(refs),"WER":round(we/max(wt,1),4),"CER":round(ce/max(ct,1),4)}

def audio(row):
    obj=row.get("audio")
    if not isinstance(obj,dict) or "array" not in obj: return None
    x=np.asarray(obj["array"],dtype=np.float32)
    if x.ndim==2: x=x.mean(axis=0) if x.shape[0]<=x.shape[1] else x.mean(axis=1)
    sr=int(obj.get("sampling_rate",SR))
    if sr!=SR: x=librosa.resample(x,orig_sr=sr,target_sr=SR)
    if len(x)<SR or len(x)>14*SR: return None
    peak=float(np.max(np.abs(x)))
    if peak<1e-5: return None
    return np.clip(x/max(peak,1.0),-1,1).astype(np.float32)

def features(x):
    m=librosa.feature.melspectrogram(y=x,sr=SR,n_fft=400,hop_length=160,win_length=400,n_mels=NMEL,fmin=60,fmax=7600)
    z=np.log(np.maximum(m,1e-8)).astype(np.float32)
    return torch.from_numpy((z-z.mean())/max(float(z.std()),1e-4))

class Student(nn.Module):
    def __init__(self,n):
        super().__init__()
        self.front=nn.Sequential(nn.Conv1d(NMEL,128,5,2,2),nn.GroupNorm(8,128),nn.GELU(),
                                 nn.Conv1d(128,160,5,2,2),nn.GroupNorm(8,160),nn.GELU())
        self.rnn=nn.LSTM(160,128,2,batch_first=True,bidirectional=True,dropout=.1)
        self.head=nn.Linear(256,n)
    def forward(self,x):
        z=self.front(x).transpose(1,2)
        z,_=self.rnn(z)
        return self.head(z)

def load_rows(ds_id, split, text_key, dialect, limit, proc, teacher, device, seed):
    ds=load_dataset(ds_id,split=split,streaming=True)
    if dialect: ds=ds.filter(lambda r:r.get("language")==dialect)
    if split=="train": ds=ds.shuffle(seed=seed,buffer_size=3000)
    out=[]; scanned=0; tok=proc.tokenizer
    for row in ds:
        scanned+=1
        text=norm(row.get(text_key,""))
        if len(text)<2: continue
        ids=tok(text,add_special_tokens=False).input_ids
        if not ids or tok.unk_token_id in ids or tok.pad_token_id in ids: continue
        x=audio(row)
        if x is None: continue
        try:
            inp=proc(x,sampling_rate=SR,return_tensors="pt")
            with torch.inference_mode(): soft=teacher(input_values=inp.input_values.to(device)).logits[0]
            out.append({"x":features(x),"soft":soft.detach().float().cpu(),
                        "y":torch.tensor(ids,dtype=torch.long),"text":text,
                        "lang":"libyan" if dialect=="ly" else "msa_oriented"})
        except Exception as e:
            print("SKIP",type(e).__name__,flush=True); continue
        if len(out)>=limit: break
        if scanned>limit*120: break
    print("DATA",ds_id,split,"kept",len(out),"scanned",scanned,flush=True)
    return out

def train_step(model,item,opt,blank,device):
    model.train(); x=item["x"].unsqueeze(0).to(device); y=item["y"].to(device)
    soft=item["soft"].unsqueeze(0).to(device); opt.zero_grad(set_to_none=True)
    logits=model(x); steps=logits.shape[1]
    soft=F.interpolate(soft.transpose(1,2),size=steps,mode="linear",align_corners=False).transpose(1,2)
    ctc=F.ctc_loss(F.log_softmax(logits,-1).transpose(0,1),y,
                    torch.tensor([steps]),torch.tensor([len(y)]),blank=blank,zero_infinity=True)
    t=2.0
    kl=F.kl_div(F.log_softmax(logits/t,-1),F.softmax(soft/t,-1),
                reduction="none").sum(-1).mean()*(t*t)
    loss=.55*ctc+.45*kl
    if not torch.isfinite(loss): return None
    loss.backward(); torch.nn.utils.clip_grad_norm_(model.parameters(),2.0); opt.step()
    return float(loss.detach().cpu())

def decode(logits,tok):
    return tok.decode(logits.argmax(-1).tolist(),group_tokens=True,skip_special_tokens=True)

def evaluate(model,items,tok,device):
    results={}
    for lang in ("msa_oriented","libyan"):
        group=[x for x in items if x["lang"]==lang]
        if not group: continue
        refs=[]; student=[]; teacher=[]
        model.eval()
        for x in group:
            refs.append(x["text"])
            with torch.inference_mode(): pred=model(x["x"].unsqueeze(0).to(device))[0].cpu()
            student.append(decode(pred,tok)); teacher.append(decode(x["soft"],tok))
        results[lang]={"student":metric(refs,student),"teacher":metric(refs,teacher)}
    return results

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--train-per-source",type=int,default=48)
    ap.add_argument("--eval-per-source",type=int,default=16)
    ap.add_argument("--epochs",type=int,default=3)
    ap.add_argument("--out",default="eve-arabic-student")
    ap.add_argument("--seed",type=int,default=17)
    a=ap.parse_args()
    random.seed(a.seed); np.random.seed(a.seed); torch.manual_seed(a.seed)
    torch.set_num_threads(max(1,min(4,os.cpu_count() or 2)))
    device=torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print("DEVICE",device,"TEACHER",TEACHER,flush=True)
    proc=AutoProcessor.from_pretrained(TEACHER)
    teacher=AutoModelForCTC.from_pretrained(TEACHER).to(device).eval()
    for p in teacher.parameters(): p.requires_grad_(False)
    tok=proc.tokenizer; vocab=tok.get_vocab(); n=max(vocab.values())+1; blank=tok.pad_token_id
    if n!=teacher.config.vocab_size or blank is None: raise RuntimeError("Teacher vocabulary mismatch")

    train=load_rows(MSA,"train","sentence",None,a.train_per_source,proc,teacher,device,a.seed)
    train+=load_rows(LY,"train","transcript_text","ly",a.train_per_source,proc,teacher,device,a.seed)
    test=load_rows(MSA,"test","sentence",None,a.eval_per_source,proc,teacher,device,a.seed)
    test+=load_rows(LY,"test","transcript_text","ly",a.eval_per_source,proc,teacher,device,a.seed)
    counts={"train_msa":sum(x["lang"]=="msa_oriented" for x in train),
            "train_libyan":sum(x["lang"]=="libyan" for x in train),
            "test_msa":sum(x["lang"]=="msa_oriented" for x in test),
            "test_libyan":sum(x["lang"]=="libyan" for x in test)}
    if min(counts.values())<8: raise SystemExit("Not enough clips for a balanced pilot: "+str(counts))

    model=Student(n).to(device); opt=torch.optim.AdamW(model.parameters(),lr=2e-4,weight_decay=1e-4)
    losses=[]; start=time.time()
    for ep in range(a.epochs):
        random.shuffle(train); vals=[]
        for i,row in enumerate(train,1):
            v=train_step(model,row,opt,blank,device)
            if v is not None: vals.append(v)
            if i%20==0: print("EPOCH",ep+1,"STEP",i,"/",len(train),flush=True)
        losses.append(round(float(np.mean(vals)),5) if vals else None)
        print("EPOCH_LOSS",ep+1,losses[-1],flush=True)

    results=evaluate(model,test,tok,device)
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True); model.eval()
    torch.save(model.state_dict(),out/"eve_student_state.pt")
    (out/"vocab.json").write_text(json.dumps(vocab,ensure_ascii=False,indent=2),encoding="utf-8")
    cfg={"architecture":"EVE log-mel BiLSTM CTC","sample_rate":SR,"n_mels":NMEL,
         "vocab_size":n,"blank_id":blank,"teacher":TEACHER,"teacher_in_student":False,
         "distillation":"frame-level teacher-logit KL plus ground-truth CTC",
         "temperature":2.0,"ctc_weight":.55,"kl_weight":.45,
         "parameters":sum(p.numel() for p in model.parameters())}
    try:
        torch.onnx.export(model,torch.zeros(1,NMEL,200,device=device),str(out/"eve_student_float.onnx"),
            input_names=["log_mel"],output_names=["ctc_logits"],
            dynamic_axes={"log_mel":{0:"batch",2:"frames"},"ctc_logits":{0:"batch",1:"tokens"}},
            opset_version=17,do_constant_folding=True,dynamo=False)
        try:
            from onnxruntime.quantization import quantize_dynamic,QuantType
            quantize_dynamic(str(out/"eve_student_float.onnx"),str(out/"eve_student_int8.onnx"),weight_type=QuantType.QInt8)
            cfg["int8_quantization"]="created"
        except Exception as e: cfg["int8_quantization"]="unavailable: "+type(e).__name__
    except Exception as e: cfg["onnx_export"]="failed: "+type(e).__name__+": "+str(e)[:250]
    (out/"model_config.json").write_text(json.dumps(cfg,ensure_ascii=False,indent=2),encoding="utf-8")
    sizes={}
    for name in ("eve_student_state.pt","eve_student_float.onnx","eve_student_int8.onnx"):
        f=out/name
        if f.exists(): sizes[name]={"bytes":f.stat().st_size,"MiB":round(f.stat().st_size/1048576,3)}
    report={"status":"trained_pilot","method":"actual teacher soft-logit distillation + supervised CTC",
        "counts":counts,"epochs":a.epochs,"epoch_mean_losses":losses,"held_out_metrics":results,
        "student_parameters":cfg["parameters"],"model_files":sizes,"teacher_training_only":TEACHER,
        "sources":{"libyan":LY,"filter":"language == ly","msa_oriented":MSA},
        "rights_note":"The visible Lahgtna card exposes no explicit reuse license. Public access alone does not establish reuse rights. No raw source audio/transcript is packaged.",
        "limitations":["Small pilot, not production accuracy.","Speaker independence not verified.",
        "Common Voice Arabic is MSA-oriented read speech, not guaranteed pure MSA.",
        "Not yet integrated into Android; Redmi 12 offline latency is not measured."],
        "elapsed_seconds":round(time.time()-start,1)}
    (out/"metrics.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    print("PASS: student training completed",flush=True)

if __name__=="__main__": main()
