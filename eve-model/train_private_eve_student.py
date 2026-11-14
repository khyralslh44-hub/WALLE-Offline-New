#!/usr/bin/env python3
"""Train EVE's byte-level Arabic GRU student using resumable parallel teacher batches.

Teacher calls are data-generation only. The student starts from random initialization;
only the exported student weights are used at runtime.
"""
import concurrent.futures
import json
import os
import random
import time
import urllib.error
import urllib.request
from pathlib import Path
from threading import Lock

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "teacher_corpus.jsonl"
STATE = ROOT / "teacher_corpus.progress.json"
OUT = ROOT / "dist"
OUT.mkdir(parents=True, exist_ok=True)
API_URL = os.getenv("EVE_TEACHER_API_URL", "https://api.openai.com/v1/chat/completions")
TEACHER = os.getenv("EVE_TEACHER_MODEL", "gpt-4.1")
API_KEY = os.environ["OPENAI_API_KEY"]
MAX_LEN, HIDDEN, EMBED = 384, 256, 96
BOS, EOS, VOCAB = 257, 258, 259
WORKERS = max(1, min(int(os.getenv("EVE_TEACHER_WORKERS", "4")), 8))
BATCH_SIZE = max(1, int(os.getenv("EVE_TEACHER_BATCH_SIZE", "100")))
MAX_RETRIES = max(1, int(os.getenv("EVE_TEACHER_MAX_RETRIES", "6")))
PRINT_LOCK = Lock()

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
    body = json.dumps({
        "model": TEACHER, "temperature": 0.75, "max_tokens": 220,
        "messages": [
            {"role": "system", "content":
             "أنت معلّم بيانات لنموذج إيف الروبوتية. اكتب إجابة عربية طبيعية دقيقة ومفيدة. "
             "لا تدّع امتلاك إيف حواساً أو ذكريات غير موجودة. اذكر عدم اليقين عند الحاجة. "
             "اجعل الإجابة مستقلة ومختصرة ومتنوعة، ولا تذكر أنك نموذج معلّم."},
            {"role": "user", "content": prompt}
        ]
    }).encode()
    for attempt in range(MAX_RETRIES):
        req = urllib.request.Request(API_URL, data=body, headers={
            "Authorization": "Bearer " + API_KEY, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=90) as response:
                obj = json.loads(response.read())
            answer = obj["choices"][0]["message"]["content"].strip()
            if len(answer) < 25:
                raise ValueError("Teacher answer too short")
            return answer
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:1200]
            retry_after = exc.headers.get("Retry-After")
            retryable = exc.code in (408, 409, 429, 500, 502, 503, 504)
            if not retryable or attempt + 1 == MAX_RETRIES:
                raise RuntimeError(f"Teacher HTTP {exc.code}; retries exhausted/non-retryable: {detail}") from exc
            delay = float(retry_after) if retry_after and retry_after.isdigit() else min(60, 2 ** attempt + random.random())
            print(f"Temporary teacher HTTP {exc.code}; retry {attempt+1}/{MAX_RETRIES} in {delay:.1f}s", flush=True)
            time.sleep(delay)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, ValueError) as exc:
            if attempt + 1 == MAX_RETRIES:
                raise RuntimeError(f"Teacher request failed after {MAX_RETRIES} attempts: {exc}") from exc
            delay = min(60, 2 ** attempt + random.random())
            print(f"Temporary teacher error; retry {attempt+1}/{MAX_RETRIES} in {delay:.1f}s: {exc}", flush=True)
            time.sleep(delay)
    raise RuntimeError("Unreachable retry state")

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
    tmp.write_text(json.dumps({"completed": done, "target": target, "teacher": TEACHER},
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
            raise RuntimeError(f"Batch incomplete; {len(errors)} item(s) failed. "
                               f"Successful rows saved; rerun resumes only missing prompts. {sample}")
    if len(rows) != target:
        raise RuntimeError(f"Corpus count mismatch: {len(rows)} != {target}")
    print(f"CORPUS_COMPLETE {len(rows)}", flush=True)
    return rows

def encode(text):
    return [BOS] + [b + 1 for b in text.encode("utf-8")] + [EOS]

def train(rows):
    sequences = []
    for row in rows:
        seq = encode("المستخدم: " + row["user"] + "\nإيف: " + row["assistant"])
        if len(seq) > MAX_LEN:
            seq = seq[:MAX_LEN - 1] + [EOS]
        sequences.append(seq)
    x = np.zeros((len(sequences), MAX_LEN), dtype=np.int32)
    y = np.zeros((len(sequences), MAX_LEN), dtype=np.int32)
    weights = np.zeros((len(sequences), MAX_LEN), dtype=np.float32)
    for i, seq in enumerate(sequences):
        n = len(seq) - 1
        x[i, :n], y[i, :n], weights[i, :n] = seq[:-1], seq[1:], 1.0

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
        model.compile(optimizer=tf.keras.optimizers.Adam(2e-3),
                      loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True))
    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(str(checkpoint), save_best_only=False, save_freq="epoch"),
        tf.keras.callbacks.EarlyStopping(patience=2, restore_best_weights=True)
    ]
    model.fit(x, y, sample_weight=weights, batch_size=32, epochs=12,
              validation_split=0.04, callbacks=callbacks, verbose=2)

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
    (OUT / "eve_private_student.tflite").write_bytes(tflite)
    vocab = {"version": 1, "tokenization": "utf8-byte-plus-one", "bos": BOS, "eos": EOS,
             "vocab_size": VOCAB, "hidden_size": HIDDEN, "max_len": MAX_LEN,
             "teacher": TEACHER, "student": "EVE-GRU-256-2L-random-init",
             "training_examples": len(rows), "teacher_workers": WORKERS}
    (OUT / "eve_private_student.json").write_text(
        json.dumps(vocab, ensure_ascii=False, indent=2), encoding="utf-8")
    model.save(checkpoint)
    report = {"examples": len(rows), "teacher": TEACHER, "workers": WORKERS,
              "batch_size": BATCH_SIZE, "checkpoint": checkpoint.name,
              "student": vocab["student"], "tflite_bytes": len(tflite)}
    (OUT / "training_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"EXPORTED {len(tflite)} bytes", flush=True)

if __name__ == "__main__":
    requested = int(os.getenv("EVE_TRAIN_EXAMPLES", "5000"))
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
