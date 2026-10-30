#!/usr/bin/env python3
"""Train EVE-AR-95M from random initialization, then optionally SFT its persona."""
from __future__ import annotations
import argparse, json, random, time
from pathlib import Path
from typing import Iterator
import torch
from torch.utils.data import DataLoader, IterableDataset
from transformers import LlamaConfig, LlamaForCausalLM, AutoTokenizer

SPECIAL = {
    "system": "<|system|>",
    "user": "<|user|>",
    "assistant": "<|assistant|>",
    "end": "<|end|>",
}

def load_spec(path: Path) -> dict:
    spec = json.loads(path.read_text(encoding="utf-8"))
    if spec.get("model_name") != "EVE-AR-95M":
        raise ValueError("Unexpected model_name in architecture spec")
    if spec.get("training", {}).get("pretrained_weights") is not False:
        raise ValueError("From-scratch training requires pretrained_weights=false")
    return spec

def build_config(spec: dict, vocab_size: int, tokenizer) -> LlamaConfig:
    return LlamaConfig(
        vocab_size=vocab_size,
        hidden_size=spec["hidden_size"],
        intermediate_size=spec["intermediate_size"],
        num_hidden_layers=spec["num_hidden_layers"],
        num_attention_heads=spec["num_attention_heads"],
        num_key_value_heads=spec["num_key_value_heads"],
        max_position_embeddings=spec["max_position_embeddings"],
        rms_norm_eps=spec["rms_norm_eps"],
        rope_theta=spec["rope_theta"],
        attention_bias=spec["attention_bias"],
        mlp_bias=spec["mlp_bias"],
        tie_word_embeddings=spec["tie_word_embeddings"],
        bos_token_id=tokenizer.bos_token_id,
        eos_token_id=tokenizer.eos_token_id,
        pad_token_id=tokenizer.pad_token_id,
    )

class PackedBlocks(IterableDataset):
    """Streams packed token blocks from local corpus without loading it all into RAM."""
    def __init__(self, files: list[Path], tokenizer, seq_len: int, stage: str):
        super().__init__()
        self.files, self.tokenizer, self.seq_len, self.stage = files, tokenizer, seq_len, stage
        self.special_ids = {key: tokenizer.convert_tokens_to_ids(value) for key, value in SPECIAL.items()}

    def _pretrain_stream(self) -> Iterator[tuple[list[int], list[int]]]:
        carry: list[int] = []
        while True:
            produced = 0
            for path in self.files:
                with path.open("r", encoding="utf-8", errors="replace") as handle:
                    for line in handle:
                        line = line.strip()
                        if not line:
                            continue
                        carry.extend(self.tokenizer.encode(line, add_special_tokens=False))
                        carry.append(self.tokenizer.eos_token_id)
                        while len(carry) >= self.seq_len + 1:
                            chunk = carry[: self.seq_len + 1]
                            del carry[: self.seq_len]
                            produced += 1
                            # Yield tensors so DataLoader collates each sequence into [batch, seq_len].
                            yield torch.tensor(chunk[:-1], dtype=torch.long), torch.tensor(chunk[1:], dtype=torch.long)
            if produced == 0:
                raise RuntimeError("The text corpus did not produce a training block. Add more licensed Arabic text.")

    def _sft_stream(self) -> Iterator[tuple[list[int], list[int]]]:
        carry_ids: list[int] = []
        carry_mask: list[int] = []
        while True:
            produced = 0
            for path in self.files:
                with path.open("r", encoding="utf-8") as handle:
                    for line_no, line in enumerate(handle, 1):
                        if not line.strip():
                            continue
                        item = json.loads(line)
                        for message in item["messages"]:
                            role = message["role"]
                            role_token = SPECIAL.get(role)
                            if role_token is None:
                                continue
                            role_id = self.tokenizer.convert_tokens_to_ids(role_token)
                            content_ids = self.tokenizer.encode(message["content"], add_special_tokens=False)
                            end_id = self.special_ids["end"]
                            segment = [role_id] + content_ids + [end_id]
                            carry_ids.extend(segment)
                            carry_mask.extend(
                                [0] + ([1] * (len(content_ids) + 1) if role == "assistant"
                                       else [0] * (len(content_ids) + 1))
                            )
                        while len(carry_ids) >= self.seq_len + 1:
                            ids = carry_ids[: self.seq_len + 1]
                            mask = carry_mask[: self.seq_len + 1]
                            del carry_ids[: self.seq_len]
                            del carry_mask[: self.seq_len]
                            labels = [ids[i] if mask[i] else -100 for i in range(1, self.seq_len + 1)]
                            if not any(label != -100 for label in labels):
                                continue
                            produced += 1
                            # Tensor samples avoid default_collate transposing Python lists into a list of tensors.
                            yield torch.tensor(ids[:-1], dtype=torch.long), torch.tensor(labels, dtype=torch.long)
            if produced == 0:
                raise RuntimeError("Persona JSONL did not produce an assistant training block.")

    def __iter__(self):
        yield from (self._pretrain_stream() if self.stage == "pretrain" else self._sft_stream())

def find_files(root: Path, suffix: str) -> list[Path]:
    files = sorted(p for p in root.rglob(f"*{suffix}") if p.is_file())
    if not files:
        raise SystemExit(f"No {suffix} files found under {root}")
    return files

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=["pretrain", "sft"], required=True)
    parser.add_argument("--spec", type=Path, default=Path("eve-model/config/eve_ar_95m.json"))
    parser.add_argument("--tokenizer-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--init-checkpoint", type=Path, default=None)
    parser.add_argument("--seq-len", type=int, default=1024)
    parser.add_argument("--max-steps", type=int, default=20000)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--save-every", type=int, default=500)
    parser.add_argument("--seed", type=int, default=20261030)
    parser.add_argument("--allow-cpu", action="store_true", help="Allow a slow CPU run for debugging only")
    args = parser.parse_args()
    if args.stage == "sft" and args.init_checkpoint is None:
        raise SystemExit("SFT must start from the EVE pretraining checkpoint via --init-checkpoint.")
    if args.max_steps < 1 or args.seq_len < 128 or args.gradient_accumulation < 1:
        raise SystemExit("Invalid max-steps, seq-len or gradient-accumulation value.")
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    spec = load_spec(args.spec)
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer_dir, use_fast=True)
    if tokenizer.pad_token_id is None or tokenizer.eos_token_id is None:
        raise SystemExit("Tokenizer must define pad_token and eos_token.")
    if len(tokenizer) != int(spec["vocab_size"]):
        raise SystemExit(
            f"Tokenizer vocab size {len(tokenizer)} differs from config {spec['vocab_size']}. "
            "Review the config/tokenizer pair before initializing the model."
        )
    if args.stage == "pretrain":
        files = find_files(args.data_dir, ".txt")
        model = LlamaForCausalLM(build_config(spec, len(tokenizer), tokenizer))
        print(f"Initialized from RANDOM weights: {sum(p.numel() for p in model.parameters()):,} parameters")
    else:
        files = find_files(args.data_dir, ".jsonl")
        model = LlamaForCausalLM.from_pretrained(args.init_checkpoint, torch_dtype="auto")
        if model.config.vocab_size != len(tokenizer):
            raise SystemExit("SFT tokenizer/checkpoint vocabulary mismatch.")
    dataset = PackedBlocks(files, tokenizer, args.seq_len, args.stage)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if device.type != "cuda" and not args.allow_cpu:
        raise SystemExit("A CUDA GPU is required for training. Pass --allow-cpu only for a small debugging run.")
    if device.type == "cuda":
        print(f"GPU training device: {torch.cuda.get_device_name(0)}", flush=True)
    model.to(device)
    model.train()
    model.config.use_cache = False
    model.gradient_checkpointing_enable()
    loader = DataLoader(dataset, batch_size=args.batch_size, num_workers=0)
    iterator = iter(loader)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.1)
    warmup_steps = max(20, int(args.max_steps * 0.03))
    scheduler = torch.optim.lr_scheduler.LambdaLR(
        optimizer,
        lambda step: min(
            (step + 1) / warmup_steps,
            max(0.0, (args.max_steps - step) / max(1, args.max_steps - warmup_steps)),
        ),
    )
    use_bf16 = device.type == "cuda" and torch.cuda.is_bf16_supported()
    use_fp16 = device.type == "cuda" and not use_bf16
    scaler = torch.amp.GradScaler("cuda", enabled=use_fp16)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    optimizer.zero_grad(set_to_none=True)
    running_loss, start = 0.0, time.time()

    for step in range(1, args.max_steps + 1):
        micro_loss_total = 0.0
        for _ in range(args.gradient_accumulation):
            input_ids, labels = next(iterator)
            input_ids = input_ids.to(device=device, dtype=torch.long)
            labels = labels.to(device=device, dtype=torch.long)
            with torch.autocast(
                device_type=device.type,
                dtype=torch.bfloat16 if use_bf16 else torch.float16,
                enabled=(use_bf16 or use_fp16),
            ):
                loss = model(input_ids=input_ids, labels=labels).loss
                scaled_loss = loss / args.gradient_accumulation
            if not torch.isfinite(loss):
                raise RuntimeError("Non-finite training loss; stop and inspect corpus/tokenizer.")
            if use_fp16:
                scaler.scale(scaled_loss).backward()
            else:
                scaled_loss.backward()
            micro_loss_total += float(loss.detach().cpu())

        if use_fp16:
            scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        if use_fp16:
            scaler.step(optimizer)
            scaler.update()
        else:
            optimizer.step()
        scheduler.step()
        optimizer.zero_grad(set_to_none=True)
        running_loss += micro_loss_total / args.gradient_accumulation

        if step == 1 or step % 10 == 0:
            divisor = 1 if step == 1 else 10
            print(
                f"stage={args.stage} step={step}/{args.max_steps} "
                f"loss={running_loss/divisor:.4f} lr={scheduler.get_last_lr()[0]:.3e} "
                f"elapsed_min={(time.time()-start)/60:.1f}",
                flush=True,
            )
            running_loss = 0.0

        if step % args.save_every == 0 or step == args.max_steps:
            checkpoint = args.output_dir / f"checkpoint-{step}"
            checkpoint.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(checkpoint, safe_serialization=True)
            tokenizer.save_pretrained(checkpoint)
            (checkpoint / "training-state.json").write_text(
                json.dumps({
                    "stage": args.stage, "step": step, "max_steps": args.max_steps,
                    "seed": args.seed, "random_init": args.stage == "pretrain",
                    "elapsed_seconds": round(time.time() - start, 2),
                }, indent=2), encoding="utf-8",
            )
            print(f"Saved checkpoint: {checkpoint}", flush=True)

    final_dir = args.output_dir / "final"
    model.config.use_cache = True
    model.save_pretrained(final_dir, safe_serialization=True)
    tokenizer.save_pretrained(final_dir)
    print(f"Training stage complete: {final_dir}")

if __name__ == "__main__":
    main()
