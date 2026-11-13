#!/usr/bin/env python3
"""Switch EVE 12 to its existing on-device Arabic MFCC+DTW recognizer only."""
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(text: str, old: str, new: str, label: str) -> str:
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected one match, found {n}")
    return text.replace(old, new, 1)

def edit(rel: str, transform) -> None:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    text = transform(text)
    p.write_text(text, encoding="utf-8")

main_rel = "app/src/main/java/com/eve/companion/MainActivity.kt"
def patch_main(t: str) -> str:
    t = replace_once(t, '    private var sttPref = "auto"\n', '', "remove external STT preference")
    t = replace_once(
        t,
        '            tts?.apply { stop(); setPitch(1.0f); setSpeechRate((rate / voice.factor(style)).coerceIn(0.4f, 2.0f)) }',
        '            tts?.apply { stop(); setPitch(1.0f); setSpeechRate((rate / this@MainActivity.voice.factor(style)).coerceIn(0.4f, 2.0f)) }',
        "fix voice factor reference"
    )
    t = re.sub(
        r'        /\*\* محرك الاستماع: auto .*?\n        @JavascriptInterface fun sttEmbedded\(\): Boolean = hasEmbedded\(\)\n',
        '        /** يعيد تشغيل أذن EVE المحلية فقط. */\n'
        '        @JavascriptInterface fun sttEngine(name: String) { runOnUiThread { speech?.stop(); speech = null; refreshSenses() } }\n',
        t, count=1, flags=re.S
    )
    if 'fun sttEmbedded()' in t or 'sttPref' in t:
        raise SystemExit("External STT preference bridge was not fully removed")
    pattern = r'                val onT: \(String\) -> Unit = .*?\n                speech\?\.start\(\)'
    replacement = (
        '                val onL: (Float) -> Unit = { l -> js("Eve.rms($l)") }\n'
        '                val onS: (Boolean) -> Unit = { ok -> js("Eve.stt($ok)") }\n'
        '                val onD: (String) -> Unit = { d -> js("Eve.diag($d)") }\n'
        '                val onU: (String) -> Unit = { u -> js("Eve.utt(\'$u\')") }\n'
        '                speech = PcmEar(onU, onL, onS, { speaking }, onD)\n'
        '                speech?.start()'
    )
    t, n = re.subn(pattern, lambda _: replacement, t, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"replace STT selection block: expected one match, found {n}")
    t = replace_once(
        t,
        '    private fun hasEmbedded(): Boolean = try { (assets.list("vosk/model")?.size ?: 0) > 0 } catch (e: Exception) { false }\n\n',
        '',
        "remove embedded Vosk model check"
    )
    for token in ["VoskSpeech", "SpeechRecognizer", "sttPref", "hasEmbedded()", "Speech(this"]:
        if token in t:
            raise SystemExit(f"Old speech engine reference remains in MainActivity: {token}")
    return t
edit(main_rel, patch_main)

html_rel = "app/src/main/assets/eve.html"
def patch_html(t: str) -> str:
    t = replace_once(
        t,
        "M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true,stt:'auto'},M.cfg||{});",
        "M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true,stt:'local'},M.cfg||{});M.cfg.stt='local';",
        "make EVE local recognizer default"
    )
    t = replace_once(
        t,
        "$('bEng').textContent='محرك الأذن: '+(M.cfg.stt==='google'?'جوجل':M.cfg.stt==='local'?'محلي (بصوتي أنا)':'تلقائي (المدمج إن وُجد)')",
        "$('bEng').textContent='أذن إيف المحلية (تتعلم صوتك)'",
        "update engine label"
    )
    t = replace_once(
        t,
        "$('bEng').onclick=()=>{const C=['auto','google','local'];M.cfg.stt=C[(C.indexOf(M.cfg.stt)+1)%3];nCall('sttEngine',M.cfg.stt);save();syncBtns()};",
        "$('bEng').onclick=()=>{M.cfg.stt='local';nCall('sttEngine','local');save();syncBtns();say('أذني محلية وتتعلم صوتك. لا أستخدم جوجل أو الإنترنت للتعرف على كلامك.','proud',null,true)};",
        "remove google/automatic engine choices"
    )
    t = replace_once(
        t,
        "if(M.cfg.stt&&M.cfg.stt!=='auto')nCall('sttEngine',M.cfg.stt);",
        "M.cfg.stt='local';nCall('sttEngine','local');",
        "force local engine at startup"
    )
    old_diag = "if(M.cfg.mic&&!micNat&&DIAG.stt!=='VOSK_LOADING')P.push(['mic',DIAG.stt==='LANG_PACK_MISSING'?'أذني متوقفة لأن الحزمة العربية دون اتصال غير منزّلة، ويمكن استعمال المحرك المدمج إن وُجد في التطبيق':DIAG.stt==='VOSK_MODEL_ERROR'?'النموذج العربي المدمج لم يُحمَّل، فأستعمل محرك جوجل':'أذني لا تعمل الآن'+(DIAG.stt?' ('+DIAG.stt+')':'')]);"
    t = replace_once(
        t, old_diag,
        "if(M.cfg.mic&&!micNat)P.push(['mic','أذن إيف المحلية غير نشطة الآن'+(DIAG.stt?' ('+DIAG.stt+')':'')]);",
        "remove Vosk/google diagnostic fallbacks"
    )
    for token in ["const C=['auto','google','local']", "stt:'auto'", "VOSK_MODEL_ERROR", "LANG_PACK_MISSING"]:
        if token in t:
            raise SystemExit(f"Old speech engine option remains in EVE UI: {token}")
    return t
edit(html_rel, patch_html)

gradle_rel = "app/build.gradle.kts"
def patch_gradle(t: str) -> str:
    t, n = re.subn(
        r'    // «lite» .*?\n    flavorDimensions \+= "engine"\n    productFlavors \{\n        create\("lite"\) \{ dimension = "engine" \}\n        create\("full"\) \{ dimension = "engine"; versionNameSuffix = "-full" \}\n    \}\n',
        '', t, count=1, flags=re.S
    )
    if n != 1:
        raise SystemExit("Could not remove full/lite product flavors")
    t = replace_once(
        t,
        '    // أذن عربية مدمجة بلا إنترنت: في النكهة الكاملة فقط (النموذج يُضمَّن وقت البناء من .github/workflows/build.yml)\n'
        '    "fullImplementation"("com.alphacephei:vosk-android:0.3.47")\n'
        '    "fullImplementation"("net.java.dev.jna:jna:5.13.0@aar")\n',
        '',
        "remove Vosk/JNA dependencies"
    )
    t = replace_once(t, '        versionCode = 3\n        versionName = "3.0"', '        versionCode = 4\n        versionName = "4.0"', "set EVE 4.0 version")
    if "vosk" in t.lower() or "alphacephei" in t.lower():
        raise SystemExit("Vosk dependency remains in Gradle")
    return t
edit(gradle_rel, patch_gradle)

manifest_rel = "app/src/main/AndroidManifest.xml"
edit(manifest_rel, lambda t: replace_once(
    t,
    '        <intent><action android:name="android.speech.RecognitionService"/></intent>\n',
    '',
    "remove external recognition-service query"
))

for rel in [
    "app/src/main/java/com/eve/companion/Speech.kt",
    "app/src/full/java/com/eve/companion/VoskSpeech.kt",
    "app/src/lite/java/com/eve/companion/VoskSpeech.kt",
]:
    p = root / rel
    if not p.exists():
        raise SystemExit(f"Expected obsolete speech adapter not found: {rel}")
    p.unlink()

for rel in ["app/src/full", "app/src/lite"]:
    p = root / rel
    if p.exists():
        shutil.rmtree(p)

docs = root / "docs"
docs.mkdir(exist_ok=True)
(docs / "EVE-SPEECH.md").write_text(
    """# EVE Speech — local Arabic voice model

EVE 4 uses its own on-device Arabic command recognizer. It does not use Vosk,
Android SpeechRecognizer, Google speech services, a network connection, or an
Internet permission.

## How it works
- The microphone captures 16 kHz mono PCM locally.
- EVE computes MFCC acoustic features and compares them with locally learned
  voice templates using Dynamic Time Warping (DTW).
- The user teaches the phrases by speaking each prompt three times.
- Templates and calibration data are stored in EVE's private local memory.
- A confidence threshold rejects uncertain matches; confident matches can
  gradually improve the local templates.

## Training
Open EVE settings and select **تعليم الأذن**, then repeat each phrase three
times when prompted. The optional **تعليم الأذن (ليبي)** pack teaches Libyan
variants. Use **اختبار الأذن** to check recognition accuracy.

The first pack recognizes a defined set of commands, not unrestricted Arabic
dictation. Free-form conversation remains available through the app's text
input until a larger on-device Arabic transcription model is separately
integrated and measured.
""",
    encoding="utf-8"
)

manifest = (root / manifest_rel).read_text(encoding="utf-8")
if "android.permission.INTERNET" in manifest:
    raise SystemExit("Unexpected INTERNET permission in offline EVE manifest")
main = (root / main_rel).read_text(encoding="utf-8")
all_java = "\n".join(p.read_text(encoding="utf-8") for p in (root / "app/src/main/java").rglob("*.kt"))
if any(x in all_java for x in ["SpeechRecognizer", "VoskSpeech", "org.vosk", "com.alphacephei"]):
    raise SystemExit("Old or external recognizer reference remains in Kotlin source")
if (root / "app/src/main/java/com/eve/companion/Speech.kt").exists():
    raise SystemExit("Android SpeechRecognizer source file still exists")
html = (root / html_rel).read_text(encoding="utf-8")
for token in ["stt:'auto'", "const C=['auto','google','local']"]:
    if token in html:
        raise SystemExit(f"Automatic/Google speech engine option remains in UI: {token}")
print("EVE Speech patch applied: EVE local MFCC+DTW is the only recognizer.")
