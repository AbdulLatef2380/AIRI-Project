package com.airi.assistant.agent.durable

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airi.assistant.core.ServiceLocator
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Provider adapter for an idempotent status lookup; it must never resubmit the original job. */
fun interface ExternalOperationStatusPoller {
    suspend fun poll(operation: ExternalOperation): ExternalOperationPollResult
}

data class ExternalOperationPollResult(
    val status: ExternalOperationStatus,
    val resultReference: String? = null,
    /** Provider's minimum retry delay, for example a normalized Retry-After value. */
    val retryAfterMs: Long? = null
)

data class ExternalOperationSchedulingSummary(
    val scheduled: Int,
    val missingAdapter: Int,
    val needsReconciliation: Int,
    val scheduleFailures: Int = 0
)

/** Runtime adapter registry. Adapters own provider-specific credentials and request validation. */
object ExternalOperationPollerRegistry {
    private val pollers = ConcurrentHashMap<String, ExternalOperationStatusPoller>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(provider: String, poller: ExternalOperationStatusPoller): AutoCloseable {
        require(provider.isNotBlank()) { "provider must not be blank" }
        val key = provider.trim()
        pollers[key] = poller
        listeners.forEach { listener -> runCatching(listener) }
        return AutoCloseable {
            if (pollers.remove(key, poller)) listeners.forEach { listener -> runCatching(listener) }
        }
    }

    fun get(provider: String): ExternalOperationStatusPoller? = pollers[provider.trim()]

    fun addChangeListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

/** Enqueues one immutable due-time snapshot; reconciliation can safely call this repeatedly. */
class ExternalOperationWorkScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun schedule(taskId: String, operationId: String, runAtMs: Long, nowMs: Long) {
        val delayMs = (runAtMs - nowMs).coerceAtLeast(0L)
        val input = Data.Builder()
            .putString(ExternalOperationPollWorker.KEY_TASK_ID, taskId)
            .putString(ExternalOperationPollWorker.KEY_OPERATION_ID, operationId)
            .putLong(ExternalOperationPollWorker.KEY_EXPECTED_POLL_AT, runAtMs)
            .build()
        val request = OneTimeWorkRequestBuilder<ExternalOperationPollWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(taskId, operationId, runAtMs),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun workName(taskId: String, operationId: String, runAtMs: Long): String {
        val source = "$taskId|$operationId|$runAtMs".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(source)
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "airi_external_poll_$digest"
    }
}

/** Performs a provider status lookup only, persists the observation, then yields. */
class ExternalOperationPollWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure()
        val expectedPollAt = inputData.getLong(KEY_EXPECTED_POLL_AT, -1L)
        val manager = ServiceLocator.durableTaskManager
        val task = manager.getTask(taskId) ?: return Result.success()
        if (task.status !in setOf(DurableTaskStatus.WAITING_EXTERNAL, DurableTaskStatus.WAITING_TIMER)) {
            return Result.success()
        }
        val operation = task.externalOperations.firstOrNull { it.operationId == operationId }
            ?: return Result.success()
        if (operation.externalStatus.isTerminal) return Result.success()
        if (operation.nextPollAt != expectedPollAt) {
            val recovery = manager.reconcileExternalOperationWork()
            if (recovery.scheduleFailures > 0) {
                return if (runAttemptCount < MAX_SCHEDULING_RECOVERY_RETRIES) Result.retry() else Result.failure()
            }
            return Result.success()
        }

        val decision = ExternalOperationRecoveryCoordinator.plan(listOf(task), System.currentTimeMillis())
            .actions.firstOrNull { it.operationId == operationId }
        if (decision is ExternalOperationRecoveryAction.NeedsReconciliation) {
            Log.w(TAG, "External operation requires reconciliation; taskId=$taskId operationId=$operationId")
            manager.pauseExternalOperationForReconciliation(taskId, operationId, decision.reason)
            return Result.failure()
        }
        if (decision !is ExternalOperationRecoveryAction.ScheduleStatusCheck) return Result.success()
        if (decision.delayMs > 0L) {
            // WorkManager normally honors the initial delay. If the platform starts early,
            // retain this same request and let its bounded backoff defer the status lookup.
            return Result.retry()
        }

        val poller = ExternalOperationPollerRegistry.get(operation.externalProvider)
            ?: return Result.failure() // A later adapter registration will reconcile this wait.

        val nowMs = System.currentTimeMillis()
        val result = try {
            poller.poll(operation)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val nextAttempt = operation.copy(pollAttemptCount = operation.pollAttemptCount + 1)
            val updated = manager.recordExternalOperationCheck(
                taskId = taskId,
                operationId = operationId,
                status = operation.externalStatus,
                nextPollAt = ExternalOperationPollBackoff.nextPollAtMs(
                    nowMs,
                    ExternalOperationPollBackoff.nextDelayMs(nextAttempt),
                    operation.timeoutPolicy.deadlineAt
                ),
                resultReference = operation.resultReference,
                expectedNextPollAt = expectedPollAt,
                nowMs = nowMs
            )
            if (!updated) {
                Log.i(TAG, "Ignored stale external poll result taskId=$taskId operationId=$operationId")
                return Result.success()
            }
            if (manager.reconcileExternalOperationWork(nowMs).scheduleFailures > 0) {
                return if (runAttemptCount < MAX_SCHEDULING_RECOVERY_RETRIES) Result.retry() else Result.failure()
            }
            return Result.success()
        }

        val nextPollAt = if (result.status.isTerminal) null else {
            val nextAttempt = operation.copy(pollAttemptCount = operation.pollAttemptCount + 1)
            ExternalOperationPollBackoff.nextPollAtMs(
                nowMs,
                ExternalOperationPollBackoff.nextDelayMs(nextAttempt, result.retryAfterMs),
                operation.timeoutPolicy.deadlineAt
            )
        }
        val persisted = manager.recordExternalOperationCheck(
            taskId = taskId,
            operationId = operationId,
            status = result.status,
            nextPollAt = nextPollAt,
            resultReference = result.resultReference,
            expectedNextPollAt = expectedPollAt,
            nowMs = nowMs
        )
        if (!persisted) {
            Log.i(TAG, "Ignored stale external poll result taskId=$taskId operationId=$operationId")
            return Result.success()
        }
        if (!result.status.isTerminal && manager.reconcileExternalOperationWork(nowMs).scheduleFailures > 0) {
            return if (runAttemptCount < MAX_SCHEDULING_RECOVERY_RETRIES) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_EXPECTED_POLL_AT = "expected_poll_at"
        private const val MAX_SCHEDULING_RECOVERY_RETRIES = 3
        private const val TAG = "ExternalOperationPollWorker"
    }
}
