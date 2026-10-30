#!/usr/bin/env python3
"""Train EVE's tokenizer from licensed local Arabic text; no pretrained tokenizer."""
from __future__ import annotations
import argparse, json
from pathlib import Path
from tokenizers import Tokenizer, decoders, models, pre_tokenizers, trainers
from transformers import PreTrainedTokenizerFast

SPECIAL = ["<pad>", "<unk>", "<s>", "</s>", "<|system|>", "<|user|>", "<|assistant|>", "<|end|>"]

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--vocab-size", type=int, default=24576)
    args = parser.parse_args()
    files = sorted(p for p in args.corpus_dir.rglob("*") if p.is_file() and p.suffix.lower() in {".txt", ".md"})
    if not files:
        raise SystemExit(f"No .txt or .md corpus files found under {args.corpus_dir}")
    total_bytes = sum(p.stat().st_size for p in files)
    if total_bytes < 1_000_000:
        raise SystemExit("Corpus is under 1 MB. Add a substantially larger, licensed Arabic corpus before tokenizer training.")
    tokenizer = Tokenizer(models.BPE(unk_token="<unk>"))
    tokenizer.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=False)
    tokenizer.decoder = decoders.ByteLevel()
    trainer = trainers.BpeTrainer(
        vocab_size=args.vocab_size, min_frequency=2, special_tokens=SPECIAL,
        initial_alphabet=pre_tokenizers.ByteLevel.alphabet(), show_progress=True,
    )
    tokenizer.train([str(p) for p in files], trainer=trainer)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    fast = PreTrainedTokenizerFast(
        tokenizer_object=tokenizer, bos_token="<s>", eos_token="</s>",
        unk_token="<unk>", pad_token="<pad>",
        additional_special_tokens=["<|system|>", "<|user|>", "<|assistant|>", "<|end|>"],
        clean_up_tokenization_spaces=False,
    )
    fast.chat_template = (
        "{% for message in messages %}"
        "{% if message['role'] == 'system' %}<|system|>\\n{{ message['content'] }}<|end|>\\n"
        "{% elif message['role'] == 'user' %}<|user|>\\n{{ message['content'] }}<|end|>\\n"
        "{% elif message['role'] == 'assistant' %}<|assistant|>\\n{{ message['content'] }}<|end|>\\n"
        "{% endif %}{% endfor %}"
    )
    if len(fast) != args.vocab_size:
        raise SystemExit(f"Tokenizer produced {len(fast)} tokens; expected exactly {args.vocab_size}. Add more corpus text before training.")
    fast.save_pretrained(args.output_dir)
    tokenizer.save(str(args.output_dir / "tokenizer.json"))
    probes = [
        "السلام عليكم، كيف حالك يا إيف؟",
        "الطقسُ ممطرٌ اليوم؛ هل نخرج؟ 12345",
        "🚀 أهلاً بالعالم! EVE",
        "سطر أول\nسطر ثانٍ\tومسافة",
    ]
    results = []
    for text in probes:
        ids = fast.encode(text, add_special_tokens=False)
        decoded = fast.decode(ids, skip_special_tokens=False)
        if decoded != text:
            raise SystemExit(f"Tokenizer round-trip failed: {text!r} -> {decoded!r}")
        results.append({"input": text, "token_count": len(ids), "round_trip": True})
    metadata = {
        "model": "EVE-AR-95M", "tokenizer_type": "ByteLevel BPE trained from local corpus",
        "vocab_size": len(fast), "special_tokens": SPECIAL,
        "corpus_files": [str(p) for p in files], "corpus_bytes": total_bytes,
        "round_trip_tests": results, "pretrained_tokenizer_used": False,
    }
    (args.output_dir / "tokenizer-report.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
