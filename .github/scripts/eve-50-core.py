#!/usr/bin/env python3
"""EVE 5.0: evidence-backed long-term memory consolidation and low-confidence action guard."""
from pathlib import Path
import sys

MARK = "EVE_50_LONG_MEMORY_AND_CONFIDENCE_GUARD_V1"

JS = r"""
/* EVE_50_LONG_MEMORY_AND_CONFIDENCE_GUARD_V1
   Consolidate only repeated/weighted observed events; never invent personal facts.
   Keep long-term memory bounded and prefer observation when perception is uncertain. */
M.longMemory=Array.isArray(M.longMemory)?M.longMemory.slice(-60):[];
M.memoryStats=Object.assign({consolidated:0,expired:0,lowConfidenceHolds:0},M.memoryStats||{});
function eveMemoryKey(k,d){
 return (String(k||'').trim().toLowerCase()+'|'+String(d||'').trim().toLowerCase()
  .replace(/[،؛.!؟]+/g,' ').replace(/\s+/g,' ').slice(0,150));
}
function eveConsolidateMemory(now){
 const events=Array.isArray(M.events)?M.events.slice(-120):[];
 const groups=new Map();
 for(const e of events){
  if(!e||!e.d)continue;
  const key=eveMemoryKey(e.k,e.d),g=groups.get(key)||{key,k:e.k,d:e.d,first:e.t||now,last:e.t||now,count:0,w:e.w||1};
  g.first=Math.min(g.first,e.t||now);g.last=Math.max(g.last,e.t||now);
  g.count+=Math.max(1,e.n||1);g.w=Math.max(g.w,e.w||1);groups.set(key,g);
 }
 let changed=false;
 for(const g of groups.values()){
  if(g.count<2&&g.w<3)continue;
  const old=M.longMemory.find(x=>x.key===g.key);
  if(old){
   old.last=Math.max(old.last,g.last);old.count=Math.max(old.count,g.count);
   old.confidence=Math.min(.98,.45+Math.min(old.count,8)*.065+(old.w>=3?.12:0));
   old.w=Math.max(old.w,g.w);
  }else{
   M.longMemory.push({key:g.key,k:String(g.k||'ذكرى'),d:String(g.d).slice(0,180),
    first:g.first,last:g.last,count:g.count,w:g.w,
    confidence:Math.min(.95,.45+Math.min(g.count,8)*.065+(g.w>=3?.12:0))});
   M.memoryStats.consolidated++;changed=true;
  }
 }
 const cutoff=now-120*86400000,before=M.longMemory.length;
 M.longMemory=M.longMemory.filter(x=>x&&x.last>cutoff).sort((a,b)=>b.last-a.last).slice(0,60);
 M.memoryStats.expired+=before-M.longMemory.length;
 if(changed||before!==M.longMemory.length)save();
}
const eveRetrieveRecent=eveRetrieve;
eveRetrieve=function(evidence){
 const recent=eveRetrieveRecent(evidence),terms=(evidence||[]).map(x=>String(x.label||'').toLowerCase())
  .join(' ').split(/[^\p{L}\p{N}]+/u).filter(x=>x.length>2);
 const now=Date.now(),long=(M.longMemory||[]).map(e=>{
  const hay=(String(e.k||'')+' '+String(e.d||'')).toLowerCase();
  let hits=0;for(const t of terms)if(hay.includes(t))hits++;
  const score=(hits/Math.max(2,terms.length))*.82+(e.confidence||.4)*.12+
   Math.exp(-Math.max(0,now-e.last)/(45*86400000))*.06;
  return {k:e.k,d:e.d,score:+score.toFixed(3),t:e.last,longTerm:true};
 }).filter(x=>x.score>.08);
 const merged=[...recent,...long].sort((a,b)=>b.score-a.score),seen=new Set();
 return merged.filter(x=>{const k=eveMemoryKey(x.k,x.d);if(seen.has(k))return false;seen.add(k);return true}).slice(0,8);
};
// When the perception layer is unsure, avoid confident social/play actions.
const eveSetGoalBeforeConfidence=setGoal;
setGoal=function(n,steps,why){
 const stats=M.cog&&M.cog.stats,confidence=M.cog&&M.cog.lastConfidence;
 if(stats&&stats.observations>0&&confidence<.24&&
    ['الاستكشاف','اللعب','طلب الانتباه'].includes(n)){
  M.memoryStats.lowConfidenceHolds++;
  return eveSetGoalBeforeConfidence('الملاحظة',
   (typeof EVE_GOAL_STEPS!=='undefined'?EVE_GOAL_STEPS[5]:['أراقب بهدوء']),
   'ثقة الإدراك منخفضة؛ أفضّل جمع أدلة إضافية قبل التصرف.');
 }
 return eveSetGoalBeforeConfidence(n,steps,why);
};
"""

def main(root: Path):
    p=root/"app/src/main/assets/eve.html"
    s=p.read_text(encoding="utf-8")
    if MARK in s:
        print("EVE 5.0 memory core already applied")
        return
    anchor="function tick(){"
    call="tickN++;if(tickN%25===0)magicSpark(Date.now());"
    if s.count(anchor)!=1: raise SystemExit(f"tick anchor count: {s.count(anchor)}")
    if s.count(call)!=1: raise SystemExit(f"4.1 tick call anchor count: {s.count(call)}")
    s=s.replace(anchor,JS+"\n"+anchor,1)
    s=s.replace(call,call+"if(tickN%90===0)eveConsolidateMemory(Date.now());",1)
    p.write_text(s,encoding="utf-8")
    print("Applied EVE 5.0 long-term memory consolidation and uncertainty guard.")

if __name__=="__main__":
    if len(sys.argv)!=2: raise SystemExit("usage: eve-50-core.py <project-root>")
    main(Path(sys.argv[1]))
