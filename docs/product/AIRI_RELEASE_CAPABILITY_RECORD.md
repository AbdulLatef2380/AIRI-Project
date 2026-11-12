# AIRI Release Capability Record

يُنسخ هذا السجل إلى release evidence لكل release candidate، ولا يُعتبر الإصدار مغلقاً قبل تعبئة القيم الفعلية.

## Identity

- Version/tag: `TODO`
- Source commit: `TODO`
- Branch: `TODO`
- Build workflow/run: `TODO`
- Build date (UTC): `TODO`

## Capability review

| Capability | State from matrix | Evidence path/run | User-visible wording reviewed | Owner/sign-off |
|---|---|---|---|---|
| Android chat/local/cloud | `TODO` | `TODO` | `TODO` | `TODO` |
| Desktop history/attachments/preferences | `TODO` | `TODO` | `TODO` | `TODO` |
| Desktop model/skill execution | `PLANNED` unless adapter evidence is attached | `TODO` | `TODO` | `TODO` |
| Scheduling | `IMPLEMENTED` + external verification status | `TODO` | `TODO` | `TODO` |
| Realtime cloud voice | `PLANNED` until full-duplex evidence | `TODO` | `TODO` | `TODO` |

## Mandatory evidence

- [ ] `AIRI_PRODUCT_CAPABILITY_MATRIX.md` was reviewed at this commit.
- [ ] `AIRI_KNOWN_LIMITATIONS.md` was updated in the same release review.
- [ ] Every `RUNTIME_VERIFIED` row names a device/OS, scenario, timestamps, and artifact/log.
- [ ] Placeholder and unavailable capabilities are not presented as completed features.
- [ ] Android Doze/reboot/timezone/notification evidence is attached if scheduling is marketed as reliable background execution.
- [ ] Desktop inference/skills remain `PLANNED` if no real adapter and provider smoke evidence exists.
- [ ] Realtime voice remains `PLANNED` if PCM transport is not connected end-to-end.

## Release decision

- Decision: `TODO: GO / NO-GO / CONDITIONAL`
- Blocking gaps: `TODO`
- External verification accepted by: `TODO`
