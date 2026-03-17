package eu.kanade.tachiyomi.data.mokuro

import io.kotest.matchers.shouldBe
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
    fun `submitJob sends POST with correct body and returns job`() {
        server.enqueue(MockResponse()
            .setBody("""{"job_id":"j1","chapter_id":"42","state":"pending"}""")
            .setResponseCode(201))

        val result = client.submitJob(server.url("/").toString().trimEnd('/'), "tok", "http://src/ch/1", 42L)

        result.jobId shouldBe "j1"
        result.state shouldBe "pending"

        val req = server.takeRequest()
        req.method shouldBe "POST"
        req.path shouldBe "/jobs"
        req.getHeader("Authorization") shouldBe "Bearer tok"
        req.body.readUtf8() shouldBe """{"source_url":"http://src/ch/1","chapter_id":"42"}"""
    }

    @Test
    fun `getJobStatus sends GET to correct path`() {
        server.enqueue(MockResponse()
            .setBody("""{"job_id":"j1","chapter_id":"42","state":"done","progress":1.0,"page_count":10,"error_message":null}"""))

        val result = client.getJobStatus(server.url("/").toString().trimEnd('/'), "tok", "j1")

        result.state shouldBe "done"
        result.pageCount shouldBe 10

        val req = server.takeRequest()
        req.path shouldBe "/jobs/j1/status"
        req.getHeader("Authorization") shouldBe "Bearer tok"
    }

    @Test
    fun `listJobs returns all jobs`() {
        server.enqueue(MockResponse()
            .setBody("""[{"job_id":"j1","chapter_id":"42","state":"done","page_count":5,"created_at":"2026-01-01T00:00:00Z"}]"""))

        val result = client.listJobs(server.url("/").toString().trimEnd('/'), "tok")

        result.size shouldBe 1
        result[0].jobId shouldBe "j1"

        val req = server.takeRequest()
        req.method shouldBe "GET"
        req.path shouldBe "/jobs"
        req.getHeader("Authorization") shouldBe "Bearer tok"
    }

    @Test
    fun `submitJob throws on server error`() {
        server.enqueue(MockResponse()
            .setBody("""{"detail":"source_url not supported"}""")
            .setResponseCode(422))

        val exception = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            client.submitJob(server.url("/").toString().trimEnd('/'), "tok", "http://src/ch/1", 42L)
        }
        exception.message!!.contains("422") shouldBe true
    }

    @Test
    fun `pageUrl constructs correct URL with zero-padded filename`() {
        val url = client.pageUrl("http://localhost:8000", "mytoken", "job-uuid", 3)
        url shouldBe "http://localhost:8000/jobs/job-uuid/pages/page_003.html?token=mytoken"
    }

    @Test
    fun `fetchPageHtml returns HTML content from page endpoint`() {
        server.enqueue(MockResponse()
            .setBody("<html><body>page content</body></html>")
            .setResponseCode(200))

        val result = client.fetchPageHtml(server.url("/").toString().trimEnd('/'), "tok", "job-uuid", 2)

        result shouldBe "<html><body>page content</body></html>"

        val req = server.takeRequest()
        req.path shouldBe "/jobs/job-uuid/pages/page_002.html?token=tok"
        req.method shouldBe "GET"
    }
}
