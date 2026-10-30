# EVE training data provenance

The repository must not silently scrape or redistribute arbitrary web text.

For every pretraining corpus placed under `pretrain/`, add a row recording:
- exact dataset name and version,
- source URL,
- license and attribution requirements,
- retrieval date,
- language composition,
- preprocessing and deduplication steps,
- whether commercial redistribution is permitted.

Do not include private conversations, face images, identity embeddings, or personal user data in public training corpora. Do not use data with unclear licensing. Keep large corpora outside Git; mount them from private storage during training.

| Dataset | Version | Source URL | License | Retrieved | Notes |
|---|---|---|---|---|---|
| _Not selected yet_ | — | — | — | — | Add only after license review |

The current `personality_seed.jsonl` contains twelve synthetic seed examples. They are not a language pretraining corpus and are not enough to produce a capable conversational model. Expand and human-review the persona dataset before SFT.
