package com.airi.assistant.accessibility

object ContextIntelligence {

    fun computeRefinedHash(context: String): Int {

        val packageName = Regex("\\[App Package: (.*?)\\]")
            .find(context)
            ?.groupValues?.get(1)
            ?: ""

        val headings = context
            .lines()
            .filter { it.length in 10..120 }
            .take(3)
            .joinToString()

        val criticalSignals = listOf("Exception", "Error", "Fatal", "Warning")
            .filter { context.contains(it, ignoreCase = true) }
            .joinToString()

        val refinedSignature = packageName + headings + criticalSignals

        return refinedSignature.hashCode()
    }
}
