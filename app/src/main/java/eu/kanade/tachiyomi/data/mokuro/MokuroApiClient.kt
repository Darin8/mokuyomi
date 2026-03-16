package eu.kanade.tachiyomi.data.mokuro

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
    data class SubmitJobRequest(val source_url: String, val chapter_id: String)

    @Serializable
    data class JobCreatedResponse(val job_id: String, val chapter_id: String, val state: String)

    @Serializable
    data class JobStatusResponse(
        val job_id: String,
        val chapter_id: String,
        val state: String,
        val progress: Float,
        val page_count: Int?,
        val error_message: String?,
    )

    @Serializable
    data class JobSummary(
        val job_id: String,
        val chapter_id: String,
        val state: String,
        val page_count: Int?,
        val created_at: String,
    )

    fun submitJob(serverUrl: String, token: String, sourceUrl: String, chapterId: Long): JobCreatedResponse {
        val body = json.encodeToString(SubmitJobRequest(source_url = sourceUrl, chapter_id = chapterId.toString()))
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server error ${response.code}: ${response.body?.string()}")
            json.decodeFromString(response.body!!.string())
        }
    }

    fun getJobStatus(serverUrl: String, token: String, jobId: String): JobStatusResponse {
        val request = Request.Builder()
            .url("$serverUrl/jobs/$jobId/status")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server error ${response.code}")
            json.decodeFromString(response.body!!.string())
        }
    }

    fun listJobs(serverUrl: String, token: String): List<JobSummary> {
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server error ${response.code}")
            json.decodeFromString(response.body!!.string())
        }
    }

    fun pageUrl(serverUrl: String, token: String, jobId: String, pageIndex: Int): String {
        val filename = "page_%03d.html".format(pageIndex)
        return "$serverUrl/jobs/$jobId/pages/$filename?token=$token"
    }
}
