import tempfile
import unittest
from pathlib import Path

import torch

from incremental_brain import EVEBrain, EVEGRUCore, Observation
from evaluate_incremental_core import evaluate_text


class IncrementalBrainTests(unittest.TestCase):
    def test_uncertain_observation_is_not_promoted_to_fact(self):
        with tempfile.TemporaryDirectory() as tmp:
            brain = EVEBrain(Path(tmp) / "memory.json")
            result = brain.perceive(Observation("cat", confidence=0.55))
            self.assertTrue(result["needs_confirmation"])
            self.assertNotIn("cat", brain.memory.facts)

    def test_confirmed_high_confidence_fact_persists(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory.json"
            brain = EVEBrain(path)
            brain.perceive(Observation("cat", {"kind": "animal"}, 0.97, confirmed=True))
            restored = EVEBrain(path)
            self.assertIn("cat", restored.memory.facts)
            self.assertEqual(restored.memory.facts["cat"]["details"]["kind"], "animal")

    def test_novelty_decreases_after_repeated_observation(self):
        with tempfile.TemporaryDirectory() as tmp:
            brain = EVEBrain(Path(tmp) / "memory.json")
            first = brain.perceive(Observation("car", confidence=0.8))
            second = brain.perceive(Observation("car", confidence=0.8))
            self.assertGreater(first["novelty"], second["novelty"])

    def test_planner_ignores_unsafe_goal(self):
        with tempfile.TemporaryDirectory() as tmp:
            brain = EVEBrain(Path(tmp) / "memory.json")
            chosen = brain.choose_goal([
                {"concept": "unsafe", "safe": False, "usefulness": 1.0},
                {"concept": "safe", "safe": True, "usefulness": 0.5},
            ])
            self.assertEqual(chosen["concept"], "safe")

    def test_gru_core_starts_random_and_returns_logits(self):
        torch.manual_seed(7)
        model = EVEGRUCore(vocab_size=32, embedding_dim=16, hidden_size=16)
        x = torch.randint(0, 32, (2, 5))
        logits, hidden = model(x)
        self.assertEqual(tuple(logits.shape), (2, 5, 32))
        self.assertIsNotNone(hidden)
        self.assertTrue(torch.isfinite(logits).all().item())

    def test_unknown_validation_characters_break_context(self):
        torch.manual_seed(3)
        model = EVEGRUCore(vocab_size=16, embedding_dim=8, hidden_size=8)
        model.eval()
        metrics = evaluate_text(model, ["a", "b"] + [f"unused-{i}" for i in range(14)],
                                "aaaa?bbbb", seq_len=2, batch_size=4)
        self.assertEqual(metrics["unknown_characters"], 1)
        self.assertEqual(metrics["evaluated_targets"], 4)

    def test_evaluator_rejects_only_too_short_known_segments(self):
        torch.manual_seed(4)
        model = EVEGRUCore(vocab_size=16, embedding_dim=8, hidden_size=8)
        with self.assertRaises(ValueError):
            evaluate_text(model, ["a"] + [f"unused-{i}" for i in range(15)],
                          "aa?aa", seq_len=2, batch_size=2)


if __name__ == "__main__":
    unittest.main()
