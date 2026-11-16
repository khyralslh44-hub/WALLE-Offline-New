#!/usr/bin/env python3
"""Structural smoke test for the third-party quantized Moonshine Arabic ONNX candidate."""
import json
import pathlib
import sys

import onnxruntime as ort

root = pathlib.Path(sys.argv[1])
required = (
    "encoder_model_quantized.onnx",
    "decoder_model_quantized.onnx",
    "tokenizer.json",
    "config.json",
    "model_meta.json",
)
for name in required:
    path = root / name
    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit(f"FAIL: missing or empty candidate file: {name}")

tokenizer = json.loads((root / "tokenizer.json").read_text(encoding="utf-8"))
config = json.loads((root / "config.json").read_text(encoding="utf-8"))
if not isinstance(tokenizer, dict) or not isinstance(config, dict):
    raise SystemExit("FAIL: tokenizer/config are not JSON objects.")

options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
encoder = ort.InferenceSession(
    str(root / "encoder_model_quantized.onnx"),
    sess_options=options,
    providers=["CPUExecutionProvider"],
)
decoder = ort.InferenceSession(
    str(root / "decoder_model_quantized.onnx"),
    sess_options=options,
    providers=["CPUExecutionProvider"],
)

def signature(session):
    return {
        "inputs": [(x.name, x.type, x.shape) for x in session.get_inputs()],
        "outputs": [(x.name, x.type, x.shape) for x in session.get_outputs()],
    }

enc = signature(encoder)
dec = signature(decoder)
print("Encoder signature:", enc)
print("Decoder signature:", dec)

enc_inputs = {x[0] for x in enc["inputs"]}
dec_inputs = {x[0] for x in dec["inputs"]}
if "input_values" not in enc_inputs:
    raise SystemExit("FAIL: encoder input_values tensor is absent.")
if not {"input_ids", "encoder_hidden_states"}.issubset(dec_inputs):
    raise SystemExit("FAIL: decoder does not expose the documented input_ids and encoder_hidden_states tensors.")
if not enc["outputs"] or not dec["outputs"]:
    raise SystemExit("FAIL: encoder/decoder graph has no output tensors.")

print("PASS: tokenizer/config parse and both quantized ONNX graphs load on CPU.")
print("Model config keys:", sorted(config.keys()))
