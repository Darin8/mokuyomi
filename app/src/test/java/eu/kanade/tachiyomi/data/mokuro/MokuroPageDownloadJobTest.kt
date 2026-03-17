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
import java.io.File
import java.nio.file.Files

class MokuroPageDownloadJobTest {

    private val getJob = mockk<GetMokuroJobByChapterId>()
    private val upsertJob = mockk<UpsertMokuroJob>(relaxed = true)
    private val apiClient = mockk<MokuroApiClient>()
    private val preferences = mockk<MokuroPreferences>()

    private val baseJob = MokuroJob(
        chapterId = 1L, jobId = "j1", state = "done",
        pageCount = 3, errorMessage = null, serverUrl = "http://srv", createdAt = 0L,
    )

    @Test
    fun `downloader writes all pages and marks isOfflineAvailable true`() = runTest {
        val dir = Files.createTempDirectory("mokuro_test").toFile()
        every { preferences.serverUrl().get() } returns "http://srv"
        every { preferences.token().get() } returns "tok"
        coEvery { getJob.await(1L) } returns baseJob
        every { apiClient.fetchPageHtml("http://srv", "tok", "j1", any()) } returns "<html>page</html>"

        val downloader = MokuroPageDownloader(getJob, upsertJob, apiClient, preferences)
        val result = downloader.download(chapterId = 1L, filesDir = dir)

        result shouldBe true
        File(dir, "mokuro/j1/page_001.html").readText() shouldBe "<html>page</html>"
        File(dir, "mokuro/j1/page_002.html").readText() shouldBe "<html>page</html>"
        File(dir, "mokuro/j1/page_003.html").readText() shouldBe "<html>page</html>"
        coVerify { upsertJob.await(match { it.isOfflineAvailable }) }

        dir.deleteRecursively()
    }

    @Test
    fun `downloader returns false and cleans up on API error`() = runTest {
        val dir = Files.createTempDirectory("mokuro_test").toFile()
        every { preferences.serverUrl().get() } returns "http://srv"
        every { preferences.token().get() } returns "tok"
        coEvery { getJob.await(1L) } returns baseJob
        every { apiClient.fetchPageHtml("http://srv", "tok", "j1", 1) } returns "<html>p1</html>"
        every { apiClient.fetchPageHtml("http://srv", "tok", "j1", 2) } throws RuntimeException("network error")

        val downloader = MokuroPageDownloader(getJob, upsertJob, apiClient, preferences)
        val result = downloader.download(chapterId = 1L, filesDir = dir)

        result shouldBe false
        File(dir, "mokuro/j1").exists() shouldBe false  // partial files cleaned up

        dir.deleteRecursively()
    }
}
