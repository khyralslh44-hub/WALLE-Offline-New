#!/usr/bin/env python3
# Revision: single-pass goal arbitration after duplicate workflow-step cleanup.
from pathlib import Path
import sys

def once(s, old, new, label):
    n=s.count(old)
    if n != 1: raise SystemExit(f"{label}: expected one anchor, found {n}")
    return s.replace(old,new,1)

def patch(p):
    s=p.read_text(encoding="utf-8")
    if "EVE_CENTRAL_STABILITY_MEMORY_V1" in s:
        print("central stability and memory already applied"); return
    old="const remember=(k,d)=>{M.events.push({t:Date.now(),k,d});if(M.events.length>120)M.events.shift()};"
    new="""/*EVE_CENTRAL_STABILITY_MEMORY_V1*/
M.personality=Object.assign({curiosity:.56,caution:.62,playfulness:.46,warmth:.64,patience:.58},M.personality||{});
M.decisionStats=M.decisionStats||{accepted:0,held:0};
const remember=(k,d)=>{
 const now=Date.now(),text=String(d||'').slice(0,180),weight=/تعلم|معلومة|شخص|مكان|روتين|تصحيح|عائلة/.test(String(k))?3:/عاطفة|مزاج|حدث/.test(String(k))?2:1;
 const prior=M.events.slice(-12).find(e=>e.k===k&&e.d===text&&now-e.t<180000);
 if(prior){prior.t=now;prior.n=(prior.n||1)+1;prior.w=Math.max(prior.w||1,weight);return prior}
 const e={t:now,k:String(k||'ملاحظة'),d:text,w:weight,n:1};M.events.push(e);
 while(M.events.length>160){let ix=0;for(let i=1;i<M.events.length;i++){const a=M.events[i],b=M.events[ix];if((a.w||1)<(b.w||1)||((a.w||1)===(b.w||1)&&a.t<b.t))ix=i}M.events.splice(ix,1)}
 return e
};"""
    s=once(s,old,new,"episodic memory")
    wrapper="""// Goal arbitration: hold a chosen intention briefly; urgent needs may pre-empt it.
const eveSetGoalRaw=setGoal;
const eveGoalPriority={'الراحة':6,'الاطمئنان':5,'الاستكشاف':3,'اللعب':2,'طلب الانتباه':1,'الملاحظة':0};
setGoal=function(n,steps,why){
 const now=Date.now(),age=goal&&goal.since?now-goal.since:1e9;
 const current=eveGoalPriority[goal&&goal.n]??0,next=eveGoalPriority[n]??0;
 const urgent=(n==='الراحة'&&S.en<.16)||(n==='الاطمئنان'&&S.v<-.65);
 if(goal&&goal.n&&goal.n!==n&&age<9000&&!urgent&&next<=current){M.decisionStats.held++;return false}
 if(goal&&goal.n===n)return false;
 eveSetGoalRaw(n,steps,why);M.decisionStats.accepted++;return true
};
"""
    tick_anchor="function tick(){"
    if s.count(tick_anchor)!=1: raise SystemExit(f"tick insertion anchor: expected one, found {s.count(tick_anchor)}")
    s=s.replace(tick_anchor,wrapper+"\n"+tick_anchor,1)
    p.write_text(s,encoding="utf-8")
    print("Applied central goal arbitration and weighted episodic memory.")

if __name__=="__main__":
    if len(sys.argv)!=2: raise SystemExit("usage: eve-memory-core.py <eve.html>")
    patch(Path(sys.argv[1]))
