# EVE private language model

This pipeline creates a new EVE student model with randomly initialized weights. A large
teacher is queried only to produce Arabic supervised examples. The student is trained
from scratch on those examples; the teacher's weights, tokenizer, and inference runtime
are not included in the student artifact.

## Output
- \`dist/eve_private_student.tflite\`: custom GRU-192 one-step language model
- \`dist/eve_private_student.json\`: tokenizer and model metadata
- \`dist/teacher_corpus.jsonl\`: teacher-generated training corpus
- \`dist/training_checkpoint.keras\`: training checkpoint

## Training
Run \`python train_private_eve_student.py\` with \`OPENAI_API_KEY\` set.
Optional environment variables: \`EVE_TEACHER_MODEL\`, \`EVE_TEACHER_API_URL\`,
and \`EVE_TRAIN_EXAMPLES\` (default 5,000).

The model is not considered ready for EVE until it passes Arabic quality, hallucination,
latency, memory, and Redmi 12 offline tests. This training script does not claim those
tests have passed.
