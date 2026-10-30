# تشغيل تدريب إيف من الصفر على Kaggle عبر GitHub Actions

جهزنا سير عمل يرسل دفتر التدريب إلى حساب Kaggle ويطلب تشغيله على GPU T4، مع جعل الدفتر خاصاً.

## 1. أضف رمز Kaggle السري مرة واحدة
افتح [أسرار GitHub للمستودع](https://github.com/khyralslh44-hub/WALLE-Offline-New/settings/secrets/actions)، ثم:
- اضغط **New repository secret**.
- الاسم: `KAGGLE_API_TOKEN`
- القيمة: رمز API الذي أنشأته من [إعدادات Kaggle API](https://www.kaggle.com/settings/api) عبر **Create New Token**.
- اضغط **Add secret**.

لا تضف الرمز إلى أي ملف في المشروع، ولا ترسله لي أو لأي شخص. هذا هو السر الوحيد المطلوب لهذا المسار.

## 2. شغّل الإرسال إلى Kaggle
افتح [تشغيل سير تدريب EVE على GitHub Actions](https://github.com/khyralslh44-hub/WALLE-Offline-New/actions/workflows/submit-eve-training-to-kaggle.yml):
1. اضغط **Run workflow**.
2. في خانة `kaggle_username` اكتب اسم المستخدم في Kaggle فقط (ليس البريد الإلكتروني).
3. اضغط **Run workflow** مرة أخرى.

سيقوم GitHub بتجهيز بيانات الدفتر وإرساله إلى Kaggle لطلب تشغيله على GPU T4 مع تفعيل الإنترنت. وسيُنشأ الدفتر خاصاً بحسابك.

## 3. راقب نتيجة التشغيل
بعد نجاح خطوة الإرسال، افتح دفتر Kaggle الخاص بك:
`https://www.kaggle.com/code/اسم-مستخدمك/eve-ar-95m-training`

تأكد من ظهور GPU وCUDA في السجل، ثم راقب خلايا التدريب. قبول الإرسال لا يعني أن التدريب اكتمل؛ لا نعتبر التدريب قد بدأ إلا بعد ظهور سجل GPU وخطوات التدريب، ولا نعتبره مكتملاً إلا بعد إنشاء checkpoint صالح يمكن تنزيله وفحصه.

## ما الذي يتدرّب؟
الدفتر ينشئ tokenizer من البيانات العربية ويبدأ pretraining لنموذج EVE-AR-95M من أوزان عشوائية. لا يستخدم أوزان نموذج جاهز. الهدف الحالي 20,000 خطوة، وقد لا تنتهي في جلسة واحدة بسبب حدود وقت/حصة Kaggle المجانية.

## ملاحظات أمان وحالة
- لا تضع رمز API أو كلمة مرورك في المستودع أو في المحادثة.
- لا ترفع صور الوجوه أو معلومات شخصية إلى مجموعة التدريب.
- ملف الدفتر موجود هنا: [kaggle_train_eve.ipynb](https://github.com/khyralslh44-hub/WALLE-Offline-New/blob/eve-companion-core-smoothness/eve-model/notebooks/kaggle_train_eve.ipynb).
- نجاح فحوصات GitHub لا يعني أن الأوزان دُرّبت، ولا يوجد حالياً checkpoint أو APK يستخدم هذا النموذج.
