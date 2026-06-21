---
name: SkillRegistry getAllSkillInfos Filter Rule
description: getAllSkillInfos() includes both official and custom skills; official-only UI must filter by author field.
---

## Rule
When displaying official skills in `SkillManagerScreen` (or any UI), always filter `SkillRegistry.getAllSkillInfos()` by `it.author == "AIRI Official"` before using the result as the official skills list.

## Why
`SkillRegistry.getAllSkillInfos()` (SkillRegistry.kt line ~462) appends all custom skills from `CustomSkillRepository` with a default `author = "builtin"`. Without filtering, custom skills would appear in both the "Official Skills" and "My Custom Skills" sections simultaneously.

## How to apply
```kotlin
// In SkillManagerScreen or any official-skills display:
var officialSkills by remember {
    mutableStateOf(skillRegistry.getAllSkillInfos().filter { it.author == "AIRI Official" })
}

fun reload() {
    officialSkills = skillRegistry.getAllSkillInfos().filter { it.author == "AIRI Official" }
    customSkills   = repository.getAllSkills()
}
```

The custom skills (author = "builtin") should be loaded separately from `CustomSkillRepository.getAllSkills()` and shown in their own section.
