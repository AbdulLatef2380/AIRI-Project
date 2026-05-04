package com.airi.assistant.domain.skill

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SkillManagerBackend — real dynamic skill loading from SKILL.md files.
 *
 * REAL EXECUTION:
 *   1. Scans the app's `files/skills/` directory (internal storage) for
 *      subdirectories each containing a `SKILL.md` file.
 *   2. Parses each SKILL.md into a [DynamicSkill] descriptor: name,
 *      description, trigger keywords, and the raw Markdown body.
 *   3. Exposes [loadedSkills] as the authoritative list consumed by
 *      [SkillManagerScreen] and the agent routing layer.
 *   4. Supports install-time bundled skills from `assets/skills/` — these
 *      are copied to internal storage on first run so updates are possible.
 *
 * FILE FORMAT (SKILL.md header block):
 *   # Skill Name
 *   **Description:** One-line summary.
 *   **Triggers:** keyword1, keyword2, keyword3
 *   ---
 *   (body: full skill instructions for the LLM)
 *
 * WIRING:
 *   - [ServiceLocator.skillManagerBackend] holds the singleton.
 *   - [SkillManagerScreen] reads [loadedSkills] and calls [installSkillMd].
 *   - [SubAgentRegistry] route() checks [matchesAny] before LLM fallback.
 */
class SkillManagerBackend(private val context: Context) {

    companion object {
        private const val TAG          = "SkillManagerBackend"
        private const val SKILLS_DIR   = "skills"
        private const val SKILL_FILE   = "SKILL.md"
        private const val MAX_MD_BYTES = 64_000   // 64 KB per skill file
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** All currently loaded skills — refreshed by [reload]. */
    var loadedSkills: List<DynamicSkill> = emptyList()
        private set

    /**
     * Scan the skills directory and (re)populate [loadedSkills].
     * Safe to call repeatedly; always returns the current state.
     */
    fun reload(): List<DynamicSkill> {
        val dir = skillsDir()
        if (!dir.exists()) {
            dir.mkdirs()
            copyBundledSkills(dir)
        }
        val skills = dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { subDir -> loadSkillFromDir(subDir) }
            ?.sortedBy { it.name }
            ?: emptyList()
        loadedSkills = skills
        Log.i(TAG, "Loaded ${skills.size} dynamic skills from ${dir.absolutePath}")
        return skills
    }

    /**
     * Install a SKILL.md string as a new skill directory.
     *
     * @param skillMdContent Raw SKILL.md text.
     * @param overwriteIfExists Replace an existing skill with the same name.
     * @return The parsed [DynamicSkill] on success, null on parse failure.
     */
    fun installSkillMd(skillMdContent: String, overwriteIfExists: Boolean = true): DynamicSkill? {
        val parsed = parseMd(skillMdContent) ?: run {
            Log.w(TAG, "installSkillMd: parse failed")
            return null
        }
        val slug   = slugify(parsed.name)
        val target = File(skillsDir(), slug)
        if (target.exists() && !overwriteIfExists) {
            Log.d(TAG, "Skill '$slug' already exists — skipping")
            return parsed
        }
        target.mkdirs()
        File(target, SKILL_FILE).writeText(skillMdContent)
        Log.i(TAG, "Installed skill '${parsed.name}' at ${target.absolutePath}")
        reload()
        return parsed
    }

    /**
     * Delete a skill by its directory slug (derived from [DynamicSkill.id]).
     */
    fun uninstallSkill(skillId: String): Boolean {
        val target = File(skillsDir(), skillId)
        val deleted = target.deleteRecursively()
        if (deleted) {
            loadedSkills = loadedSkills.filter { it.id != skillId }
            Log.i(TAG, "Uninstalled skill '$skillId'")
        }
        return deleted
    }

    /**
     * Return the skill whose keywords best match [input], or null if no
     * skill exceeds the minimum relevance threshold.
     */
    fun matchBest(input: String): DynamicSkill? {
        val lower = input.lowercase()
        return loadedSkills
            .map { skill ->
                val score = skill.triggers.count { kw -> lower.contains(kw.lowercase()) }
                skill to score
            }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** True if any loaded skill's triggers match [input]. */
    fun matchesAny(input: String): Boolean = matchBest(input) != null

    /**
     * Build the LLM system-prompt fragment for a matched skill.
     * Injects the full SKILL.md body so the model follows the skill's
     * specific instructions.
     */
    fun buildSkillPromptFragment(skill: DynamicSkill): String =
        """=== Active Skill: ${skill.name} ===
${skill.description}

Instructions:
${skill.body}
=== End of Skill ==="""

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun skillsDir(): File = File(context.filesDir, SKILLS_DIR)

    private fun loadSkillFromDir(dir: File): DynamicSkill? {
        val mdFile = File(dir, SKILL_FILE)
        if (!mdFile.exists() || mdFile.length() > MAX_MD_BYTES) return null
        return runCatching {
            parseMd(mdFile.readText())?.copy(id = dir.name)
        }.getOrElse { e ->
            Log.w(TAG, "Failed to load skill from ${dir.name}: ${e.message}")
            null
        }
    }

    private fun parseMd(content: String): DynamicSkill? {
        val lines = content.lines()
        if (lines.isEmpty()) return null

        val name        = lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim() ?: return null
        val description = lines.firstOrNull { it.startsWith("**Description:**") }
            ?.removePrefix("**Description:**")?.trim() ?: ""
        val triggersRaw = lines.firstOrNull { it.startsWith("**Triggers:**") }
            ?.removePrefix("**Triggers:**")?.trim() ?: ""
        val triggers    = triggersRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }

        // Body = everything after the first horizontal rule
        val separatorIdx = lines.indexOfFirst { it.startsWith("---") || it.startsWith("___") }
        val body         = if (separatorIdx >= 0 && separatorIdx < lines.size - 1) {
            lines.subList(separatorIdx + 1, lines.size).joinToString("\n").trim()
        } else {
            // Fallback: whole file minus the header block
            content.substringAfter(triggersRaw).trim()
        }

        return DynamicSkill(
            id          = slugify(name),
            name        = name,
            description = description,
            triggers    = triggers.ifEmpty { listOf(name.lowercase()) },
            body        = body
        )
    }

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun copyBundledSkills(targetDir: File) {
        runCatching {
            val assetManager = context.assets
            val assetSkills  = assetManager.list("skills") ?: return
            for (skillDir in assetSkills) {
                val mdAsset = "skills/$skillDir/SKILL.md"
                runCatching {
                    val content = assetManager.open(mdAsset).bufferedReader().readText()
                    val dest    = File(targetDir, skillDir)
                    dest.mkdirs()
                    File(dest, SKILL_FILE).writeText(content)
                    Log.d(TAG, "Copied bundled skill: $skillDir")
                }
            }
        }
    }
}

/**
 * A dynamically loaded skill parsed from a SKILL.md file.
 *
 * @param id          Filesystem slug (derived from [name]).
 * @param name        Human-readable skill name (from `# Skill Name` header).
 * @param description One-line summary (from `**Description:**` header).
 * @param triggers    Keywords that activate this skill (from `**Triggers:**`).
 * @param body        Full Markdown body injected into the LLM system prompt.
 */
data class DynamicSkill(
    val id:          String,
    val name:        String,
    val description: String,
    val triggers:    List<String>,
    val body:        String
)
