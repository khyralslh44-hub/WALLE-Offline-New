# EVE training data provenance

## Approved starter candidates (review before redistribution)

1. **ArabicText-Large** — Hugging Face dataset `Jr23xd23/ArabicText-Large`, Apache-2.0 on its dataset card, with approximately 743k Arabic text records. The viewer exposes `title`, `text`, `url`, and metadata including source. Keep the source URL/title manifest produced by the downloader; records visibly include Wikipedia-derived sources, so preserve attribution and check underlying source terms before redistributing derived text or weights.
2. **CIDAR** — Hugging Face dataset `arbml/CIDAR`, Apache-2.0 on its dataset card, approximately 10k Arabic instruction/output records. Its maintainers describe it as research-oriented and note that examples were translated/assembled from multiple sources and reviewed by contributors. Review the dataset card and the outputs before commercial or public redistribution.

The repository must not silently scrape or redistribute arbitrary web text.

For every corpus, record:
- exact dataset name and version / revision,
- source URL,
- license and attribution requirements,
- retrieval date,
- language composition,
- preprocessing and deduplication steps,
- whether commercial redistribution is permitted.

Do not include private conversations, face images, identity embeddings, or personal user data in public training corpora. Keep large corpora outside Git; mount them from private storage during training.

| Dataset | Version | Source URL | License on dataset card | Notes |
|---|---|---|---|---|
| ArabicText-Large | Hugging Face current revision at download | https://huggingface.co/datasets/Jr23xd23/ArabicText-Large | Apache-2.0 | Preserve row URL/title attribution; underlying source metadata includes Wikipedia |
| CIDAR | Hugging Face current revision at download | https://huggingface.co/datasets/arbml/CIDAR | Apache-2.0 | Research-oriented instruction data; human review does not guarantee factual accuracy |
| EVE persona seed | Project-authored synthetic draft | This repository | Project file | 12 starter examples only; not enough for robust persona training |

The downloader writes a manifest with dataset names and source URLs. Store the dataset revisions and retrieval date in each experiment report. Do not assume a dataset card license overrides every upstream source's separate terms.
