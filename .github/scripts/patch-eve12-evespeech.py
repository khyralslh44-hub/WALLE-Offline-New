#!/usr/bin/env python3
"""Make EVE 12 use its own fully local, user-trained Arabic MFCC+DTW ear."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()

def edit(rel: str, pairs: list[tuple[str, str]]) -> None:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    for old, new in pairs:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{rel}: expected exactly one match, got {count}: {old[:90]!r}")
        text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")

edit("app/src/main/java/com/eve/companion/MainActivity.kt", [
    ('    private var sttPref = "auto"\n', ''),
    ('            tts?.apply { stop(); setPitch(1.0f); setSpeechRate((rate / voice.factor(style)).coerceIn(0.4f, 2.0f)) }',
     '            tts?.apply { stop(); setPitch(1.0f); setSpeechRate((rate / this@MainActivity.voice.factor(style)).coerceIn(0.4f, 2.0f)) }'),
    ('        /** محرك الاستماع: auto (المدمج إن وُجد) أو google. */\n'
     '        @JavascriptInterface fun sttEngine(name: String) { runOnUiThread { sttPref = name; speech?.stop(); speech = null; refreshSenses() } }\n'
     '        @JavascriptInterface fun sttEmbedded(): Boolean = hasEmbedded()\n',
     '        /** محرك EVE المحلي فقط؛ لا يستدعي خدمات التعرف في Android أو Google. */\n'
     '        @JavascriptInterface fun sttEngine(name: String) { runOnUiThread { speech?.stop(); speech = null; refreshSenses() } }\n'),
    ('                val onT: (String) -> Unit = { t -> if (!speaking) js("Eve.speech(\${JSONObject.quote(t)})") }\n'
     '                val onL: (Float) -> Unit = { l -> js("Eve.rms($l)") }\n'
     '                val onS: (Boolean) -> Unit = { ok -> js("Eve.stt($ok)") }\n'
     '                val onD: (String) -> Unit = { d -> js("Eve.diag($d)") }\n'
     '                val onU: (String) -> Unit = { u -> js("Eve.utt(\'$u\')") }\n'
     '                speech = if (sttPref == "local") PcmEar(onU, onL, onS, { speaking }, onD)\n'
     '                else if (hasEmbedded() && sttPref != "google")\n'
     '                    VoskSpeech(this, onT, onL, onS, { speaking }, onD) { runOnUiThread { sttPref = "google"; speech = null; refreshSenses() } }\n'
     '                else Speech(this, onT, onL, onS, { speaking }, onD)\n'
     '                speech?.start()',
     '                val onL: (Float) -> Unit = { l -> js("Eve.rms($l)") }\n'
     '                val onS: (Boolean) -> Unit = { ok -> js("Eve.stt($ok)") }\n'
     '                val onD: (String) -> Unit = { d -> js("Eve.diag($d)") }\n'
     '                val onU: (String) -> Unit = { u -> js("Eve.utt(\'$u\')") }\n'
     '                speech = PcmEar(onU, onL, onS, { speaking }, onD)\n'
     '                speech?.start()'),
    ('    private fun hasEmbedded(): Boolean = try { (assets.list("vosk/model")?.size ?: 0) > 0 } catch (e: Exception) { false }\n\n', ''),
])

edit("app/src/main/assets/eve.html", [
    ("M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true,stt:'auto'},M.cfg||{});",
     "M.cfg=Object.assign({idFloor:.65,thFloor:.78,cam:true,mic:true,ev:true,msgs:true,stt:'local'},M.cfg||{});M.cfg.stt='local';"),
    ("$('bEng').textContent='محرك الأذن: '+(M.cfg.stt==='google'?'جوجل':M.cfg.stt==='local'?'محلي (بصوتي أنا)':'تلقائي (المدمج إن وُجد)')",
     "$('bEng').textContent='أذن إيف المحلية (تتعلم صوتك)'"),
    ("$('bEng').onclick=()=>{const C=['auto','google','local'];M.cfg.stt=C[(C.indexOf(M.cfg.stt)+1)%3];nCall('sttEngine',M.cfg.stt);save();syncBtns()};",
     "$('bEng').onclick=()=>{M.cfg.stt='local';nCall('sttEngine','local');save();syncBtns();say('أذني محلية وتتعلم صوتك. لا أستخدم جوجل أو الإنترنت للتعرف على كلامك.','proud',null,true)};"),
    ("if(M.cfg.stt&&M.cfg.stt!=='auto')nCall('sttEngine',M.cfg.stt);",
     "M.cfg.stt='local';nCall('sttEngine','local');"),
    ("if(M.cfg.mic&&!micNat&&DIAG.stt!=='VOSK_LOADING')P.push(['mic',DIAG.stt==='LANG_PACK_MISSING'?'أذني متوقفة لأن الحزمة العربية دون اتصال غير منزّلة، ويمكن استعمال المحرك المدمج إن وُجد في التطبيق':DIAG.stt==='VOSK_MODEL_ERROR'?'النموذج العربي المدمج لم يُحمَّل، فأستعمل محرك جوجل':'أذني لا تعمل الآن'+(DIAG.stt?' ('+DIAG.stt+')':'')]);",
     "if(M.cfg.mic&&!micNat)P.push(['mic','أذن إيف المحلية غير نشطة الآن'+(DIAG.stt?' ('+DIAG.stt+')':'')]);"),
])

edit("app/build.gradle.kts", [
    ('    // «lite» (الافتراضية): بلا مكتبة Vosk ولا نموذجها | «full»: مع الأذن العربية المدمجة (وزن كبير)\n'
     '    flavorDimensions += "engine"\n'
     '    productFlavors {\n'
     '        create("lite") { dimension = "engine" }\n'
     '        create("full") { dimension = "engine"; versionNameSuffix = "-full" }\n'
     '    }\n', ''),
    ('    // أذن عربية مدمجة بلا إنترنت: في النكهة الكاملة فقط (النموذج يُضمَّن وقت البناء من .github/workflows/build.yml)\n'
     '    "fullImplementation"("com.alphacephei:vosk-android:0.3.47")\n'
     '    "fullImplementation"("net.java.dev.jna:jna:5.13.0@aar")\n', ''),
    ('        versionCode = 3\n        versionName = "3.0"', '        versionCode = 4\n        versionName = "4.0"'),
])

edit("app/src/main/AndroidManifest.xml", [
    ('        <intent><action android:name="android.speech.RecognitionService"/></intent>\n', ''),
])

# Delete all old Android/Google SpeechRecognizer and Vosk adapters.
for rel in [
    "app/src/main/java/com/eve/companion/Speech.kt",
    "app/src/full/java/com/eve/companion/VoskSpeech.kt",
    "app/src/lite/java/com/eve/companion/VoskSpeech.kt",
]:
    p = root / rel
    if not p.exists():
        raise SystemExit(f"Expected obsolete speech adapter not found: {rel}")
    p.unlink()

# Remove now-empty source-set directories.
for rel in ["app/src/full", "app/src/lite"]:
    p = root / rel
    if p.exists():
        shutil.rmtree(p)

# Add a concise, user-facing description of the local model.
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
""", encoding="utf-8")

manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if 'android.permission.INTERNET' in manifest:
    raise SystemExit("Unexpected INTERNET permission in offline EVE manifest")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
if "vosk" in gradle.lower() or "alphacephei" in gradle.lower():
    raise SystemExit("Vosk dependency remains in Gradle configuration")
main = (root / "app/src/main/java/com/eve/companion/MainActivity.kt").read_text(encoding="utf-8")
if "SpeechRecognizer" in main or "VoskSpeech" in main or "Speech(this" in main:
    raise SystemExit("External speech recognizer reference remains in MainActivity")
html = (root / "app/src/main/assets/eve.html").read_text(encoding="utf-8")
if "stt:'auto'" in html or "const C=['auto','google','local']" in html:
    raise SystemExit("Automatic/Google speech engine option remains in UI")
print("EVE Speech patch applied: local MFCC+DTW is the only recognizer.")
