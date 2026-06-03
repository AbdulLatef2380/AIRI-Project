# REPORT 3 — Override Mismatches
*2026-06-02 23:50:07 — DO NOT MODIFY CODE*

## Summary

- Override issues: **11**


## Override Issues

| Severity | Confidence | File | Line | Symbol | Message | Kotlin Error |
|---|---|---|---|---|---|---|
| **HIGH** | 50% | `integration/TelegramIntegration.kt` | 12 | `TelegramIntegration.disconnect` | 'TelegramIntegration.disconnect()' overrides nothing in 'Integration' | _Kotlin compiler: "'disconnect' overrides nothing"_ |
| **HIGH** | 50% | `integration/TelegramIntegration.kt` | 14 | `TelegramIntegration.state` | 'TelegramIntegration.state()' overrides nothing in 'Integration' | _Kotlin compiler: "'state' overrides nothing"_ |
| **HIGH** | 50% | `integration/NotionIntegration.kt` | 12 | `NotionIntegration.disconnect` | 'NotionIntegration.disconnect()' overrides nothing in 'Integration' | _Kotlin compiler: "'disconnect' overrides nothing"_ |
| **HIGH** | 50% | `integration/NotionIntegration.kt` | 14 | `NotionIntegration.state` | 'NotionIntegration.state()' overrides nothing in 'Integration' | _Kotlin compiler: "'state' overrides nothing"_ |
| **HIGH** | 50% | `integration/GithubIntegration.kt` | 12 | `GithubIntegration.disconnect` | 'GithubIntegration.disconnect()' overrides nothing in 'Integration' | _Kotlin compiler: "'disconnect' overrides nothing"_ |
| **HIGH** | 50% | `integration/GithubIntegration.kt` | 14 | `GithubIntegration.state` | 'GithubIntegration.state()' overrides nothing in 'Integration' | _Kotlin compiler: "'state' overrides nothing"_ |
| **HIGH** | 50% | `ai/skills/impl/CalendarEventsSkill.kt` | 46 | `CalendarEventsSkill.execute` | 'CalendarEventsSkill.execute()' overrides nothing in 'AiriSkill' | _Kotlin compiler: "'execute' overrides nothing"_ |
| **HIGH** | 50% | `ai/skills/impl/GmailAssistantSkill.kt` | 45 | `GmailAssistantSkill.execute` | 'GmailAssistantSkill.execute()' overrides nothing in 'AiriSkill' | _Kotlin compiler: "'execute' overrides nothing"_ |
| **HIGH** | 50% | `ai/skills/impl/GithubGuardianSkill.kt` | 45 | `GithubGuardianSkill.execute` | 'GithubGuardianSkill.execute()' overrides nothing in 'AiriSkill' | _Kotlin compiler: "'execute' overrides nothing"_ |
| **HIGH** | 50% | `ai/skills/impl/DriveSearchSkill.kt` | 44 | `DriveSearchSkill.execute` | 'DriveSearchSkill.execute()' overrides nothing in 'AiriSkill' | _Kotlin compiler: "'execute' overrides nothing"_ |
| **HIGH** | 50% | `ai/skills/impl/TelegramMessengerSkill.kt` | 43 | `TelegramMessengerSkill.execute` | 'TelegramMessengerSkill.execute()' overrides nothing in 'AiriSkill' | _Kotlin compiler: "'execute' overrides nothing"_ |


