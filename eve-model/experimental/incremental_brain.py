#!/usr/bin/env python3
"""Experimental EVE incremental brain: random-init language core + explicit cognitive state.

This is a research prototype, not a trained model and not a claim of consciousness.
All neural parameters are initialized locally by PyTorch; no pretrained weights load.
"""
from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import torch
from torch import nn


class EVEGRUCore(nn.Module):
    """Small CPU-friendly causal language core trained from random initialization."""

    def __init__(self, vocab_size: int, embedding_dim: int = 192,
                 hidden_size: int = 256, num_layers: int = 2):
        super().__init__()
        if vocab_size < 16:
            raise ValueError("vocab_size must be at least 16")
        self.embedding = nn.Embedding(vocab_size, embedding_dim)
        self.recurrent = nn.GRU(
            embedding_dim, hidden_size, num_layers=num_layers,
            batch_first=True, dropout=0.1 if num_layers > 1 else 0.0,
        )
        self.norm = nn.LayerNorm(hidden_size)
        self.output = nn.Linear(hidden_size, vocab_size, bias=False)
        self.output.weight = self.embedding.weight
        self._reset_parameters()

    def _reset_parameters(self) -> None:
        for name, parameter in self.named_parameters():
            if parameter.dim() >= 2:
                nn.init.xavier_uniform_(parameter)
            else:
                nn.init.zeros_(parameter)

    def forward(self, input_ids: torch.Tensor,
                hidden: torch.Tensor | None = None):
        x = self.embedding(input_ids)
        states, hidden = self.recurrent(x, hidden)
        logits = self.output(self.norm(states))
        return logits, hidden


@dataclass
class Observation:
    concept: str
    details: dict[str, Any] = field(default_factory=dict)
    confidence: float = 0.0
    source: str = "user_or_sensor"
    confirmed: bool = False


class EVEExperienceMemory:
    """Small durable memory; uncertain observations are never silently promoted to facts."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.observations: list[dict[str, Any]] = []
        self.facts: dict[str, dict[str, Any]] = {}
        self.load()

    def load(self) -> None:
        if self.path.exists():
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.observations = data.get("observations", [])
            self.facts = data.get("facts", {})

    def observe(self, item: Observation) -> None:
        confidence = min(1.0, max(0.0, float(item.confidence)))
        record = asdict(item)
        record["confidence"] = confidence
        self.observations.append(record)
        if item.confirmed and confidence >= 0.90:
            self.facts[item.concept] = {
                "details": item.details, "confidence": confidence,
                "evidence_count": 1,
            }
        self.save()

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(self.path.suffix + ".tmp")
        temp.write_text(json.dumps({
            "observations": self.observations,
            "facts": self.facts,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.path)

    def novelty(self, concept: str) -> float:
        count = sum(1 for x in self.observations if x["concept"] == concept)
        return 1.0 / (1.0 + count)


@dataclass
class AffectState:
    curiosity: float = 0.55
    joy: float = 0.50
    irritation: float = 0.05
    concern: float = 0.10
    energy: float = 0.80

    def update(self, *, novelty: float = 0.0, positive: float = 0.0,
               frustration: float = 0.0, uncertainty: float = 0.0) -> None:
        def clamp(x: float) -> float:
            return min(1.0, max(0.0, x))
        self.curiosity = clamp(0.85 * self.curiosity + 0.25 * novelty - 0.10 * self.energy)
        self.joy = clamp(0.90 * self.joy + 0.20 * positive)
        self.irritation = clamp(0.90 * self.irritation + 0.25 * frustration)
        self.concern = clamp(0.90 * self.concern + 0.20 * uncertainty)
        self.energy = clamp(self.energy - 0.01)


class EVEBrain:
    """Central coordinator: confidence-gated perception, memory, affect and curiosity."""

    def __init__(self, memory_path: str | Path):
        self.memory = EVEExperienceMemory(memory_path)
        self.affect = AffectState()

    def perceive(self, observation: Observation) -> dict[str, Any]:
        confidence = min(1.0, max(0.0, observation.confidence))
        self.memory.observe(observation)
        novelty = self.memory.novelty(observation.concept)
        self.affect.update(novelty=novelty, uncertainty=1.0 - confidence)
        return {
            "concept": observation.concept,
            "confidence": confidence,
            "needs_confirmation": not (observation.confirmed and confidence >= 0.90),
            "curiosity": self.affect.curiosity,
            "novelty": novelty,
        }

    def choose_goal(self, candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
        """Choose a safe, useful learning goal; reject malformed or unsafe candidates."""
        scored = []
        for item in candidates:
            if not item.get("safe", False):
                continue
            concept = str(item.get("concept", "")).strip()
            if not concept:
                continue
            usefulness = min(1.0, max(0.0, float(item.get("usefulness", 0.0))))
            novelty = self.memory.novelty(concept)
            score = 0.60 * novelty + 0.40 * usefulness
            scored.append((score, item))
        return max(scored, key=lambda pair: pair[0])[1] if scored else None
