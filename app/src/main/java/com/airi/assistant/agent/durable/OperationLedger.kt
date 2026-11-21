package com.airi.assistant.agent.durable

import java.security.MessageDigest

/** Result of an operation attempt. UNKNOWN is intentionally non-replayable. */
enum class OperationOutcome { SUCCEEDED, FAILED, UNKNOWN }

data class LedgerRecord(
    val operationId: String,
    val taskId: String,
    val runId: String,
    val stepId: String,
    val actionId: String,
    val idempotencyKey: String,
    val payloadHash: String,
    val provider: String,
    val state: DurableOperationStatus = DurableOperationStatus.PREPARED,
    val providerReference: String? = null,
    val safeError: String = "",
    val version: Long = 0L,
)

/** In-memory reference implementation used by JVM tests and small runtimes. */
class OperationLedger {
    private val records = LinkedHashMap<String, LedgerRecord>()

    @Synchronized
    fun prepare(record: LedgerRecord): LedgerRecord? {
        require(record.operationId.isNotBlank() && record.idempotencyKey.isNotBlank())
        require(record.payloadHash.matches(SHA256)) { "payloadHash must be SHA-256" }
        if (records.values.any { it.operationId == record.operationId || it.idempotencyKey == record.idempotencyKey }) return null
        val prepared = record.copy(state = DurableOperationStatus.PREPARED, version = 1L)
        records[record.operationId] = prepared
        return prepared
    }

    /** Compare-and-set transition; stale writers and terminal records fail closed. */
    @Synchronized
    fun cas(operationId: String, expectedVersion: Long, from: DurableOperationStatus, to: DurableOperationStatus,
            providerReference: String? = null, safeError: String = ""): LedgerRecord? {
        val current = records[operationId] ?: return null
        if (current.version != expectedVersion || current.state != from || to == DurableOperationStatus.PREPARED) return null
        val updated = current.copy(
            state = to,
            providerReference = providerReference ?: current.providerReference,
            safeError = safeError.replace(Regex("\\s+"), " ").trim().take(240),
            version = current.version + 1L,
        )
        records[operationId] = updated
        return updated
    }

    @Synchronized fun get(operationId: String): LedgerRecord? = records[operationId]
    @Synchronized fun all(): List<LedgerRecord> = records.values.toList()

    fun prepareOperation(record: LedgerRecord): Boolean = prepare(record) != null
    fun claimOperation(operationId: String): Boolean {
        val current = get(operationId) ?: return false
        return cas(operationId, current.version, DurableOperationStatus.PREPARED, DurableOperationStatus.CLAIMED) != null
    }
    fun completeOperation(operationId: String, status: DurableOperationStatus, providerReference: String? = null, safeError: String = ""): Boolean {
        val current = get(operationId) ?: return false
        return cas(operationId, current.version, DurableOperationStatus.CLAIMED, status, providerReference, safeError) != null
    }

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

/** Narrow seam used by ConnectorRuntimeManager so Android persistence is injectable in JVM tests. */
interface ConnectorOperationLedger {
    fun prepareOperation(record: LedgerRecord): Boolean
    fun claimOperation(operationId: String): Boolean
    fun completeOperation(operationId: String, status: DurableOperationStatus, providerReference: String? = null, safeError: String = ""): Boolean
}

class InMemoryConnectorOperationLedger(private val ledger: OperationLedger = OperationLedger()) : ConnectorOperationLedger {
    override fun prepareOperation(record: LedgerRecord) = ledger.prepareOperation(record)
    override fun claimOperation(operationId: String) = ledger.claimOperation(operationId)
    override fun completeOperation(operationId: String, status: DurableOperationStatus, providerReference: String?, safeError: String) = ledger.completeOperation(operationId, status, providerReference, safeError)
}

/** Adapter keeps DurableTaskManager out of connector/domain APIs while persisting in the task aggregate. */
class DurableTaskConnectorOperationLedger(private val manager: DurableTaskManager) : ConnectorOperationLedger {
    override fun prepareOperation(record: LedgerRecord): Boolean = manager.prepareOperation(record.taskId,
        DurableOperation(record.operationId, record.taskId, record.runId, record.stepId, record.actionId, record.idempotencyKey, record.payloadHash, record.provider))
    override fun claimOperation(operationId: String): Boolean {
        val taskId = manager.tasks.value.asSequence().flatMap { it.operations.asSequence() }.firstOrNull { it.operationId == operationId }?.taskId ?: return false
        return manager.claimOperation(taskId, operationId)
    }
    override fun completeOperation(operationId: String, status: DurableOperationStatus, providerReference: String?, safeError: String): Boolean {
        val taskId = manager.tasks.value.asSequence().flatMap { it.operations.asSequence() }.firstOrNull { it.operationId == operationId }?.taskId ?: return false
        return manager.completeOperation(taskId, operationId, status, providerReference, safeError)
    }
}

/** Stable key and redacted payload digest shared by writable connectors/approvals. */
object OperationIdentity {
    fun idempotencyKey(taskId: String, runId: String, stepId: String, actionId: String): String =
        listOf(taskId, runId, stepId, actionId).joinToString(":")

    fun payloadHash(payload: Map<String, String>): String = sha256(
        payload.toSortedMap().entries.joinToString("&") { "${escape(it.key)}=${escape(it.value)}" }
    )

    fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun escape(value: String) = value.replace("%", "%25").replace("&", "%26").replace("=", "%3D")
}

/** Provider contract for tests and adapters: same key must return the first result. */
fun interface IdempotentConnector {
    fun execute(idempotencyKey: String, payloadHash: String): ConnectorAttempt
}

data class ConnectorAttempt(val outcome: OperationOutcome, val providerReference: String? = null)

class FakeIdempotentConnector(
    private val failAfterAcceptance: Boolean = false,
    private val supportsIdempotency: Boolean = true,
) : IdempotentConnector {
    var invocationCount: Int = 0
        private set
    private val accepted = mutableMapOf<String, ConnectorAttempt>()

    override fun execute(idempotencyKey: String, payloadHash: String): ConnectorAttempt {
        if (supportsIdempotency) accepted[idempotencyKey]?.let { return it }
        invocationCount++
        val result = ConnectorAttempt(OperationOutcome.SUCCEEDED, "fake:$invocationCount")
        if (failAfterAcceptance) {
            val unknown = ConnectorAttempt(OperationOutcome.UNKNOWN, result.providerReference)
            accepted[idempotencyKey] = unknown
            return unknown
        }
        accepted[idempotencyKey] = result
        return result
    }
}
