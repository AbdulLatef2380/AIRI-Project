# دعم OCR العربي في AIRI

## القرار

يدعم `ocr_analysis` الآن ثلاثة أوضاع للمعامل `language`: `auto`، و`latin`، و`arabic`. يستمر ML Kit في معالجة Latin محليًا. عند اختيار `arabic`، أو عندما لا يعيد ML Kit نصًا في وضع `auto`، تستخدم المهارة Tesseract4Android مع نموذج `ara.traineddata` المضمّن داخل التطبيق.

هذا التصميم لا يرسل الصور أو المستندات إلى خادم خارجي. كما أنه يحافظ على حدود المهارة الحالية للمدخلات وعدد الصفحات وعدد الأحرف، وينسخ نموذج اللغة إلى مساحة التطبيق الخاصة عند أول استخدام فقط.

## سبب عدم استخدام ML Kit للعربية

توثيق Google ML Kit Text Recognition v2 يذكر حزم Latin والصينية والديفاناغارية واليابانية والكورية، ولا يقدّم حزمة عربية رسمية. لذلك لم يتم تسجيل ML Kit على أنه يدعم العربية بشكل صامت أو مضلل.

## المكتبة والنموذج

تم اختيار [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)، وهو غلاف Android حديث لـTesseract 5.5.1 وLeptonica، مع الاعتماد:

```kotlin
implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
```

ويُستخدم نموذج [ara.traineddata](https://github.com/tesseract-ocr/tessdata/blob/main/ara.traineddata) من مستودع Tesseract الرسمي. النموذج المضمّن حجمه نحو 2.5MB، وتُتحقق المهارة من وجوده قبل التهيئة.

## حدود الدقة وتجربة المستخدم

النتائج العربية تعتمد على جودة الصورة، اتجاه النص، الخط، الأعمدة، والتشكيل. المستندات المختلطة عربي/Latin تستخدم اللغة الأساسية المطلوبة حاليًا، ولا تدّعي المهارة فصل اللغتين أو الحفاظ على ترتيب RTL في كل تخطيط معقد. لذلك تظهر metadata طريقة التنفيذ واللغة، وتبقى النتيجة خاضعة لبوابة التحقق العامة إذا دخلت في مسار الوكيل.

## المراجع

1. [Google ML Kit Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
2. [Tesseract4Android README](https://github.com/adaptech-cz/Tesseract4Android)
3. [Tesseract Arabic trained data](https://github.com/tesseract-ocr/tessdata/blob/main/ara.traineddata)
