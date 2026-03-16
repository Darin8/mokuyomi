package eu.kanade.tachiyomi.data.mokuro

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MokuroApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MokuroApiClient
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MokuroApiClient(OkHttpClient(), json)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `submitJob sends POST with correct body and returns job`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"job_id":"j1","chapter_id":"42","state":"pending"}""")
            .setResponseCode(201))

        val result = client.submitJob(server.url("/").toString().trimEnd('/'), "tok", "http://src/ch/1", 42L)

        result.job_id shouldBe "j1"
        result.state shouldBe "pending"

        val req = server.takeRequest()
        req.method shouldBe "POST"
        req.path shouldBe "/jobs"
        req.getHeader("Authorization") shouldBe "Bearer tok"
        req.body.readUtf8() shouldBe """{"source_url":"http://src/ch/1","chapter_id":"42"}"""
    }

    @Test
    fun `getJobStatus sends GET to correct path`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"job_id":"j1","chapter_id":"42","state":"done","progress":1.0,"page_count":10,"error_message":null}"""))

        val result = client.getJobStatus(server.url("/").toString().trimEnd('/'), "tok", "j1")

        result.state shouldBe "done"
        result.page_count shouldBe 10

        val req = server.takeRequest()
        req.path shouldBe "/jobs/j1/status"
        req.getHeader("Authorization") shouldBe "Bearer tok"
    }

    @Test
    fun `pageUrl constructs correct URL with zero-padded filename`() {
        val url = client.pageUrl("http://localhost:8000", "mytoken", "job-uuid", 3)
        url shouldBe "http://localhost:8000/jobs/job-uuid/pages/page_003.html?token=mytoken"
    }
}
