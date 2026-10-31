# EVE Incremental Brain — experimental prototype

This branch adds a CPU-first research prototype without replacing or deleting EVE-AR-95M.

## Design invariants
- Neural language-core weights are initialized randomly by this project.
- No pretrained language-model weights or tokenizer are loaded.
- Memory distinguishes observations from confirmed facts.
- Perception confidence is explicit; low-confidence observations remain unconfirmed.
- Curiosity/affect/planning are explicit stateful components coordinated by one EVEBrain.
- The current implementation is a prototype, not a trained assistant and not evidence of consciousness.

## Why this approach
A small GRU core is used as a CPU benchmark baseline because recurrent sequence models can be cheaper to train than a 95M-parameter decoder Transformer. The cognitive coordinator is kept separate from language generation, so new memories and goals do not require retraining the language core on every event.

## CPU smoke test
Install the existing training requirements, then run:

```bash
python eve-model/experimental/train_incremental_core.py \
  --corpus-dir dataset/pretrain \
  --output-dir eve-model/runs/incremental-cpu \
  --steps 20 --seq-len 64 --hidden-size 64
```

The `--smoke-test` flag is only for tiny pipeline checks and bypasses the minimum corpus-size guard. It must not be used to claim language capability. For a meaningful experiment, use a legally reusable Arabic corpus with a documented source, license, deduplication policy and separate train/validation files.

## Held-out evaluation
After training, evaluate on a separate directory that was not used for training:

```bash
python eve-model/experimental/evaluate_incremental_core.py \
  --checkpoint eve-model/runs/incremental-cpu/eve-gru-smoke.pt \
  --validation-dir dataset/validation \
  --seq-len 128 --batch-size 8 \
  --output eve-model/runs/incremental-cpu/validation-report.json
```

The report records character-level cross-entropy/perplexity, unknown-character rate, CPU throughput, elapsed time and peak RSS. These are engineering diagnostics only; character metrics do not prove Arabic comprehension, grounded reasoning or conversational usefulness. Validation data must remain separate from training data.

## Next gates
1. Obtain and document a sufficiently large, legally reusable Arabic corpus; do not silently substitute synthetic text.
2. Measure repeatable held-out loss, throughput and peak memory on the same data and compute budget for this GRU and EVE-AR-95M.
3. Add a tokenizer trained only on the selected corpus, then rerun the same controlled comparison.
4. Add replay and regularization before any continual-learning claims; test forgetting on held-out tasks.
5. Validate perception, memory and planning against realistic scenarios before Android integration.
6. Only after measured wins and Android resource testing, decide whether to migrate the production training path. The existing 95M model and production app remain unchanged.
