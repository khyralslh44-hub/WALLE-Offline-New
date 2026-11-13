#!/usr/bin/env python3
"""Convert a Hugging Face Moonshine tokenizer.json into sherpa-onnx tokens.txt.

The output is one base64-encoded UTF-8 token piece and its numeric ID per line.
This follows Moonshine AI's BinTokenizer byte representation and sherpa-onnx's
scripts/moonshine/v2/generate_tokens.py output format.
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tokenizer_json", type=Path)
    parser.add_argument("tokens_txt", type=Path)
    args = parser.parse_args()

    data = json.loads(args.tokenizer_json.read_text(encoding="utf-8"))
    model = data.get("model") or {}
    vocab = model.get("vocab")
    if isinstance(vocab, dict):
        if not vocab:
            raise SystemExit("Tokenizer vocabulary is empty")
        highest_id = max(int(token_id) for token_id in vocab.values())
        tokens = [""] * (highest_id + 1)
        for piece, token_id in vocab.items():
            tokens[int(token_id)] = piece
    elif isinstance(vocab, list):
        # Hugging Face Unigram stores [piece, score] entries in ID order.
        tokens = [str(entry[0]) for entry in vocab]
    else:
        raise SystemExit(f"Unsupported tokenizer vocabulary: {type(vocab).__name__}")

    for added in data.get("added_tokens", []):
        token_id = added.get("id")
        if token_id is None:
            continue
        token_id = int(token_id)
        if token_id >= len(tokens):
            tokens.extend([""] * (token_id - len(tokens) + 1))
        tokens[token_id] = str(added.get("content", ""))

    if len(tokens) < 30000:
        raise SystemExit(f"Unexpectedly small Moonshine vocabulary: {len(tokens)}")
    if len(tokens) > 40000:
        raise SystemExit(f"Unexpectedly large Moonshine vocabulary: {len(tokens)}")

    args.tokens_txt.parent.mkdir(parents=True, exist_ok=True)
    with args.tokens_txt.open("w", encoding="utf-8", newline="\n") as output:
        for token_id, piece in enumerate(tokens):
            encoded = base64.b64encode(piece.encode("utf-8")).decode("ascii")
            output.write(f"{encoded} {token_id}\n")

    size = args.tokens_txt.stat().st_size
    print(f"PASS: wrote {len(tokens)} Moonshine tokens ({size} bytes) to {args.tokens_txt}")


if __name__ == "__main__":
    main()
