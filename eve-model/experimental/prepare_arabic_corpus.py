#!/usr/bin/env python3
"""Prepare a documented, deterministic Arabic text corpus split for EVE.

This tool does not download data and does not decide whether a license is legally
compatible. The operator must provide source and license metadata and verify it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import unicodedata
from pathlib import Path


def normalize_line(line: str) -> str:
    line = unicodedata.normalize("NFC", line.replace("\ufeff", ""))
    return " ".join(line.split()).strip()


def stable_bucket(line: str) -> int:
    # Stable across machines and Python versions; 0..99.
    digest = hashlib.sha256(line.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % 100


def prepare(input_dir: Path, output_dir: Path, source: str, license_name: str) -> dict:
    files = sorted(input_dir.rglob("*.txt"))
    if not files:
        raise ValueError(f"No .txt files found in {input_dir}")

    seen: set[str] = set()
    splits: dict[str, list[str]] = {"train": [], "validation": [], "test": []}
    file_records = []
    for path in files:
        raw = path.read_bytes()
        file_records.append({
            "path": str(path),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw),
        })
        text = raw.decode("utf-8-sig", errors="strict")
        for raw_line in text.splitlines():
            line = normalize_line(raw_line)
            if len(line) < 20 or line in seen:
                continue
            seen.add(line)
            bucket = stable_bucket(line)
            split = "train" if bucket < 90 else ("validation" if bucket < 95 else "test")
            splits[split].append(line)

    if len(seen) < 100:
        raise ValueError(
            f"Only {len(seen)} unique usable lines. Need at least 100; "
            "do not use synthetic smoke-test text as a real corpus."
        )
    if not splits["train"] or not splits["validation"] or not splits["test"]:
        raise ValueError("Split produced an empty partition; provide a larger, more diverse corpus.")

    for split, lines in splits.items():
        target = output_dir / split
        target.mkdir(parents=True, exist_ok=True)
        (target / "corpus.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    manifest = {
        "format_version": 1,
        "source": source,
        "declared_license": license_name,
        "license_verified_by_tool": False,
        "normalization": "Unicode NFC; BOM removed; whitespace collapsed; lines shorter than 20 characters skipped",
        "deduplication": "exact normalized-line deduplication across all input files",
        "split": "deterministic SHA-256 line hash: 90% train, 5% validation, 5% test",
        "counts": {name: len(lines) for name, lines in splits.items()},
        "unique_usable_lines": len(seen),
        "input_files": file_records,
        "warning": "Verify source terms and license compatibility yourself; this manifest records declarations, not legal approval."
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "corpus-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--source", required=True, help="Human-readable source name and URL/reference")
    parser.add_argument("--license", dest="license_name", required=True, help="Exact license or terms identifier")
    args = parser.parse_args()
    if not args.source.strip() or not args.license_name.strip():
        raise SystemExit("Both --source and --license must be non-empty.")
    report = prepare(args.input_dir, args.output_dir, args.source.strip(), args.license_name.strip())
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
