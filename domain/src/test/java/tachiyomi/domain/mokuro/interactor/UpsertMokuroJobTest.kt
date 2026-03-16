package tachiyomi.domain.mokuro.interactor

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class UpsertMokuroJobTest {
    private val repository = mockk<MokuroJobRepository>(relaxed = true)
    private val upsertMokuroJob = UpsertMokuroJob(repository)

    @Test fun `delegates to repository`() = runTest {
        val job = MokuroJob(1L, "j1", "pending", null, null, "http://x", 0L)
        upsertMokuroJob.await(job)
        coVerify { repository.upsert(job) }
    }
}
