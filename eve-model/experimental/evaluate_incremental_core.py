#!/usr/bin/env python3
"""Evaluate a trained EVE character-GRU checkpoint on a separate held-out corpus.

This reports measurements only; it does not certify conversational quality.
The validation text must not have been included in the training corpus.
"""
from __future__ import annotations

import argparse
import json
import math
import resource
import time
from pathlib import Path

import torch
from torch.nn import CrossEntropyLoss

from incremental_brain import EVEGRUCore


def load_text(directory: Path) -> str:
    files = sorted(directory.rglob("*.txt"))
    if not files:
        raise SystemExit(f"No .txt files found in {directory}")
    return "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in files)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--validation-dir", type=Path, required=True)
    parser.add_argument("--seq-len", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--threads", type=int, default=0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    if args.seq_len < 2 or args.batch_size < 1:
        raise SystemExit("seq-len must be >= 2 and batch-size must be >= 1")
    if args.threads > 0:
        torch.set_num_threads(args.threads)
        torch.set_num_interop_threads(max(1, min(4, args.threads)))

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    vocab = checkpoint["vocabulary"]
    to_id = {ch: i for i, ch in enumerate(vocab)}
    text = load_text(args.validation_dir)
    unknown = sum(ch not in to_id for ch in text)
    known_ids = [to_id[ch] for ch in text if ch in to_id]
    if len(known_ids) <= args.seq_len:
        raise SystemExit("Validation corpus is too small after excluding out-of-vocabulary characters.")

    hidden_size = int(checkpoint["architecture"]["hidden_size"])
    model = EVEGRUCore(len(vocab), embedding_dim=hidden_size, hidden_size=hidden_size, num_layers=2)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    ids = torch.tensor(known_ids, dtype=torch.long)
    criterion = CrossEntropyLoss(reduction="sum")
    total_loss = 0.0
    total_targets = 0
    start = time.perf_counter()
    with torch.inference_mode():
        for offset in range(0, len(ids) - 1, args.seq_len * args.batch_size):
            xs, ys = [], []
            for b in range(args.batch_size):
                pos = offset + b * args.seq_len
                if pos + args.seq_len >= len(ids):
                    break
                chunk = ids[pos:pos + args.seq_len + 1]
                if len(chunk) == args.seq_len + 1:
                    xs.append(chunk[:-1])
                    ys.append(chunk[1:])
            if not xs:
                continue
            x, y = torch.stack(xs), torch.stack(ys)
            logits, _ = model(x)
            total_loss += float(criterion(logits.reshape(-1, len(vocab)), y.reshape(-1)))
            total_targets += y.numel()

    elapsed = time.perf_counter() - start
    mean_loss = total_loss / total_targets
    report = {
        "status": "HELD_OUT_EVALUATION",
        "device": "cpu",
        "validation_characters": len(text),
        "evaluated_targets": total_targets,
        "unknown_character_rate": round(unknown / max(1, len(text)), 6),
        "character_cross_entropy": round(mean_loss, 6),
        "character_perplexity": round(math.exp(min(mean_loss, 50)), 6),
        "characters_per_second": round(total_targets / max(elapsed, 1e-9), 2),
        "elapsed_seconds": round(elapsed, 3),
        "peak_rss_platform_units": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "pretrained_weights_used": False,
        "quality_warning": "Character metrics alone do not establish Arabic comprehension or useful conversation."
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
