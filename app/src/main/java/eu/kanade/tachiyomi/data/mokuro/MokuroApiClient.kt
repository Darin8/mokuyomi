package eu.kanade.tachiyomi.data.mokuro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MokuroApiClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    @Serializable
    data class SubmitJobRequest(
        @SerialName("source_url") val sourceUrl: String,
        @SerialName("chapter_id") val chapterId: String,
    )

    @Serializable
    data class JobCreatedResponse(
        @SerialName("job_id") val jobId: String,
        @SerialName("chapter_id") val chapterId: String,
        val state: String,
    )

    @Serializable
    data class JobStatusResponse(
        @SerialName("job_id") val jobId: String,
        @SerialName("chapter_id") val chapterId: String,
        val state: String,
        val progress: Float,
        @SerialName("page_count") val pageCount: Int?,
        @SerialName("error_message") val errorMessage: String?,
    )

    @Serializable
    data class JobSummary(
        @SerialName("job_id") val jobId: String,
        @SerialName("chapter_id") val chapterId: String,
        val state: String,
        @SerialName("page_count") val pageCount: Int?,
        @SerialName("created_at") val createdAt: String,
    )

    private inline fun <reified T> execute(request: Request): T {
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) error("Server error ${response.code}: $body")
            json.decodeFromString(body ?: error("Empty response body"))
        }
    }

    fun submitJob(serverUrl: String, token: String, sourceUrl: String, chapterId: Long): JobCreatedResponse {
        val body = json.encodeToString(SubmitJobRequest(sourceUrl = sourceUrl, chapterId = chapterId.toString()))
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    fun getJobStatus(serverUrl: String, token: String, jobId: String): JobStatusResponse {
        val request = Request.Builder()
            .url("$serverUrl/jobs/$jobId/status")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request)
    }

    fun listJobs(serverUrl: String, token: String): List<JobSummary> {
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request)
    }

    /**
     * Constructs the URL for a processed page.
     * @param pageIndex 1-based page index (1 = first page, maps to page_001.html).
     */
    fun pageUrl(serverUrl: String, token: String, jobId: String, pageIndex: Int): String {
        val filename = "page_%03d.html".format(pageIndex)
        return "$serverUrl/jobs/$jobId/pages/$filename?token=$token"
    }

    /**
     * Downloads the HTML content of a single processed page.
     * @param pageIndex 1-based page index.
     */
    fun fetchPageHtml(serverUrl: String, token: String, jobId: String, pageIndex: Int): String {
        val request = Request.Builder()
            .url(pageUrl(serverUrl, token, jobId, pageIndex))
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) error("Server error ${response.code}: $body")
            body ?: error("Empty response body")
        }
    }
}
