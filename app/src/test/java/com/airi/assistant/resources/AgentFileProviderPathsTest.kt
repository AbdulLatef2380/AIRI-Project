package com.airi.assistant.resources

import com.airi.assistant.agent.loop.tool.AgentFileWriter
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 7: sharing a `write_file` result goes through FileProvider, which throws for any file
 * outside a declared path. This keeps the writer's folder and the provider paths in step.
 */
class AgentFileProviderPathsTest {
    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("res directory not found from ${File(".").absolutePath}")

    @Test
    fun fileProviderDeclaresTheAgentFilesFolder() {
        val xml = File(resDir(), "xml/file_paths.xml").readText(Charsets.UTF_8)
        assertTrue(
            "file_paths.xml must expose ${AgentFileWriter.DIRECTORY_NAME}/",
            Regex("""<files-path\s+name="[^"]+"\s+path="${AgentFileWriter.DIRECTORY_NAME}/"\s*/>""").containsMatchIn(xml),
        )
    }

    @Test
    fun everyFileCardStringExistsInAllLocales() {
        val keys = listOf(
            "agent_file_kind_code", "agent_file_kind_text", "agent_file_kind_data",
            "agent_file_save", "agent_file_share", "agent_file_open",
            "agent_file_saved_downloads", "agent_file_save_failed", "agent_file_save_use_share",
            "agent_file_action_failed", "agent_file_missing", "agent_file_card_description",
        )
        listOf("values", "values-ar", "values-es", "values-zh").forEach { folder ->
            val text = File(resDir(), "$folder/strings.xml").readText(Charsets.UTF_8)
            keys.forEach { key ->
                assertTrue("$folder is missing $key", text.contains("<string name=\"$key\">"))
            }
        }
    }
}
