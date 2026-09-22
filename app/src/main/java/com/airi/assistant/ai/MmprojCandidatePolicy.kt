package com.airi.assistant.ai

import java.io.File

/** Deterministic discovery policy for a vision projector sidecar. */
object MmprojCandidatePolicy {
    fun isCandidate(file: File): Boolean {
        val name = file.name.lowercase()
        return file.isFile && name.endsWith(".gguf") &&
            (name.contains("mmproj") || name.contains("mm-proj") || name.contains("projector"))
    }

    fun select(candidates: Iterable<File>): File? = candidates
        .filter(::isCandidate)
        .distinctBy { it.absolutePath }
        .sortedWith(
            compareByDescending<File> { quality(it.name) }
                .thenBy { it.name.lowercase() }
                .thenBy { it.absolutePath },
        )
        .firstOrNull()

    private fun quality(name: String): Int {
        val normalized = name.lowercase()
        return when {
            "f16" in normalized -> 4
            "f32" in normalized -> 3
            "q8" in normalized -> 2
            "q5" in normalized -> 1
            else -> 0
        }
    }
}
