#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
vision = root / "app/src/main/java/com/eve/companion/Vision.kt"
html = root / "app/src/main/assets/eve.html"

v = vision.read_text(encoding="utf-8")
old = '.setTargetResolution(Size(480, 360))'
new = '.setTargetResolution(Size(360, 270))'
if old in v:
    v = v.replace(old, new, 1)
elif new not in v:
    raise SystemExit("Vision resolution anchor not found")
v = v.replace('(if (rps) 150 else 600)', '(if (rps) 180 else 850)')
v = v.replace('(if (active) 1000 else 3500)', '(if (active) 1300 else 4500)')
vision.write_text(v, encoding="utf-8")

s = html.read_text(encoding="utf-8")
anchor = "let lastSay=0;\nfunction tick(){"
addition = """let lastSay=0;
/* EVE 4.1: small, context-led autonomous gestures; no random speech or cloud calls. */
let lastMagicSpark=0, magicTurn=0;
function magicSpark(now){
 if(inGame||asleep||!camOn||document.hidden||S.en<.18||now-lastMagicSpark<150000)return;
 const quiet=now-(M.last||now), present=!!hadPresent;
 if(present&&S.cu>.58&&quiet>45000){
  magicTurn++;gaze.tx=(magicTurn%2?-.72:.72);gaze.ty=-.22;
  bump('curious',.22);think('لمحتك، فتتبعت حركتك بعينيّ بفضول.');
 }else if(!present&&S.cu>.78&&quiet>90000){
  magicTurn++;gaze.tx=[-.8,0,.8,0][magicTurn%4];gaze.ty=[0,-.35,0,.3][magicTurn%4];
  bump('curious',.18);think('أستكشف المكان بهدوء وأقارن ما أراه بذكرياتي.');
 }else if(present&&S.bo>.82&&quiet>240000){
  gaze.tx=0;gaze.ty=.35;bump('bored',.16);
  think('أشعر بالهدوء والملل قليلاً؛ أنتظر شيئاً جديداً.');
 }else return;
 lastMagicSpark=now;
}
function tick(){"""
if anchor in s:
    s = s.replace(anchor, addition, 1)
elif "function magicSpark(now)" not in s:
    raise SystemExit("Tick insertion anchor not found")
call = "tickN++;if(goal.n==='الراحة')"
replacement = "tickN++;if(tickN%25===0)magicSpark(Date.now());if(goal.n==='الراحة')"
if call in s:
    s = s.replace(call, replacement, 1)
elif "magicSpark(Date.now())" not in s:
    raise SystemExit("Tick call anchor not found")
html.write_text(s, encoding="utf-8")

print("Applied EVE 4.1 polish: lighter native vision cadence and context-led expressive idle behavior.")
