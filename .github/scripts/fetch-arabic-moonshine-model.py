#!/usr/bin/env python3
"""Download and validate Moonshine Arabic Tiny Streaming into a clean APK asset folder."""
from pathlib import Path
import shutil
import tempfile

from moonshine_voice import ModelArch
from moonshine_voice.download import find_model_info, download_model_from_info

root = Path("eve-project/app/src/main/assets/moonshine/ar").resolve()
staging = root.with_name(root.name + ".staging")
if staging.exists():
    shutil.rmtree(staging)
staging.mkdir(parents=True)

# Keep Moonshine's own CDN cache OUTSIDE the Android assets tree. The Python
# downloader returns <cache_root>/download.moonshine.ai/model/...; putting its
# cache under assets/moonshine would package a second copy of every model file.
with tempfile.TemporaryDirectory(prefix="eve-moonshine-ar-cache-") as cache:
    info = find_model_info("ar", ModelArch.TINY_STREAMING)
    downloaded_root, arch = download_model_from_info(info, cache_root=Path(cache))
    source = Path(downloaded_root)
    if not source.is_dir():
        raise SystemExit(f"Moonshine returned a missing model directory: {source}")

    # Copy only the model payload into the stable Android asset directory.
    shutil.copytree(source, staging, dirs_exist_ok=True)

files = [p for p in staging.rglob("*") if p.is_file()]
ort_files = [p for p in files if p.suffix == ".ort"]
tokenizers = [p for p in files if p.name == "tokenizer.bin"]
if not ort_files or not tokenizers:
    raise SystemExit(
        "Moonshine Arabic Tiny Streaming payload is incomplete: "
        f"ort={len(ort_files)} tokenizer={len(tokenizers)}"
    )
total = sum(p.stat().st_size for p in files)
if total <= 0 or total > 40_000_000:
    raise SystemExit(f"Unexpected or duplicated Arabic tiny streaming model size: {total} bytes")

if root.exists():
    shutil.rmtree(root)
staging.rename(root)

# Guard against accidentally leaving the downloader's CDN cache under assets.
unexpected_cache = root.parent / "download.moonshine.ai"
if unexpected_cache.exists():
    shutil.rmtree(unexpected_cache)
print(f"PASS Moonshine Arabic Tiny Streaming: arch={arch} files={len(files)} bytes={total}")
for path in sorted(root.rglob("*")):
    if path.is_file():
        print(f"{path.relative_to(root)} {path.stat().st_size}")
