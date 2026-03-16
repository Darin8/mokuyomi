package eu.kanade.tachiyomi.data.mokuro

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import tachiyomi.domain.mokuro.model.MokuroJob

class MokuroPollingJobTest {

    private val getMokuroJob = mockk<GetMokuroJobByChapterId>()
    private val upsertMokuroJob = mockk<UpsertMokuroJob>(relaxed = true)
    private val apiClient = mockk<MokuroApiClient>()
    private val preferences = mockk<MokuroPreferences>()

    private val pendingJob = MokuroJob(1L, "j1", "pending", null, null, "http://srv", 0L)

    @Test
    fun `poll updates state to done when server returns done`() = runTest {
        every { preferences.serverUrl().get() } returns "http://srv"
        every { preferences.token().get() } returns "tok"
        coEvery { getMokuroJob.await(1L) } returns pendingJob
        every {
            apiClient.getJobStatus("http://srv", "tok", "j1")
        } returns MokuroApiClient.JobStatusResponse("j1", "1", "done", 1.0f, 10, null)

        val poller = MokuroJobPoller(getMokuroJob, upsertMokuroJob, apiClient, preferences)
        val result = poller.poll(chapterId = 1L)

        result shouldBe "done"
        coVerify {
            upsertMokuroJob.await(match { it.state == "done" && it.pageCount == 10 })
        }
    }

    @Test
    fun `poll updates state to failed when server returns failed`() = runTest {
        every { preferences.serverUrl().get() } returns "http://srv"
        every { preferences.token().get() } returns "tok"
        coEvery { getMokuroJob.await(1L) } returns pendingJob
        every {
            apiClient.getJobStatus("http://srv", "tok", "j1")
        } returns MokuroApiClient.JobStatusResponse("j1", "1", "failed", 0f, null, "gallery-dl failed")

        val poller = MokuroJobPoller(getMokuroJob, upsertMokuroJob, apiClient, preferences)
        val result = poller.poll(chapterId = 1L)

        result shouldBe "failed"
        coVerify {
            upsertMokuroJob.await(match { it.state == "failed" && it.errorMessage == "gallery-dl failed" })
        }
    }

    @Test
    fun `poll returns timeout and upserts failed state when deadline exceeded`() = runTest {
        every { preferences.serverUrl().get() } returns "http://srv"
        every { preferences.token().get() } returns "tok"
        coEvery { getMokuroJob.await(1L) } returns pendingJob
        every {
            apiClient.getJobStatus("http://srv", "tok", "j1")
        } returns MokuroApiClient.JobStatusResponse("j1", "1", "processing", 0.5f, null, null)

        // Use tiny timeout so test completes immediately
        val poller = MokuroJobPoller(
            getMokuroJob, upsertMokuroJob, apiClient, preferences,
            pollIntervalMs = 1L,
            timeoutMs = 1L,
        )
        val result = poller.poll(chapterId = 1L)

        result shouldBe "timeout"
        coVerify {
            upsertMokuroJob.await(match { it.state == "failed" && it.errorMessage == "Processing timed out" })
        }
    }
}
