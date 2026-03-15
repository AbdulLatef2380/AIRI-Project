package com.airi.assistant.tools

import android.content.Context
import android.os.Environment
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

    // المسار الافتراضي للأدوات في ذاكرة الهاتف
    private val TOOLS_DIR = File(Environment.getExternalStorageDirectory(), "AIRI/tools/")

    /**
     * مسح المجلد واكتشاف الأدوات
     */
    fun scan(context: Context): List<ToolDefinition> {
        val tools = mutableListOf<ToolDefinition>()

        if (!TOOLS_DIR.exists()) {
            Log.i(TAG, "Tools directory does not exist, creating: ${TOOLS_DIR.absolutePath}")
            TOOLS_DIR.mkdirs()
            return emptyList()
        }

        TOOLS_DIR.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val jsonFile = File(dir, "tool.json")
                if (jsonFile.exists()) {
                    try {
                        val jsonContent = jsonFile.readText()
                        val tool = gson.fromJson(jsonContent, ToolDefinition::class.java)
                        tools.add(tool)
                        Log.d(TAG, "Discovered tool: ${tool.name} in ${dir.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse tool in ${dir.name}: ${e.message}")
                    }
                }
            }
        }

        return tools
    }
}
