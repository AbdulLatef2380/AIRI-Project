package com.airi.assistant.ai.skills.impl

/** Extracts ordered Gmail message IDs from the bounded connector list projection. */
internal object GmailMessageListParser {
    private val messageIdPattern = Regex("(?m)^Message ID:\\s*([A-Za-z0-9_-]+)\\s*$")

    fun messageIds(listData: String, max: Int): List<String> = messageIdPattern
        .findAll(listData)
        .map { it.groupValues[1] }
        .filter(String::isNotBlank)
        .distinct()
        .take(max.coerceIn(1, 20))
        .toList()
}
