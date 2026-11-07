#!/usr/bin/env python3
"""Fail-closed static audit for the EVE 5.0 Android package."""
from pathlib import Path
import re, subprocess, sys, zipfile

def require(ok, message):
    if not ok:
        raise SystemExit("FAIL: " + message)
    print("PASS: " + message)

def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: audit-eve5-apk.py APK AAPT MANIFEST HTML SPEECH_KT")
    apk, aapt, manifest, html, speech = map(Path, sys.argv[1:])
    for p in (apk, aapt, manifest, html, speech):
        require(p.is_file(), f"required file exists: {p.name}")
    m = manifest.read_text(encoding="utf-8")
    require(not re.search(r'<uses-permission[^>]+android\.permission\.INTERNET', m),
            "Android manifest does not request INTERNET permission")
    sp = speech.read_text(encoding="utf-8")
    require("createOnDeviceSpeechRecognizer" in sp and
            "isOnDeviceRecognitionAvailable" in sp,
            "speech path explicitly uses Android on-device recognition")
    require("createSpeechRecognizer(ctx)" not in sp,
            "no generic/cloud-capable speech recognizer fallback")
    h = html.read_text(encoding="utf-8")
    for marker, label in (
        ("EVE_HIERARCHICAL_COGNITIVE_CORE_V1", "central evidence-memory-planning core wired"),
        ("EVE_VERIFIED_OBJECT_LEARNING_V1", "provisional object learning and later verification wired"),
        ("stableObjectLabels", "temporal vision confirmation wired"),
    ):
        require(marker in h, label)
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    require("name='com.eve.companion.standalone'" in badging, "standalone package identity")
    require("versionCode='6'" in badging and "versionName='5.0'" in badging,
            "APK is version 5.0 / versionCode 6")
    with zipfile.ZipFile(apk) as z:
        names=set(z.namelist())
        for name in ("assets/models/objects.tflite", "assets/models/face.task",
                     "assets/models/gesture.task", "assets/models/embed.tflite",
                     "assets/models/classify.tflite"):
            require(name in names and z.getinfo(name).file_size>0, f"bundled offline model: {name}")
    print("EVE 5.0 static audit completed. Device-level offline behavior still requires a real Redmi 12 test.")

if __name__ == "__main__":
    main()
