#!/usr/bin/env python3
"""Train EVE's own byte-level Arabic GRU language model from teacher-generated data.

The student is initialized randomly. A large teacher is used only to create supervised
examples; no teacher weights, tokenizer, or runtime code are copied into the student.
Requires OPENAI_API_KEY and TensorFlow. The exported TFLite model is a custom artifact.
"""
import json, os, random, time, urllib.request
from pathlib import Path

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "teacher_corpus.jsonl"
OUT = ROOT / "dist"
OUT.mkdir(parents=True, exist_ok=True)
API_URL = os.getenv("EVE_TEACHER_API_URL", "https://api.openai.com/v1/chat/completions")
TEACHER = os.getenv("EVE_TEACHER_MODEL", "gpt-4.1-mini")
API_KEY = os.environ["OPENAI_API_KEY"]
MAX_LEN = 384
HIDDEN = 192
EMBED = 96
BOS, EOS, VOCAB = 257, 258, 259
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
 "التمييز بين الرؤية والتخمين والذاكرة"
]

def teacher(prompt):
    body = json.dumps({
      "model": TEACHER, "temperature": 0.75, "max_tokens": 220,
      "messages": [
        {"role":"system","content":
         "أنت معلّم بيانات لنموذج إيف الروبوتية. اكتب إجابة عربية طبيعية دقيقة ومفيدة. "
         "لا تدّع امتلاك إيف حواساً أو ذكريات غير موجودة. اذكر عدم اليقين عند الحاجة. "
         "اجعل الإجابة مستقلة ومختصرة ومتنوعة، ولا تذكر أنك نموذج معلّم."},
        {"role":"user","content":prompt}
      ]
    }).encode()
    req=urllib.request.Request(API_URL, data=body, headers={
      "Authorization":"Bearer "+API_KEY, "Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        obj=json.loads(r.read())
    return obj["choices"][0]["message"]["content"].strip()

def build_corpus(target=5000):
    existing=[]
    if DATA.exists():
        existing=[json.loads(x) for x in DATA.read_text(encoding="utf-8").splitlines() if x.strip()]
    seen={x["user"] for x in existing}
    templates=[
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
    cases=[
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
    while len(existing)<target:
        index=len(existing)
        topic=SEED_TOPICS[index % len(SEED_TOPICS)]
        variant=index // len(SEED_TOPICS)
        prompt=templates[variant % len(templates)].format(
            topic=topic, case=cases[(variant // len(templates)) % len(cases)]
        )
        if prompt in seen:
            raise RuntimeError("Training prompt collision; refusing to spin on duplicate examples.")
        try:
            answer=teacher(prompt)
            if len(answer)<25:
                raise ValueError("Teacher returned an answer that is too short.")
            row={"user":prompt,"assistant":answer}
            with DATA.open("a",encoding="utf-8") as f:
                f.write(json.dumps(row,ensure_ascii=False)+"\n")
            existing.append(row)
            seen.add(prompt)
            if len(existing)%25==0:
                print(f"teacher examples: {len(existing)}/{target}",flush=True)
        except Exception as e:
            print("teacher retry:",repr(e),flush=True)
            time.sleep(4)
    return existing

def encode(text):
    return [BOS]+[b+1 for b in text.encode("utf-8")]+[EOS]

def train(rows):
    sequences=[]
    for row in rows:
        s=encode("المستخدم: "+row["user"]+"\nإيف: "+row["assistant"])
        if len(s)>MAX_LEN: s=s[:MAX_LEN-1]+[EOS]
        sequences.append(s)
    x=np.zeros((len(sequences),MAX_LEN),dtype=np.int32)
    y=np.zeros((len(sequences),MAX_LEN),dtype=np.int32)
    w=np.zeros((len(sequences),MAX_LEN),dtype=np.float32)
    for i,s in enumerate(sequences):
        n=len(s)-1; x[i,:n]=s[:-1]; y[i,:n]=s[1:]; w[i,:n]=1.0
    tf.keras.utils.set_random_seed(20261114)
    inp=tf.keras.Input((MAX_LEN,),dtype=tf.int32,name="tokens")
    emb=tf.keras.layers.Embedding(VOCAB,EMBED,name="token_embedding")(inp)
    cell=tf.keras.layers.GRUCell(HIDDEN,name="student_gru_cell")
    seq=tf.keras.layers.RNN(cell,return_sequences=True,name="student_gru")(emb)
    logits=tf.keras.layers.Dense(VOCAB,name="student_lm_head")(seq)
    model=tf.keras.Model(inp,logits)
    model.compile(optimizer=tf.keras.optimizers.Adam(2e-3),
      loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True))
    model.fit(x,y,sample_weight=w,batch_size=32,epochs=12,validation_split=0.04,
      callbacks=[tf.keras.callbacks.EarlyStopping(patience=2,restore_best_weights=True)])
    # Export a one-token step model with explicit recurrent state for efficient Android decoding.
    token=tf.keras.Input((1,),dtype=tf.int32,name="token")
    state=tf.keras.Input((HIDDEN,),dtype=tf.float32,name="state")
    e=model.get_layer("token_embedding")(token)
    new_out,new_states=cell(e[:,0,:],states=[state])
    new_state=new_states[0]
    step_logits=model.get_layer("student_lm_head")(new_out)
    step=tf.keras.Model([token,state],[step_logits,new_state])
    converter=tf.lite.TFLiteConverter.from_keras_model(step)
    converter.optimizations=[tf.lite.Optimize.DEFAULT]
    tflite=converter.convert()
    (OUT/"eve_private_student.tflite").write_bytes(tflite)
    vocab={"version":1,"tokenization":"utf8-byte-plus-one","bos":BOS,"eos":EOS,
           "vocab_size":VOCAB,"hidden_size":HIDDEN,"max_len":MAX_LEN,
           "teacher":TEACHER,"student":"EVE-GRU-192-random-init"}
    (OUT/"eve_private_student.json").write_text(json.dumps(vocab,ensure_ascii=False,indent=2))
    model.save(OUT/"training_checkpoint.keras")
    print("EXPORTED",len(tflite),"bytes",flush=True)

if __name__=="__main__":
    corpus=build_corpus(int(os.getenv("EVE_TRAIN_EXAMPLES","5000")))
    train(corpus)
