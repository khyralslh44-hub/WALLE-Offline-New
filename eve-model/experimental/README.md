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
  --steps 20 --seq-len 64 --hidden-size 64 --smoke-test
```

The smoke test verifies that random initialization, forward/backward passes, optimizer steps, checkpoint writing and CPU metrics work. It does not establish useful Arabic language quality. For a meaningful experiment, use a legally reusable, much larger Arabic corpus and compare held-out validation loss against the existing 95M baseline.

## Next gates
1. Add unit tests for memory persistence, confidence gating, novelty and safe goal selection.
2. Run CPU benchmark on the actual target cloud/phone hardware and record tokens/second, peak RAM and thermal limits.
3. Add subword tokenization trained from the same Arabic corpus, then compare GRU vs the existing 95M Transformer on identical data and compute budgets.
4. Add replay and regularization before any continual-learning claims; test forgetting on held-out tasks.
5. Only after measured wins, decide whether to migrate the production training path. The existing 95M model remains unchanged.
