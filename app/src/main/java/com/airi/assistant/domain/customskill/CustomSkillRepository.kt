package com.airi.assistant.domain.customskill

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CustomSkillRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("airi_custom_skills", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSkill(skill: CustomSkill) {
        val secured = skill.withEncryptedHeaders()
        val skills = loadRaw().filterNot { it.id == skill.id }.toMutableList()
        skills.add(secured)
        prefs.edit().putString(KEY_SKILLS, gson.toJson(skills.sortedByDescending { it.createdAt })).apply()
    }

    fun getAllSkills(): List<CustomSkill> =
        loadRaw().map { it.withDecryptedHeaders() }

    fun deleteSkill(id: String) {
        val skills = loadRaw().filterNot { it.id == id }
        prefs.edit().putString(KEY_SKILLS, gson.toJson(skills)).apply()
    }

    fun getSkillById(id: String): CustomSkill? =
        getAllSkills().firstOrNull { it.id == id }

    private fun loadRaw(): List<CustomSkill> {
        val json = prefs.getString(KEY_SKILLS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<CustomSkill>>() {}.type
            gson.fromJson<List<CustomSkill>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun CustomSkill.withEncryptedHeaders(): CustomSkill =
        copy(config = config.copy(headers = CustomSkillCrypto.encryptSensitiveHeaders(config.headers)))

    private fun CustomSkill.withDecryptedHeaders(): CustomSkill =
        copy(config = config.copy(headers = CustomSkillCrypto.decryptSensitiveHeaders(config.headers)))

    private companion object {
        private const val KEY_SKILLS = "skills"
    }
}
