#!/usr/bin/env python3
from pathlib import Path
import sys

MARK = "EVE_HIERARCHICAL_COGNITIVE_CORE_V1"
ANCHOR = "/*NATIVE_START*/"

JS = r"""
/* EVE_HIERARCHICAL_COGNITIVE_CORE_V1
   Local, bounded cognitive controller:
   evidence -> confidence -> memory retrieval -> appraisal -> utility planning
   -> action -> measured outcome -> online value update.
   This is a deterministic cognitive architecture, not a claim of sentience. */
M.cog=Object.assign({
 v:1,episodes:[],values:[0,0,0,0,0,0],counts:[0,0,0,0,0,0],
 last:null,lastReason:'',lastConfidence:0,updates:0,
 traits:{curiosity:.56,caution:.62,playfulness:.46,warmth:.64,patience:.58},
 stats:{observations:0,plans:0,evaluations:0,lowConfidence:0}
},M.cog||{});
M.cog.values=(Array.isArray(M.cog.values)&&M.cog.values.length===6)?M.cog.values:[0,0,0,0,0,0];
M.cog.counts=(Array.isArray(M.cog.counts)&&M.cog.counts.length===6)?M.cog.counts:[0,0,0,0,0,0];
M.cog.episodes=Array.isArray(M.cog.episodes)?M.cog.episodes.slice(-80):[];
const EVE_GOAL_NAMES=['الراحة','الاستكشاف','طلب الانتباه','اللعب','الاطمئنان','الملاحظة'];
const EVE_GOAL_STEPS=[
 ['أخفّض نشاطي','أغمض عينيّ','أستعيد طاقتي'],
 ['أتحقق من المشهد','أقارن الأدلة بما أتذكره','أتعلم إن ظهر شيء جديد'],
 ['أبادر بإشارة لطيفة','أنتظر استجابة مناسبة'],
 ['أختار تفاعلاً مرحاً','أراقب استجابتك'],
 ['أخفّف التوتر','أبقى قريبة دون إزعاج'],
 ['أراقب بهدوء','أنتظر دليلاً أوضح']
];
const eveClip=(x,a,b)=>Math.max(a,Math.min(b,x));
function eveEvidence(now){
 const out=[], add=(kind,label,confidence,source,detail='')=>{
  confidence=eveClip(Number(confidence)||0,0,1);
  if(confidence>=.12)out.push({kind,label:String(label||'').slice(0,80),c:confidence,source,detail:String(detail||'').slice(0,100),t:now});
 };
 if(typeof FACE!=='undefined'&&FACE)add('social',IDS&&IDS.who?'شخص معروف: '+IDS.who:'وجه بشري ظاهر',IDS&&IDS.conf?Math.max(.55,IDS.conf):.72,'face');
 if(Array.isArray(OBJ))for(const o of OBJ.slice(0,8))if(o&&o.l)add('object',o.l,o.s||.25,'detector');
 if(typeof ACT!=='undefined'&&ACT&&ACT.n)add('activity',ACT.n,ACT.c||.3,'temporal-activity');
 if(typeof PLACE!=='undefined'&&PLACE)add('place',PLACE,.78,'place-memory');
 if(typeof GEST!=='undefined'&&Array.isArray(GEST)){
  const g=GEST.filter(x=>now-x.t<2200).sort((a,b)=>b.s-a.s)[0];
  if(g)add('gesture',g.n,g.s||.3,'gesture');
 }
 if(typeof scene!=='undefined'&&scene&&scene.desc)add('scene',scene.desc,.38,'scene-summary');
 if(typeof vlmText!=='undefined'&&vlmText)add('scene',vlmText,.35,'optional-local-description');
 return out;
}
function eveRetrieve(evidence){
 const ev=Array.isArray(M.events)?M.events.slice(-100):[];
 const terms=evidence.map(x=>x.label.toLowerCase()).join(' ').split(/[^\p{L}\p{N}]+/u).filter(x=>x.length>2);
 const now=Date.now(),ranked=ev.map(e=>{
  const d=String(e.d||''),k=String(e.k||''),hay=(d+' '+k).toLowerCase();
  let hits=0;for(const w of terms)if(hay.includes(w))hits++;
  const age=Math.max(0,now-(e.t||now)),recency=Math.exp(-age/(7*86400000));
  return {e,score:(hits/(Math.max(2,terms.length)))*.72+recency*.12+(e.w||1)*.035};
 }).filter(x=>x.score>.06).sort((a,b)=>b.score-a.score).slice(0,5);
 return ranked.map(x=>({k:x.e.k,d:x.e.d,score:+x.score.toFixed(3),t:x.e.t}));
}
function eveAppraise(evidence,mem,now){
 const best=evidence.length?Math.max(...evidence.map(x=>x.c)):0;
 const confidence=evidence.length?eveClip(best*.55+Math.min(1,evidence.length/5)*.25+(typeof FACE!=='undefined'&&(FACE ? .45 : 0)),0,1):0;
 const novelty=evidence.length?eveClip(evidence.filter(x=>x.kind==='object'&&!M.seenL?.[x.label]).length*.22+
  (evidence.some(x=>x.kind==='place'&&!PLACE)? .15:0),0,1):0;
 const uncertainty=1-confidence;
 const social=evidence.some(x=>x.kind==='social')?1:0;
 const activity=evidence.find(x=>x.kind==='activity');
 const risk=/fire|knife|blood|broken|edge|fall|danger/i.test(evidence.map(x=>x.label).join(' '))?1:0;
 const valence=eveClip((S.v+1)/2,0,1),energy=eveClip(S.en,0,1),boredom=eveClip(S.bo,0,1);
 return {confidence,novelty,uncertainty,social,activity:activity?activity.label:'',risk,valence,energy,boredom,
  memory:mem.length,person:typeof IDS!=='undefined'&&IDS.who?IDS.who:''};
}
function eveOutcome(last,app){
 if(!last)return 0;
 const age=Date.now()-last.t;if(age<1800||age>45000)return 0;
 const dv=S.v-last.v,de=S.en-last.en,db=(M.bond||0)-last.bond,dbored=S.bo-last.bo,dc=S.cu-last.cu;
 let r=0;
 switch(last.a){
  case 0:r=1.6*de+.15*(S.en>.55?1:0)-.25*Math.max(0,-dv);break;
  case 1:r=.55*dc+.3*(app.novelty>0?1:0)+.2*(app.confidence>last.confidence?1:0)-.2*app.uncertainty;break;
  case 2:r=.65*db+.25*(dbored<0?1:0)+.15*(app.social?1:0);break;
  case 3:r=.75*dv+.25*(db>0?1:0)+.15*(app.social?1:0);break;
  case 4:r=.9*dv+.35*db-.2*(dbored>0?1:0);break;
  default:r=.25*(app.confidence-last.confidence)+.15*(dc>0?1:0);
 }
 return eveClip(r,-1,1);
}
function eveLearnOutcome(app){
 const c=M.cog,last=c.last;if(!last)return 0;
 const reward=eveOutcome(last,app),a=last.a;
 if(last.z&&last.z.length===NZ&&BR&&BR.Wg){
  let q=0,norm=1e-3;const off=a*NZ;
  for(let i=0;i<NZ;i++){q+=BR.Wg[off+i]*last.z[i];norm+=last.z[i]*last.z[i]}
  const next=Math.max(...Array.from({length:6},(_,k)=>{let z=0,o=k*NZ;for(let i=0;i<NZ;i++)z+=BR.Wg[o+i]*BR.z[i];return z}));
  const delta=eveClip(reward+.72*next-q,-1.5,1.5),rate=.035;
  for(let i=0;i<NZ;i++)BR.Wg[off+i]+=rate*delta*last.z[i]/norm;
  c.values[a]=.92*c.values[a]+.08*reward;c.counts[a]++;
 }
 c.evaluations=(c.evaluations||0)+1;c.updates++;
 c.episodes.push({t:Date.now(),a,goal:EVE_GOAL_NAMES[a],r:+reward.toFixed(3),confidence:+app.confidence.toFixed(2)});
 if(c.episodes.length>80)c.episodes.shift();
 c.last=null;
 return reward;
}
function eveCognitivePlan(){
 if(!BR.ready||inGame)return;
 const now=Date.now(),evidence=eveEvidence(now),memory=eveRetrieve(evidence),app=eveAppraise(evidence,memory,now),reward=eveLearnOutcome(app),c=M.cog;
 const world=eveWorldStep(app,evidence,now);app.predictionError=world.error;app.novelty=eveClip(Math.max(app.novelty,world.error*.48),0,1);
 c.stats.observations++;c.stats.plans++;c.lastConfidence=app.confidence;
 if(app.confidence<.35)c.stats.lowConfidence++;
 const q=Array.from({length:6},(_,a)=>{let v=0,o=a*NZ;for(let i=0;i<NZ;i++)v+=BR.Wg[o+i]*BR.z[i];return v});
 const traits=c.traits,night=(new Date().getHours()>=23||new Date().getHours()<6)?1:0;
 const util=[
  (1-app.energy)*1.45+world.drives.rest*.32+night*.22+(lowPower?.35:0),
  app.novelty*1.05+traits.curiosity*.38*app.uncertainty+app.predictionError*.42+(camOn?.18:0)-app.risk*.8,
  app.boredom*.75+(app.social?.12:0)+(world.drives.social*.12)-.35*(app.energy<.22?1:0),
  app.valence*.28+app.energy*.35+traits.playfulness*.35+(app.social?.25:0)-.5*(app.energy<.3?1:0),
  (1-app.valence)*.95+traits.warmth*.2+(app.activity==='يبكي'?.3:0),
  .22+app.uncertainty*.4+app.confidence*.12
 ];
 // Learned action values are blended with explicit, auditable needs and costs.
 const scores=q.map((v,a)=>v*.42+util[a]*.58+(c.values[a]||0)*.18);
 // Safety/availability constraints suppress impossible or harmful actions.
 if(app.energy<.14){scores[0]+=2.2;for(const a of [2,3,1])scores[a]-=1.1}
 if(!camOn)scores[1]-=.55;
 if(!app.social)scores[2]-=.42;
 if(app.risk)scores[1]-=1.2;
 if(goal&&goal.n&&now-(goal.since||0)<9000){
  const current=EVE_GOAL_NAMES.indexOf(goal.n);
  if(current>=0&&current!==0&&current!==4)scores[current]+=.16;
 }
 let a=0;for(let i=1;i<6;i++)if(scores[i]>scores[a]+1e-8)a=i;
 // A very low confidence scene is not permission to invent an explanation.
 const reason=['حاجة الطاقة والراحة','فضول مضبوط بثقة الأدلة ودرجة الجِدّة','الملل والحضور الاجتماعي','المزاج والطاقة وفرصة اللعب','الحالة الوجدانية والحاجة إلى الطمأنينة','عدم اليقين والحاجة إلى المراقبة'][a];
 const explain=reason+'؛ ثقة الإدراك '+Math.round(app.confidence*100)+'٪؛ الجِدّة '+Math.round(app.novelty*100)+'٪؛ أثر القرار السابق '+reward.toFixed(2);
 c.lastReason=explain;
 if(typeof jlog==='function')jlog('',explain,'cognitive-plan');
 if(typeof think==='function'&&(!goal||goal.n!==EVE_GOAL_NAMES[a]))think('خطة داخلية: '+reason+' ('+Math.round(app.confidence*100)+'٪ ثقة)');
 c.last={a,t:now,v:S.v,en:S.en,bond:M.bond||0,bo:S.bo,cu:S.cu,confidence:app.confidence,
  z:Array.from(BR.z),evidence:evidence.slice(0,8).map(x=>({k:x.kind,l:x.label,c:+x.c.toFixed(2)}))};
 curAct=a;BR.lastA=a;BR.zq=Float32Array.from(BR.z);
 setGoal(EVE_GOAL_NAMES[a],EVE_GOAL_STEPS[a],explain);
 if(now-(c.lastSave||0)>30000){c.lastSave=now;save()}
}
/* EVE_PREDICTIVE_WORLD_MODEL_V1
   Tiny predictive layer: working memory, scene-transition model, prediction error,
   and bounded drives. It learns temporal regularities without claiming identity or intent. */
M.cog.world=Object.assign({v:1,previous:null,working:[],transitions:{},surprise:0,observations:0,drive:{rest:0,curiosity:.3,social:.2,play:.2,safety:.1}},M.cog.world||{});
M.cog.world.working=Array.isArray(M.cog.world.working)?M.cog.world.working.slice(-8):[];
M.cog.world.transitions=M.cog.world.transitions&&typeof M.cog.world.transitions==='object'?M.cog.world.transitions:{};
function eveWorldStep(app,evidence,now){
 const w=M.cog.world,features=[app.confidence,app.novelty,app.social,app.energy,app.valence,app.boredom,app.risk];
 let error=0,used=0;
 if(Array.isArray(w.previous)&&w.previous.length===features.length){
  for(let i=0;i<features.length;i++){error+=Math.abs(features[i]-w.previous[i]);used++}
  error=used?eveClip(error/used,0,1):0;
 }
 w.surprise=.72*(w.surprise||0)+.28*error;
 w.previous=features;
 w.observations++;
 // Compact working memory: merge repeats, decay stale evidence, retain strongest recent items.
 for(const ev of evidence){
  const key=ev.kind+':'+ev.label.toLowerCase();
  const prior=w.working.find(x=>x.key===key);
  if(prior){prior.c=Math.max(prior.c*.88,ev.c);prior.n=(prior.n||1)+1;prior.t=now}
  else w.working.push({key,k:ev.kind,l:ev.label,c:ev.c,n:1,t:now});
 }
 w.working=w.working.filter(x=>now-x.t<6*3600000).sort((a,b)=>(b.c*Math.min(4,b.n))/(1+Math.max(0,now-b.t)/3600000)-(a.c*Math.min(4,a.n))/(1+Math.max(0,now-a.t)/3600000)).slice(0,8);
 const sceneKey=evidence.filter(x=>x.c>.35).slice(0,3).map(x=>x.kind+':'+x.label.toLowerCase()).sort().join('|')||'uncertain';
 if(w.previousScene&&w.previousScene!==sceneKey){
  const edge=w.previousScene+'>'+sceneKey;w.transitions[edge]=Math.min(20,(w.transitions[edge]||0)+1);
 }
 w.previousScene=sceneKey;
 const d=w.drive;
 d.rest=eveClip((1-app.energy)*.72+(app.boredom<.1?.08:0),0,1);
 d.curiosity=eveClip(app.novelty*.55+w.surprise*.55,0,1);
 d.social=eveClip(app.social*.65+(app.person? .2:0),0,1);
 d.play=eveClip(app.valence*.35+app.energy*.35+app.boredom*.3,0,1);
 d.safety=eveClip(app.risk*.8+app.uncertainty*.25,0,1);
 return {error:w.surprise,working:w.working.length,drives:d};
}

const evePlanBeforeCognition=plan;
plan=function(){return eveCognitivePlan()};
"""
def patch(path):
    s=path.read_text(encoding="utf-8")
    if MARK in s:
        print("Hierarchical cognitive core already present")
        return
    if s.count(ANCHOR)!=1:
        raise SystemExit(f"Expected one NATIVE_START anchor, found {s.count(ANCHOR)}")
    s=s.replace(ANCHOR,JS+"\n"+ANCHOR,1)
    path.write_text(s,encoding="utf-8")
    print("Installed hierarchical evidence-memory-appraisal-planning-learning core")
if __name__=="__main__":
    if len(sys.argv)!=2: raise SystemExit("usage: eve-cognitive-architecture.py <eve.html>")
    patch(Path(sys.argv[1]))

