package eu.kanade.tachiyomi.data.mokuro

import kotlinx.coroutines.delay
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob

/**
 * Pure polling logic extracted for testability.
 * Returns the final state: "done", "failed", or "timeout".
 */
class MokuroJobPoller(
    private val getJob: GetMokuroJobByChapterId,
    private val upsertJob: UpsertMokuroJob,
    private val client: MokuroApiClient,
    private val preferences: MokuroPreferences,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun poll(chapterId: Long): String {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        var current = getJob.await(chapterId) ?: return "failed"
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val status = client.getJobStatus(serverUrl, token, current.jobId)
                current = current.copy(
                    state = status.state,
                    pageCount = status.pageCount,
                    errorMessage = status.errorMessage,
                )
                upsertJob.await(current)
                when (status.state) {
                    "done", "failed" -> return status.state
                    else -> delay(pollIntervalMs)
                }
            } catch (e: Exception) {
                delay(pollIntervalMs)
            }
        }
        val timedOut = current.copy(state = "failed", errorMessage = "Processing timed out")
        upsertJob.await(timedOut)
        return "timeout"
    }

    companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 35 * 60 * 1_000L
    }
}
