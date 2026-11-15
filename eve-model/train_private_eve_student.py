#!/usr/bin/env python3
"""Distil Arabic dialogue behavior from a local open-weight teacher into EVE's tiny GRU.

The teacher is downloaded only on the training runner. No API key or paid endpoint is used.
This is sequence-level supervised distillation, not logit transfer: EVE's byte tokenizer
and GRU architecture differ from the teacher's tokenizer and Transformer.
"""
import concurrent.futures
import hashlib
import json
import os
import re
from pathlib import Path
from threading import Lock

from huggingface_hub import hf_hub_download
from llama_cpp import Llama
import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "teacher_corpus.jsonl"
STATE = ROOT / "teacher_corpus.progress.json"
OUT = ROOT / "dist"
OUT.mkdir(parents=True, exist_ok=True)

TEACHER_REPO = "ggml-org/Qwen3-0.6B-GGUF"
TEACHER_FILE = "Qwen3-0.6B-Q4_0.gguf"
TEACHER_REVISION = "main"
TEACHER = f"{TEACHER_REPO}:{TEACHER_FILE}"
TEACHER_SHA256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"
TEACHER_THREADS = max(1, min(int(os.getenv("EVE_TEACHER_THREADS", "4")), 8))
TEACHER_CONTEXT = 2048

MAX_LEN, HIDDEN, EMBED = 384, 256, 96
BOS, EOS, VOCAB = 257, 258, 259
WORKERS = max(1, min(int(os.getenv("EVE_TEACHER_WORKERS", "1")), 4))
BATCH_SIZE = max(1, int(os.getenv("EVE_TEACHER_BATCH_SIZE", "50")))
PRINT_LOCK = Lock()
TEACHER_INIT_LOCK = Lock()
TEACHER_INFERENCE_LOCK = Lock()
_TEACHER_MODEL = None

def get_teacher_model():
    """Download once, verify the pinned artifact, and load it locally via llama.cpp."""
    global _TEACHER_MODEL
    if _TEACHER_MODEL is not None:
        return _TEACHER_MODEL
    with TEACHER_INIT_LOCK:
        if _TEACHER_MODEL is not None:
            return _TEACHER_MODEL
        cache_dir = ROOT / ".teacher_cache"
        cache_dir.mkdir(parents=True, exist_ok=True)
        model_path = hf_hub_download(
            repo_id=TEACHER_REPO,
            filename=TEACHER_FILE,
            revision=TEACHER_REVISION,
            local_dir=str(cache_dir),
        )
        digest = hashlib.sha256()
        with open(model_path, "rb") as model_file:
            for chunk in iter(lambda: model_file.read(8 * 1024 * 1024), b""):
                digest.update(chunk)
        actual = digest.hexdigest()
        if actual != TEACHER_SHA256:
            raise RuntimeError(
                f"Open teacher checksum mismatch: expected {TEACHER_SHA256}, got {actual}"
            )
        print(
            f"Verified local open teacher: {TEACHER} bytes={os.path.getsize(model_path)} "
            f"sha256={actual}",
            flush=True,
        )
        _TEACHER_MODEL = Llama(
            model_path=model_path,
            n_ctx=TEACHER_CONTEXT,
            n_threads=TEACHER_THREADS,
            n_batch=128,
            n_gpu_layers=0,
            verbose=False,
        )
    return _TEACHER_MODEL

SEED_TOPICS = [
  "الشخصية والهوية والحدود الأخلاقية لإيف",
  "الحوار العربي الفصيح واللهجات وفهم المقصود",
  "الفضول ومتى تسأل إيف سؤالاً مفيداً",
  "الذاكرة العرضية وكيف تفرق إيف بين الحقيقة والاحتمال",
  "التخطيط وتقسيم الهدف إلى خطوات والتحقق من النتيجة",
  "حل المشكلات اليومية والتفكير السببي",
  "المشاعر وتغير المزاج دون اختلاق حقائق",
  "فهم المشاهد والأشياء مع الاعتراف بعدم اليقين",
  "التعامل مع الخطأ والاعتذار والتعلم منه",
  "ألعاب الشطرنج وإكس أو والاستراتيجية",
  "السلامة والخصوصية والعمل دون إنترنت",
  "التعامل مع المستخدم باحترام دون ادعاء الوعي البشري",
  "الطقس والنهار والليل والأنشطة البشرية من أدلة موثوقة",
  "التعلم من التصحيح وعدم حفظ المعلومة غير المؤكدة",
  "المحادثة الطبيعية والردود المختصرة غير المتكررة",
  "التمييز بين الرؤية والتخمين والذاكرة",
  "أساسيات الرياضيات والحساب الذهني والنسب المئوية",
  "الجبر والهندسة والقياس وحل المسائل",
  "الفيزياء والطاقة والحركة والحرارة",
  "الكيمياء والمواد والتفاعلات والسلامة المنزلية",
  "الأحياء والحيوانات والنباتات والبيئة",
  "الفلك والكواكب والنجوم والفضاء",
  "الجغرافيا والدول والمدن والمناخ",
  "التاريخ والحضارات والأحداث التاريخية",
  "اللغة العربية والنحو والبلاغة والكتابة",
  "الأدب والقصص وتحليل النصوص",
  "اللغات والترجمة واختلاف المعاني",
  "البرمجة والخوارزميات وتصحيح الأخطاء",
  "الروبوتات والإلكترونيات والحساسات",
  "الذكاء الاصطناعي وكيفية تقييم مخرجاته",
  "الإحصاء والاحتمالات وقراءة البيانات",
  "التفكير النقدي واكتشاف المغالطات",
  "التعلم والتعليم وشرح المفاهيم للمبتدئين",
  "الصحة العامة والعادات الآمنة دون تشخيص طبي",
  "الإسعافات الأولية ومتى يجب طلب الطوارئ",
  "الطبخ والمقادير وطرق حفظ الطعام",
  "التنظيف والعناية بالمنزل وإصلاحات بسيطة آمنة",
  "الزراعة المنزلية والعناية بالنباتات",
  "الحيوانات الأليفة واحتياجاتها الأساسية",
  "السفر والتخطيط للرحلات والتنقل",
  "المال والميزانية والادخار والحسابات الأساسية",
  "المشاريع الصغيرة ودراسة التكاليف والمخاطر",
  "القانون ومتى يجب الرجوع إلى مختص",
  "التواصل وحل الخلافات وفهم وجهات النظر",
  "إدارة الوقت والعادات وتحقيق الأهداف",
  "الأمان الرقمي وحماية الحسابات والخصوصية",
  "الفنون والتصميم والموسيقى والإبداع",
  "الاستراتيجيات والألعاب واتخاذ القرار",
  "الطقس القاسي والاستعداد للمخاطر البيئية"
]
TEMPLATES = [
 "اشرح لإيف معرفة عملية عن {topic}. أعطها قاعدة تفكير ومثالاً. الحالة: {case}",
 "اكتب حواراً قصيراً طبيعياً بين المستخدم وإيف حول {topic}. سياق الحوار: {case}",
 "كيف تتصرف إيف إذا واجهت موقفاً غير مؤكد متعلقاً بـ {topic}؟ الحالة: {case}",
 "قدّم مسألة تدريبية لإيف في {topic} مع حلها خطوة بخطوة. المثال: {case}",
 "اكتب إجابة عربية لشخص يسأل إيف عن {topic}. خلفية السؤال: {case}",
 "درّب إيف على التمييز بين الحقيقة والتخمين في {topic}. أعط مثالاً عن {case}",
 "ضع لإيف قاعدة قرار قابلة للتطبيق في {topic} ثم اختبرها على {case}",
 "اكتب ردّين مختلفين لإيف في موقف يخص {topic}: رد جيد ورد خاطئ. الموقف: {case}",
 "اشرح كيف تتعلم إيف من تجربة في {topic} دون حفظ معلومة غير مؤكدة. التجربة: {case}",
 "أنشئ تدريباً قصيراً بالعربية لإيف عن {topic}، مع سؤال المستخدم وإجابة إيف. الموقف: {case}"
]
CASES = [
 "المستخدم مستعجل ويريد جواباً مختصراً", "المستخدم يطلب توضيح السبب لا النتيجة فقط",
 "المعلومات المتاحة ناقصة ويجب طرح سؤال واحد", "ظهرت معلومة جديدة تناقض اعتقاداً سابقاً",
 "إيف ارتكبت خطأ ويجب عليها تصحيحه", "المستخدم يتحدث بلهجة عامية",
 "هناك أكثر من حل ولكل حل تكلفة مختلفة", "يجب التحقق من النتيجة قبل إعلان النجاح",
 "المستخدم يكرر السؤال بصياغة مختلفة", "إيف تلاحظ شيئاً جديداً في محيطها",
 "المستخدم يطلب من إيف تذكر تجربة سابقة", "الموقف حساس ويحتاج إلى الحذر والخصوصية",
 "يجب تقسيم الهدف الكبير إلى خطوات صغيرة", "الوقت والموارد المتاحة محدودان",
 "المستخدم لا يوافق على استنتاج إيف ويقدم دليلاً", "المشهد البصري غير واضح والثقة منخفضة",
 "إيف تشعر بالانزعاج لكن يجب ألا تغيّر الحقائق", "إيف تشعر بالفضول وتحتاج إلى سؤال مفيد",
 "هناك احتمالان متشابهان ويجب التمييز بينهما", "يجب أن تعترف إيف بأنها لا تعرف",
 "المستخدم يطلب مثالاً واقعياً قابلاً للتطبيق", "المستخدم يريد مقارنة خيارين",
 "توجد ذاكرة قديمة ربما لم تعد صحيحة", "إيف تحتاج إلى مراجعة خطتها بعد فشل محاولة",
 "المستخدم يطلب شرحاً مناسباً للمبتدئ", "المستخدم يريد إجابة دقيقة لا مجاملة",
 "يجب احترام قرار المستخدم وعدم الضغط عليه", "المعلومة تحتاج إلى مصدر أو تحقق خارجي",
 "إيف تريد التعلم لكن يجب طلب تأكيد المستخدم", "المشهد يتغير ويجب الاعتماد على الملاحظة الحديثة",
 "هناك خطر محتمل ويجب اختيار التصرف الأكثر أماناً", "المستخدم يختبر إن كانت إيف ستختلق تفاصيل"
]

def make_prompt(index):
    topic = SEED_TOPICS[index % len(SEED_TOPICS)]
    variant = index // len(SEED_TOPICS)
    return TEMPLATES[variant % len(TEMPLATES)].format(
        topic=topic, case=CASES[(variant // len(TEMPLATES)) % len(CASES)])

def teacher(prompt):
    """Generate one Arabic target locally; inference never calls an external API."""
    model = get_teacher_model()
    with TEACHER_INFERENCE_LOCK:
        result = model.create_chat_completion(
            messages=[
                {
                    "role": "system",
                    "content": (
                        "أنت معلّم عربي لنموذج إيف الروبوتية. أجب بالعربية الطبيعية الدقيقة. "
                        "قدّم جواباً عملياً ومختصراً، وميّز الحقيقة من الاحتمال، واعترف بعدم المعرفة. "
                        "لا تدّع أن إيف واعية أو ترى شيئاً لم يُعرض عليها. "
                        "لا تكتب سلسلة التفكير أو وسوم think؛ أخرج الجواب النهائي فقط."
                    ),
                },
                {"role": "user", "content": prompt},
            ],
            temperature=0.65,
            top_p=0.9,
            repeat_penalty=1.08,
            max_tokens=180,
            stop=["<|im_end|>"],
        )
    answer = (result["choices"][0]["message"].get("content") or "").strip()
    answer = re.sub(r"<think>.*?</think>", "", answer, flags=re.DOTALL).strip()
    answer = answer.replace("<|im_end|>", "").strip()
    if len(answer) < 25:
        raise ValueError(f"Open teacher answer too short ({len(answer)} chars)")
    return answer

def load_corpus():
    rows = []
    if DATA.exists():
        with DATA.open(encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                row = json.loads(line)
                if not isinstance(row.get("user"), str) or not isinstance(row.get("assistant"), str):
                    raise ValueError(f"Invalid corpus row at line {line_no}")
                rows.append(row)
    seen = set()
    unique = []
    for row in rows:
        if row["user"] not in seen:
            seen.add(row["user"])
            unique.append(row)
    if len(unique) != len(rows):
        raise ValueError("Duplicate prompts found in existing corpus; refusing unsafe resume.")
    return rows, seen

def save_progress(done, target):
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps({"completed": done, "target": target, "teacher": TEACHER, "teacher_sha256": TEACHER_SHA256},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(STATE)

def build_corpus(target=5000):
    rows, seen = load_corpus()
    if len(rows) > target:
        raise ValueError(f"Existing corpus has {len(rows)} rows, above requested target {target}.")
    pending = [i for i in range(target) if make_prompt(i) not in seen]
    print(f"Resuming corpus: {len(rows)}/{target} complete; {len(pending)} remaining; "
          f"workers={WORKERS}; batch_size={BATCH_SIZE}", flush=True)
    for offset in range(0, len(pending), BATCH_SIZE):
        batch = pending[offset:offset + BATCH_SIZE]
        results = {}
        errors = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            future_map = {pool.submit(teacher, make_prompt(i)): i for i in batch}
            for future in concurrent.futures.as_completed(future_map):
                index = future_map[future]
                try:
                    results[index] = {"user": make_prompt(index), "assistant": future.result()}
                except Exception as exc:
                    errors.append((index, repr(exc)))
        # Persist successful responses even if a sibling request failed.
        with DATA.open("a", encoding="utf-8") as handle:
            for index in sorted(results):
                row = results[index]
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        rows.extend(results[index] for index in sorted(results))
        save_progress(len(rows), target)
        print(f"Saved batch {offset // BATCH_SIZE + 1}: {len(results)}/{len(batch)}; "
              f"total={len(rows)}/{target}", flush=True)
        if errors:
            sample = "; ".join(f"item {i}: {e}" for i, e in errors[:5])
            # Authentication and request-configuration errors are systemic:
            # continuing would waste the remaining teacher calls.
            fatal_markers = ("Teacher HTTP 400", "Teacher HTTP 401",
                             "Teacher HTTP 403", "Teacher HTTP 404")
            if any(marker in detail for _, detail in errors for marker in fatal_markers):
                raise RuntimeError(
                    f"Fatal teacher API/configuration error; {len(errors)} item(s) failed. "
                    f"Successful rows were saved; fix the API configuration and rerun. {sample}"
                )
            print(
                f"Partial batch failure: {len(errors)}/{len(batch)} request(s) failed. "
                f"Successful rows saved; continuing with the next batch. {sample}",
                flush=True
            )
    if len(rows) != target:
        missing = target - len(rows)
        raise RuntimeError(
            f"Corpus incomplete after processing all batches: {len(rows)}/{target}; "
            f"{missing} prompt(s) remain. Successful rows are saved; rerun resumes only missing prompts."
        )
    print(f"CORPUS_COMPLETE {len(rows)}", flush=True)
    return rows

def encode(text):
    return [BOS] + [b + 1 for b in text.encode("utf-8")] + [EOS]

def train(rows):
    # Train only on EVE's answer tokens. The user prompt is context, not a target.
    sequences = []
    answer_starts = []
    for row in rows:
        prompt_bytes = ("المستخدم: " + row["user"] + "\nإيف: ").encode("utf-8")
        answer_bytes = row["assistant"].encode("utf-8")
        payload_limit = MAX_LEN - 2  # BOS and EOS
        # Preserve the end of an unusually long prompt (including the EVE prefix),
        # while reserving at least one byte for the answer.
        prompt_limit = min(len(prompt_bytes), payload_limit - 1)
        if len(prompt_bytes) > prompt_limit:
            prompt_bytes = prompt_bytes[-prompt_limit:]
        answer_bytes = answer_bytes[:payload_limit - len(prompt_bytes)]
        answer_start = 1 + len(prompt_bytes)  # index of first answer byte in seq
        seq = [BOS] + [b + 1 for b in prompt_bytes] + [b + 1 for b in answer_bytes] + [EOS]
        sequences.append(seq)
        answer_starts.append(answer_start)

    x = np.zeros((len(sequences), MAX_LEN), dtype=np.int32)
    y = np.zeros((len(sequences), MAX_LEN), dtype=np.int32)
    weights = np.zeros((len(sequences), MAX_LEN), dtype=np.float32)
    for i, (seq, answer_start) in enumerate(zip(sequences, answer_starts)):
        n = len(seq) - 1
        x[i, :n] = seq[:-1]
        y[i, :n] = seq[1:]
        # y[:, t] predicts seq[t+1]; include answer bytes and the final EOS only.
        first_answer_target = max(0, answer_start - 1)
        answer_target_count = n - first_answer_target
        if answer_target_count <= 0:
            raise ValueError("Training row has no answer target tokens.")
        # Keras reduces over the padded sequence width. Normalize each row's
        # weights so padding does not shrink the effective answer-token loss.
        weights[i, first_answer_target:n] = MAX_LEN / answer_target_count

    # Shuffle deterministically before Keras takes its final 4% as validation data.
    order = np.random.default_rng(20261114).permutation(len(sequences))
    x, y, weights = x[order], y[order], weights[order]

    checkpoint = OUT / "training_checkpoint.keras"
    if checkpoint.exists():
        print("Resuming from saved Keras checkpoint.", flush=True)
        model = tf.keras.models.load_model(checkpoint)
    else:
        tf.keras.utils.set_random_seed(20261114)
        inp = tf.keras.Input((MAX_LEN,), dtype=tf.int32, name="tokens")
        emb = tf.keras.layers.Embedding(VOCAB, EMBED, name="token_embedding")(inp)
        seq1 = tf.keras.layers.RNN(tf.keras.layers.GRUCell(HIDDEN, name="student_gru_cell_1"),
                                   return_sequences=True, name="student_gru_1")(emb)
        seq2 = tf.keras.layers.RNN(tf.keras.layers.GRUCell(HIDDEN, name="student_gru_cell_2"),
                                   return_sequences=True, name="student_gru_2")(seq1)
        logits = tf.keras.layers.Dense(VOCAB, name="student_lm_head")(seq2)
        model = tf.keras.Model(inp, logits)
    # Recompile after loading too, so older checkpoints use the answer-only
    # weighted metric definition with the new assistant-token sample weights.
    optimizer = getattr(model, "optimizer", None) or tf.keras.optimizers.Adam(2e-3)
    model.compile(
        optimizer=optimizer,
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        weighted_metrics=[tf.keras.metrics.SparseCategoricalAccuracy(name="answer_token_accuracy")]
    )

    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(str(checkpoint), save_best_only=False, save_freq="epoch"),
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss", patience=2, restore_best_weights=True
        )
    ]
    history = model.fit(
        x, y, sample_weight=weights, batch_size=32, epochs=12,
        validation_split=0.04, callbacks=callbacks, verbose=2
    )
    val_losses = history.history.get("val_loss", [])
    best_epoch = int(np.argmin(val_losses)) + 1 if val_losses else None
    best_index = best_epoch - 1 if best_epoch is not None else -1
    best_val_loss = float(val_losses[best_index]) if val_losses else None
    best_train_loss = float(history.history["loss"][best_index]) if best_index >= 0 else None
    accuracy_values = history.history.get("answer_token_accuracy", [])
    best_answer_accuracy = (
        float(accuracy_values[best_index]) if best_index >= 0 and accuracy_values else None
    )

    # Export a one-token recurrent step model for Android inference.
    token = tf.keras.Input((1,), dtype=tf.int32, name="token")
    state1 = tf.keras.Input((HIDDEN,), dtype=tf.float32, name="state_1")
    state2 = tf.keras.Input((HIDDEN,), dtype=tf.float32, name="state_2")
    emb = model.get_layer("token_embedding")(token)
    cell1 = model.get_layer("student_gru_1").cell
    cell2 = model.get_layer("student_gru_2").cell
    out1, states1 = cell1(emb[:, 0, :], states=[state1])
    out2, states2 = cell2(out1, states=[state2])
    step_logits = model.get_layer("student_lm_head")(out2)
    step = tf.keras.Model([token, state1, state2], [step_logits, states1[0], states2[0]])
    converter = tf.lite.TFLiteConverter.from_keras_model(step)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite = converter.convert()
    model_file = OUT / "eve_private_student.tflite"
    model_file.write_bytes(tflite)

    # Smoke-test the actual exported artifact with the TFLite interpreter.
    interpreter = tf.lite.Interpreter(model_content=tflite)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    def find_tensor(details, name):
        matches = [item for item in details if name in item["name"]]
        if len(matches) != 1:
            raise RuntimeError(
                f"TFLite smoke test: expected one {name} tensor, found {len(matches)}"
            )
        return matches[0]

    token_input = find_tensor(input_details, "token")
    state1_input = find_tensor(input_details, "state_1")
    state2_input = find_tensor(input_details, "state_2")
    interpreter.set_tensor(token_input["index"], np.array([[BOS]], dtype=np.int32))
    interpreter.set_tensor(state1_input["index"], np.zeros((1, HIDDEN), dtype=np.float32))
    interpreter.set_tensor(state2_input["index"], np.zeros((1, HIDDEN), dtype=np.float32))
    interpreter.invoke()
    smoke_outputs = [interpreter.get_tensor(item["index"]) for item in output_details]
    if not smoke_outputs or any(not np.all(np.isfinite(value)) for value in smoke_outputs):
        raise RuntimeError("TFLite smoke test failed: empty or non-finite output.")
    if not any(value.shape[-1] == VOCAB for value in smoke_outputs if value.ndim >= 2):
        raise RuntimeError("TFLite smoke test failed: vocabulary logits were not exported.")

    vocab = {"version": 1, "tokenization": "utf8-byte-plus-one", "bos": BOS, "eos": EOS,
             "vocab_size": VOCAB, "hidden_size": HIDDEN, "max_len": MAX_LEN,
             "teacher": TEACHER, "teacher_sha256": TEACHER_SHA256, "distillation": "local sequence-level supervised targets", "student": "EVE-GRU-256-2L",
             "training_examples": len(rows), "teacher_workers": WORKERS}
    (OUT / "eve_private_student.json").write_text(
        json.dumps(vocab, ensure_ascii=False, indent=2), encoding="utf-8")
    model.save(checkpoint)
    report = {
        "examples": len(rows), "teacher": TEACHER, "teacher_sha256": TEACHER_SHA256, "distillation": "sequence_level_supervised_targets", "workers": WORKERS,
        "batch_size": BATCH_SIZE, "checkpoint": checkpoint.name,
        "student": vocab["student"], "tflite_bytes": len(tflite),
        "training_objective": "assistant_answer_tokens_only",
        "best_epoch": best_epoch, "best_train_loss": best_train_loss,
        "best_validation_loss": best_val_loss,
        "best_answer_token_accuracy": best_answer_accuracy,
        "validation_split": 0.04, "tflite_smoke_test": "passed",
        "tflite_input_tensors": [item["name"] for item in input_details],
        "tflite_output_tensors": [item["name"] for item in output_details]
    }
    (OUT / "training_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"EXPORTED {len(tflite)} bytes; best_val_loss={best_val_loss}; "
        f"answer_token_accuracy={best_answer_accuracy}; TFLITE_SMOKE_TEST_PASSED",
        flush=True
    )

if __name__ == "__main__":
    get_teacher_model()  # Fail early on model download, checksum, or local-runtime errors.
    requested = int(os.getenv("EVE_TRAIN_EXAMPLES", "1000"))
    if requested < 1:
        raise ValueError("EVE_TRAIN_EXAMPLES must be >= 1")
    corpus = build_corpus(requested)
    report_path = OUT / "training_report.json"
    model_path = OUT / "eve_private_student.tflite"
    if report_path.exists() and model_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if report.get("examples") == requested and report.get("teacher") == TEACHER:
            print("TRAINING_ALREADY_COMPLETE; reusing verified cached export.", flush=True)
        else:
            train(corpus)
    else:
        train(corpus)
