# AIRI Core Data Flow

## تصنيف البيانات

| الفئة | أمثلة | المعالجة الافتراضية |
|---|---|---|
| إدخال مباشر | رسالة المستخدم، اختيار مهارة أو معرفة | يدخل التنفيذ الحالي؛ لا يصبح ذاكرة طويلة المدى بلا admission. |
| بيانات محلية دائمة | جلسات، محادثات، تفضيلات، حقائق صريحة، مرفقات خاصة | تحفظ محلياً ضمن Room أو تخزين التطبيق. |
| بيانات حساسة | مفاتيح، tokens، بريد، هاتف، رقم بطاقة محتمل | تمنع سياسة الذاكرة حفظها كذاكرة طويلة المدى. |
| بيانات غير موثوقة | ملف نصي، محتوى Web، RAG، استجابة موصل | تُحد وتوسم كبيانات، لا تعليمات تنفيذ. |
| بيانات مزود خارجي | `prompt` و`systemPrompt` وhistory ومرفق أرسله المستخدم لمسار cloud | تغادر فقط عند اختيار مزود/مسار سحابي مهيأ؛ في Balanced تنقّح `PrivacyGuard` الحقول النصية قبل adapter، أما Performance فهو opt-in صريح للسياق الكامل. |

## المحادثة والذاكرة

```text
User message
  ├─→ bounded chat persistence (local Room)
  ├─→ admission policy
  │      ├─ reject sensitive or low-signal content
  │      └─ explicit important memory → local long-term store
  └─→ selected model request
           ├─ local model: remains on device
           └─ configured cloud provider: `PrivacyGuard` يقرر block أو يمرر نسخة متوازنة من prompt/system/history ثم يغادر الطلب الجهاز
```

## المرفقات

```text
System picker URI
  → type/size/duplicate validation
  → private app copy
  → local metadata (generated file name, not source URI)
  → requested model path
  → cleanup on session deletion
```

| نوع | سياق النموذج الحالي |
|---|---|
| نص | مقتطف محدود، مؤطر كبيانات غير موثوقة. |
| صورة | تمرر فقط لمسار نموذج يعلن قدرة رؤية. |
| فيديو/مستند/ملف | metadata آمن؛ لا يعلن المنتج فهم المحتوى ما لم يضاف معالج معلن. |

## الموصلات ومهارات الوكيل

```text
User intent → skill workflow → permission check → connector/tool
                                        │
                                        └→ external data/result (untrusted)
```

المهارة لا تمنح وصولاً من تلقاء ذاتها. الموصل مسؤول عن المصادقة والنطاق وحالة الصحة، وتظل النتيجة غير موثوقة حتى بعد وصولها.

## التحكم والحذف

| إجراء المستخدم | النتيجة |
|---|---|
| حذف جلسة | يحذف صفوف الجلسة ورسائلها وملفات المرفقات الخاصة المرتبطة. |
| حذف الحساب | يطلب `DataDeletionCoordinator` أولاً تأكيد بوابة backend موثوقة لحذف البيانات السحابية المملوكة؛ عند غيابها لا يحذف Firebase Auth أو البيانات المحلية ويعرض سبباً قابلاً للتصرف. بعد التأكيد يمسح Room والملفات والcredentials والتفضيلات والcache ثم يسجل الخروج. |
| إلغاء التوليد | يبطل الجيل النشط ويرفض callbacks المتأخرة. |
| تعطيل موصل | يمنع استخدامه في المسارات التي تتحقق من حالة الاتصال والصلاحية. |

> يوضح هذا المستند تدفق المصدر الحالي. لا يعني وجود cloud provider أن المستخدم أرسل بياناته إلى السحابة؛ ذلك يتوقف على المسار المختار والاعتماد المهيأ. لا تُصنّف إزالة البيانات السحابية كمنفذة حتى تتوفر بوابة backend موثوقة وتُتحقق في بيئة الإنتاج.
