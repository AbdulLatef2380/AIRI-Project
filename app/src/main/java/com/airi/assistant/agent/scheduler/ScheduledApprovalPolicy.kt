package com.airi.assistant.agent.scheduler

/**
 * A1 — decides whether an automatic scheduled run must be held for the user's approval.
 *
 * The "confirm / skip" switch on a scheduled task used to be stored and passed along but never
 * read. A background worker has no screen to ask on, so [ApprovalMode.CONFIRM] means:
 * an automatic run that would CHANGE or DELETE data does not execute; the user is told, and
 * approves by running the task manually ("run now"), which is a user-initiated action.
 * [ApprovalMode.SKIP] keeps the previous behaviour (the user chose not to be asked).
 */
object ScheduledApprovalPolicy {

    /**
     * @param approvalMode  the stored mode name; anything unknown is treated as CONFIRM (safe default).
     * @param isManualRun   true when the user started this run themselves.
     * @param requiresWrite true = the target would change data, false = read-only,
     *                      null = the target could not be classified (treated as a write).
     */
    fun shouldHold(approvalMode: String?, isManualRun: Boolean, requiresWrite: Boolean?): Boolean {
        val mode = runCatching { ApprovalMode.valueOf(approvalMode.orEmpty()) }
            .getOrDefault(ApprovalMode.CONFIRM)
        if (mode == ApprovalMode.SKIP) return false
        if (isManualRun) return false
        return requiresWrite != false
    }
}
