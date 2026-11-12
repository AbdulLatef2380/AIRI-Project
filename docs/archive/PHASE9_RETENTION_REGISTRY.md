# Phase 9 Retention Registry

**الحالة:** `ARCHIVE_REGISTERED / NO_OWNER_APPROVAL_FOR_DELETION`

هذا السجل يحقق متطلب archive الواضح دون نقل الملفات أو حذفها. المسار canonical المقترح لا يصبح نافذًا حتى يوافق مالك المشروع ويُحدّث كل مرجع في commit منفصل.

| المجموعة | canonical المقترح | retained/archive paths | القرار الحالي |
|---|---|---|---|
| Multi-agent design | `docs/MULTI_AGENT_DESIGN.md` | `attached_assets/MULTI_AGENT_DESIGN_1783397036647.md` | canonical موثق؛ المرفق retained تاريخيًا |
| Engineering report phase 2 | `docs/engineering_report_phase2_tasks19_35.md` | `attached_assets/engineering_report_phase2_tasks19_35_1783397036685.md` | retained source |
| Localization missing | لا دمج آلي | `docs/localization_missing_es.md`, `docs/localization_missing_zh.md` | التطابق byte-identical لكن الدلالة/اللغة تحتاج owner review |
| Verification report phase 2 | `docs/verification_report_phase2_tasks19_35.md` | `attached_assets/verification_report_phase2_tasks19_35_1783397036727.md` | retained source |
| Engineering Blueprint | أول نسخة زمنية موثقة في manifest | النسخ الأربع في `attached_assets/` | retained by timestamp |
| Capability 9 | أول نسخة زمنية موثقة في manifest | النسخ الأربع في `attached_assets/` | retained by timestamp |
| Dependency Trees | أول نسخة زمنية موثقة في manifest | النسخ الأربع في `attached_assets/` | retained by timestamp |
| Language-problem pasted context | أول نسخة زمنية موثقة في manifest | النسختان في `attached_assets/` | retained conversation evidence |
| Production-audit handoff | أول نسخة زمنية موثقة في manifest | النسختان في `attached_assets/` | retained conversation evidence |

## عناصر خارج canonicalization

- `SKILL.md` وموارد runtime تبقى في مواقعها المتوقعة.
- `scripts/__pycache__/build_validation.cpython-312.pyc` يبقى متتبعًا حتى مراجعة مالك المشروع وتاريخ الملف.
- JAR/ZIP/BIN لا تُحذف أو تُستبعد عبر wildcard؛ كل ملف يحتاج قرار غرض مستقل.

## إغلاق المعيار

تمثل هذه الوثيقة archive واضحًا للتكرارات والوثائق التاريخية. أي إزالة أو نقل فعلي لاحقًا ليس جزءًا من هذا الإغلاق، ويتطلب موافقة صريحة، تحديث روابط، واختبارًا مستقلًا.
