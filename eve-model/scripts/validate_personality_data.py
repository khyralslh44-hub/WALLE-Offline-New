#!/usr/bin/env python3
"""Validate the structure and basic hygiene of EVE persona SFT examples."""
from __future__ import annotations
import argparse, json
from pathlib import Path
ALLOWED_ROLES = {"system", "user", "assistant"}

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--min-examples", type=int, default=10)
    args = parser.parse_args()
    seen, categories = set(), set()
    count = assistant_turns = user_turns = 0
    for line_no, raw in enumerate(args.jsonl.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            item = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{args.jsonl}:{line_no}: invalid JSON: {exc}") from exc
        if not isinstance(item, dict) or not isinstance(item.get("messages"), list):
            raise SystemExit(f"{args.jsonl}:{line_no}: expected object with messages array")
        item_id = item.get("id")
        if not isinstance(item_id, str) or not item_id.strip() or item_id in seen:
            raise SystemExit(f"{args.jsonl}:{line_no}: missing or duplicate id")
        seen.add(item_id)
        if not item.get("category"):
            raise SystemExit(f"{args.jsonl}:{line_no}: missing category")
        categories.add(str(item["category"]))
        roles = [m.get("role") for m in item["messages"] if isinstance(m, dict)]
        if len(roles) != len(item["messages"]) or not roles or "assistant" not in roles:
            raise SystemExit(f"{args.jsonl}:{line_no}: malformed messages or no assistant answer")
        if any(role not in ALLOWED_ROLES for role in roles):
            raise SystemExit(f"{args.jsonl}:{line_no}: unsupported role")
        if roles[-1] != "assistant":
            raise SystemExit(f"{args.jsonl}:{line_no}: final message must be assistant")
        for message in item["messages"]:
            content = message.get("content")
            if not isinstance(content, str) or not content.strip():
                raise SystemExit(f"{args.jsonl}:{line_no}: empty or non-text message")
            if message["role"] == "assistant":
                assistant_turns += 1
            elif message["role"] == "user":
                user_turns += 1
        count += 1
    if count < args.min_examples:
        raise SystemExit(f"Only {count} examples; at least {args.min_examples} required for this validation.")
    print(json.dumps({
        "examples": count, "categories": sorted(categories),
        "assistant_turns": assistant_turns, "user_turns": user_turns,
        "status": "structure_valid_only_not_quality_reviewed",
    }, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
