package com.airi.assistant.domain.permission

/** Maps platform permission requirements and grants to truthful UI status. */
internal object PermissionDisplayPolicy {
    enum class Status { GRANTED, NOT_GRANTED, NOT_REQUIRED, DEVICE_UNAVAILABLE }

    fun status(requiredOnDevice: Boolean, granted: Boolean, deviceAvailable: Boolean = true): Status = when {
        !requiredOnDevice -> Status.NOT_REQUIRED
        !deviceAvailable -> Status.DEVICE_UNAVAILABLE
        granted -> Status.GRANTED
        else -> Status.NOT_GRANTED
    }

    fun requiredCount(statuses: Collection<Status>): Int =
        statuses.count { it != Status.NOT_REQUIRED && it != Status.DEVICE_UNAVAILABLE }

    fun grantedRequiredCount(statuses: Collection<Status>): Int =
        statuses.count { it == Status.GRANTED }
}
