#!/usr/bin/env python3
"""Upgrade the existing EVE v7 central brain without replacing its architecture."""
from pathlib import Path
import sys

path = Path(sys.argv[1])
src = path.read_text(encoding="utf-8")

def replace_once(old: str, new: str, label: str) -> None:
    global src
    count = src.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    src = src.replace(old, new, 1)

replace_once(
    "M.protos=M.protos||{};M.wv=M.wv||{};M.bg=M.bg||{};M.seed=M.seed??.05;M.stage=M.stage||0;M.last=M.last||Date.now();",
    "M.protos=M.protos||{};M.wv=M.wv||{};M.bg=M.bg||{};M.seed=M.seed??.05;M.stage=M.stage||0;M.last=M.last||Date.now();\n"
    "M.agent=Object.assign({actions:{},episodes:[],recent:[],pending:null},M.agent||{});"
    "M.agent.actions=M.agent.actions||{};M.agent.episodes=Array.isArray(M.agent.episodes)?M.agent.episodes:[];"
    "M.agent.recent=Array.isArray(M.agent.recent)?M.agent.recent:[];",
    "persistent decision memory",
)

replace_once(
    "const NI=40,NR=96,NS=24,NZ=NR+NS+1+NI,NG=6,GN=",
    "const NI=48,NR=96,NS=24,NZ=NR+NS+1+NI,NG=6,GN=",
    "central-brain sensory width",
)
replace_once("var BR={ready:false,r:", "var BR={ready:false,training:false,r:", "training guard")

replace_once(
    "function bpick(tau){const q=[];let m=-1e9;for(let k=0;k<NG;k++){q[k]=dotz(BR.Wg,k*NZ);m=Math.max(m,q[k])}\n"
    " let t=0;const p=q.map(v=>{const e=Math.exp((v-m)/tau);t+=e;return e});for(let k=0;k<NG;k++)p[k]/=t;BR.p=p;BR.q=q;\n"
    " let r=Math.random(),a=0;for(;a<NG-1;a++){r-=p[a];if(r<=0)break}return a}",
    "function bpick(tau){const q=[];let m=-1e9;for(let k=0;k<NG;k++){q[k]=dotz(BR.Wg,k*NZ);"
    "if(!BR.training){const st=M.agent.actions[String(k)];if(st&&st.n>=3)q[k]+=.045*clamp(st.mean,-.5,.5)*Math.min(1,st.n/8);"
    "const recent=M.agent.recent.slice(-4).filter(v=>v===k).length;if(recent>=2)q[k]-=.025*recent}m=Math.max(m,q[k])}\n"
    " let t=0;const p=q.map(v=>{const e=Math.exp((v-m)/tau);t+=e;return e});for(let k=0;k<NG;k++)p[k]/=t;BR.p=p;BR.q=q;\n"
    " let r=Math.random(),a=0;for(;a<NG-1;a++){r-=p[a];if(r<=0)break}return a}",
    "outcome-aware policy selection",
)

replace_once(
    "function bpolicy(R,tau){\n"
    " if(BR.zq&&BR.lastA>=0){let nq=1e-3,q=0;const o=BR.lastA*NZ;for(let i=0;i<NZ;i++){nq+=BR.zq[i]*BR.zq[i];q+=BR.Wg[o+i]*BR.zq[i]}\n"
    "  const g=.2*(clamp(R,-2,2)-q)/nq;for(let i=0;i<NZ;i++)BR.Wg[o+i]+=g*BR.zq[i]}\n"
    " const a=bpick(tau||.15);BR.lastA=a;BR.zq=Float32Array.from(BR.z);return a}",
    "function bpolicy(R,tau){\n"
    " if(BR.zq&&BR.lastA>=0){let nq=1e-3,q=0;const o=BR.lastA*NZ;for(let i=0;i<NZ;i++){nq+=BR.zq[i]*BR.zq[i];q+=BR.Wg[o+i]*BR.zq[i]}\n"
    "  const g=.2*(clamp(R,-2,2)-q)/nq;for(let i=0;i<NZ;i++)BR.Wg[o+i]+=g*BR.zq[i];\n"
    "  if(!BR.training){const key=String(BR.lastA),st=M.agent.actions[key]||{n:0,mean:0};st.n=Math.min(10000,st.n+1);"
    "st.mean+=(clamp(R,-2,2)-st.mean)/Math.min(st.n,24);st.t=Date.now();M.agent.actions[key]=st;\n"
    "   const p=M.agent.pending;if(p&&p.a===BR.lastA){M.agent.episodes.push({t:p.t,a:BR.lastA,r:Math.round(clamp(R,-2,2)*100)/100,"
    "v:p.v,en:p.en,cu:p.cu,person:p.person,food:p.food,activity:p.activity,unknown:p.unknown});"
    "if(M.agent.episodes.length>48)M.agent.episodes.shift()}}}\n"
    " const a=bpick(tau||.15);BR.lastA=a;BR.zq=Float32Array.from(BR.z);\n"
    " if(!BR.training){const x=BR.x;M.agent.pending={a,t:Date.now(),v:S.v,en:S.en,cu:S.cu,person:x[40],food:x[42],activity:x[45],unknown:x[44]}}\n"
    " return a}",
    "reinforcement outcomes and episodic learning",
)

replace_once(
    "function istep(t,n,st){const R=Math.random,x=BR.x;\n"
    " if(st.hold--<=0){st.hold=5+(R()*15|0);x.fill(0);for(let k=0;k<NI;k++)x[k]=R()<.1?(R()*2-1)*.8:0;\n"
    "  x[15]=R()<.4?1:0;x[14]=x[15]*R()*.6;x[36]=R()*1-.5;x[37]=R();x[38]=R()-.5;x[39]=R()-.5;x[32]=R()<.2?(R()<.5?-.8:.8):0;x[27]=R()<.1?1:0;x[24]=R()<.05?1:0;x[23]=R()<.1?R():0}\n"
    " bcore(x,true);learnAff(innateAffect(x),1);\n"
    " if(t%2===1)BR.act=bpolicy(innateReward(BR.act,x),.6-.45*t/n)}",
    "function istep(t,n,st){const R=Math.random,x=BR.x;\n"
    " if(st.hold--<=0){st.hold=5+(R()*15|0);x.fill(0);for(let k=0;k<40;k++)x[k]=R()<.1?(R()*2-1)*.8:0;\n"
    "  x[15]=R()<.4?1:0;x[14]=x[15]*R()*.6;x[36]=R()*1-.5;x[37]=R();x[38]=R()-.5;x[39]=R()-.5;x[32]=R()<.2?(R()<.5?-.8:.8):0;x[27]=R()<.1?1:0;x[24]=R()<.05?1:0;x[23]=R()<.1?R():0;\n"
    "  x[40]=x[15]*(R()<.6?1:0);x[41]=R()*.6;x[42]=x[41]*(R()<.25?1:0);x[43]=x[41]*(R()<.15?1:0);"
    "x[44]=R()<.12?R()*.8:0;x[45]=R()<.2?R()*.7:0;x[46]=x[15]&&R()<.35?R()*.8:0;x[47]=R()<.12?R()*.8:0}\n"
    " bcore(x,true);learnAff(innateAffect(x),1);\n"
    " if(t%2===1)BR.act=bpolicy(innateReward(BR.act,x),.6-.45*t/n)}",
    "coherent warm-start sensory samples",
)

replace_once(
    "function infancyAsync(n,done){let t=0;const st={hold:0};BR.act=5;\n"
    " (function chunk(){const e=Math.min(n,t+200);for(;t<e;t++)istep(t,n,st);\n"
    "  if(t<n)setTimeout(chunk,0);else{BR.r.fill(0);BR.s.fill(0);BR.zq=null;BR.lastA=-1;BR.yh=null;done()}})()}",
    "function infancyAsync(n,done){let t=0;const st={hold:0};BR.act=5;BR.training=true;\n"
    " (function chunk(){const e=Math.min(n,t+200);for(;t<e;t++)istep(t,n,st);\n"
    "  if(t<n)setTimeout(chunk,0);else{BR.r.fill(0);BR.s.fill(0);BR.zq=null;BR.lastA=-1;BR.yh=null;BR.training=false;done()}})()}",
    "training lifecycle",
)

replace_once(
    "function packBrain(){const a=v=>Array.from(v,q=>Math.round(q*1e4)/1e4);return{Wp:a(BR.Wp),Wa:a(BR.Wa),Wg:a(BR.Wg)}}\n"
    "(function(){const B=M.brain;\n"
    " if(B&&B.Wp&&B.Wp.length===NI*NZ&&B.Wa&&B.Wa.length===3*NZ&&B.Wg&&B.Wg.length===NG*NZ){BR.Wp.set(B.Wp);BR.Wa.set(B.Wa);BR.Wg.set(B.Wg)}\n"
    " else{BR.booting=true;infancyAsync(12000,()=>{BR.ready=true;BR.booting=false;save()});return}\n"
    " BR.ready=true})();",
    "function packBrain(){const a=v=>Array.from(v,q=>Math.round(q*1e4)/1e4);return{ni:NI,version:2,Wp:a(BR.Wp),Wa:a(BR.Wa),Wg:a(BR.Wg)}}\n"
    "function migrateBrain(B){if(!B||!B.Wp||!B.Wa||!B.Wg)return false;const prefix=NR+NS+1;\n"
    " let oldNi=Number(B.ni)||0;if(!oldNi){if(B.Wp.length===40*(prefix+40))oldNi=40;else if(B.Wp.length===NI*NZ)oldNi=NI}\n"
    " if(oldNi!==40&&oldNi!==NI)return false;const oldNz=prefix+oldNi;\n"
    " if(B.Wp.length!==oldNi*oldNz||B.Wa.length!==3*oldNz||B.Wg.length!==NG*oldNz)return false;\n"
    " const copy=(dst,src,rows)=>{for(let r=0;r<rows;r++)for(let c=0;c<oldNz;c++){const nc=c<prefix?c:c+(NZ-oldNz);dst[r*NZ+nc]=Number(src[r*oldNz+c])||0}};\n"
    " copy(BR.Wp,B.Wp,Math.min(oldNi,NI));copy(BR.Wa,B.Wa,3);copy(BR.Wg,B.Wg,NG);return true}\n"
    "(function(){const B=M.brain;\n"
    " if(!migrateBrain(B)){BR.booting=true;infancyAsync(12000,()=>{BR.ready=true;BR.booting=false;save()});return}\n"
    " BR.ready=true})();",
    "backward-compatible learned-weight migration",
)

replace_once(
    "  touchE*=.9;msgE*=.97;const now=Date.now(),sk=shakes.filter(t=>now-t<3000).length,bt=window._bat,d=new Date(),h=d.getHours()+d.getMinutes()/60;",
    "  const now=Date.now(),visualFresh=now-lastVisT<3000,objs=visualFresh&&Array.isArray(OBJ)?OBJ:[];\n"
    "  const labels=objs.map(o=>String(o.l||'').toLowerCase()),food=!!(typeof CLS!=='undefined'&&CLS&&now-CLS.t<7000&&CLS.food)||/banana|apple|sandwich|orange|broccoli|carrot|hot dog|pizza|donut|cake|bowl of food|food/.test(labels.join('|'));\n"
    "  const sport=!!(typeof CLS!=='undefined'&&CLS&&now-CLS.t<7000&&CLS.sport)||/sports ball|tennis racket|skateboard|bicycle|dumbbell|barbell/.test(labels.join('|'));\n"
    "  let person=FACE?1:0;for(const o of objs)if(o.l==='person')person=Math.max(person,Number(o.s)||0);\n"
    "  const unknown=typeof UNK!=='undefined'&&UNK?Object.values(UNK).filter(u=>now-u.t<15000).length:0;\n"
    "  const activity=typeof ACT!=='undefined'&&ACT&&now-ACT.t<10000?clamp(Number(ACT.c)||0):0;\n"
    "  const familiar=FACE&&typeof IDS!=='undefined'&&IDS&&IDS.who?clamp(Number(IDS.conf)||0):0;\n"
    "  const gestures=typeof GEST!=='undefined'&&Array.isArray(GEST)?GEST.filter(g=>now-g.t<2500):[];\n"
    "  const gesture=gestures.reduce((m,g)=>Math.max(m,Number(g.s)||0),0);\n"
    "  x[40]=clamp(person);x[41]=clamp(objs.length/4);x[42]=food?1:0;x[43]=sport?1:0;x[44]=clamp(unknown/3);x[45]=activity;x[46]=familiar;x[47]=gesture;\n"
    "  touchE*=.9;msgE*=.97;const sk=shakes.filter(t=>now-t<3000).length,bt=window._bat,d=new Date(),h=d.getHours()+d.getMinutes()/60;",
    "semantic perception into central brain",
)

replace_once(
    "function plan(){\n"
    " if(inGame){setGoal('اللعب',ST[3],'ألعب مع صاحبي');return}if(!BR.ready)return;const x=BR.x;\n"
    " const R=innateReward(curAct,x)+2*(S.v-prevV)+40*(M.bond-prevBond)+(curAct===1?.3*BR.su*(.5+M.tr.cu):0)+(curAct===3?.1*(M.tr.pla-.5):0);prevV=S.v;prevBond=M.bond;\n"
    " let a=bpolicy(R,.12);if(S.en<.12)a=0;curAct=a;\n"
    " setGoal(GN[a],ST[a],'قرار شبكتي العصبية (احتمال '+Math.round(BR.p[a]*100)+'٪، مكافأتي '+R.toFixed(2)+')')}",
    "function plan(){\n"
    " if(inGame){setGoal('اللعب',ST[3],'ألعب مع صاحبي');return}if(!BR.ready)return;const x=BR.x,now=Date.now();\n"
    " const novelty=x[44]+.35*x[41];\n"
    " const R=innateReward(curAct,x)+2*(S.v-prevV)+40*(M.bond-prevBond)+(curAct===1?.3*BR.su*(.5+M.tr.cu)+.18*novelty+.08*x[43]:0)+(curAct===2?.08*x[46]:0)+(curAct===3?.12*x[47]+.08*x[45]+.1*(M.tr.pla-.5):0)+(curAct===4?.08*Math.max(0,-S.v):0);\n"
    " prevV=S.v;prevBond=M.bond;const proposed=bpolicy(R,.18);let a=proposed;\n"
    " const urgent=(typeof lowPower!=='undefined'&&lowPower)||S.en<.16,current=GN.indexOf(goal.n);\n"
    " if(urgent)a=0;else if(a===1&&!camOn)a=5;else if((a===2||a===3)&&x[40]<.25)a=camOn&&S.cu>.58?1:5;\n"
    " if(current>=0&&now-goal.since<8000&&!urgent&&a!==current)a=current;\n"
    " if(a!==proposed){BR.lastA=a;BR.zq=Float32Array.from(BR.z)}curAct=a;\n"
    " const before=goal.n;setGoal(GN[a],ST[a],'قرار الشبكة العصبية بعد تقييم الحالة والنتيجة السابقة (احتمال '+Math.round((BR.p?BR.p[a]:0)*100)+'٪، مكافأتي '+R.toFixed(2)+')');\n"
    " if(goal.n!==before){M.agent.recent.push(a);if(M.agent.recent.length>12)M.agent.recent.shift()}}",
    "context-aware stable decision planner",
)

replace_once(
    "function act(){\n"
    " if(inGame)return;const g=goal.n;\n"
    " if(g==='الاستكشاف'){const s=goal.i++%4;if(vlmUp&&Date.now()-lastVlm>30000&&!llmBusy&&!(window._bat&&!_bat.charging&&_bat.level<.3)){lastVlm=Date.now();describeScene().then(()=>{if(vlmText&&llmUp)askLLM('علّقي بجملة قصيرة على ما تشاهدينه الآن.')})};gaze.tx=[-1,0,1,0][s];gaze.ty=[0,-.7,0,.5][s];\n"
    "  if(scene.desc&&Math.random()<.35&&Date.now()-lastSay>9000){lastSay=Date.now();S.cu-=.2;M.tr.cu=clamp(M.tr.cu+.004)}}\n"
    "  else if(g==='طلب الانتباه'&&Date.now()-lastSay>12000){(Date.now()-lastChirp>20000?(lastChirp=Date.now(),chirp('curious')):0);lastSay=Date.now();S.bo=.3}\n"
    " else if(g==='اللعب'){gaze.tx=Math.sin(Date.now()/500);bump('play',.2)}\n"
    " else if(g==='الراحة'){gaze.tx=0;gaze.ty=.4;bump('sleepy',.1)}\n"
    " else{if(Math.random()<.3){gaze.tx=rnd(-.8,.8);gaze.ty=rnd(-.4,.4)}}}",
    "function focusScene(){let b=FACE&&FACE.b?FACE.b:null;if(!b&&Array.isArray(OBJ)){const o=OBJ.filter(v=>v&&v.b&&v.s>.35&&v.l!=='person').sort((a,c)=>c.s-a.s)[0];if(o)b=o.b}\n"
    " if(b&&b.length>=4){gaze.tx=clamp((b[0]+b[2]/2-.5)*2.2,-1,1);gaze.ty=clamp((b[1]+b[3]/2-.5)*2,-1,1);return}\n"
    " const i=Math.floor(Date.now()/6500)%4;gaze.tx=[-.65,.65,0,0][i];gaze.ty=[0,0,-.45,.45][i]}\n"
    "function act(){\n"
    " if(inGame)return;const g=goal.n;\n"
    " if(g==='الاستكشاف'){goal.i++;if(vlmUp&&Date.now()-lastVlm>30000&&!llmBusy&&!(window._bat&&!_bat.charging&&_bat.level<.3)){lastVlm=Date.now();describeScene().then(()=>{if(vlmText&&llmUp)askLLM('علّقي بجملة قصيرة على ما تشاهدينه الآن.')})};focusScene();\n"
    "  if(scene.desc&&Date.now()-lastSay>20000&&xNovelty()){lastSay=Date.now();S.cu=Math.max(0,S.cu-.12);M.tr.cu=clamp(M.tr.cu+.004)}}\n"
    " else if(g==='طلب الانتباه'&&Date.now()-lastSay>12000){if(Date.now()-lastChirp>20000){lastChirp=Date.now();chirp('curious')}lastSay=Date.now();S.bo=.3}\n"
    " else if(g==='اللعب'){gaze.tx=Math.sin(Date.now()/500);bump('play',.2)}\n"
    " else if(g==='الراحة'){gaze.tx=0;gaze.ty=.4;bump('sleepy',.1)}\n"
    " else focusScene()}\n"
    "function xNovelty(){return BR.x[44]>.15||BR.x[41]>.3}",
    "salience-directed non-random attention",
)

path.write_text(src, encoding="utf-8")
print(f"Upgraded {path}")
print("Central brain: 40 -> 48 sensory inputs; learned-weight migration enabled.")
print("Added persistent action outcomes, episodic summaries, action-context bias, goal hysteresis, and salience-directed gaze.")
