#!/usr/bin/env python3
# Revision 3: trigger robust AAPT package and icon resource verification.
from pathlib import Path
import re, sys

PACKAGE = "com.eve.companion.standalone"
LABEL = "إيف 3.1"

def replace_once(text, pattern, repl, flags=0, label=""):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label or pattern}: expected one match, found {n}")
    return out

def main(root: Path):
    gradle = root / "app/build.gradle.kts"
    manifest = root / "app/src/main/AndroidManifest.xml"
    if not gradle.is_file() or not manifest.is_file():
        raise SystemExit("Android Gradle file or manifest is missing")

    g = gradle.read_text(encoding="utf-8")
    g = replace_once(g, r'applicationId\s*=\s*"[^"]+"',
                     f'applicationId = "{PACKAGE}"', label="applicationId")
    g = replace_once(g, r'versionCode\s*=\s*\d+',
                     'versionCode = 4', label="versionCode")
    g = replace_once(g, r'versionName\s*=\s*"[^"]+"',
                     'versionName = "3.1"', label="versionName")
    gradle.write_text(g, encoding="utf-8")

    m = manifest.read_text(encoding="utf-8")
    old = '<application android:label="إيف" android:allowBackup="false"'
    new = ('<application android:label="إيف 3.1" '
           'android:icon="@mipmap/ic_launcher" '
           'android:roundIcon="@mipmap/ic_launcher" '
           'android:allowBackup="false"')
    if m.count(old) != 1:
        raise SystemExit(f"Manifest application tag anchor count: {m.count(old)}")
    m = m.replace(old, new, 1)
    manifest.write_text(m, encoding="utf-8")

    res = root / "app/src/main/res"
    (res / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "values").mkdir(parents=True, exist_ok=True)

    (res / "mipmap-anydpi-v26/ic_launcher.xml").write_text(
        '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/eve_icon_background"/>
    <foreground android:drawable="@drawable/ic_eve_foreground"/>
</adaptive-icon>
''', encoding="utf-8")
    (res / "values/eve_icon_colors.xml").write_text(
        '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="eve_icon_background">#071B2D</color>
</resources>
''', encoding="utf-8")
    (res / "drawable/ic_eve_foreground.xml").write_text(
        '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#F4FAFF"
        android:pathData="M54,12 C34,12 20,25 18,45 L16,69 C15,84 26,94 41,94 L67,94 C82,94 93,84 92,69 L90,45 C88,25 74,12 54,12 Z"/>
    <path
        android:fillColor="#061522"
        android:pathData="M23,44 C24,34 34,29 54,29 C74,29 84,34 85,44 L83,68 C82,77 73,82 54,82 C35,82 26,77 25,68 Z"/>
    <path
        android:fillColor="#42E7F5"
        android:pathData="M32,55 C35,45 43,42 50,51 C44,49 39,50 32,55 Z"/>
    <path
        android:fillColor="#42E7F5"
        android:pathData="M58,51 C65,42 73,45 76,55 C69,50 64,49 58,51 Z"/>
    <path
        android:fillColor="#A7FBFF"
        android:pathData="M35,53 C39,47 44,47 48,51 C43,50 39,51 35,53 Z"/>
    <path
        android:fillColor="#A7FBFF"
        android:pathData="M60,51 C64,47 69,47 73,53 C69,51 65,50 60,51 Z"/>
    <path
        android:fillColor="#73DCEB"
        android:pathData="M28,87 C36,91 45,93 54,93 C63,93 72,91 80,87 L75,97 L33,97 Z"/>
</vector>
''', encoding="utf-8")

    # Fail closed: make sure the new package cannot replace the existing app.
    if f'applicationId = "{PACKAGE}"' not in g:
        raise SystemExit("Standalone applicationId not found after patch")
    if 'android:icon="@mipmap/ic_launcher"' not in m:
        raise SystemExit("Launcher icon was not attached to the manifest")
    print(f"Prepared side-by-side EVE app: {PACKAGE}, version 4 (3.1), adaptive launcher icon")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: prepare-eve-standalone.py <project-root>")
    main(Path(sys.argv[1]))
