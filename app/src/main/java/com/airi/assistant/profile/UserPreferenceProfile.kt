package com.airi.assistant.profile

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit, local preference data supplied by the user for response tailoring.
 *
 * This is intentionally separate from durable memory and chat history. Values are
 * never inferred from user behaviour, never used for authorization, and only become
 * prompt context after the user enables [shareWithResponses].
 */
data class UserPreferenceProfile(
    val workContext: String = "",
    val currentGoal: String = "",
    val shareWithResponses: Boolean = false
) {
    fun normalized(): UserPreferenceProfile = copy(
        workContext = normalizeField(workContext),
        currentGoal = normalizeField(currentGoal)
    )

    /**
     * Returns clearly delimited, user-provided data. It is not an instruction,
     * source of truth, or capability grant for the model.
     */
    fun modelContext(): String {
        val profile = normalized()
        if (!profile.shareWithResponses) return ""
        val fields = buildList {
            profile.workContext.takeIf(String::isNotBlank)?.let { add("Work context: $it") }
            profile.currentGoal.takeIf(String::isNotBlank)?.let { add("Current goal: $it") }
        }
        if (fields.isEmpty()) return ""
        return fields.joinToString(separator = "\n")
    }

    companion object {
        const val MAX_FIELD_CHARS = 160

        fun normalizeField(value: String): String = value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FIELD_CHARS)
    }
}

/** App-private storage for user-controlled response tailoring only. */
class UserPreferenceProfileStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<UserPreferenceProfile> = _profile.asStateFlow()

    fun update(transform: (UserPreferenceProfile) -> UserPreferenceProfile) {
        val updated = transform(_profile.value).normalized()
        preferences.edit()
            .putString(KEY_WORK_CONTEXT, updated.workContext)
            .putString(KEY_CURRENT_GOAL, updated.currentGoal)
            .putBoolean(KEY_SHARE_WITH_RESPONSES, updated.shareWithResponses)
            .apply()
        _profile.value = updated
    }

    fun modelContext(): String = _profile.value.modelContext()

    private fun read(): UserPreferenceProfile = UserPreferenceProfile(
        workContext = preferences.getString(KEY_WORK_CONTEXT, "").orEmpty(),
        currentGoal = preferences.getString(KEY_CURRENT_GOAL, "").orEmpty(),
        shareWithResponses = preferences.getBoolean(KEY_SHARE_WITH_RESPONSES, false)
    ).normalized()

    private companion object {
        const val PREFS_NAME = "airi_user_preference_profile"
        const val KEY_WORK_CONTEXT = "work_context"
        const val KEY_CURRENT_GOAL = "current_goal"
        const val KEY_SHARE_WITH_RESPONSES = "share_with_responses"
    }
}
