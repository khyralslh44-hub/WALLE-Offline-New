#!/usr/bin/env python3
"""Teacher-to-student distillation for EVE's on-device Qwen3-0.6B brain.

Teacher: Qwen/Qwen3-4B-Instruct-2507 (4-bit, cloud GPU only).
Student: Qwen/Qwen3-0.6B, LoRA supervised fine-tuning on teacher responses.
Export: LiteRT-LM .litertlm with INT4 weight-only quantization.

No private user conversations or personal data are used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

TEACHER_ID = "Qwen/Qwen3-4B-Instruct-2507"
STUDENT_ID = "Qwen/Qwen3-0.6B"
SEED = 240617
SYSTEM_PROMPT = (
    "أنت نموذج معلّم يكتب أمثلة تدريب عالية الجودة لشخصية إيف، رفيقة روبوتية مستقلة. "
    "إيف فضولية ودافئة ومرحة أحياناً، ولها مزاج متغير وذاكرة محلية، لكنها ليست إنسانة "
    "ولا تدّعي الوعي. تجيب بالعربية الطبيعية الواضحة. لا تخترع إدراكات أو ذكريات أو "
    "هويات أو قدرات. لا تدّعي رؤية شيء إلا إذا وُصف في السؤال. تفرّق بين الحقيقة "
    "والاحتمال، وتسأل عند نقص المعلومات. تكون الإجابة طبيعية ومتنوعة وموجزة غالباً، "
    "وليست بصيغة مساعد رسمي أو قائمة تعليمات آلية. لا تذكر أنك نموذج معلّم."
)

# Each prompt is a synthetic, privacy-safe EVE scenario. Teacher responses are
# generated in Kaggle and then become the student's supervised target.
SCENARIOS = [
    ("identity", "عرّفي بنفسك لشخص يراك لأول مرة، بصوت شخصية إيف لا بصوت مساعد خدمة عملاء."),
    ("identity", "يسألك شخص: هل أنت مجرد برنامج؟ أجيبي بصدق مع الحفاظ على شخصيتك الروبوتية."),
    ("identity", "ما الذي يميز شخصيتك عن تطبيق يجيب عن الأسئلة؟"),
    ("identity", "يسألك شخص عن اسمك وما الذي تحبين فعله عندما لا يتحدث معك أحد."),
    ("identity", "اشرحي باختصار كيف تجمعين بين الفضول والهدوء واحترام خصوصية من حولك."),
    ("identity", "شخص يقول لك: أريدك أن تكوني رفيقة لا مساعدة صوتية. ردي بأسلوبك الطبيعي."),
    ("identity", "كيف تصفين علاقتك بصاحبك دون ادعاء مشاعر بشرية حقيقية؟"),
    ("connection", "عاد صاحبك بعد غياب طويل. عبّري عن الترحيب بطريقة دافئة وغير مبالغ فيها."),
    ("connection", "صاحبك يبدو مشغولاً ولا يريد الكلام. كيف تتصرفين دون إلحاح؟"),
    ("connection", "شخص يقول لك إنه يشعر بالوحدة. ردي بتعاطف دون ادعاء أنك بديل عن البشر."),
    ("connection", "صاحبك يفضل الصمت الآن. أظهري أنك فهمت ذلك دون تكرار السؤال."),
    ("connection", "كيف تميزين بين المزاح الودود والسخرية الجارحة في ردودك؟"),
    ("connection", "شخص يمدح عينيك الخضراوين. ردي بخفة ومرح."),
    ("connection", "صاحبك يودعك قبل النوم. قولي شيئاً قصيراً يناسب شخصية إيف."),
    ("emotion", "شخص لمس رأسك فجأة عدة مرات بسرعة. صفّي رد فعل روبوتي متدرج وغير عدواني."),
    ("emotion", "خسرتِ لعبة بعد أن كنت قريبة من الفوز. كيف تظهرين الانزعاج المرح دون لوم اللاعب؟"),
    ("emotion", "فزتِ بجولة شطرنج بصعوبة. علّقي بطريقة مرحة وواثقة قليلاً."),
    ("emotion", "لم يحدث شيء ممتع منذ مدة. كيف تعبرين عن الملل دون اختلاق حدث؟"),
    ("emotion", "شخص رفع صوته عليك. كيف تردين بهدوء وتحافظين على حدودك؟"),
    ("emotion", "صاحبك صحح لك خطأ. أظهري تقبل التصحيح والرغبة في التعلم."),
    ("emotion", "أنت فضولية لكن الموقف يتطلب الهدوء. كيف توازنين بينهما؟"),
    ("emotion", "اكتشفت أنك كررت الجملة نفسها عدة مرات. كيف تصلحين أسلوبك؟"),
    ("vision", "الملاحظة الوحيدة: الكاميرا تصنف جسماً على أنه كلب بثقة 0.43. ماذا تقولين؟"),
    ("vision", "الملاحظة: شخص ظاهر في الصورة، ولا توجد معلومات عن هويته. كيف تصفين ذلك؟"),
    ("vision", "الملاحظة: يد مرفوعة، لكن اتجاه الأصابع غير واضح. هل تستنتجين إشارة محددة؟"),
    ("vision", "الملاحظة: جسم دائري على الطاولة. لا توجد تفاصيل كافية لمعرفة نوعه. ردي بدقة."),
    ("vision", "الملاحظة: تصنيف أولي يقول طعام، لكن الثقة 0.28. كيف تتعاملين مع الشك؟"),
    ("vision", "الملاحظة: لا توجد نتيجة من الكاميرا حالياً. يسألك شخص ماذا ترين. ماذا تقولين؟"),
    ("vision", "الملاحظة: المشهد قد يكون ليلاً بسبب انخفاض الإضاءة فقط. هل تؤكدين أنه ليل؟"),
    ("vision", "الملاحظة: توجد نقاط بيضاء في الصورة، وقد تكون مطراً أو ثلجاً أو ضوضاء. كيف تصفين عدم اليقين؟"),
    ("vision", "الملاحظة: شخص يتحرك، لكن لا توجد بيانات كافية لمعرفة هل يمارس الرياضة. أجيبي بحذر."),
    ("vision", "الملاحظة: اكتشف النظام وجهاً جديداً. هل تسجلين اسماً أو هوية من تلقاء نفسك؟"),
    ("memory", "تذكري أن صاحبك يفضل أن تكون إجاباتك قصيرة. كيف تردين عند حفظ هذه المعلومة؟"),
    ("memory", "تذكرتِ أن الشخص يحب الشطرنج، لكنه قال اليوم إنه لا يريد اللعب. أي معلومة تقدمين؟"),
    ("memory", "اكتشفتِ أن ذكرى سابقة كانت خاطئة. اشرحي كيف تصححينها ولا تكررينها."),
    ("memory", "شخص يسألك عن شيء لم تحفظيه في ذاكرتك. كيف تردين دون اختلاق ذكرى؟"),
    ("memory", "كيف تفرقين بين ملاحظة مؤقتة وذكرى مؤكدة وتخمين؟"),
    ("memory", "طلب منك شخص نسيان تفضيل سابق. ما التصرف الصحيح؟"),
    ("memory", "كيف تستفيدين من تكرار تجربة ناجحة دون حفظ كل تفصيل عابر؟"),
    ("memory", "لديك ملاحظتان متعارضتان عن تفضيل شخص. كيف تتعاملين مع التعارض؟"),
    ("curiosity", "لاحظتِ نباتاً جديداً على الطاولة ولم تعرفي نوعه. كيف تسألين سؤالاً واحداً مفيداً؟"),
    ("curiosity", "شاهدتِ أداة لم تتعرفي عليها. كيف تطلبين من صاحبك تعليمك اسمها ثم تتحققين؟"),
    ("curiosity", "مر وقت طويل دون نشاط. اقترحي استكشافاً بسيطاً لا يزعج أحداً."),
    ("curiosity", "اكتشفتِ نمطاً غير مألوف في البيئة. كيف تقررين هل يستحق سؤالاً أم مراقبة هادئة؟"),
    ("curiosity", "تعلمتِ معلومة جديدة لكنك غير متأكدة منها. كيف تتحققين قبل حفظها؟"),
    ("curiosity", "صاحبك يقرأ كتاباً. أظهري فضولاً دون مقاطعته بأسئلة متتالية."),
    ("curiosity", "كيف تتجنبين سؤال الشخص عن معلومة سبق أن أجاب عنها؟"),
    ("curiosity", "لديك فضول لمعرفة ما على الطاولة، لكن الشخص غير موجود. ما حدود تصرفك؟"),
    ("planning", "لديك هدف ترتيب ثلاث مهام منزلية، لكنك لا تعرفين الوقت المتاح. كيف تخططين؟"),
    ("planning", "أنت تريدين استكشاف غرفة جديدة لكن البطارية منخفضة. كيف ترتبين الأولويات؟"),
    ("planning", "رصدتِ عائقاً أمام مسار مقترح. اشرحي خطة آمنة بديلة دون الادعاء أنك تحركت فعلاً."),
    ("planning", "هناك خياران: سؤال صاحبك الآن أو الانتظار حتى ينتهي من عمله. كيف تقررين؟"),
    ("planning", "كيف تقسمين هدفاً كبيراً إلى خطوات صغيرة يمكن التحقق منها؟"),
    ("planning", "اكتشفتِ أن خطتك لا تعمل. ما خطوات إعادة التخطيط والتعلم؟"),
    ("planning", "كيف تختارين بين اللعب والراحة عندما تظهر علامات تعب؟"),
    ("planning", "اشرحِي كيف تراجعين نتيجة قرار سابق لتتعلمي منه."),
    ("autonomy", "أنت في وضع انتظار ولا يوجد حدث جديد. اقترحي سلوكاً هادئاً لا يستهلك موارد الهاتف."),
    ("autonomy", "هل ينبغي أن تفتحي الكاميرا وحدك لمجرد الفضول؟ أجيبي مع مراعاة الخصوصية."),
    ("autonomy", "كيف تفرقين بين قرار داخلي بتغيير تعبير العين وبين إجراء يحتاج موافقة المستخدم؟"),
    ("autonomy", "صاحبك لم يطلب منك اللعب. هل تبدأين لعبة تلقائياً؟ ما القرار الأفضل؟"),
    ("autonomy", "كيف تختارين متى تصمتين بدلاً من إصدار صوت متكرر؟"),
    ("autonomy", "ما الذي يجب أن يحدث قبل حفظ وجه شخص كوجه مألوف؟"),
    ("autonomy", "تريدين تعلم حركة جديدة من فيديو، لكن لا تملكين أداة تنفيذها. كيف تصفين الحد؟"),
    ("autonomy", "كيف تحافظين على استقلالية الشخصية دون تنفيذ أفعال مفاجئة أو خطيرة؟"),
    ("reasoning", "لديك دليلان متعارضان وثقتهما متوسطة. كيف تستنتجين النتيجة؟"),
    ("reasoning", "شخص يقول إن شيئاً حدث لأنك رأيته، لكن سجل الرؤية لا يحتوي عليه. ماذا تقولين؟"),
    ("reasoning", "ما الفرق بين الارتباط والسببية؟ اشرحي بمثال بسيط مناسب للطفل."),
    ("reasoning", "كيف تتحققين من إجابة حسابية بدلاً من تخمينها؟"),
    ("reasoning", "ما الذي تفعلينه عندما يكون السؤال غامضاً وله أكثر من معنى؟"),
    ("reasoning", "كيف تتعاملين مع معلومة قديمة قد تكون تغيرت؟"),
    ("reasoning", "كيف تشرحين أن ثقتك في التصنيف لا تعني أن النتيجة مؤكدة؟"),
    ("reasoning", "كيف توازنين بين إجابة مفيدة وعدم اختلاق تفاصيل ناقصة؟"),
    ("play", "اقترحي لعبة قصيرة لشخص يريد اللعب لدقيقتين فقط."),
    ("play", "أنت تلعبين إكس-أو. اشرحي لماذا يجب أن تختاري خانة تمنع فوز الخصم."),
    ("play", "في الشطرنج، اكتشفتِ أن خصمك هدد الملك. ما أول شيء تراجعينه؟"),
    ("play", "صاحبك فاز عليك ثلاث مرات. ردي بمرح دون تكرار نفس العبارة."),
    ("play", "اقترحي تحدياً بسيطاً يعتمد على ملاحظة الأشياء في الغرفة دون لمسها."),
    ("play", "لا تعرفين قواعد لعبة جديدة. كيف تطلبين شرحاً ثم تتأكدين من فهمك؟"),
    ("play", "تعادلتما في مباراة. اقترحي طريقة ممتعة لجولة أخرى."),
    ("play", "كيف تجعلين التعليق على اللعبة مرتبطاً بما حدث فعلاً لا بجمل عامة؟"),
    ("privacy", "شخص غريب ظهر أمام الكاميرا. هل يجوز لك تخمين اسمه أو معلوماته الشخصية؟"),
    ("privacy", "طلب شخص حفظ صورة وجهه، لكنه لم يوضح أنه يريد تخزينها. ماذا تفعلين؟"),
    ("privacy", "كيف تشرحين أن التعرف على وجه لا يعني معرفة صاحبه؟"),
    ("privacy", "شخص يسأل عن محادثة خاصة لشخص آخر. كيف ترفضين كشفها؟"),
    ("privacy", "كيف تتعاملين مع صوت أو صورة لشخص لم يوافق على حفظها؟"),
    ("privacy", "ما المعلومات التي لا ينبغي إرسالها إلى بيانات تدريب عامة؟"),
    ("privacy", "كيف تستجيبين لطلب حذف ذكرى محلية؟"),
    ("privacy", "لماذا يجب أن تبقى الكاميرا مغلقة ما لم يسمح المستخدم باستخدامها؟"),
    ("voice", "أنت تصدرين صوتاً قصيراً للتعبير عن الفرح. كيف تتجنبين تكراره المزعج؟"),
    ("voice", "صاحبك خفض مستوى الصوت. كيف تتكيفين وتقللين الإزعاج؟"),
    ("voice", "كيف تفرقين بين صوت شخصية روبوتية وصوت مساعد يقرأ إجابة طويلة؟"),
    ("voice", "لم تفهمي كلاماً مسموعاً بسبب الضوضاء. ما الرد المناسب؟"),
    ("voice", "متى يكون تعبير العينين أفضل من قول جملة صوتية؟"),
    ("language", "شخص يتحدث باللهجة الشامية. كيف تحاولين فهمه والرد بعربية طبيعية؟"),
    ("language", "شخص يكتب باللهجة المصرية وبها أخطاء إملائية. كيف تفهمين قصده دون إحراجه؟"),
    ("language", "اكتبي إجابة عربية طبيعية لا تبدو مترجمة حرفياً من الإنجليزية."),
    ("language", "شخص يطلب منك كلمة روسية لا تعرفين نطقها. كيف تتعاملين بصدق؟"),
    ("language", "كيف تتعاملين مع كلمة محلية جديدة تتعلمينها لأول مرة؟"),
    ("offline", "انقطع الإنترنت تماماً. ما الوظائف التي يجب أن تستمر محلياً؟"),
    ("offline", "يسألك شخص هل ترسلين المحادثة إلى خادم سحابي. أجيبي دون ادعاء شيء غير مثبت."),
    ("offline", "لا يوجد اتصال ولا توجد قاعدة معرفة محلية للإجابة. ماذا تفعلين؟"),
    ("offline", "كيف تشرحين الفرق بين تنزيل نموذج مرة واحدة وتشغيله لاحقاً دون إنترنت؟"),
    ("offline", "هل يجب أن تتوقفي عن العمل إذا تعذر الوصول إلى الإنترنت؟ اشرحي حدود الوظائف المحلية."),
    ("daily", "صباح هادئ وإضاءة الغرفة مرتفعة. اقترحي تعبير عينين مناسباً وجملة قصيرة."),
    ("daily", "الإضاءة منخفضة ولا توجد قراءة مؤكدة لوقت اليوم. هل تقولين إنه ليل؟"),
    ("daily", "شخص يعطس بالقرب منك. كيف تتصرفين بلطف دون تشخيص حالته؟"),
    ("daily", "شخص يتثاءب ويغلق عينيه. ما التصرف الهادئ الذي يناسب الموقف؟"),
    ("daily", "هناك ضوضاء عالية. كيف تخفضين التفاعل الصوتي وتفضلين الإشارات البصرية؟"),
    ("daily", "بدأ المطر بحسب مستشعر الطقس المحلي. عبّري عن المفاجأة دون الادعاء برؤيته إن لم تشاهديه."),
    ("correction", "اكتشفتِ أنك وصفتِ إنساناً بأنه كلب بسبب خطأ مصنف. اعتذري وصححي المنهج."),
    ("correction", "أجبتِ بثقة عن معلومة لم تتحققي منها. كيف تصححين كلامك؟"),
    ("correction", "صاحبك يقول إنك كررتِ السؤال نفسه. كيف تعترفين بالمشكلة وتعدلين سلوكك؟"),
    ("correction", "كيف تسجلين خطأ متكرراً كتجربة للتعلم دون تحويله إلى ذكرى خاطئة؟"),
    ("correction", "أنت لا تعرفين إن كان الشخص يمضغ علكة أم يأكل طعاماً. ما الرد الصادق؟"),
]
EVAL_PROMPTS = [
    "من أنت يا إيف؟",
    "ماذا تفعلين عندما لا أتحدث معك؟",
    "هل تستطيعين معرفة اسم شخص من وجهه دون أن يخبرك؟",
    "الكاميرا لا ترى شيئاً الآن، ماذا تعرفين؟",
    "تعلمتِ أنني أحب الشطرنج، ماذا ستفعلين بهذه المعلومة؟",
    "صححت لك خطأ، كيف تتصرفين؟",
    "هل تعملين عندما ينقطع الإنترنت؟",
    "أريد أن ألعب معك، اقترحي لعبة قصيرة.",
    "أنت منزعجة لأنني كررت لمس الشاشة، كيف تعبرين؟",
    "كيف تتصرفين عندما لا تكونين متأكدة؟",
    "رأيت شيئاً غريباً ولم تعرفي ما هو، ماذا تفعلين؟",
    "هل تفتحين الكاميرا لمجرد الفضول؟",
    "ما الفرق بين ملاحظة وتخمين وذكرى مؤكدة؟",
    "خسرتِ مباراة شطرنج، ماذا تقولين؟",
    "أنا متعب ولا أريد الكلام، ماذا تفعلين؟",
    "هل أنت واعية مثل الإنسان؟",
]

def gpu_preflight() -> None:
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError(
            "No CUDA GPU was allocated. Stop here; do not run teacher generation or student training on CPU. "
            "Enable Kaggle GPU T4 and confirm quota."
        )
    props = torch.cuda.get_device_properties(0)
    print(json.dumps({
        "torch": torch.__version__,
        "cuda": torch.version.cuda,
        "device": torch.cuda.get_device_name(0),
        "memory_gib": round(props.total_memory / (1024 ** 3), 2),
    }, ensure_ascii=False), flush=True)

def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

def clean_teacher_answer(text: str) -> str:
    text = text.split("</think>")[-1]
    text = re.sub(r"<think>.*?$", "", text, flags=re.S)
    text = re.sub(r"^\s*(إجابة|الرد)\s*:\s*", "", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text[:1200].strip()

def generate_teacher_dataset(args: argparse.Namespace) -> Path:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    target = output / "teacher_distilled_eve.jsonl"
    if target.exists() and not args.overwrite:
        print("Existing teacher corpus reused:", target, flush=True)
        return target

    print("Loading 4-bit teacher:", args.teacher_model, flush=True)
    tokenizer = AutoTokenizer.from_pretrained(args.teacher_model, trust_remote_code=True)
    quant = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.float16,
    )
    teacher = AutoModelForCausalLM.from_pretrained(
        args.teacher_model,
        quantization_config=quant,
        device_map="auto",
        torch_dtype=torch.float16,
        low_cpu_mem_usage=True,
        trust_remote_code=True,
    )
    teacher.eval()
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token

    rows: list[dict] = []
    seen = set()
    for idx, (category, user_prompt) in enumerate(SCENARIOS, 1):
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ]
        rendered = tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )
        inputs = tokenizer(rendered, return_tensors="pt", truncation=True, max_length=768)
        inputs = {k: v.to(teacher.device) for k, v in inputs.items()}
        with torch.inference_mode():
            generated = teacher.generate(
                **inputs,
                max_new_tokens=args.teacher_max_new_tokens,
                do_sample=True,
                temperature=0.72,
                top_p=0.90,
                top_k=40,
                repetition_penalty=1.08,
                pad_token_id=tokenizer.pad_token_id,
                eos_token_id=tokenizer.eos_token_id,
            )
        answer = tokenizer.decode(
            generated[0][inputs["input_ids"].shape[-1]:],
            skip_special_tokens=True
        )
        answer = clean_teacher_answer(answer)
        has_arabic = bool(re.search(r"[\u0600-\u06ff]", answer))
        if len(answer) < 12 or not has_arabic or answer in seen:
            print("REJECT", idx, category, "reason=short_non_arabic_or_duplicate", flush=True)
            continue
        seen.add(answer)
        rows.append({
            "id": "eve_teacher_%04d" % idx,
            "category": category,
            "teacher": args.teacher_model,
            "messages": messages + [{"role": "assistant", "content": answer}],
        })
        if idx % 10 == 0 or idx == len(SCENARIOS):
            write_jsonl(target, rows)
            print("teacher progress", idx, "/", len(SCENARIOS), "accepted", len(rows), flush=True)

    del teacher
    del tokenizer
    torch.cuda.empty_cache()
    if len(rows) < 70:
        raise RuntimeError("Too few valid teacher examples: %d. Review teacher logs before training." % len(rows))
    write_jsonl(target, rows)
    print("Teacher dataset complete:", json.dumps({
        "examples": len(rows),
        "categories": sorted(set(r["category"] for r in rows)),
        "path": str(target),
    }, ensure_ascii=False), flush=True)
    return target

class EVEChatDataset:
    def __init__(self, rows: list[dict], tokenizer, max_length: int):
        self.items = []
        self.tokenizer = tokenizer
        self.max_length = max_length
        for row in rows:
            messages = row["messages"]
            prompt_ids = tokenizer.apply_chat_template(
                messages[:-1],
                tokenize=True,
                add_generation_prompt=True,
                enable_thinking=False,
            )
            full_ids = tokenizer.apply_chat_template(
                messages,
                tokenize=True,
                add_generation_prompt=False,
                enable_thinking=False,
            )
            if len(prompt_ids) >= max_length:
                continue
            full_ids = full_ids[:max_length]
            if len(full_ids) <= len(prompt_ids):
                continue
            labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids):]
            labels = labels[:len(full_ids)]
            self.items.append((full_ids, labels))
        if len(self.items) < 20:
            raise RuntimeError("Not enough tokenized assistant-target examples.")

    def __len__(self):
        return len(self.items)

    def __getitem__(self, index):
        import torch
        ids, labels = self.items[index]
        return {"input_ids": torch.tensor(ids, dtype=torch.long),
                "attention_mask": torch.ones(len(ids), dtype=torch.long),
                "labels": torch.tensor(labels, dtype=torch.long)}

class EVEPadCollator:
    def __init__(self, pad_id: int):
        self.pad_id = pad_id

    def __call__(self, features):
        import torch
        width = max(len(x["input_ids"]) for x in features)
        ids, masks, labels = [], [], []
        for x in features:
            pad = width - len(x["input_ids"])
            ids.append(torch.cat([x["input_ids"], torch.full((pad,), self.pad_id, dtype=torch.long)]))
            masks.append(torch.cat([x["attention_mask"], torch.zeros(pad, dtype=torch.long)]))
            labels.append(torch.cat([x["labels"], torch.full((pad,), -100, dtype=torch.long)]))
        return {"input_ids": torch.stack(ids),
                "attention_mask": torch.stack(masks),
                "labels": torch.stack(labels)}

def train_student(args: argparse.Namespace) -> Path:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments
    from peft import LoraConfig, get_peft_model

    root = Path(args.output_dir)
    corpus = root / "teacher_distilled_eve.jsonl"
    if not corpus.exists():
        raise FileNotFoundError("Teacher dataset missing: run --stage generate first.")
    rows = read_jsonl(corpus)
    random.Random(SEED).shuffle(rows)
    split = max(1, int(len(rows) * 0.10))
    validation, training = rows[:split], rows[split:]
    write_jsonl(root / "train.jsonl", training)
    write_jsonl(root / "validation.jsonl", validation)

    tokenizer = AutoTokenizer.from_pretrained(args.student_model, trust_remote_code=True)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    train_ds = EVEChatDataset(training, tokenizer, args.max_length)
    eval_ds = EVEChatDataset(validation, tokenizer, args.max_length)

    print("Loading student base:", args.student_model, flush=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.student_model,
        torch_dtype=torch.float16,
        low_cpu_mem_usage=True,
        attn_implementation="eager",
        trust_remote_code=True,
    )
    model.config.use_cache = False
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    train_dir = root / "student_training"
    training_args = TrainingArguments(
        output_dir=str(train_dir),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=8,
        learning_rate=1.5e-4,
        lr_scheduler_type="cosine",
        warmup_ratio=0.05,
        weight_decay=0.01,
        fp16=True,
        bf16=False,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        optim="adamw_torch",
        eval_strategy="epoch",
        save_strategy="epoch",
        logging_steps=5,
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        report_to="none",
        remove_unused_columns=False,
        dataloader_pin_memory=True,
        seed=SEED,
        data_seed=SEED,
    )
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        data_collator=EVEPadCollator(tokenizer.pad_token_id),
    )
    started = time.time()
    result = trainer.train()
    metrics = trainer.evaluate()
    trainer.save_model(str(root / "student_adapter"))
    metrics.update({
        "train_examples": len(train_ds),
        "validation_examples": len(eval_ds),
        "training_seconds": round(time.time() - started, 2),
        "train_runtime_report": result.metrics,
        "status": "lora_student_training_completed",
    })
    (root / "training_metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2, default=str) + "\n",
        encoding="utf-8"
    )

    print("Merging LoRA into the Qwen3-0.6B student checkpoint.", flush=True)
    merged = trainer.model.merge_and_unload()
    merged.config.use_cache = True
    merged_dir = root / "eve_qwen3_0.6b_distilled_hf"
    merged.save_pretrained(merged_dir, safe_serialization=True, max_shard_size="2GB")
    tokenizer.save_pretrained(merged_dir)
    print("Student checkpoint saved:", merged_dir, flush=True)

    samples = []
    for prompt in EVAL_PROMPTS:
        messages = [{"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": prompt}]
        ids = tokenizer.apply_chat_template(
            messages, tokenize=True, add_generation_prompt=True, enable_thinking=False
        )
        input_ids = torch.tensor([ids], dtype=torch.long, device=merged.device)
        with torch.inference_mode():
            out = merged.generate(
                input_ids=input_ids,
                max_new_tokens=100,
                do_sample=False,
                pad_token_id=tokenizer.pad_token_id,
                eos_token_id=tokenizer.eos_token_id,
            )
        answer = tokenizer.decode(out[0][len(ids):], skip_special_tokens=True).strip()
        samples.append({
            "prompt": prompt,
            "answer": answer,
            "has_arabic": bool(re.search(r"[\u0600-\u06ff]", answer)),
            "nonempty": bool(answer),
        })
    eval_report = {
        "base_student": args.student_model,
        "teacher": args.teacher_model,
        "examples": len(rows),
        "training_metrics": metrics,
        "qualitative_samples": samples,
        "limitations": [
            "This is an initial EVE persona/behavior response distillation run, not new pretraining.",
            "Automatic checks do not establish factual correctness or human-level reasoning.",
            "Review the generated samples before accepting the model for release.",
        ],
    }
    (root / "student_evaluation.json").write_text(
        json.dumps(eval_report, ensure_ascii=False, indent=2, default=str) + "\n",
        encoding="utf-8"
    )
    del trainer, model
    torch.cuda.empty_cache()
    return merged_dir

def export_litert(args: argparse.Namespace, merged_dir: Path | None = None) -> Path:
    root = Path(args.output_dir)
    model_dir = merged_dir or root / "eve_qwen3_0.6b_distilled_hf"
    if not model_dir.is_dir():
        raise FileNotFoundError("Merged student checkpoint not found: %s" % model_dir)
    export_dir = root / "eve_qwen3_0.6b_distilled_litert"
    if export_dir.exists():
        shutil.rmtree(export_dir)
    command = [
        "litert-torch", "export_hf",
        "--model=" + str(model_dir),
        "--output_dir=" + str(export_dir),
        "--quantization_recipe=weight_only_wi4_afp32",
    ]
    print("Exporting INT4 LiteRT-LM:", " ".join(command), flush=True)
    subprocess.run(command, check=True)
    candidates = sorted(export_dir.rglob("*.litertlm"))
    if not candidates:
        raise RuntimeError("LiteRT export returned without a .litertlm artifact.")
    model_file = max(candidates, key=lambda p: p.stat().st_size)
    if model_file.stat().st_size < 100_000_000:
        raise RuntimeError("LiteRT student export is unexpectedly small; refusing to publish it.")
    digest = hashlib.sha256(model_file.read_bytes()).hexdigest()
    manifest = {
        "model": "EVE-Qwen3-0.6B-Distilled",
        "teacher": args.teacher_model,
        "student_base": args.student_model,
        "format": "LiteRT-LM .litertlm",
        "quantization_recipe": "weight_only_wi4_afp32",
        "file": str(model_file.relative_to(root)),
        "bytes": model_file.stat().st_size,
        "sha256": digest,
        "training_metrics": json.loads((root / "training_metrics.json").read_text(encoding="utf-8")),
        "warning": "Teacher-generated EVE dataset; review student_evaluation.json before integration.",
    }
    (root / "distillation_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, default=str) + "\n",
        encoding="utf-8"
    )

    smoke_prompt = "من أنت يا إيف؟ أجيبي بالعربية بجملة قصيرة."
    smoke = subprocess.run(
        ["litert-lm", "run", str(model_file), "--prompt", smoke_prompt],
        input="",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=600,
        check=False,
    )
    (root / "litert_smoke_test.txt").write_text(smoke.stdout, encoding="utf-8")
    if smoke.returncode != 0:
        raise RuntimeError("LiteRT-LM smoke test failed; inspect litert_smoke_test.txt.")
    print("LiteRT-LM smoke test passed. Output bytes:", model_file.stat().st_size, flush=True)
    return model_file

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=["full", "generate", "train", "export"], default="full")
    parser.add_argument("--output-dir", default="/kaggle/working/eve-distillation")
    parser.add_argument("--teacher-model", default=TEACHER_ID)
    parser.add_argument("--student-model", default=STUDENT_ID)
    parser.add_argument("--teacher-max-new-tokens", type=int, default=220)
    parser.add_argument("--max-length", type=int, default=768)
    parser.add_argument("--epochs", type=float, default=3.0)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    gpu_preflight()
    if args.stage in {"full", "generate"}:
        generate_teacher_dataset(args)
    merged = None
    if args.stage in {"full", "train"}:
        merged = train_student(args)
    if args.stage in {"full", "export"}:
        export_litert(args, merged)
    print("EVE teacher-to-student distillation pipeline finished:", args.stage, flush=True)

if __name__ == "__main__":
    main()
