package tachiyomi.domain.mokuro.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MokuroJobTest {
    private fun job(state: String) = MokuroJob(
        chapterId = 1L, jobId = "abc", state = state, pageCount = null,
        errorMessage = null, serverUrl = "http://localhost", createdAt = 0L,
    )

    @Test fun `isDone returns true only for done state`() {
        job("done").isDone shouldBe true
        job("pending").isDone shouldBe false
        job("processing").isDone shouldBe false
        job("failed").isDone shouldBe false
    }

    @Test fun `isFailed returns true only for failed state`() {
        job("failed").isFailed shouldBe true
        job("done").isFailed shouldBe false
    }

    @Test fun `isInProgress returns true for pending and processing`() {
        job("pending").isInProgress shouldBe true
        job("processing").isInProgress shouldBe true
        job("done").isInProgress shouldBe false
        job("failed").isInProgress shouldBe false
    }
}
