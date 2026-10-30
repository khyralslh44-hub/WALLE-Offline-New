#!/usr/bin/env python3
"""Download pinned Arabic starter datasets and normalize them to EVE's local formats."""
from __future__ import annotations
import argparse, json
from datetime import datetime, timezone
from pathlib import Path
from datasets import load_dataset
from huggingface_hub import HfApi

PRETRAIN_ID = "Jr23xd23/ArabicText-Large"
INSTRUCTION_ID = "arbml/CIDAR"

def pinned_revision(repo_id: str) -> str:
    info = HfApi().dataset_info(repo_id=repo_id)
    if not info.sha:
        raise RuntimeError(f"Could not resolve a commit SHA for dataset {repo_id}")
    return info.sha

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--max-pretrain-docs", type=int, default=0, help="0 downloads all available records")
    parser.add_argument("--skip-pretrain", action="store_true")
    parser.add_argument("--skip-cidar", action="store_true")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
        "datasets": [],
        "warning": "Review dataset cards, upstream source terms, and samples before redistribution or model release."
    }

    if not args.skip_pretrain:
        revision = pinned_revision(PRETRAIN_ID)
        out = args.output_dir / "pretrain"
        out.mkdir(parents=True, exist_ok=True)
        stream = load_dataset(PRETRAIN_ID, split="train", streaming=True, revision=revision)
        shard_no, docs_in_shard, total = 0, 0, 0
        handle = None
        shard_paths = []
        seen_hashes = set()
        url_path = out / "source-urls.jsonl"
        try:
            with url_path.open("w", encoding="utf-8") as urls:
                for row in stream:
                    row_hash = row.get("hash")
                    if row_hash and row_hash in seen_hashes:
                        continue
                    if row_hash:
                        seen_hashes.add(row_hash)
                    text = (row.get("text") or "").strip()
                    if not text:
                        continue
                    title = (row.get("title") or "").strip()
                    clean = " ".join((title + "\n" + text).split())
                    if handle is None or docs_in_shard >= 5000:
                        if handle:
                            handle.close()
                        shard_no += 1
                        docs_in_shard = 0
                        path = out / f"arabictext_large_{shard_no:04d}.txt"
                        shard_paths.append(str(path))
                        handle = path.open("w", encoding="utf-8")
                    handle.write(clean + "\n")
                    urls.write(json.dumps({
                        "dataset": PRETRAIN_ID,
                        "dataset_revision": revision,
                        "title": title,
                        "url": row.get("url", ""),
                        "hash": row_hash,
                        "source_metadata": row.get("metadata", {}),
                    }, ensure_ascii=False) + "\n")
                    docs_in_shard += 1
                    total += 1
                    if total % 10000 == 0:
                        print(f"ArabicText-Large exported: {total:,} documents", flush=True)
                    if args.max_pretrain_docs and total >= args.max_pretrain_docs:
                        break
        finally:
            if handle:
                handle.close()
        if total == 0:
            raise SystemExit("ArabicText-Large produced no non-empty text records.")
        manifest["datasets"].append({
            "id": PRETRAIN_ID, "revision": revision, "split": "train",
            "records_exported": total, "text_shards": shard_paths,
            "attribution_manifest": str(url_path), "license_on_hf_card": "apache-2.0",
        })

    if not args.skip_cidar:
        revision = pinned_revision(INSTRUCTION_ID)
        out = args.output_dir / "persona_general"
        out.mkdir(parents=True, exist_ok=True)
        data = load_dataset(INSTRUCTION_ID, split="train", streaming=True, revision=revision)
        path = out / "cidar_instructions.jsonl"
        count = 0
        with path.open("w", encoding="utf-8") as handle:
            for row in data:
                instruction = (row.get("instruction") or "").strip()
                answer = (row.get("output") or "").strip()
                if not instruction or not answer:
                    continue
                item = {
                    "id": f"cidar-{row.get('index', count)}",
                    "category": "arabic_general_instruction",
                    "messages": [
                        {"role": "user", "content": instruction},
                        {"role": "assistant", "content": answer},
                    ],
                }
                handle.write(json.dumps(item, ensure_ascii=False) + "\n")
                count += 1
        if count == 0:
            raise SystemExit("CIDAR produced no usable instruction/output rows.")
        manifest["datasets"].append({
            "id": INSTRUCTION_ID, "revision": revision, "split": "train",
            "records_exported": count, "normalized_file": str(path),
            "license_on_hf_card": "apache-2.0",
            "note": "General instruction tuning only; EVE persona tuning is a separate later stage."
        })

    (args.output_dir / "download-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
