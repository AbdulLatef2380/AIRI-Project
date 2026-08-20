package com.airi.assistant.ai.skills.impl

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.util.Date

class FileManagerSkill(private val context: Context) : AiriSkill {

    override val skillId    = "file_manager"
    override val name       = "file_manager"
    override val description = "List, search, and inspect files in device storage directories"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "PRODUCTIVITY"
    override val iconEmoji  = ""
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess  = SkillModelAccess.NONE

    override val requiredPermissions = listOf(
        "android.permission.READ_EXTERNAL_STORAGE"
    )

    override val parameters = mapOf(
        "action"    to "string — 'list', 'search', or 'storage_info'",
        "directory" to "string (optional) — directory path to list",
        "query"     to "string (optional) — file name pattern to search for",
        "extension" to "string (optional) — filter by file extension"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "list_files",
            description = "List files in a device directory",
            parameters  = mapOf(
                "directory" to SkillParamDef("string", "Directory path (default: Downloads)", required = false),
                "extension" to SkillParamDef("string", "File extension filter (e.g. 'pdf')", required = false)
            )
        ),
        SkillToolDefinition(
            name        = "search_files",
            description = "Search for files by name pattern",
            parameters  = mapOf(
                "query"     to SkillParamDef("string", "File name pattern to search", required = true),
                "directory" to SkillParamDef("string", "Root directory to search in", required = false)
            )
        )
    )

    private val fileKeywords = listOf(
        "files", "folder", "directory", "storage", "download",
        "documents", "pictures", "music", "videos", "list files",
        "show files", "find file", "search files", "how much space"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        fileKeywords.forEach { kw -> if (lower.contains(kw)) score += 12 }
        if (lower.contains("files") && (lower.contains("list") || lower.contains("show") || lower.contains("find"))) score += 25
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        val start  = System.currentTimeMillis()
        val input  = params["input"] as? String ?: ""
        val action = (params["action"] as? String ?: detectAction(input)).lowercase()

        return@withContext when (action) {
            "storage_info" -> getStorageInfo(start)
            "search"       -> searchFiles(params, input, start)
            else           -> listFiles(params, start)
        }
    }

    private fun listFiles(params: Map<String, Any>, start: Long): SkillResult {
        val dirPath = params["directory"] as? String
        val ext     = (params["extension"] as? String)?.lowercase()?.trimStart('.')

        val dir = if (dirPath != null) File(dirPath) else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }

        if (!dir.exists() || !dir.canRead()) {
            return SkillResult(
                false, "",
                "Cannot access directory: ${dir.absolutePath}. Check storage permissions.",
                skillId
            )
        }

        val files = dir.listFiles() ?: emptyArray()
        val filtered = if (ext != null) files.filter { it.extension.lowercase() == ext } else files.toList()
        val sorted   = filtered.sortedByDescending { it.lastModified() }.take(50)

        if (sorted.isEmpty()) {
            return SkillResult(
                success     = true,
                data        = "No files found in ${dir.name}" + if (ext != null) " with extension .$ext" else "",
                skillName   = skillId,
                executionMs = System.currentTimeMillis() - start
            )
        }

        val df = DecimalFormat("#,###")
        val output = buildString {
            append("Files in ${dir.absolutePath}${if (ext != null) " [.$ext only]" else ""}:\n\n")
            sorted.forEach { f ->
                val size = formatSize(f.length())
                val date = Date(f.lastModified())
                val icon = if (f.isDirectory) "" else getFileIcon(f.extension)
                append("$icon ${f.name} ($size) — $date\n")
            }
            if (filtered.size > 50) append("\n...and ${filtered.size - 50} more files.")
        }

        return SkillResult(
            success     = true,
            data        = output,
            skillName   = skillId,
            executionMs = System.currentTimeMillis() - start,
            metadata    = mapOf(
                "directory" to dir.absolutePath,
                "count"     to "${sorted.size}"
            )
        )
    }

    private fun searchFiles(params: Map<String, Any>, input: String, start: Long): SkillResult {
        val query   = (params["query"] as? String ?: extractSearchQuery(input)).lowercase()
        val dirPath = params["directory"] as? String
        val rootDir = if (dirPath != null) File(dirPath)
                      else Environment.getExternalStorageDirectory()

        if (!rootDir.canRead()) {
            return SkillResult(
                false, "",
                "Cannot access storage. Grant storage permission in Settings.",
                skillId
            )
        }

        val results = mutableListOf<File>()
        searchRecursive(rootDir, query, results, maxDepth = 4, maxResults = 30)

        if (results.isEmpty()) {
            return SkillResult(
                success     = true,
                data        = "No files found matching \"$query\"",
                skillName   = skillId,
                executionMs = System.currentTimeMillis() - start
            )
        }

        val output = buildString {
            append("Files matching \"$query\":\n\n")
            results.forEach { f ->
                val icon = if (f.isDirectory) "" else getFileIcon(f.extension)
                append("$icon ${f.name}\n   ${f.absolutePath}\n")
            }
        }

        return SkillResult(
            success     = true,
            data        = output,
            skillName   = skillId,
            executionMs = System.currentTimeMillis() - start,
            metadata    = mapOf("query" to query, "count" to "${results.size}")
        )
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<File>, maxDepth: Int, maxResults: Int) {
        if (maxDepth <= 0 || results.size >= maxResults || !dir.canRead()) return
        dir.listFiles()?.forEach { f ->
            if (f.name.lowercase().contains(query)) results.add(f)
            if (f.isDirectory) searchRecursive(f, query, results, maxDepth - 1, maxResults)
        }
    }

    private fun getStorageInfo(start: Long): SkillResult {
        val stat  = StatFs(Environment.getExternalStorageDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free  = stat.availableBlocksLong * stat.blockSizeLong
        val used  = total - free
        val pct   = if (total > 0) (used * 100 / total) else 0

        return SkillResult(
            success     = true,
            data        = "Storage:\n• Total: ${formatSize(total)}\n• Used: ${formatSize(used)} ($pct%)\n• Free: ${formatSize(free)}",
            skillName   = skillId,
            executionMs = System.currentTimeMillis() - start,
            metadata    = mapOf("total" to formatSize(total), "free" to formatSize(free))
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L          -> "%.1f KB".format(bytes / 1024.0)
        else                    -> "$bytes B"
    }

    private fun getFileIcon(ext: String): String = when (ext.lowercase()) {
        "pdf"  -> ""; "doc", "docx" -> ""; "xls", "xlsx" -> ""
        "jpg", "jpeg", "png", "gif", "webp" -> ""
        "mp4", "mkv", "avi", "mov" -> ""
        "mp3", "m4a", "wav", "flac" -> ""
        "zip", "rar", "7z", "tar" -> ""
        "apk" -> ""; "txt", "md" -> ""; "json" -> ""
        "kt", "java", "py", "js", "ts" -> ""
        else   -> ""
    }

    private fun detectAction(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("search") || lower.contains("find") -> "search"
            lower.contains("space") || lower.contains("storage") -> "storage_info"
            else -> "list"
        }
    }

    private fun extractSearchQuery(input: String): String {
        val regex = Regex("""(?:find|search for?|look for)\s+(.+?)(?:\s+in\s|$)""")
        return regex.find(input.lowercase())?.groupValues?.get(1) ?: ""
    }
}
