# AIRI Capability Report Remediation Plan

> **For agentic workers:** تنفيذ أصلي داخل جلسة الإصلاح، مع اختبار كل بند قبل اعتباره مغلقًا.

**Goal:** جعل الأدوات المسجلة إما قابلة للتنفيذ فعليًا مع سياق مالك ودليل قابل للتحقق، أو محجوبة مع سبب واضح قبل وصولها للنموذج.

**Architecture:** توحيد قرار القبول في `AgentLoopToolAdmission` و`AgentLoopSideEffectPolicy`، وإضافة مسار typed للـArtifacts وتوليد الصور من prompt. تبقى الموافقة والمعاينة قبل كل side effect، ويُستهلك `ToolResult.Evidence` في التحقق النهائي بدل اعتبار النص نجاحًا.

**Tech Stack:** Kotlin, Coroutines, Android, Jetpack Compose, JVM tests, existing AIRI tool/trace/artifact contracts.

**Spec:** `/home/ubuntu/upload/pasted_content.txt`

## Global Constraints

- لا تعرض أداة محجوبة في Release للنموذج.
- لا تنفيذ side effect بلا سياق durable وملكية session/run/step وموافقة صريحة.
- لا نجاح بلا Evidence قابل للتحقق.
- لا تسريب prompts أو bytes أو PII في trace.
- كل إصلاح يجب أن يملك regression test أو سببًا موثقًا لعدم توفر seam.

## Review Focus

- Artifacts: كل أداة generation/compression تُقبل فقط بسياق durable وموافقة، وتعيد artifact evidence.
- Terminal: Release لا يعرض `terminal_execute` عندما لا يوجد OS sandbox.
- File write: dispatcher يستلم durable context و`userApproved` بعد الموافقة.
- Verification: FILE_DIFF وartifact evidence لا تُعتبر نجاحًا إلا بعد تحققها.
- Image prompt: prompt يتحول إلى Artifact verified عبر خدمة الوسائط، لا URL ظاهر للمستخدم.
- Accessibility/skills: عدم الجاهزية يظهر قبل الاستدعاء مع next action.

## Tasks

- [x] إضافة typed artifact write decision وإدخال أدوات Artifacts في durable context.
- [x] ربط artifact dispatch بالموافقة والسياق والدليل.
- [x] إصلاح Release terminal admission.
- [x] إصلاح تمرير file-write approval/context.
- [x] تشديد verification حسب evidence.
- [x] إضافة `generate_image_from_prompt` مع حدود prompt/حجم/خصوصية ودليل artifact.
- [x] إضافة اختبارات عقود T1/T2/T3/T5/T8/T9/T10.
- [x] تشغيل static gates؛ اختبارات JVM لم تبدأ لأن Android SDK غير مثبت في البيئة (`ANDROID_HOME` و`sdk.dir` غير موجودين).

## Verification Result

The new `tools/verify_capability_report.py` gate passes 11/11 checks. The existing security gate passes with no secret findings, `verify_core_changes.py` passes 101/101, phase 2 passes 11/11, and phase 3 passes 10/10. The focused Gradle test command was attempted and stopped during project configuration because this sandbox has no Android SDK; this is an environment blocker, not a test assertion result.
