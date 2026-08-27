package com.airi.assistant.domain.permission

/** Maps platform permission requirements and grants to truthful UI status. */
internal object PermissionDisplayPolicy {
    enum class Status { GRANTED, NOT_GRANTED, NOT_REQUIRED }

    fun status(requiredOnDevice: Boolean, granted: Boolean): Status = when {
        !requiredOnDevice -> Status.NOT_REQUIRED
        granted -> Status.GRANTED
        else -> Status.NOT_GRANTED
    }

    fun requiredCount(statuses: Collection<Status>): Int =
        statuses.count { it != Status.NOT_REQUIRED }

    fun grantedRequiredCount(statuses: Collection<Status>): Int =
        statuses.count { it == Status.GRANTED }
}
