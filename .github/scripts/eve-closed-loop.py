#!/usr/bin/env python3
from pathlib import Path
import re,sys

MARK="EVE_CLOSED_LOOP_OUTCOME_V1"
def patch(p):
 s=p.read_text(encoding="utf-8")
 if MARK in s:
  print("closed-loop outcome learning already applied"); return
 pattern=r"function plan\(\)\{[\s\S]*?\n/\*BRAIN_END\*/"
 m=re.search(pattern,s)
 if not m: raise SystemExit("Could not find central plan block")
 new=r"""/* EVE_CLOSED_LOOP_OUTCOME_V1
   Evaluate the previous intention before choosing the next one.
   Rewards are grounded in measured changes, not a fabricated notion of awareness. */
M.decisionTrace=Array.isArray(M.decisionTrace)?M.decisionTrace.slice(-40):[];
M.decisionStats=Object.assign({accepted:0,held:0,evaluated:0,lastReward:0},M.decisionStats||{});
function eveOutcomeReward(d){
 if(!d)return 0;
 const dt=Date.now()-d.at;if(dt<1800||dt>30000)return 0;
 const dv=S.v-d.v,de=S.en-d.en,db=M.bond-d.bond,dc=S.cu-d.cu;
 const social=(msgE>.15||hadPresent)?1:0;
 let r=0;
 // Reward only observable outcomes relevant to the selected intention.
 if(d.a===0)r=2.2*de+.25*(S.en>.45?1:0);
 else if(d.a===1)r=.55*dc+.25*(BR.su<.45?1:0)+.15*(camOn?1:0);
 else if(d.a===2)r=.55*social+.35*db+.15*(S.bo<d.bo?1:0);
 else if(d.a===3)r=.8*dv+.25*(touchE>.05?1:0)+.15*social;
 else if(d.a===4)r=1.1*dv+.35*db;
 else r=.35*(d.err-BR.err)+.15*(BR.su<.5?1:0);
 return clamp(r,-1,1);
}
function plan(){
 if(!BR.ready)return;
 let reward=0;
 if(BR.lastDecision){
  const d=BR.lastDecision;reward=eveOutcomeReward(d);
  M.decisionStats.evaluated++;M.decisionStats.lastReward=reward;
  M.decisionTrace.push({t:Date.now(),a:d.a,r:+reward.toFixed(3),goal:GN[d.a]});
  if(M.decisionTrace.length>40)M.decisionTrace.shift();
  // bpolicy performs the online update for the action that just finished.
  bpolicy(reward,.16);
  remember('نتيجة قرار',GN[d.a]+'؛ تقييم النتيجة '+reward.toFixed(2));
 }else bpolicy(0,.16);
 const scores=BR.q||[];
 let a=0;for(let i=1;i<NG;i++)if((scores[i]??-1e9)>(scores[a]??-1e9))a=i;
 // Hard safety priorities override learned preference.
 if(S.en<.12)a=0;
 if(S.v<-.72&&S.en>.18)a=4;
 curAct=a;
 BR.lastDecision={a,at:Date.now(),v:S.v,en:S.en,bond:M.bond,cu:S.cu,bo:S.bo,err:BR.err};
 const why='اختيار أعلى قيمة متعلّمة؛ نتيجة القرار السابق '+reward.toFixed(2);
 setGoal(GN[a],ST[a],why);
}
/*BRAIN_END*/"""
 s=s[:m.start()]+new+s[m.end():]
 p.write_text(s,encoding="utf-8")
 print("Applied closed-loop outcome evaluation and deterministic value-based action selection.")
if __name__=="__main__":
 if len(sys.argv)!=2:raise SystemExit("usage: eve-closed-loop.py <eve.html>")
 patch(Path(sys.argv[1]))
