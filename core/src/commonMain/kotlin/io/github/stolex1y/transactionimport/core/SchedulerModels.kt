package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SchedulerTaskStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("paused")
    PAUSED,
}

@Serializable
enum class SchedulerRunStatus {
    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class SchedulerTask(
    val id: String,
    val name: String,
    @SerialName("account_refs") val accountRefs: List<String>,
    @SerialName("start_date") val startDate: String,
    @SerialName("interval_minutes") val intervalMinutes: Long,
    @SerialName("time_zone") val timeZone: String = "UTC",
    val status: SchedulerTaskStatus = SchedulerTaskStatus.ACTIVE,
    @SerialName("target_session_id") val targetSessionId: String? = null,
    @SerialName("cursor_date") val cursorDate: String? = null,
    @SerialName("last_run_at_epoch_ms") val lastRunAtEpochMs: Long? = null,
    @SerialName("next_run_at_epoch_ms") val nextRunAtEpochMs: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_result") val lastResult: SchedulerAggregateResult? = null,
)

@Serializable
data class SchedulerAggregateResult(
    @SerialName("run_id") val runId: String,
    @SerialName("window_from") val windowFrom: String,
    @SerialName("window_to") val windowTo: String,
    @SerialName("account_count") val accountCount: Int,
    @SerialName("transaction_count") val transactionCount: Int,
    @SerialName("receipt_candidate_count") val receiptCandidateCount: Int,
    @SerialName("receipt_detail_count") val receiptDetailCount: Int,
    @SerialName("enriched_item_count") val enrichedItemCount: Int,
    @SerialName("unmatched_count") val unmatchedCount: Int,
    @SerialName("ambiguous_count") val ambiguousCount: Int,
    @SerialName("target_session_id") val targetSessionId: String,
    val trace: List<SchedulerTraceEvent> = emptyList(),
)

@Serializable
data class SchedulerTraceEvent(
    val stage: String,
    @SerialName("server_id") val serverId: String? = null,
    val tool: String? = null,
    val status: String,
    val detail: String? = null,
)

@Serializable
data class SchedulerRunHistory(
    @SerialName("task_id") val taskId: String,
    @SerialName("run_id") val runId: String,
    val status: SchedulerRunStatus,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
    @SerialName("finished_at_epoch_ms") val finishedAtEpochMs: Long? = null,
    val result: SchedulerAggregateResult? = null,
    val error: String? = null,
)

interface SchedulerRepository {
    suspend fun create(task: SchedulerTask): SchedulerTask
    suspend fun list(): List<SchedulerTask>
    suspend fun get(id: String): SchedulerTask?
    suspend fun update(task: SchedulerTask): SchedulerTask
    suspend fun claimRun(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        claimedAtEpochMs: Long,
    ): SchedulerTask?
    suspend fun renewRunClaim(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        claimedAtEpochMs: Long,
    ): Boolean
    suspend fun bindTargetSession(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        sessionId: String,
    ): SchedulerTask
    suspend fun recordSuccess(
        taskId: String,
        expectedCursorDate: String?,
        result: SchedulerAggregateResult,
        nextCursorDate: String,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        nextRunAtEpochMs: Long,
    ): SchedulerTask
    suspend fun recordFailure(
        taskId: String,
        expectedCursorDate: String?,
        run: SchedulerRunHistory,
    ): SchedulerTask
    suspend fun history(taskId: String, limit: Int = 20): List<SchedulerRunHistory>
}
