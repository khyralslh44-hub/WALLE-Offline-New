#!/usr/bin/env python3
"""CPU-first smoke trainer for EVE's randomly initialized GRU language core.

Use a real, licensed Arabic corpus for meaningful training. This script deliberately
labels tiny-corpus runs as smoke tests, not as a useful language model.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch
from torch import nn
from torch.utils.data import Dataset, DataLoader

from incremental_brain import EVEGRUCore


class CharBlocks(Dataset):
    def __init__(self, files: list[Path], seq_len: int):
        text = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in files)
        self.chars = sorted(set(text))
        self.to_id = {ch: i for i, ch in enumerate(self.chars)}
        self.ids = torch.tensor([self.to_id[ch] for ch in text], dtype=torch.long)
        self.seq_len = seq_len
        if len(self.ids) <= seq_len:
            raise ValueError("Corpus is too small for the selected sequence length.")

    def __len__(self):
        return (len(self.ids) - 1) // self.seq_len

    def __getitem__(self, index):
        start = index * self.seq_len
        chunk = self.ids[start:start + self.seq_len + 1]
        return chunk[:-1], chunk[1:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=100)
    parser.add_argument("--seq-len", type=int, default=64)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--hidden-size", type=int, default=128)
    parser.add_argument("--threads", type=int, default=0, help="0 keeps PyTorch default")
    parser.add_argument("--smoke-test", action="store_true")
    args = parser.parse_args()

    files = sorted(args.corpus_dir.rglob("*.txt"))
    if not files:
        raise SystemExit("No .txt corpus files found.")
    corpus_bytes = sum(p.stat().st_size for p in files)
    if not args.smoke_test and corpus_bytes < 10_000_000:
        raise SystemExit("For a real run, provide at least 10 MB of licensed text; use --smoke-test only for pipeline checks.")
    if args.threads > 0:
        torch.set_num_threads(args.threads)
        torch.set_num_interop_threads(max(1, min(4, args.threads)))

    dataset = CharBlocks(files, args.seq_len)
    loader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True)
    model = EVEGRUCore(len(dataset.chars), embedding_dim=args.hidden_size,
                       hidden_size=args.hidden_size, num_layers=2)
    device = torch.device("cpu")
    model.to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3)
    criterion = nn.CrossEntropyLoss()
    start = time.perf_counter()
    model.train()
    losses = []
    for step in range(1, args.steps + 1):
        total = 0.0
        batches = 0
        for x, y in loader:
            optimizer.zero_grad(set_to_none=True)
            logits, _ = model(x)
            loss = criterion(logits.reshape(-1, logits.size(-1)), y.reshape(-1))
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            total += float(loss.detach())
            batches += 1
            if batches >= 20:
                break
        mean_loss = total / max(1, batches)
        losses.append(mean_loss)
        if step == 1 or step % 10 == 0:
            print(f"step={step}/{args.steps} loss={mean_loss:.4f} elapsed_s={time.perf_counter()-start:.1f}", flush=True)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    torch.save({"model_state_dict": model.state_dict(), "vocabulary": dataset.chars,
                "architecture": {"type": "GRU", "hidden_size": args.hidden_size},
                "initialized_from_random_weights": True}, args.output_dir / "eve-gru-smoke.pt")
    report = {
        "status": "SMOKE_TEST_ONLY" if args.smoke_test else "EXPERIMENTAL_TRAINING",
        "device": "cpu", "threads": torch.get_num_threads(),
        "corpus_bytes": corpus_bytes, "vocab_size": len(dataset.chars),
        "steps": args.steps, "initial_loss": losses[0], "final_loss": losses[-1],
        "elapsed_seconds": round(time.perf_counter() - start, 3),
        "pretrained_weights_used": False,
        "warning": "Character-level prototype; not yet a capable Arabic language model."
    }
    (args.output_dir / "cpu-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
