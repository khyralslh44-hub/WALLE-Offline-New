#!/usr/bin/env python3
"""Compare Arabic ASR transcripts from identical recordings using WER/CER.

Input TSV columns: dialect, reference, hypothesis. Optional model column.
No third-party packages are required.

Example:
  python3 .github/scripts/evaluate-arabic-asr.py vosk.tsv
  python3 .github/scripts/evaluate-arabic-asr.py moonshine.tsv
  python3 .github/scripts/evaluate-arabic-asr.py combined.tsv
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

DIACRITICS = re.compile(r"[\u064b-\u065f\u0670\u0640]")
PUNCTUATION = re.compile(r"[^\w\s]", re.UNICODE)
SPACES = re.compile(r"\s+")


def normalize_arabic(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = DIACRITICS.sub("", text)
    text = (text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ٱ", "ا").replace("ى", "ي")
                .replace("ؤ", "و").replace("ئ", "ي"))
    text = PUNCTUATION.sub(" ", text)
    return SPACES.sub(" ", text).strip()


def edit_distance(left: list[str], right: list[str]) -> int:
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, 1):
        current = [i]
        for j, b in enumerate(right, 1):
            current.append(min(
                current[-1] + 1,
                previous[j] + 1,
                previous[j - 1] + (a != b),
            ))
        previous = current
    return previous[-1]


def score_pairs(pairs: list[tuple[str, str]]) -> tuple[int, int, int, int]:
    word_errors = word_count = char_errors = char_count = 0
    for reference, hypothesis in pairs:
        ref_words = reference.split()
        hyp_words = hypothesis.split()
        word_errors += edit_distance(ref_words, hyp_words)
        word_count += len(ref_words)
        ref_chars = list(reference.replace(" ", ""))
        hyp_chars = list(hypothesis.replace(" ", ""))
        char_errors += edit_distance(ref_chars, hyp_chars)
        char_count += len(ref_chars)
    return word_errors, word_count, char_errors, char_count


def pct(errors: int, total: int) -> str:
    return "n/a" if total == 0 else f"{100.0 * errors / total:.2f}%"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tsv", type=Path)
    parser.add_argument("--strict", action="store_true",
                        help="Do not normalize Arabic hamza/alif/ya variants.")
    args = parser.parse_args()
    if not args.tsv.is_file():
        parser.error(f"input file not found: {args.tsv}")

    grouped: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
    with args.tsv.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        required = {"dialect", "reference", "hypothesis"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            parser.error("TSV header must include: dialect, reference, hypothesis")
        for line, row in enumerate(reader, 2):
            dialect = (row.get("dialect") or "").strip() or "unspecified"
            model = (row.get("model") or "").strip() or "unspecified"
            reference = row.get("reference") or ""
            hypothesis = row.get("hypothesis") or ""
            if not reference.strip():
                print(f"warning: empty reference at line {line}; skipped", file=sys.stderr)
                continue
            if args.strict:
                # Still remove diacritics, punctuation and spacing; retain letter variants.
                ref = SPACES.sub(" ", PUNCTUATION.sub(" ", DIACRITICS.sub("", reference))).strip()
                hyp = SPACES.sub(" ", PUNCTUATION.sub(" ", DIACRITICS.sub("", hypothesis))).strip()
            else:
                ref, hyp = normalize_arabic(reference), normalize_arabic(hypothesis)
            grouped[(model, dialect)].append((ref, hyp))

    if not grouped:
        parser.error("no usable transcript rows found")

    totals = [0, 0, 0, 0]
    print("model\tdialect\tclips\tWER\tCER")
    for (model, dialect), pairs in sorted(grouped.items()):
        values = score_pairs(pairs)
        totals = [a + b for a, b in zip(totals, values)]
        print(f"{model}\t{dialect}\t{len(pairs)}\t{pct(values[0], values[1])}\t{pct(values[2], values[3])}")
    print(f"ALL\tALL\t{sum(len(v) for v in grouped.values())}\t{pct(totals[0], totals[1])}\t{pct(totals[2], totals[3])}")
    print("\nWER/CER use corpus-level edit counts divided by reference word/character counts.")
    print("Compare models only on the same recordings and the same normalization setting.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
