#!/usr/bin/env python3
"""Apply conservative temporal confirmation to EVE's native vision summaries."""
from pathlib import Path
import sys


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} anchor, found {count}.")
    return source.replace(old, new, 1)


def patch_html(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    marker = "function stableObjectLabels(now,windowMs=8000,minHits=2){"
    if marker not in source:
        start = source.index("function objectEvents(os,now){")
        end = source.index("function analyzeAct()", start)
        replacement = '''// Do not announce an object from a single detector frame. Require the same label
// in at least two separate analyses within eight seconds. This reduces flicker;
// it does not turn a classifier into full scene understanding.
function stableObjectLabels(now,windowMs=8000,minHits=2){
 const hits=Object.create(null);
 for(const h of OBJH){if(now-h.t>windowMs)continue;
  for(const l of new Set(h.l||[]))hits[l]=(hits[l]||0)+1;
 }
 return new Set(Object.keys(hits).filter(l=>hits[l]>=minHits));
}
function objectEvents(os,now){
 const stable=stableObjectLabels(now);
 for(const o of os){
  if(stable.has(o.l)&&o.s>=.5){
   seenRecord(o,now);if(!M.seenL)M.seenL={};
   if(!M.seenL[o.l]){M.seenL[o.l]=1;if(o.l!=='person'){bump('curious',.3);remember('جديد','رأيت '+(AR[o.l]||o.l)+' أكثر من مرة')}}
  }
  if(o.l!=='person'&&o.s<.5){const k=o.l+':'+Math.round(o.b[0]*5)+Math.round(o.b[1]*5);const u=UNK[k]=UNK[k]||{n:0,t0:now,b:o.b,l:o.l};u.n++;u.t=now;u.b=o.b}
 }
 for(const k in UNK)if(now-UNK[k].t>60000)delete UNK[k]
}
'''
        source = source[:start] + replacement + source[end:]

    if "L=stableObjectLabels(now,6000,2)" not in source:
        source = replace_once(
            source,
            " const aud=Math.max(...B.map(x=>x.a)),sm=Math.max(...B.map(x=>x.smile)),mo=B.reduce((s,x)=>s+x.mo,0)/B.length,L=new Set();\n OBJH.filter(h=>now-h.t<6000).forEach(h=>h.l.forEach(l=>L.add(l)));\n",
            " const aud=Math.max(...B.map(x=>x.a)),sm=Math.max(...B.map(x=>x.smile)),mo=B.reduce((s,x)=>s+x.mo,0)/B.length,L=stableObjectLabels(now,6000,2);\n",
            "activity evidence replacement",
        )
    if "stable=stableObjectLabels(now),os=" not in source:
        source = replace_once(
            source,
            "function describeNow(){const now=Date.now(),p=[],os=OBJ.filter(o=>o.s>=.4&&o.l!=='person').slice(0,3);\n if(FACE)p.push(IDS.who?'أرى '+IDS.who+' (أظن ذلك بثقة '+Math.round(IDS.conf*100)+'٪)':'أرى شخصاً لا أعرفه');else if(OBJ.some(o=>o.l==='person'))p.push('أرى شخصاً');",
            "function describeNow(){const now=Date.now(),p=[],stable=stableObjectLabels(now),os=OBJ.filter(o=>stable.has(o.l)&&o.s>=.5&&o.l!=='person').slice(0,3);\n if(FACE)p.push(IDS.who?'أرى '+IDS.who+' (أظن ذلك بثقة '+Math.round(IDS.conf*100)+'٪)':'أرى شخصاً لا أعرفه');else if(stable.has('person'))p.push('أرى شخصاً');",
            "description evidence replacement",
        )
    path.write_text(source, encoding="utf-8")


def bump_version(project_root: Path) -> None:
    gradle = project_root / "app" / "build.gradle.kts"
    source = gradle.read_text(encoding="utf-8")
    if "versionCode = 3" in source:
        source = replace_once(source, "versionCode = 3", "versionCode = 4", "versionCode bump")
    elif "versionCode = 4" not in source:
        raise SystemExit("Unexpected versionCode; refusing an unsafe version edit.")
    if 'versionName = "3.0"' in source:
        source = replace_once(source, 'versionName = "3.0"', 'versionName = "3.1"', "versionName bump")
    elif 'versionName = "3.1"' not in source:
        raise SystemExit("Unexpected versionName; refusing an unsafe version edit.")
    gradle.write_text(source, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: harden-eve-vision.py <path-to-eve.html>")
    html = Path(sys.argv[1]).resolve()
    if not html.is_file():
        raise SystemExit(f"EVE HTML not found: {html}")
    project_root = html.parents[4]
    patch_html(html)
    bump_version(project_root)
    print("Applied temporal vision evidence gate; app version is now 3.1 (versionCode 4).")


if __name__ == "__main__":
    main()
