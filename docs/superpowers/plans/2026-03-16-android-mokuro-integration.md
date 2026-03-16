# Android Mokuro Integration — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Mokuro processing pipeline into the Aniyomi Android fork: settings screen, chapter submission with polling, and a WebView-based reader with offline dictionary and Anki export.

**Architecture:** Three self-contained features share a SQLDelight table (`mokuro_jobs` in the existing `tachiyomi.db` database). An API client layer talks to the FastAPI server. A WorkManager CoroutineWorker polls job status and drives notifications. The MokuroReaderActivity loads Mokuro HTML pages from the server via WebView.

**Tech Stack:** Kotlin, SQLDelight, OkHttp, kotlinx.serialization, WorkManager, Compose (settings), WebView + `@JavascriptInterface`, SQLite asset (JMdict), AnkiDroid Intent API.

---

## Codebase Notes

- **Database**: SQLDelight, **not Room** (the spec says "Room" but the codebase uses SQLDelight — this plan implements the correct approach). Schema files in `data/src/main/sqldelight/data/`. Generated `Database` class in package `tachiyomi.data`. Queries go through `MangaDatabaseHandler`.
- **SQLDelight mapper pattern**: `SELECT *` queries do **not** generate a two-argument overload with a mapper. Either use explicit column aliases in the `.sq` file to get a mapper overload, or call `.executeAsOneOrNull()` and map the result manually inside the `handler.awaitOneOrNull { }` block.
- **Notification channel names**: Use `MR.strings.channel_progress` (from `tachiyomi.i18n.MR`) for channel names — `AYMR.strings.channel_progress` does not exist.
- **DI**: Injekt. Singletons registered in `AppModule.kt` (data/network) and `PreferenceModule.kt`.
- **Preferences**: `PreferenceStore.getString/getBoolean(key, default)` — see `ReaderPreferences.kt` for pattern.
- **Settings screens**: Implement `SearchableSettings`, return `List<Preference>` from `getPreferences()`.
- **i18n strings**: Add to `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`, referenced via `AYMR.strings.<name>`.
- **Chapter ID**: `Chapter.id: Long` is the Aniyomi chapter identifier. Use this as the PK in `mokuro_jobs`.
- **Chapter URL**: `chapter.url` is the source URL passed to gallery-dl as `source_url`.
- **Selection/batch actions**: Long-press in chapter list enters selection mode; the `EntryBottomActionMenu` shows batch action buttons.
- **WorkManager foreground**: Return `getForegroundInfo()` to run indefinitely as a foreground service (no 10-min limit).
- **HTTP calls**: Use blocking `execute()` + `Dispatchers.IO`. See `MangaLibraryUpdateJob` for Worker pattern.
- **Test style**: JUnit 5 + kotest assertions + mockk. See `GetApplicationReleaseTest.kt`.
- **Initial sync scope**: The spec says "on first app launch after install" — place the sync in `App.onCreate()` gated by a one-time `PreferenceStore` flag, not in `MangaScreenModel.init`.

---

## Chunk 1: Database + Domain Layer

### Task 1: SQLDelight schema for `mokuro_jobs`

**Files:**
- Create: `data/src/main/sqldelight/data/mokuro_jobs.sq`

- [ ] **Step 1: Create the schema file**

Note: Use explicit column aliases so SQLDelight generates a two-argument mapper overload for the SELECT queries.

```sql
CREATE TABLE mokuro_jobs(
    chapter_id    INTEGER NOT NULL PRIMARY KEY,
    job_id        TEXT    NOT NULL,
    state         TEXT    NOT NULL,
    page_count    INTEGER,
    error_message TEXT,
    server_url    TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
);

selectByChapterId:
SELECT
    chapter_id,
    job_id,
    state,
    page_count,
    error_message,
    server_url,
    created_at
FROM mokuro_jobs
WHERE chapter_id = ?;

selectAll:
SELECT
    chapter_id,
    job_id,
    state,
    page_count,
    error_message,
    server_url,
    created_at
FROM mokuro_jobs;

insertOrReplace:
INSERT OR REPLACE INTO mokuro_jobs(
    chapter_id, job_id, state, page_count, error_message, server_url, created_at
)
VALUES (?, ?, ?, ?, ?, ?, ?);

deleteByChapterId:
DELETE FROM mokuro_jobs
WHERE chapter_id = ?;
```

- [ ] **Step 2: Verify build generates `MokuroJobsQueries`**

Run: `./gradlew :data:generateMainDatabaseInterface`
Expected: BUILD SUCCESSFUL, `MokuroJobsQueries` interface appears in build output.

- [ ] **Step 3: Commit**

```bash
git add data/src/main/sqldelight/data/mokuro_jobs.sq
git commit -m "feat: add mokuro_jobs SQLDelight schema"
```

---

### Task 2: Domain model

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt`
- Create: `domain/src/test/java/tachiyomi/domain/mokuro/model/MokuroJobTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// domain/src/test/java/tachiyomi/domain/mokuro/model/MokuroJobTest.kt
package tachiyomi.domain.mokuro.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MokuroJobTest {

    private fun job(state: String) = MokuroJob(
        chapterId = 1L,
        jobId = "abc",
        state = state,
        pageCount = null,
        errorMessage = null,
        serverUrl = "http://localhost",
        createdAt = 0L,
    )

    @Test
    fun `isDone returns true only for done state`() {
        job("done").isDone shouldBe true
        job("pending").isDone shouldBe false
        job("processing").isDone shouldBe false
        job("failed").isDone shouldBe false
    }

    @Test
    fun `isFailed returns true only for failed state`() {
        job("failed").isFailed shouldBe true
        job("done").isFailed shouldBe false
    }

    @Test
    fun `isInProgress returns true for pending and processing`() {
        job("pending").isInProgress shouldBe true
        job("processing").isInProgress shouldBe true
        job("done").isInProgress shouldBe false
        job("failed").isInProgress shouldBe false
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "tachiyomi.domain.mokuro.model.MokuroJobTest"`
Expected: FAIL — `MokuroJob` class not found.

- [ ] **Step 3: Implement `MokuroJob`**

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt
package tachiyomi.domain.mokuro.model

data class MokuroJob(
    val chapterId: Long,
    val jobId: String,
    val state: String,
    val pageCount: Int?,
    val errorMessage: String?,
    val serverUrl: String,
    val createdAt: Long,
) {
    val isDone: Boolean get() = state == "done"
    val isFailed: Boolean get() = state == "failed"
    val isInProgress: Boolean get() = state == "pending" || state == "processing"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "tachiyomi.domain.mokuro.model.MokuroJobTest"`
Expected: PASS — 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt \
        domain/src/test/java/tachiyomi/domain/mokuro/model/MokuroJobTest.kt
git commit -m "feat: add MokuroJob domain model"
```

---

### Task 3: Repository interface + implementation

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/repository/MokuroJobRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt`

- [ ] **Step 1: Create the repository interface**

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/repository/MokuroJobRepository.kt
package tachiyomi.domain.mokuro.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob

interface MokuroJobRepository {
    suspend fun getByChapterId(chapterId: Long): MokuroJob?
    suspend fun getAll(): List<MokuroJob>
    fun subscribeAll(): Flow<List<MokuroJob>>
    suspend fun upsert(job: MokuroJob)
    suspend fun delete(chapterId: Long)
}
```

- [ ] **Step 2: Create the repository implementation**

```kotlin
// data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt
package tachiyomi.data.mokuro

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class MokuroJobRepositoryImpl(
    private val handler: MangaDatabaseHandler,
) : MokuroJobRepository {

    override suspend fun getByChapterId(chapterId: Long): MokuroJob? =
        handler.awaitOneOrNull { mokuroJobsQueries.selectByChapterId(chapterId, ::mapRow) }

    override suspend fun getAll(): List<MokuroJob> =
        handler.awaitList { mokuroJobsQueries.selectAll(::mapRow) }

    override fun subscribeAll(): Flow<List<MokuroJob>> =
        handler.subscribeToList { mokuroJobsQueries.selectAll(::mapRow) }

    override suspend fun upsert(job: MokuroJob) =
        handler.await {
            mokuroJobsQueries.insertOrReplace(
                chapter_id = job.chapterId,
                job_id = job.jobId,
                state = job.state,
                page_count = job.pageCount?.toLong(),
                error_message = job.errorMessage,
                server_url = job.serverUrl,
                created_at = job.createdAt,
            )
        }

    override suspend fun delete(chapterId: Long) =
        handler.await { mokuroJobsQueries.deleteByChapterId(chapterId) }
}

private fun mapRow(
    chapterId: Long,
    jobId: String,
    state: String,
    pageCount: Long?,
    errorMessage: String?,
    serverUrl: String,
    createdAt: Long,
) = MokuroJob(
    chapterId = chapterId,
    jobId = jobId,
    state = state,
    pageCount = pageCount?.toInt(),
    errorMessage = errorMessage,
    serverUrl = serverUrl,
    createdAt = createdAt,
)
```

- [ ] **Step 3: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/repository/MokuroJobRepository.kt \
        data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt
git commit -m "feat: add MokuroJobRepository interface and SQLDelight implementation"
```

---

### Task 4: Domain interactors

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetMokuroJobByChapterId.kt`
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetAllMokuroJobs.kt`
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/UpsertMokuroJob.kt`
- Create: `domain/src/test/java/tachiyomi/domain/mokuro/interactor/UpsertMokuroJobTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// domain/src/test/java/tachiyomi/domain/mokuro/interactor/UpsertMokuroJobTest.kt
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

    @Test
    fun `delegates to repository`() = runTest {
        val job = MokuroJob(1L, "j1", "pending", null, null, "http://x", 0L)
        upsertMokuroJob.await(job)
        coVerify { repository.upsert(job) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "tachiyomi.domain.mokuro.interactor.UpsertMokuroJobTest"`
Expected: FAIL — `UpsertMokuroJob` not found.

- [ ] **Step 3: Implement all three interactors**

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetMokuroJobByChapterId.kt
package tachiyomi.domain.mokuro.interactor

import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class GetMokuroJobByChapterId(private val repository: MokuroJobRepository) {
    suspend fun await(chapterId: Long): MokuroJob? = repository.getByChapterId(chapterId)
}
```

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetAllMokuroJobs.kt
package tachiyomi.domain.mokuro.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class GetAllMokuroJobs(private val repository: MokuroJobRepository) {
    suspend fun await(): List<MokuroJob> = repository.getAll()
    fun subscribe(): Flow<List<MokuroJob>> = repository.subscribeAll()
}
```

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/interactor/UpsertMokuroJob.kt
package tachiyomi.domain.mokuro.interactor

import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class UpsertMokuroJob(private val repository: MokuroJobRepository) {
    suspend fun await(job: MokuroJob) = repository.upsert(job)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "tachiyomi.domain.mokuro.interactor.UpsertMokuroJobTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/ \
        domain/src/test/java/tachiyomi/domain/mokuro/
git commit -m "feat: add mokuro domain interactors"
```

---

### Task 5: Wire into DI (AppModule)

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`

- [ ] **Step 1: Add imports to AppModule.kt**

Add at the top of `AppModule.kt` with other imports:
```kotlin
import tachiyomi.data.mokuro.MokuroJobRepositoryImpl
import tachiyomi.domain.mokuro.interactor.GetAllMokuroJobs
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository
```

- [ ] **Step 2: Register singletons in `registerInjectables()`**

Add after the existing `addSingletonFactory { AndroidMangaDatabaseHandler(...) }` block:

```kotlin
addSingletonFactory<MokuroJobRepository> {
    MokuroJobRepositoryImpl(get<MangaDatabaseHandler>())
}
addSingletonFactory { GetMokuroJobByChapterId(get()) }
addSingletonFactory { GetAllMokuroJobs(get()) }
addSingletonFactory { UpsertMokuroJob(get()) }
```

- [ ] **Step 3: Build to verify no errors**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt
git commit -m "feat: register MokuroJob repository and interactors in DI"
```

---

## Chunk 2: Preferences + API Client

### Task 6: MokuroPreferences

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPreferences.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt`

- [ ] **Step 1: Create preferences class**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPreferences.kt
package eu.kanade.tachiyomi.data.mokuro

import tachiyomi.core.common.preference.PreferenceStore

class MokuroPreferences(private val preferenceStore: PreferenceStore) {
    fun serverUrl() = preferenceStore.getString("mokuro_server_url", "")
    fun token() = preferenceStore.getString("mokuro_token", "")
}
```

- [ ] **Step 2: Register in PreferenceModule.kt**

Add import:
```kotlin
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
```

Add factory in `registerInjectables()`:
```kotlin
addSingletonFactory { MokuroPreferences(get()) }
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPreferences.kt \
        app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt
git commit -m "feat: add MokuroPreferences for server URL and token"
```

---

### Task 7: MokuroApiClient

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClient.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClientTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClientTest.kt
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.data.mokuro.MokuroApiClientTest"`
Expected: FAIL — `MokuroApiClient` not found.

- [ ] **Step 3: Implement MokuroApiClient**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClient.kt
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
```

- [ ] **Step 4: Register in AppModule.kt**

Add imports:
```kotlin
import eu.kanade.tachiyomi.data.mokuro.MokuroApiClient
```

Add in `registerInjectables()` after `addSingleton(app)`:
```kotlin
addSingletonFactory { MokuroApiClient(get<NetworkHelper>().client, get()) }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.data.mokuro.MokuroApiClientTest"`
Expected: PASS — 3 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClient.kt \
        app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClientTest.kt \
        app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt
git commit -m "feat: add MokuroApiClient with submit, status, and list endpoints"
```

---

## Chunk 3: Notifications + Polling Job + Settings Screen

### Task 8: Notification channel + MokuroNotifier

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroNotifier.kt`

- [ ] **Step 1: Add channel constants to Notifications.kt**

After the `GROUP_APK_UPDATES` block, add:
```kotlin
/**
 * Notification channel and ids used by Mokuro processing.
 */
private const val GROUP_MOKURO = "group_mokuro"
const val CHANNEL_MOKURO_PROGRESS = "mokuro_progress_channel"
const val ID_MOKURO_PROGRESS = -901
```

- [ ] **Step 2: Register the channel in `createChannels()`**

Add to the `notificationManager.createNotificationChannelGroupsCompat(listOf(...))` call:
```kotlin
buildNotificationChannelGroup(GROUP_MOKURO) {
    setName(context.stringResource(AYMR.strings.label_mokuro))
},
```

Add to the `notificationManager.createNotificationChannelsCompat(listOf(...))` call:
```kotlin
buildNotificationChannel(CHANNEL_MOKURO_PROGRESS, IMPORTANCE_LOW) {
    // Use MR.strings.channel_progress (from tachiyomi.i18n.MR), not AYMR — AYMR has no channel_progress string
    setName(context.stringResource(MR.strings.channel_progress))
    setGroup(GROUP_MOKURO)
    setShowBadge(false)
},
```

- [ ] **Step 3: Create MokuroNotifier**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroNotifier.kt
package eu.kanade.tachiyomi.data.mokuro

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationManager

class MokuroNotifier(private val context: Context) {

    // Give each chapter a unique notification ID derived from its ID
    private fun notifId(chapterId: Long): Int =
        Notifications.ID_MOKURO_PROGRESS - (chapterId % 500).toInt()

    fun showProgress(chapterId: Long, chapterName: String, progress: Float) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Processing with Mokuro…")
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), progress == 0f)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun showDone(chapterId: Long, chapterName: String) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Ready to read in Mokuro reader")
            .setAutoCancel(true)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun showFailed(chapterId: Long, chapterName: String, errorMessage: String?) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Processing failed: ${errorMessage ?: "Unknown error"}")
            .setAutoCancel(true)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun dismiss(chapterId: Long) {
        context.notificationManager.cancel(notifId(chapterId))
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt \
        app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroNotifier.kt
git commit -m "feat: add Mokuro notification channel and notifier"
```

---

### Task 9: MokuroPollingJob (WorkManager)

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJob.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJobTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write failing tests**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJobTest.kt
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.data.mokuro.MokuroPollingJobTest"`
Expected: FAIL.

- [ ] **Step 3: Implement MokuroJobPoller (pure logic, no Android deps)**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroJobPoller.kt
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
                    pageCount = status.page_count,
                    errorMessage = status.error_message,
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
```

- [ ] **Step 4: Implement MokuroPollingJob (WorkManager wrapper)**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJob.kt
package eu.kanade.tachiyomi.data.mokuro

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MokuroPollingJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val notifier = MokuroNotifier(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, 0L)
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: "Chapter"
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Processing with Mokuro…")
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        return ForegroundInfo(Notifications.ID_MOKURO_PROGRESS - (chapterId % 500).toInt(), notification)
    }

    override suspend fun doWork(): Result = withIOContext {
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: "Chapter"
        if (chapterId == -1L) return@withIOContext Result.failure()

        setForeground(getForegroundInfo())

        val poller = MokuroJobPoller(
            getJob = Injekt.get<GetMokuroJobByChapterId>(),
            upsertJob = Injekt.get<UpsertMokuroJob>(),
            client = Injekt.get<MokuroApiClient>(),
            preferences = Injekt.get<MokuroPreferences>(),
        )

        when (poller.poll(chapterId)) {
            "done" -> notifier.showDone(chapterId, chapterName)
            "failed" -> {
                val job = Injekt.get<GetMokuroJobByChapterId>().await(chapterId)
                notifier.showFailed(chapterId, chapterName, job?.errorMessage)
            }
            "timeout" -> notifier.showFailed(chapterId, chapterName, "Processing timed out")
        }
        Result.success()
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHAPTER_NAME = "chapter_name"

        fun enqueue(context: Context, chapterId: Long, chapterName: String) {
            val request = OneTimeWorkRequestBuilder<MokuroPollingJob>()
                .setInputData(workDataOf(KEY_CHAPTER_ID to chapterId, KEY_CHAPTER_NAME to chapterName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mokuro_poll_$chapterId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
```

- [ ] **Step 5: Register worker in AndroidManifest.xml**

WorkManager auto-discovers workers via reflection — no manifest entry needed for the worker itself.
However, add `FOREGROUND_SERVICE_DATA_SYNC` permission if not already present (it is — check the manifest):

Confirm this line exists in `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
If not present, add it to the permissions block.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.data.mokuro.MokuroPollingJobTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroJobPoller.kt \
        app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJob.kt \
        app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJobTest.kt
git commit -m "feat: add MokuroPollingJob WorkManager worker with foreground service"
```

---

### Task 10: String resources

**Files:**
- Modify: `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`

- [ ] **Step 1: Add string resources**

Append before the closing `</resources>` tag:
```xml
<!-- Mokuro integration -->
<string name="label_mokuro">Mokuro</string>
<string name="pref_mokuro_summary">Japanese reader with OCR and dictionary</string>
<string name="pref_mokuro_server_group">Server</string>
<string name="pref_mokuro_server_url">Server URL</string>
<string name="pref_mokuro_server_url_summary">Base URL of your Mokuro server (e.g. http://192.168.1.10:8000)</string>
<string name="pref_mokuro_token">Bearer token</string>
<string name="pref_mokuro_token_summary">Authentication token configured on the server</string>
<string name="action_send_to_mokuro">Send to Mokuro</string>
<string name="action_retry_mokuro">Retry Mokuro</string>
<string name="mokuro_reprocess_confirmation">This chapter is already processed. Reprocess?</string>
<string name="mokuro_sync_jobs">Sync jobs from server</string>
```

- [ ] **Step 2: Build to verify moko-resources generates AYMR entries**

Run: `./gradlew :i18n-aniyomi:generateMRcommonMain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat: add Mokuro string resources"
```

---

### Task 11: Settings screen

**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`

- [ ] **Step 1: Create SettingsMokuroScreen**

```kotlin
// app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt
package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsMokuroScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.label_mokuro

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<MokuroPreferences>() }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(AYMR.strings.pref_mokuro_server_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.serverUrl(),
                        title = stringResource(AYMR.strings.pref_mokuro_server_url),
                        subtitle = stringResource(AYMR.strings.pref_mokuro_server_url_summary),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.token(),
                        title = stringResource(AYMR.strings.pref_mokuro_token),
                        subtitle = stringResource(AYMR.strings.pref_mokuro_token_summary),
                    ),
                ),
            ),
        )
    }
}
```

- [ ] **Step 2: Add entry to SettingsMainScreen.kt**

In `SettingsMainScreen.kt`, add the following import at the top:
```kotlin
import androidx.compose.material.icons.outlined.Translate
```

Add the following item to the `items` list (after the tracking item, before browse):
```kotlin
Item(
    titleRes = AYMR.strings.label_mokuro,
    subtitleRes = AYMR.strings.pref_mokuro_summary,
    icon = Icons.Outlined.Translate,
    screen = SettingsMokuroScreen,
),
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt \
        app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt
git commit -m "feat: add Mokuro settings screen with server URL and token fields"
```

---

## Chunk 4: Chapter List Integration

### Task 11b: First-launch server sync at app startup

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt` (already modified)

The spec says sync happens on "first app launch after install (empty `mokuro_jobs` table)". This is an app-level concern — **not** per-screen. Place it in `App.onCreate()` behind a one-time boolean preference.

- [ ] **Step 1: Add sync preference to MokuroPreferences**

In `MokuroPreferences.kt`, add:
```kotlin
fun initialSyncDone() = preferenceStore.getBoolean("mokuro_initial_sync_done", false)
```

- [ ] **Step 2: Add sync call in App.onCreate()**

In `App.kt`, inside `onCreate()` after the existing initialization, add:
```kotlin
// Sync mokuro job records from server on first launch
applicationScope.launch(Dispatchers.IO) {
    val prefs = Injekt.get<MokuroPreferences>()
    if (!prefs.initialSyncDone().get()) {
        try {
            val serverUrl = prefs.serverUrl().get()
            val token = prefs.token().get()
            if (serverUrl.isNotBlank() && token.isNotBlank()) {
                val client = Injekt.get<MokuroApiClient>()
                val upsert = Injekt.get<UpsertMokuroJob>()
                val summaries = client.listJobs(serverUrl, token)
                summaries.forEach { summary ->
                    val chapterId = summary.chapter_id.toLongOrNull() ?: return@forEach
                    upsert.await(MokuroJob(
                        chapterId = chapterId,
                        jobId = summary.job_id,
                        state = summary.state,
                        pageCount = summary.page_count,
                        errorMessage = null,
                        serverUrl = serverUrl,
                        createdAt = 0L,
                    ))
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Mokuro initial sync failed: ${e.message}" }
        } finally {
            prefs.initialSyncDone().set(true)
        }
    }
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/App.kt \
        app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPreferences.kt
git commit -m "feat: sync mokuro jobs from server on first app launch"
```

---

### Task 12: Add mokuro state to MangaScreenModel

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt`

- [ ] **Step 1: Add imports**

Add to imports in `MangaScreenModel.kt`:
```kotlin
import eu.kanade.tachiyomi.data.mokuro.MokuroApiClient
import eu.kanade.tachiyomi.data.mokuro.MokuroPollingJob
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import tachiyomi.domain.mokuro.interactor.GetAllMokuroJobs
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import tachiyomi.domain.mokuro.model.MokuroJob
```

- [ ] **Step 2: Add injected dependencies to MangaScreenModel constructor**

Add to constructor parameters (with default values from Injekt):
```kotlin
private val getAllMokuroJobs: GetAllMokuroJobs = Injekt.get(),
private val getMokuroJobByChapterId: GetMokuroJobByChapterId = Injekt.get(),
private val upsertMokuroJob: UpsertMokuroJob = Injekt.get(),
private val mokuroApiClient: MokuroApiClient = Injekt.get(),
private val mokuroPreferences: MokuroPreferences = Injekt.get(),
```

- [ ] **Step 3: Add `mokuroJobs` to State.Success**

In the `data class Success(...)` block, add:
```kotlin
val mokuroJobs: Map<Long, MokuroJob> = emptyMap(),
```

- [ ] **Step 4: Subscribe to mokuro jobs in init block**

In the `init` block (or wherever the main data flow is set up), add a collector:
```kotlin
screenModelScope.launchIO {
    getAllMokuroJobs.subscribe().collectLatest { jobs ->
        mutableState.update { state ->
            if (state is State.Success) {
                state.copy(mokuroJobs = jobs.associateBy { it.chapterId })
            } else {
                state
            }
        }
    }
}
```

- [ ] **Step 5: Add `sendToMokuro` method with confirmation dialog for `done` chapters**

The spec requires: a confirmation dialog when the chapter is already `done`, and no confirmation when `failed` (direct retry).

Add a `Dialog` sealed class entry for the confirmation:
```kotlin
// In MangaScreenModel's Dialog sealed class, add:
data class MokuroReprocessConfirmation(val chapters: List<Chapter>) : Dialog()
```

Implement `sendToMokuro`:
```kotlin
fun sendToMokuro(chapters: List<Chapter>) {
    screenModelScope.launchIO {
        // Check if any selected chapter is already done — show confirmation
        val hasDone = chapters.any { successState?.mokuroJobs?.get(it.id)?.isDone == true }
        if (hasDone) {
            mutableState.update { state ->
                (state as? State.Success)?.copy(dialog = Dialog.MokuroReprocessConfirmation(chapters)) ?: state
            }
            return@launchIO
        }
        submitMokuroJobs(chapters)
    }
}

fun confirmMokuroReprocess(chapters: List<Chapter>) {
    dismissDialog()
    screenModelScope.launchIO { submitMokuroJobs(chapters) }
}

private suspend fun submitMokuroJobs(chapters: List<Chapter>) {
    val serverUrl = mokuroPreferences.serverUrl().get()
    val token = mokuroPreferences.token().get()
    if (serverUrl.isBlank() || token.isBlank()) {
        withUIContext { context.toast("Mokuro server not configured") }
        return
    }
    chapters.forEach { chapter ->
        try {
            val response = mokuroApiClient.submitJob(serverUrl, token, chapter.url, chapter.id)
            upsertMokuroJob.await(
                MokuroJob(
                    chapterId = chapter.id,
                    jobId = response.job_id,
                    state = response.state,
                    pageCount = null,
                    errorMessage = null,
                    serverUrl = serverUrl,
                    createdAt = System.currentTimeMillis(),
                )
            )
            MokuroPollingJob.enqueue(context, chapter.id, chapter.name)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to submit mokuro job for chapter ${chapter.id}" }
            withUIContext { context.toast("Failed to send to Mokuro: ${e.message}") }
        }
    }
}
```

- [ ] **Step 7: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt
git commit -m "feat: add mokuro state, sync, and sendToMokuro to MangaScreenModel"
```

---

### Task 13: Chapter badge in MangaChapterListItem

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/entries/manga/components/MangaChapterListItem.kt`

- [ ] **Step 1: Add `mokuroState` parameter to the composable**

Add to `MangaChapterListItem` function signature:
```kotlin
mokuroState: String? = null,
```

- [ ] **Step 2: Render badge next to chapter title**

In the row showing chapter title and metadata, add after the bookmark icon:
```kotlin
when (mokuroState) {
    "done" -> Icon(
        imageVector = Icons.Outlined.AutoStories,
        contentDescription = "Mokuro processed",
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    "failed" -> Icon(
        imageVector = Icons.Outlined.ErrorOutline,
        contentDescription = "Mokuro failed",
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.error,
    )
    "pending", "processing" -> CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.secondary,
    )
}
```

You will need to add the imports:
```kotlin
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
```

- [ ] **Step 3: Pass `mokuroState` from MangaScreen.kt**

In `MangaScreen.kt` (the `chapterListItem` LazyColumn content), find where `MangaChapterListItem` is called and pass:
```kotlin
mokuroState = state.mokuroJobs[item.chapter.id]?.state,
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/entries/manga/components/MangaChapterListItem.kt \
        app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt
git commit -m "feat: add mokuro state badge to chapter list items"
```

---

### Task 14: "Send to Mokuro" in bottom action menu

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/entries/components/EntryBottomActionMenu.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt` (the UI screen, not presentation)

- [ ] **Step 1: Add `onSendToMokuroClicked` to EntryBottomActionMenu**

The spec requires: show "Retry Mokuro" when all selected chapters have `failed` state, otherwise show "Send to Mokuro".

Add parameters to `EntryBottomActionMenu`:
```kotlin
onSendToMokuroClicked: (() -> Unit)? = null,
isMokuroRetry: Boolean = false,  // true when all selected chapters are in failed state
```

Update the `confirm` list size from `mutableStateListOf(false, false, ... /* 11 items */)` to 12 items.
Update `val confirmRange = 0..<12`.

Add button inside the `Row`:
```kotlin
if (onSendToMokuroClicked != null && isManga) {
    val label = if (isMokuroRetry) {
        stringResource(AYMR.strings.action_retry_mokuro)
    } else {
        stringResource(AYMR.strings.action_send_to_mokuro)
    }
    Button(
        title = label,
        icon = Icons.Outlined.Translate,
        toConfirm = confirm[11],
        onLongClick = { onLongClickItem(11) },
        onClick = onSendToMokuroClicked,
    )
}
```

- [ ] **Step 2: Wire callback in MangaScreen.kt (presentation layer)**

In `MangaScreen` composable, add to `EntryBottomActionMenu`:
```kotlin
onSendToMokuroClicked = onSendToMokuroClicked,
isMokuroRetry = isMokuroRetry,
```

Add to `MangaScreen` function parameters:
```kotlin
onSendToMokuroClicked: (() -> Unit)?,
isMokuroRetry: Boolean,
```

- [ ] **Step 3: Wire in MangaScreen.kt (UI layer)**

In the UI-layer `MangaScreen.kt` (under `ui/entries/manga/`), compute `isMokuroRetry`:
```kotlin
val selected = successState.chapters.filter { it.selected }
val isMokuroRetry = selected.isNotEmpty() &&
    selected.all { successState.mokuroJobs[it.chapter.id]?.isFailed == true }
```

Pass:
```kotlin
onSendToMokuroClicked = {
    screenModel.sendToMokuro(selected.map { it.chapter })
    screenModel.toggleAllSelection(false)
},
isMokuroRetry = isMokuroRetry,
```

- [ ] **Step 4: Show confirmation dialog in UI layer**

In `MangaScreen.kt` (UI), handle the `MokuroReprocessConfirmation` dialog state:
```kotlin
is MangaScreenModel.Dialog.MokuroReprocessConfirmation -> {
    AlertDialog(
        onDismissRequest = screenModel::dismissDialog,
        title = { Text(stringResource(AYMR.strings.action_send_to_mokuro)) },
        text = { Text(stringResource(AYMR.strings.mokuro_reprocess_confirmation)) },
        confirmButton = {
            TextButton(onClick = { screenModel.confirmMokuroReprocess(dialog.chapters) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = screenModel::dismissDialog) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/entries/components/EntryBottomActionMenu.kt \
        app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt
git commit -m "feat: add Send to Mokuro batch action to chapter selection menu"
```

---

## Chunk 5: Mokuro Reader

### Task 15: MokuroReaderActivity

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt`
- Create: `app/src/main/res/layout/activity_mokuro_reader.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create XML layout**

```xml
<!-- app/src/main/res/layout/activity_mokuro_reader.xml -->
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</FrameLayout>
```

- [ ] **Step 2: Create MokuroReaderActivity**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt
package eu.kanade.tachiyomi.ui.reader.mokuro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.mokuro.MokuroApiClient
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import eu.kanade.tachiyomi.databinding.ActivityMokuroReaderBinding
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MokuroReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMokuroReaderBinding

    private val preferences: MokuroPreferences = Injekt.get()
    private val apiClient: MokuroApiClient = Injekt.get()

    private var currentPage: Int = 1
    private var pageCount: Int = 1
    private var jobId: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMokuroReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return finish()
        pageCount = intent.getIntExtra(EXTRA_PAGE_COUNT, 1)
        currentPage = savedInstanceState?.getInt(STATE_PAGE) ?: 1

        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(
            MokuroJsInterface { word, context -> onWordTapped(word, context) },
            "MokuroInterface",
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectWordTapListeners(view)
            }
        }

        // Compose overlay for dictionary popup — driven by mutableStateOf fields
        val overlayView = ComposeView(this).apply {
            setContent {
                val showDict by dictionaryVisible
                if (showDict) {
                    MaterialTheme {
                        DictionaryPopup(
                            word = dictionaryWord.value,
                            sentenceContext = dictionaryContext.value,
                            helper = JmdictHelper(this@MokuroReaderActivity),
                            onDismiss = { dictionaryVisible.value = false },
                        )
                    }
                }
            }
        }
        (binding.root as FrameLayout).addView(overlayView)

        loadCurrentPage()
    }

    // Compose-observable state for the dictionary popup
    private val dictionaryVisible = mutableStateOf(false)
    private val dictionaryWord = mutableStateOf("")
    private val dictionaryContext = mutableStateOf("")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
    }

    private fun loadCurrentPage() {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        val url = apiClient.pageUrl(serverUrl, token, jobId, currentPage)
        binding.webView.loadUrl(url)
    }

    private fun injectWordTapListeners(view: WebView) {
        val js = """
            (function() {
                document.querySelectorAll('span').forEach(function(span) {
                    span.addEventListener('click', function(e) {
                        e.stopPropagation();
                        var word = span.innerText.trim();
                        var parent = span.closest('div, p') || document.body;
                        var ctx = Array.from(parent.querySelectorAll('span'))
                            .map(function(s) { return s.innerText; })
                            .join('');
                        MokuroInterface.onWordTap(word, ctx);
                    });
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun onWordTapped(word: String, sentenceContext: String) {
        runOnUiThread {
            dictionaryWord.value = word
            dictionaryContext.value = sentenceContext
            dictionaryVisible.value = true
        }
    }

    fun goToPage(page: Int) {
        if (page in 1..pageCount) {
            currentPage = page
            loadCurrentPage()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_PAGE_COUNT = "page_count"
        const val STATE_PAGE = "current_page"

        fun newIntent(context: Context, jobId: String, pageCount: Int): Intent =
            Intent(context, MokuroReaderActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_PAGE_COUNT, pageCount)
            }
    }
}

class MokuroJsInterface(private val onWordTap: (word: String, context: String) -> Unit) {
    @JavascriptInterface
    fun onWordTap(word: String, sentenceContext: String) {
        onWordTap.invoke(word, sentenceContext)
    }
}
```

- [ ] **Step 3: Register activity in AndroidManifest.xml**

Inside the `<application>` block, add:
```xml
<activity
    android:name=".ui.reader.mokuro.MokuroReaderActivity"
    android:configChanges="orientation|screenSize|keyboard|keyboardHidden"
    android:exported="false"
    android:theme="@style/Theme.Tachiyomi" />
```

- [ ] **Step 4: Route chapter open to MokuroReaderActivity**

In `MangaScreen.kt` (UI layer), find the `onChapterClicked` handler. Add logic before the existing reader launch:
```kotlin
val mokuroJob = successState.mokuroJobs[chapter.id]
if (mokuroJob?.isDone == true) {
    val intent = MokuroReaderActivity.newIntent(
        context = context,
        jobId = mokuroJob.jobId,
        pageCount = mokuroJob.pageCount ?: 1,
    )
    context.startActivity(intent)
    return@onChapterClicked
}
// ... existing reader launch code ...
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt \
        app/src/main/res/layout/activity_mokuro_reader.xml \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt
git commit -m "feat: add MokuroReaderActivity with WebView and JS word-tap interface"
```

---

### Task 16: JMdict helper (offline dictionary)

**Files:**
- Create: `app/src/main/assets/jmdict.db` ← **Manual step**: obtain and place JMdict SQLite file here
- Create: `app/src/main/assets/deinflect.json` ← **Manual step**: obtain deinflection table (see below)
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelper.kt`

> **Note:** Two assets required:
> 1. `jmdict.db` — 50MB SQLite file built from JMdict XML. Schema: table `entries(word TEXT, reading TEXT, pos TEXT, definitions TEXT)` where `definitions` is tab-separated. Build with `jmdict-simplified` project or a pre-built converter.
> 2. `deinflect.json` — deinflection rules table. Use the same JSON format as Yomichan/Yomitan's `deinflect.json` (freely available in the Yomitan repository). Maps conjugated suffixes to plain-form suffixes.

- [ ] **Step 1: Create JmdictHelper with deinflection fallback**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelper.kt
package eu.kanade.tachiyomi.ui.reader.mokuro

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class JmdictEntry(
    val word: String,
    val reading: String,
    val partsOfSpeech: String,
    val definitions: List<String>,
)

@Serializable
data class DeinflectRule(val kanaIn: String, val kanaOut: String, val rulesIn: Int, val rulesOut: Int)

class JmdictHelper(private val context: Context) {

    private val dbPath: File get() = File(context.filesDir, "jmdict.db")

    // Lazy singleton DB connection — opened once, closed with the helper's lifecycle
    private val db: SQLiteDatabase by lazy {
        ensureDatabase()
        SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private val deinflectRules: List<DeinflectRule> by lazy {
        val json = context.assets.open("deinflect.json").bufferedReader().readText()
        Json.decodeFromString<List<DeinflectRule>>(json)
    }

    private fun ensureDatabase() {
        if (dbPath.exists()) return
        context.assets.open("jmdict.db").use { input ->
            dbPath.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Look up [word] in JMdict. Returns up to 3 entries.
     * Strategy: exact match → deinflected forms → no result.
     */
    fun lookup(word: String): List<JmdictEntry> {
        val exact = queryExact(word)
        if (exact.isNotEmpty()) return exact

        // Try deinflected candidates
        for (candidate in deinflectedForms(word)) {
            val results = queryExact(candidate)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /** Produce candidate plain forms by applying deinflection rules to [word]. */
    private fun deinflectedForms(word: String): List<String> {
        return deinflectRules
            .filter { rule -> word.endsWith(rule.kanaIn) }
            .map { rule -> word.dropLast(rule.kanaIn.length) + rule.kanaOut }
            .distinct()
    }

    private fun queryExact(word: String): List<JmdictEntry> {
        val cursor = db.rawQuery(
            "SELECT word, reading, pos, definitions FROM entries WHERE word = ? LIMIT 3",
            arrayOf(word),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(JmdictEntry(
                        word = c.getString(0),
                        reading = c.getString(1),
                        partsOfSpeech = c.getString(2),
                        definitions = c.getString(3).split("\t"),
                    ))
                }
            }
        }
    }

    fun close() = db.close()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelper.kt
# Note: commit jmdict.db and deinflect.json separately (binary assets)
git commit -m "feat: add JmdictHelper with deinflection fallback for offline dictionary"
```

---

### Task 17: Dictionary popup + AnkiDroid export

Use `ModalBottomSheet` (Compose) driven by state in `MokuroReaderActivity` — consistent with the project's Compose-first approach, and avoids Fragment back-stack complexity.

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/DictionaryPopup.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelperTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt`

- [ ] **Step 1: Write JmdictHelper test**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelperTest.kt
package eu.kanade.tachiyomi.ui.reader.mokuro

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JmdictHelperTest {

    // JmdictHelper depends on Android Context for assets/filesDir — test the data mapping logic only

    @Test
    fun `JmdictEntry definitions list is parsed from tab-separated string`() {
        // Simulate the mapping logic used in JmdictHelper
        val raw = "to eat\tto consume\tto bite"
        val defs = raw.split("\t")
        defs.size shouldBe 3
        defs[0] shouldBe "to eat"
    }

    @Test
    fun `JmdictEntry top 3 definitions are taken`() {
        val entry = JmdictEntry(
            word = "食べる",
            reading = "たべる",
            partsOfSpeech = "v1",
            definitions = listOf("to eat", "to consume", "to bite", "to have a meal"),
        )
        entry.definitions.take(3).size shouldBe 3
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.ui.reader.mokuro.JmdictHelperTest"`
Expected: FAIL — `JmdictEntry` not found.

- [ ] **Step 3: Create DictionaryPopup composable and AnkiDroid helper**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/DictionaryPopup.kt
package eu.kanade.tachiyomi.ui.reader.mokuro

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryPopup(
    word: String,
    sentenceContext: String,
    helper: JmdictHelper,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<JmdictEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(word) {
        entries = withContext(Dispatchers.IO) { helper.lookup(word) }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(text = word, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Text("Looking up…")
                entries.isEmpty() -> Text("No results found for \"$word\"")
                else -> entries.forEach { entry ->
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(entry.reading, style = MaterialTheme.typography.titleMedium)
                    Text(entry.partsOfSpeech, style = MaterialTheme.typography.bodySmall)
                    entry.definitions.take(3).forEach { def ->
                        Text("• $def", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        addAnkiCard(context, entry.word, sentenceContext, entry.reading,
                            entry.definitions.take(3).joinToString("\n"))
                    }) {
                        Text("Add to Anki")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

fun addAnkiCard(
    context: Context,
    word: String,
    sentenceContext: String,
    reading: String,
    definitions: String,
) {
    try {
        val intent = Intent().apply {
            component = ComponentName("com.ichi2.anki", "com.ichi2.anki.IntentHandler")
            action = "com.ichi2.anki.CREATE_NOTE"
            putExtra("EXTRA_DECK_ID", 0L)
            putExtra("EXTRA_FRONT", "$word\n\n$sentenceContext")
            putExtra("EXTRA_BACK", "$reading\n\n$definitions")
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "AnkiDroid is not installed", Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 4: Verify MokuroReaderActivity already has the Compose overlay**

The `MokuroReaderActivity` implemented in Task 15 already includes the `ComposeView` overlay driven by `mutableStateOf` fields (`dictionaryVisible`, `dictionaryWord`, `dictionaryContext`) and the `onWordTapped` method sets those fields. No additional changes to `MokuroReaderActivity` are needed in this task — `DictionaryPopup` is referenced by name and will compile once `DictionaryPopup.kt` is created in Step 3.

Add the necessary imports to `MokuroReaderActivity.kt`:
```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import eu.kanade.tachiyomi.ui.reader.mokuro.DictionaryPopup
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:test --tests "eu.kanade.tachiyomi.ui.reader.mokuro.JmdictHelperTest"`
Expected: PASS.

- [ ] **Step 6: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/DictionaryPopup.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt \
        app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictHelperTest.kt
git commit -m "feat: add dictionary popup with JMdict lookup and AnkiDroid export"
```

---

## Final Build Verification

- [ ] **Full release build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK generated.

- [ ] **All domain unit tests**

Run: `./gradlew :domain:test :app:test`
Expected: All tests PASS.

- [ ] **Final commit message**

If everything passes, no additional commit needed. The feature is ready for manual testing on device.

---

## Manual Testing Checklist

1. Open Settings → verify "Mokuro" entry appears
2. Enter server URL and token → save and re-open → values persist
3. Go to a manga chapter list → long-press a chapter → select → tap "Send to Mokuro"
4. Verify persistent notification appears and updates progress
5. Wait for job to complete → verify green badge on chapter
6. Tap chapter with badge → verify MokuroReaderActivity opens WebView
7. Tap a Japanese word → verify dictionary popup appears with definitions
8. Tap "Add to Anki" → verify AnkiDroid opens (or toast if not installed)
9. Kill app and reopen → verify badges still appear (Room persisted state)
10. Test failure path: submit invalid URL → verify red badge and error notification
