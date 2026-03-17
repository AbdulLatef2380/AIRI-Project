package com.airi.assistant.tools

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * طبقة اكتشاف الأدوات تلقائياً (Tool Auto-Discovery)
 * تقوم بمسح المجلدات المخصصة للأدوات وقراءة تعريفاتها.
 */
object ToolScanner {

    private const val TAG = "ToolScanner"
    private val gson = Gson()

    /**
     * مسح المجلد واكتشاف الأدوات
     */
    fun scan(context: Context): List<ToolDefinition> {

        val tools = mutableListOf<ToolDefinition>()

        val toolsDir = File(context.getExternalFilesDir(null), "AIRI/tools")

        if (!toolsDir.exists()) {
            Log.i(TAG, "Creating tools directory: ${toolsDir.absolutePath}")
            toolsDir.mkdirs()
            return emptyList()
        }

        toolsDir.listFiles()?.forEach { dir ->

            if (!dir.isDirectory) return@forEach

            val jsonFile = File(dir, "tool.json")

            if (!jsonFile.exists()) return@forEach

            try {

                val jsonContent = jsonFile.readText()

                val tool = gson.fromJson(
                    jsonContent,
                    ToolDefinition::class.java
                )

                tools.add(tool)

                Log.d(TAG, "Discovered tool: ${tool.name}")

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to parse tool ${dir.name}: ${e.message}"
                )
            }
        }

        return tools
    }
}
