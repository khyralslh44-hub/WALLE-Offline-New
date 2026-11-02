from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.os.Build\n')
old = '''    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { fail("NO_SERVICE"); return }
        on = true; disabled = false; errCount = 0; vad()
    }'''
new = '''    fun start() {
        // Fail closed: never fall back to a cloud-capable/general recognition service.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            fail("ON_DEVICE_STT_REQUIRES_ANDROID_12"); return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            fail("ON_DEVICE_STT_UNAVAILABLE"); return
        }
        on = true; disabled = false; errCount = 0; vad()
    }'''
if old not in s:
    raise SystemExit('Speech.start() anchor not found; refusing unsafe partial patch')
s = s.replace(old, new, 1)
s = s.replace('rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->', 'rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { r ->', 1)
old2 = '''                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) onText(t)
                    errCount = 0; back(350)'''
new2 = '''                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val confidence = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
                    // Reject weak hypotheses instead of feeding guesses into EVE's decision loop.
                    if (!t.isNullOrBlank() && (confidence < 0f || confidence >= 0.55f)) onText(t)
                    errCount = 0; back(350)'''
if old2 not in s:
    raise SystemExit('Speech result anchor not found; refusing unsafe partial patch')
s = s.replace(old2, new2, 1)
if 'createSpeechRecognizer(ctx)' in s:
    raise SystemExit('Generic recognizer remains after patch')
p.write_text(s, encoding='utf-8')
print('Applied strict on-device speech recognition patch')
