#!/usr/bin/env python3
from pathlib import Path
import sys

def once(s, old, new, label):
    n=s.count(old)
    if n != 1: raise SystemExit(f"{label}: expected one anchor, found {n}")
    return s.replace(old,new,1)

def patch(p):
    s=p.read_text(encoding="utf-8")
    if "EVE_VERIFIED_OBJECT_LEARNING_V1" in s:
        print("verified learning already applied"); return
    s=once(s,
      'M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true},M.cfg||{});',
      """/*EVE_VERIFIED_OBJECT_LEARNING_V1*/
M.objectCandidates=M.objectCandidates||{};
for(const k of Object.keys(M.objectCandidates).slice(12))delete M.objectCandidates[k];
M.learningStats=M.learningStats||{promoted:0,rejected:0};
// User-taught labels stay provisional until later independent visual checks agree.
function verifyObjectCandidate(v,now){
 let changed=false;
 for(const name of Object.keys(M.objectCandidates)){
  const c=M.objectCandidates[name];
  if(!c||!Array.isArray(c.samples)){delete M.objectCandidates[name];continue}
  if(now-c.created>30*86400000){delete M.objectCandidates[name];M.learningStats.rejected++;changed=true;continue}
  let best=0;for(const sample of c.samples)best=Math.max(best,cos(sample,v));
  if(best>=.84&&now-(c.lastVerify||0)>=1800){
   c.lastVerify=now;c.verify=(c.verify||0)+1;
   if(c.verify>=4&&now-c.created>=15000){
    M.things[name]=[...c.samples,r3(v)].slice(0,8);delete M.objectCandidates[name];
    M.learningStats.promoted++;remember('تعلم موثّق','تأكدت من شكل '+name+' عبر مشاهدات لاحقة مستقلة');
    say('تأكدت الآن من «'+name+'» بعد أن رأيته مرة أخرى.','proud');changed=true;
   }
  }
 }
 if(changed)save();
}
M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true},M.cfg||{});""",
      "candidate verifier")
    import re
    pattern=r" if\\(e\\.obj\\)\\{lastObjEmb=\\{v:r3\\(e\\.obj\\),l:e\\.ol,t:now\\};.*?(?=\\n if\\(e\\.scene\\))"
    new=""" if(e.obj){lastObjEmb={v:r3(e.obj),l:e.ol,t:now};
  if(PEND&&PEND.kind==='thing'&&now<PEND.until){
   const name=PEND.name,buf=PEND.samples||(PEND.samples=[]);
   if(!buf.some(x=>cos(x,lastObjEmb.v)>.985)&&buf.length<6)buf.push(lastObjEmb.v);
   PEND.n=buf.length;
   if(buf.length>=4){M.objectCandidates[name]={samples:buf.slice(0,6),created:now,verify:0,lastVerify:0};
    remember('تعلم مؤقت','تعلمت وصف '+name+' وأنتظر التحقق في مشاهدات لاحقة');
    say('سجلت «'+name+'» مبدئياً. سأراقب ظهوره مرة أخرى قبل أن أعتمد التعرف عليه.','curious');PEND=null;save()}
  }else{
   verifyObjectCandidate(e.obj,now);
   TH=null;const d=osrDecide(M.things,e.obj,1,M.cfg.thFloor,.02);if(d&&d.ok){TH={n:d.name,s:d.s,t:now};M.seenObj[d.name]={t:now,p:PLACE||'',x:.5,y:.5}}
  }}"""
    s,n=re.subn(pattern,lambda m:new,s,count=1,flags=re.S)
    if n!=1: raise SystemExit(f"object learning flow: expected one block, found {n}")
    p.write_text(s,encoding="utf-8")
    print("Applied verified offline object learning.")
if __name__=="__main__":
    if len(sys.argv)!=2: raise SystemExit("usage: eve-core-memory.py <eve.html>")
    patch(Path(sys.argv[1]))
