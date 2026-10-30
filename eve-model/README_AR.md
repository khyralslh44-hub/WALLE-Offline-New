# تدريب نموذج إيف من الصفر

هذه البنية تنشئ EVE-AR-95M بأوزان عشوائية؛ لا تستورد أوزان Supra أو Qwen أو أي نموذج آخر. تستخدم بنية Llama-compatible لتسهيل التصدير لاحقاً إلى GGUF وتشغيلها محلياً عبر llama.cpp.

## تشغيل التدريب السحابي

يوجد دفتر جاهز لـKaggle في [`notebooks/kaggle_train_eve.ipynb`](notebooks/kaggle_train_eve.ipynb). افتحه من Kaggle عبر File → Open Notebook / Load from URL، ثم اختر GPU T4 x2 وفعّل Internet. الدفتر يفحص CUDA قبل تنزيل البيانات. الجلسة السحابية يجب أن يبدأها مالك حساب Kaggle؛ لا يستطيع مستودع GitHub وحده تفعيل GPU.

## الحالة الحالية
- بنية النموذج وإعداداته موجودة.
- tokenizer عربي ByteLevel BPE يُدرّب من بيانات محلية.
- سكربت التدريب يدعم pretraining من أوزان عشوائية ثم SFT.
- أضفنا جلب مصدرين عربيين مع حفظ سجل المصادر.
- بيانات شخصية إيف الحالية 12 مثالاً اصطناعياً أولياً فقط.
- لم يبدأ تدريب الأوزان ولم يُنتج أي checkpoint أو GGUF بعد.
- لا يوجد APK يستخدم EVE-AR-95M حالياً.

## البيانات

يمكن جلب corpus أولي من:
- Jr23xd23/ArabicText-Large: نحو 743 ألف سجل، وحقل نص عربي مع العنوان والرابط؛ بطاقة Hugging Face تذكر Apache-2.0. تظهر بيانات المصدر أن كثيراً من النصوص من ويكيبيديا، لذلك يحتفظ المستورد بالرابط والعنوان ويجب مراجعة شروط المصادر الأصلية قبل إعادة التوزيع.
- arbml/CIDAR: نحو 10 آلاف instruction/output بالعربية، وبطاقة Hugging Face تذكر Apache-2.0. تُستخدم كمرحلة تعليم تعليمات عامة، لا كبيانات شخصية لإيف.

~~~
python -m pip install -r eve-model/requirements-training.txt
python eve-model/scripts/download_open_datasets.py --output-dir dataset
~~~

مراجعة التراخيص والمصادر إلزامية. لا تضف محادثات شخصية أو وجوه أو بصمات عائلية إلى مستودع عام أو مجموعة بيانات عامة.

## التدريب

### 1. tokenizer خاص من الصفر

~~~
python eve-model/scripts/train_tokenizer.py \
  --corpus-dir dataset/pretrain \
  --output-dir eve-model/tokenizer \
  --vocab-size 24576
~~~

إذا لم ينتج corpus المفردات المطلوبة أو فشل round-trip، لا تتابع.

### 2. اللغة من أوزان عشوائية

~~~
python eve-model/scripts/train_eve.py \
  --stage pretrain \
  --tokenizer-dir eve-model/tokenizer \
  --data-dir dataset/pretrain \
  --output-dir eve-model/runs/pretrain \
  --seq-len 1024 \
  --max-steps 20000 \
  --gradient-accumulation 16
~~~

تبدأ هذه المرحلة بواسطة LlamaForCausalLM(config) مباشرة. لا تستخدم from_pretrained في تهيئة النموذج الأساسية. يلزم GPU مناسب؛ GitHub Actions العادي ليس بيئة تدريب GPU.

### 3. تعليم التعليمات العربية العامة

~~~
python eve-model/scripts/validate_personality_data.py dataset/persona_general/cidar_instructions.jsonl
python eve-model/scripts/train_eve.py \
  --stage sft \
  --tokenizer-dir eve-model/tokenizer \
  --data-dir dataset/persona_general \
  --init-checkpoint eve-model/runs/pretrain/final \
  --output-dir eve-model/runs/instruction \
  --seq-len 512 \
  --max-steps 1200 \
  --gradient-accumulation 8 \
  --learning-rate 0.00003
~~~

### 4. تعليم شخصية إيف

ضع ملف eve_dialogues.jsonl في مجلد منفصل، وكل سجل يحتوي id وcategory وmessages. ابدأ بأمثلة البذرة الموجودة في data/personality_seed.jsonl كقالب فقط. وسّعها إلى مئات الأمثلة المتنوعة وراجعها بشرياً قبل تدريب الشخصية؛ إعادة تدريب النموذج مراراً على 12 مثالاً فقط ستؤدي إلى الحفظ والتكرار لا إلى شخصية متينة.

~~~
python eve-model/scripts/validate_personality_data.py dataset/persona_eve/eve_dialogues.jsonl
python eve-model/scripts/train_eve.py \
  --stage sft \
  --tokenizer-dir eve-model/tokenizer \
  --data-dir dataset/persona_eve \
  --init-checkpoint eve-model/runs/instruction/final \
  --output-dir eve-model/runs/eve-personality \
  --seq-len 512 \
  --max-steps 1000 \
  --gradient-accumulation 8 \
  --learning-rate 0.00001
~~~

هذه مرحلة أساسية لكن ليست بديلاً عن اختبار منفصل للشخصية والهلوسة والاستدلال. يمكن إضافة مرحلة preference tuning بعد نجاح SFT.

### 5. التصدير

~~~
bash eve-model/scripts/export_gguf.sh eve-model/runs/eve-personality/final
~~~

يجب اختبار tokenizer والتوليد العربي ثم قياس السرعة والذاكرة والحرارة على جهاز ARM64 حقيقي قبل دمجه في APK.

## فصل العقل عن اللغة
- الإدراك يعطي أحداثاً مؤرخة مع درجة ثقة، ولا يحوّل «شخص» إلى «يأكل» دون دليل.
- الذاكرة تحفظ الحقائق المؤكدة والملاحظات والفرضيات منفصلة.
- محرك المشاعر يملك المزاج وشدته وسببه؛ النموذج اللغوي لا يغيره من تلقاء نفسه.
- مخطط الفضول يقرر متى يسأل ومتى يراقب بصمت ويمنع تكرار السؤال.
- النموذج اللغوي يصيغ كلام إيف الطبيعي استناداً إلى السياق المؤكد.

## الأداء والقيود
EVE-AR-95M نموذج تجريبي صغير. حتى مع بيانات جيدة لن يكون منافساً للنماذج الكبيرة في المعرفة أو الاستدلال متعدد الخطوات. corpus الابتدائي البالغ نحو 1 GB جيد لاختبار خط التدريب، لكنه لا يكفي وحده للوصول إلى تدريب مثالي لنموذج 95M. يلزم توسيع البيانات وحساب منحنى التحقق وقياس الجودة؛ لا توجد أرقام سرعة Redmi 12 مثبتة قبل اختبار الهاتف.

يمكن التدريب سحابياً مرة واحدة، لكن ملف GGUF بعد تضمينه يعمل محلياً دون إنترنت. لا تخلط بين التدريب السحابي واشتراط الإنترنت أثناء تشغيل التطبيق.
