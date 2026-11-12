#!/usr/bin/env python3
"""Train a tiny Libyan Arabic review-polarity model from a CC BY 4.0 XLSX corpus.

The output is a UTF-8 TSV of Multinomial Naive Bayes feature log-ratios.
Only a narrow restaurant-review sentiment task is learned; this is not a dialogue LLM.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
ALIAS = {
    "شن": "ما", "علاش": "لماذا", "توا": "الان", "تو": "الان",
    "هلبة": "كثير", "هلبه": "كثير", "هلبا": "كثير", "واجد": "كثير",
    "باهي": "جيد", "نبي": "اريد", "نبو": "نريد", "تبي": "تريد",
    "يبي": "يريد", "قداش": "كم", "هكي": "هكذا", "هكيكا": "هكذا",
    "وينك": "اين انت", "مفيش": "لا يوجد", "مافيش": "لا يوجد",
    "نبيش": "لا اريد", "مانبيش": "لا اريد", "مانحبش": "لا احب",
    "درت": "فعلت", "تدير": "تفعل", "شني": "ما", "شلون": "كيف",
    "شخبارك": "كيف حالك", "طفشان": "مللت", "زهقان": "مللت",
    "مبسوط": "سعيد", "فرحان": "سعيد", "زعلان": "حزين",
}
DIACRITICS = re.compile(r"[\u064b-\u065f\u0670\u0640]")
NON_WORD = re.compile(r"[^\w\s]", re.UNICODE)
SPACES = re.compile(r"\s+")


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", str(text or "")).lower()
    text = DIACRITICS.sub("", text)
    for old, new in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"),
                     ("ى", "ي"), ("ؤ", "و"), ("ئ", "ي"), ("ة", "ه"),
                     ("ی", "ي"), ("ک", "ك")):
        text = text.replace(old, new)
    text = SPACES.sub(" ", NON_WORD.sub(" ", text)).strip()
    words: list[str] = []
    for token in text.split():
        replacement = ALIAS.get(token)
        words.extend(replacement.split() if replacement else [token])
    return " ".join(words)


def features(text: str) -> set[str]:
    tokens = normalize(text).split()
    result: set[str] = set()
    for token in tokens:
        result.add("w:" + token)
        padded = "^" + token + "$"
        for n in (2, 3, 4):
            for start in range(0, len(padded) - n + 1):
                result.add("c:" + padded[start:start + n])
    for i in range(len(tokens) - 1):
        result.add("b:" + tokens[i] + "_" + tokens[i + 1])
    return result


def xml_text(node: ET.Element) -> str:
    return "".join(part.text or "" for part in node.findall(".//m:t", NS))


def read_xlsx(path: Path) -> list[tuple[str, int]]:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        shared: list[str] = []
        if "xl/sharedStrings.xml" in names:
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            for item in root.findall("m:si", NS):
                shared.append(xml_text(item))
        sheet_name = next(
            (name for name in names if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", name)),
            None
        )
        if not sheet_name:
            raise RuntimeError("XLSX worksheet XML was not found.")
        sheet = ET.fromstring(archive.read(sheet_name))
        parsed: list[list[str]] = []
        for row in sheet.findall(".//m:sheetData/m:row", NS):
            cells: dict[str, str] = {}
            for cell in row.findall("m:c", NS):
                ref = cell.attrib.get("r", "")
                col = re.sub(r"\d", "", ref)
                kind = cell.attrib.get("t", "")
                if kind == "inlineStr":
                    value = xml_text(cell)
                else:
                    value_node = cell.find("m:v", NS)
                    value = value_node.text or "" if value_node is not None else ""
                    if kind == "s" and value:
                        value = shared[int(value)]
                cells[col] = value
            parsed.append([cells.get("A", ""), cells.get("B", "")])

    header_index = next(
        (i for i, row in enumerate(parsed[:8])
         if row[0].strip().lower() in {"message", "text", "comment"} and
         row[1].strip().lower() in {"label", "sentiment", "polarity"}),
        None
    )
    if header_index is None:
        raise RuntimeError("Could not find message/label columns in the Libyan XLSX.")
    rows_out: list[tuple[str, int]] = []
    for message, label_text in parsed[header_index + 1:]:
        message = str(message).strip()
        try:
            label = int(float(label_text))
        except (TypeError, ValueError):
            continue
        if message and label in (0, 1):
            rows_out.append((message, label))
    return rows_out


def stratified_split(rows: list[tuple[str, int]]) -> tuple[list[tuple[str, int]], list[tuple[str, int]]]:
    train: list[tuple[str, int]] = []
    test: list[tuple[str, int]] = []
    for label in (0, 1):
        group = [(i, row) for i, row in enumerate(rows) if row[1] == label]
        group.sort(key=lambda pair: hashlib.sha256(
            (str(pair[0]) + "|" + normalize(pair[1][0])).encode("utf-8")
        ).hexdigest())
        test_count = max(1, round(len(group) * 0.20))
        test.extend(row for _, row in group[:test_count])
        train.extend(row for _, row in group[test_count:])
    train.sort(key=lambda row: (row[1], normalize(row[0])))
    test.sort(key=lambda row: (row[1], normalize(row[0])))
    return train, test


def train_nb(train: list[tuple[str, int]], vocab_size: int = 6000, alpha: float = 0.5):
    doc_counts = {0: Counter(), 1: Counter()}
    documents = {0: 0, 1: 0}
    for text, label in train:
        documents[label] += 1
        doc_counts[label].update(features(text))

    vocabulary_counts = doc_counts[0] + doc_counts[1]
    vocabulary = [feature for feature, _ in vocabulary_counts.most_common(vocab_size)]
    if len(vocabulary) < 50:
        raise RuntimeError(f"Too few learned features: {len(vocabulary)}")

    totals = {
        label: sum(doc_counts[label][feature] for feature in vocabulary)
        for label in (0, 1)
    }
    vocab_count = len(vocabulary)
    bias = math.log((documents[1] + 1.0) / (documents[0] + 1.0))
    weights: dict[str, float] = {}
    for feature in vocabulary:
        positive_prob = (doc_counts[1][feature] + alpha) / (totals[1] + alpha * vocab_count)
        negative_prob = (doc_counts[0][feature] + alpha) / (totals[0] + alpha * vocab_count)
        weights[feature] = math.log(positive_prob) - math.log(negative_prob)
    return bias, weights


def predict(text: str, bias: float, weights: dict[str, float]) -> int:
    score = bias + sum(weights.get(feature, 0.0) for feature in features(text))
    return 1 if score >= 0 else 0


def metrics(test: list[tuple[str, int]], bias: float, weights: dict[str, float]):
    truth = [label for _, label in test]
    guesses = [predict(text, bias, weights) for text, _ in test]
    accuracy = sum(a == b for a, b in zip(truth, guesses)) / max(1, len(test))
    f1s = []
    for positive in (0, 1):
        tp = sum(t == positive and p == positive for t, p in zip(truth, guesses))
        fp = sum(t != positive and p == positive for t, p in zip(truth, guesses))
        fn = sum(t == positive and p != positive for t, p in zip(truth, guesses))
        precision = tp / max(1, tp + fp)
        recall = tp / max(1, tp + fn)
        f1s.append(2 * precision * recall / max(1e-12, precision + recall))
    return accuracy, sum(f1s) / 2.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    input_path = Path(args.input)
    rows = read_xlsx(input_path)
    if len(rows) < 3000:
        raise RuntimeError(f"Expected a substantial Libyan review corpus, found only {len(rows)} rows.")
    train, test = stratified_split(rows)
    bias, weights = train_nb(train)
    accuracy, macro_f1 = metrics(test, bias, weights)
    print(f"Rows={len(rows)} train={len(train)} held_out={len(test)} features={len(weights)}")
    print(f"Held-out accuracy={accuracy:.4f} macro-F1={macro_f1:.4f}")

    if accuracy < 0.68 or macro_f1 < 0.66:
        raise RuntimeError("Held-out Libyan sentiment metrics fell below the minimum quality gate.")

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("EVE_LIBYAN_SENTIMENT_V1\n")
        handle.write("# Dataset: Libyan Restaurants, CC BY 4.0; attribution in EVE readiness report.\n")
        handle.write("rows\t%d\n" % len(rows))
        handle.write("accuracy\t%.6f\n" % accuracy)
        handle.write("macro_f1\t%.6f\n" % macro_f1)
        handle.write("threshold\t0.64\n")
        handle.write("bias\t%.9f\n" % bias)
        for feature in sorted(weights):
            handle.write("f\t%s\t%.8f\n" % (feature, weights[feature]))

    size = output_path.stat().st_size
    if size > 300_000:
        raise RuntimeError(f"Model size {size} bytes exceeds 300 KB.")
    report = {
        "dataset": "Mansour-Essgaer/Libyan-Resturant",
        "dataset_revision": "a8b5cc1dfa2137caee565646428acc10f1d4b629",
        "license": "CC BY 4.0",
        "rows": len(rows),
        "training_rows": len(train),
        "held_out_rows": len(test),
        "features": len(weights),
        "model_bytes": size,
        "held_out_accuracy": round(accuracy, 6),
        "held_out_macro_f1": round(macro_f1, 6),
        "scope": "binary sentiment for Libyan restaurant-review language only"
    }
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Model written:", output_path, "bytes=", size)


if __name__ == "__main__":
    main()
