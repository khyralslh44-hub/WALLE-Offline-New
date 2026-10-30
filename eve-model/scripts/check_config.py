#!/usr/bin/env python3
"""Dependency-free structural checks for EVE-AR-95M configuration."""
import json
from pathlib import Path

path = Path("eve-model/config/eve_ar_95m.json")
cfg = json.loads(path.read_text(encoding="utf-8"))
assert cfg["model_name"] == "EVE-AR-95M"
assert cfg["training"]["initialization"] == "random"
assert cfg["training"]["pretrained_weights"] is False
v, h = cfg["vocab_size"], cfg["hidden_size"]
i, layers, kv = cfg["intermediate_size"], cfg["num_hidden_layers"], cfg["num_key_value_heads"]
assert h % cfg["num_attention_heads"] == 0
assert cfg["num_attention_heads"] % kv == 0
assert cfg["max_position_embeddings"] >= 1024
# Llama-style parameter count with tied token embeddings and no linear biases.
attention = 2 * h * h + 2 * h * (h * kv // cfg["num_attention_heads"])
mlp = 3 * h * i
norms = 2 * h * layers + h
total = v * h + layers * (attention + mlp + 2 * h) + h
assert total == cfg["parameter_count_estimate"], (total, cfg["parameter_count_estimate"])
print(json.dumps({
    "model": cfg["model_name"],
    "parameters_estimated": total,
    "vocab_size": v,
    "layers": layers,
    "status": "configuration_valid_not_trained",
}, indent=2))
