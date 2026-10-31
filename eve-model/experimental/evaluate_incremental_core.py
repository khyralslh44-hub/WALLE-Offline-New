#!/usr/bin/env python3
"""Evaluate a randomly initialized/trained EVE character-GRU on held-out text.

Unknown characters split the stream into independent segments. They are never
silently deleted, because deleting them would create artificial character
transitions and make validation loss misleading.
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


def evaluate_text(model: EVEGRUCore, vocab: list[str], text: str,
                  seq_len: int, batch_size: int) -> dict:
    """Evaluate only contiguous in-vocabulary spans; never bridge unknown chars."""
    to_id = {ch: i for i, ch in enumerate(vocab)}
    unknown = sum(ch not in to_id for ch in text)
    segments: list[list[int]] = []
    current: list[int] = []
    for char in text:
        token = to_id.get(char)
        if token is None:
            if current:
                segments.append(current)
                current = []
        else:
            current.append(token)
    if current:
        segments.append(current)

    windows: list[list[int]] = []
    for segment in segments:
        for start in range(0, len(segment) - seq_len, seq_len):
            windows.append(segment[start:start + seq_len + 1])
    total_targets = sum(len(window) - 1 for window in windows)
    if total_targets == 0:
        raise ValueError("Validation text has no complete in-vocabulary sequence windows.")

    criterion = CrossEntropyLoss(reduction="sum")
    total_loss = 0.0
    with torch.inference_mode():
        for start in range(0, len(windows), batch_size):
            batch = windows[start:start + batch_size]
            x = torch.tensor([w[:-1] for w in batch], dtype=torch.long)
            y = torch.tensor([w[1:] for w in batch], dtype=torch.long)
            logits, _ = model(x)
            total_loss += float(criterion(logits.reshape(-1, len(vocab)), y.reshape(-1)))

    mean_loss = total_loss / total_targets
    return {
        "validation_characters": len(text),
        "evaluated_targets": total_targets,
        "unknown_characters": unknown,
        "unknown_character_rate": round(unknown / max(1, len(text)), 6),
        "character_cross_entropy": round(mean_loss, 6),
        "character_perplexity": round(math.exp(min(mean_loss, 50)), 6),
    }


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
    hidden_size = int(checkpoint["architecture"]["hidden_size"])
    model = EVEGRUCore(len(vocab), embedding_dim=hidden_size, hidden_size=hidden_size, num_layers=2)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    text = load_text(args.validation_dir)
    start = time.perf_counter()
    metrics = evaluate_text(model, vocab, text, args.seq_len, args.batch_size)
    elapsed = time.perf_counter() - start
    metrics.update({
        "status": "HELD_OUT_EVALUATION",
        "device": "cpu",
        "characters_per_second": round(metrics["evaluated_targets"] / max(elapsed, 1e-9), 2),
        "elapsed_seconds": round(elapsed, 3),
        "peak_rss_platform_units": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "pretrained_weights_used": False,
        "quality_warning": "Character metrics alone do not establish Arabic comprehension or useful conversation."
    })
    rendered = json.dumps(metrics, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
