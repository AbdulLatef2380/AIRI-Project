package com.airi.assistant.domain.customskill

enum class SkillPermission {
    READ_ONLY,
    WRITE,
    EXTERNAL_CALL
}

object SkillPermissionEnforcer {

    private val READ_ONLY_METHODS = setOf("GET", "HEAD")

    fun check(permission: SkillPermission, method: String, isOnline: Boolean): PermissionCheckResult {
        return when (permission) {
            SkillPermission.READ_ONLY -> {
                if (method.uppercase() !in READ_ONLY_METHODS) {
                    PermissionCheckResult.Denied(
                        "Permission denied: this skill is READ_ONLY but uses method '$method'. " +
                                "Only GET or HEAD are allowed for read-only skills."
                    )
                } else {
                    PermissionCheckResult.Allowed
                }
            }
            SkillPermission.WRITE -> {
                PermissionCheckResult.Allowed
            }
            SkillPermission.EXTERNAL_CALL -> {
                if (!isOnline) {
                    PermissionCheckResult.Denied(
                        "Permission denied: skill requires network access but the device is offline."
                    )
                } else {
                    PermissionCheckResult.Allowed
                }
            }
        }
    }

    fun deriveFromMethod(method: String): SkillPermission =
        if (method.uppercase() in READ_ONLY_METHODS) SkillPermission.READ_ONLY
        else SkillPermission.EXTERNAL_CALL

    sealed class PermissionCheckResult {
        object Allowed : PermissionCheckResult()
        data class Denied(val reason: String) : PermissionCheckResult()
    }
}
