# Mokuro Offline Page Storage — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically download processed Mokuro HTML pages to the phone after processing completes so chapters can be read without the home server.

**Architecture:** When `MokuroPollingJob` gets "done", it enqueues a new `MokuroPageDownloadJob` that fetches all pages sequentially and stores them in `context.filesDir/mokuro/{jobId}/`. `MokuroReaderActivity` checks for local files first and loads via `file://` URI if found. `MangaScreenModel` gains `downloadOfflinePages()` and `deleteOfflinePages()` methods that power "Download offline" / "Delete offline pages" batch actions in the chapter selection menu.

**Tech Stack:** Kotlin, SQLDelight, WorkManager, OkHttp, Compose, Injekt DI

---

## Codebase Notes

- **SQLDelight**: Schema version is auto-derived from migration file count. Current latest migration is `32.sqm` → new file is `33.sqm` at `data/src/main/sqldelight/migrations/33.sqm`. No other version constant to update — SQLDelight reads the migration files.
- **`mokuro_jobs.sq` named queries**: ALL four named queries (`selectByChapterId`, `selectAll`, `insertOrReplace`, `deleteByChapterId`) must be updated when adding a new column — not just the `CREATE TABLE`. SQLDelight generates code from the queries, so a column missing from `insertOrReplace` causes a compile error.
- **`mapRow` function** in `MokuroJobRepositoryImpl.kt` must also be updated to accept and pass `isOfflineAvailable`.
- **Token + serverUrl**: Never pass via WorkManager input data (stored as plaintext on disk). Read from `Injekt.get<MokuroPreferences>()` at `doWork()` time.
- **`filesDir` consistency**: Both `MokuroPageDownloadJob` (Worker) and `MokuroReaderActivity` use `applicationContext.filesDir`, which resolves to the same path in the same process.
- **Build verification**: Run `JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :data:generateDebugDatabaseInterface` to verify SQLDelight compiles. No Android SDK required for this task.
- **Test style**: JUnit 5 + kotest assertions + mockk. See `MokuroPollingJobTest.kt` for pattern.
- **`EntryBottomActionMenu`**: Currently has 12 confirm slots (0..<12), Mokuro button at index 11. No new slots needed — repurpose the existing index 11 button.
- **`MokuroJob.isOfflineAvailable`**: New field, defaults to `false`. Determines visibility of "Download offline" vs "Delete offline pages" in the chapter menu.

---

## Chunk 1: Database + Domain Layer

### Task 1: SQLDelight schema migration

**Files:**
- Create: `data/src/main/sqldelight/migrations/33.sqm`
- Modify: `data/src/main/sqldelight/data/mokuro_jobs.sq`

- [ ] **Step 1: Create migration file**

```sql
-- data/src/main/sqldelight/migrations/33.sqm
ALTER TABLE mokuro_jobs ADD COLUMN is_offline_available INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Update `mokuro_jobs.sq` — CREATE TABLE**

Replace the existing `CREATE TABLE mokuro_jobs(...)` block with:

```sql
CREATE TABLE mokuro_jobs(
    chapter_id           INTEGER NOT NULL PRIMARY KEY,
    job_id               TEXT    NOT NULL,
    state                TEXT    NOT NULL,
    page_count           INTEGER,
    error_message        TEXT,
    server_url           TEXT    NOT NULL,
    created_at           INTEGER NOT NULL,
    is_offline_available INTEGER NOT NULL DEFAULT 0
);
```

- [ ] **Step 3: Update all named queries in `mokuro_jobs.sq`**

Replace the two SELECT queries, the INSERT, and the DELETE to include `is_offline_available` where needed (SELECT and INSERT only — DELETE has no column list and needs no change):

```sql
selectByChapterId:
SELECT
    chapter_id,
    job_id,
    state,
    page_count,
    error_message,
    server_url,
    created_at,
    is_offline_available
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
    created_at,
    is_offline_available
FROM mokuro_jobs;

insertOrReplace:
INSERT OR REPLACE INTO mokuro_jobs(
    chapter_id, job_id, state, page_count, error_message,
    server_url, created_at, is_offline_available
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

deleteByChapterId:
DELETE FROM mokuro_jobs
WHERE chapter_id = ?;
```

- [ ] **Step 4: Verify SQLDelight compiles**

Run: `JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :data:generateDebugDatabaseInterface`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add data/src/main/sqldelight/migrations/33.sqm \
        data/src/main/sqldelight/data/mokuro_jobs.sq
git commit -m "feat: add is_offline_available column to mokuro_jobs"
```

---

### Task 2: Domain model and repository mapper

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt`
- Modify: `data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt`

- [ ] **Step 1: Update `MokuroJob.kt`**

Add `isOfflineAvailable: Boolean` as the last constructor parameter (with default `false` for backwards compat):

```kotlin
package tachiyomi.domain.mokuro.model

data class MokuroJob(
    val chapterId: Long,
    val jobId: String,
    val state: String,
    val pageCount: Int?,
    val errorMessage: String?,
    val serverUrl: String,
    val createdAt: Long,
    val isOfflineAvailable: Boolean = false,
) {
    val isDone: Boolean get() = state == "done"
    val isFailed: Boolean get() = state == "failed"
    val isInProgress: Boolean get() = state == "pending" || state == "processing"
}
```

Note on call-site compatibility: `isOfflineAvailable` is added as the **last** parameter with a default value (`= false`). All existing `MokuroJob(...)` construction sites — whether positional or named — compile unchanged because Kotlin uses the default for any positional call that stops before the new parameter.

- [ ] **Step 2: Update `MokuroJobRepositoryImpl.kt` — `upsert` and `mapRow`**

Update `upsert` to pass the new field:

```kotlin
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
            is_offline_available = if (job.isOfflineAvailable) 1L else 0L,
        )
    }
```

Update `mapRow` at the bottom of the file:

```kotlin
private fun mapRow(
    chapterId: Long, jobId: String, state: String, pageCount: Long?,
    errorMessage: String?, serverUrl: String, createdAt: Long,
    isOfflineAvailable: Long,
) = MokuroJob(
    chapterId = chapterId, jobId = jobId, state = state,
    pageCount = pageCount?.toInt(), errorMessage = errorMessage,
    serverUrl = serverUrl, createdAt = createdAt,
    isOfflineAvailable = isOfflineAvailable != 0L,
)
```

- [ ] **Step 3: Verify SQLDelight still compiles**

Run: `JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :data:generateDebugDatabaseInterface`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt \
        data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt
git commit -m "feat: add isOfflineAvailable to MokuroJob domain model"
```

---

## Chunk 2: Download Job

### Task 3: Add `fetchPageHtml` to `MokuroApiClient`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClient.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClientTest.kt`

- [ ] **Step 1: Write failing test**

Add to `MokuroApiClientTest`:

```kotlin
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
```

- [ ] **Step 2: Implement `fetchPageHtml` in `MokuroApiClient.kt`**

Add after `pageUrl`:

```kotlin
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
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClient.kt \
        app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroApiClientTest.kt
git commit -m "feat: add fetchPageHtml to MokuroApiClient"
```

---

### Task 4: Add offline notifications to `MokuroNotifier`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroNotifier.kt`

- [ ] **Step 1: Add three new notification methods**

Add after `showFailed`:

```kotlin
fun showDownloadProgress(chapterId: Long, chapterName: String, current: Int, total: Int) {
    val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(chapterName)
        .setContentText("Downloading $chapterName — $current / $total pages")
        .setOngoing(true)
        .setProgress(total, current, false)
        .build()
    context.notificationManager.notify(notifId(chapterId), notification)
}

fun showOfflineReady(chapterId: Long, chapterName: String) {
    val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(chapterName)
        .setContentText("Ready for offline reading")
        .setAutoCancel(true)
        .build()
    context.notificationManager.notify(notifId(chapterId), notification)
}

fun showOfflineFailed(chapterId: Long, chapterName: String) {
    val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(chapterName)
        .setContentText("Could not download offline pages for $chapterName")
        .setAutoCancel(true)
        .build()
    context.notificationManager.notify(notifId(chapterId), notification)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroNotifier.kt
git commit -m "feat: add offline download notification methods to MokuroNotifier"
```

---

### Task 5: Create `MokuroPageDownloadJob`

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJob.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJobTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJobTest.kt
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
```

- [ ] **Step 2: Create `MokuroPageDownloadJob.kt`**

The job has two classes: pure `MokuroPageDownloader` (testable) and `MokuroPageDownloadJob` (WorkManager wrapper).

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJob.kt
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
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Pure download logic extracted for testability.
 * Returns true on success, false on failure (partial files cleaned up on failure).
 */
class MokuroPageDownloader(
    private val getJob: GetMokuroJobByChapterId,
    private val upsertJob: UpsertMokuroJob,
    private val client: MokuroApiClient,
    private val preferences: MokuroPreferences,
    private val onProgress: ((current: Int, total: Int) -> Unit)? = null,
) {
    suspend fun download(chapterId: Long, filesDir: File): Boolean {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        val job = getJob.await(chapterId) ?: return false
        val pageCount = job.pageCount ?: return false
        val destDir = File(filesDir, "mokuro/${job.jobId}")

        return try {
            destDir.mkdirs()
            for (page in 1..pageCount) {
                val html = client.fetchPageHtml(serverUrl, token, job.jobId, page)
                val filename = "page_%03d.html".format(page)
                File(destDir, filename).writeText(html)
                onProgress?.invoke(page, pageCount)
            }
            upsertJob.await(job.copy(isOfflineAvailable = true))
            true
        } catch (e: CancellationException) {
            destDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            destDir.deleteRecursively()
            false
        }
    }
}

class MokuroPageDownloadJob(
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
            .setContentText("Downloading offline…")
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        return ForegroundInfo(notifier.notifId(chapterId), notification)
    }

    override suspend fun doWork(): Result = withIOContext {
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: "Chapter"
        if (chapterId == -1L) return@withIOContext Result.failure()

        setForeground(getForegroundInfo())

        val downloader = MokuroPageDownloader(
            getJob = Injekt.get<GetMokuroJobByChapterId>(),
            upsertJob = Injekt.get<UpsertMokuroJob>(),
            client = Injekt.get<MokuroApiClient>(),
            preferences = Injekt.get<MokuroPreferences>(),
            onProgress = { current, total ->
                notifier.showDownloadProgress(chapterId, chapterName, current, total)
            },
        )

        val success = downloader.download(
            chapterId = chapterId,
            filesDir = applicationContext.filesDir,
        )

        return@withIOContext if (success) {
            notifier.showOfflineReady(chapterId, chapterName)
            Result.success()
        } else {
            notifier.showOfflineFailed(chapterId, chapterName)
            Result.failure()
        }
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHAPTER_NAME = "chapter_name"

        fun enqueue(context: Context, chapterId: Long, chapterName: String) {
            val request = OneTimeWorkRequestBuilder<MokuroPageDownloadJob>()
                .setInputData(workDataOf(
                    KEY_CHAPTER_ID to chapterId,
                    KEY_CHAPTER_NAME to chapterName,
                ))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mokuro_download_$chapterId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
```

Note: `MokuroPageDownloadJob` uses the standard WorkManager `(Context, WorkerParameters)` constructor. WorkManager discovers it via reflection — no `WorkerFactory` registration needed, same as `MokuroPollingJob`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJob.kt \
        app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPageDownloadJobTest.kt
git commit -m "feat: add MokuroPageDownloadJob with offline page download"
```

---

### Task 6: Enqueue download job from `MokuroPollingJob`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJob.kt`

- [ ] **Step 1: Enqueue `MokuroPageDownloadJob` after "done"**

In `doWork()`, in the `"done"` branch, add the enqueue call after `notifier.showDone`:

```kotlin
"done" -> {
    notifier.showDone(chapterId, chapterName)
    MokuroPageDownloadJob.enqueue(applicationContext, chapterId, chapterName)
    Result.success()
}
```

- [ ] **Step 2: Update `MokuroPollingJobTest` — add "done" enqueue assertion**

In `app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJobTest.kt`, add a test (or extend the existing "done" test) to assert that `MokuroPageDownloadJob.enqueue` is called when the poller returns "done". Since `enqueue` uses `WorkManager.getInstance(context)`, mock it with `mockk<WorkManager>` and verify `enqueueUniqueWork` is called with the unique name `"mokuro_download_$chapterId"`:

```kotlin
@Test
fun `enqueues download job when state is done`() {
    // Arrange: mock WorkManager
    val workManager = mockk<WorkManager>(relaxed = true)
    mockkStatic(WorkManager::class)
    every { WorkManager.getInstance(any()) } returns workManager

    // ... set up poller to return "done" as in existing test ...

    // Assert
    verify {
        workManager.enqueueUniqueWork(
            "mokuro_download_$chapterId",
            ExistingWorkPolicy.KEEP,
            any(),
        )
    }

    unmockkStatic(WorkManager::class)
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJob.kt \
        app/src/test/java/eu/kanade/tachiyomi/data/mokuro/MokuroPollingJobTest.kt
git commit -m "feat: enqueue MokuroPageDownloadJob after processing completes"
```

---

## Chunk 3: Reader + UI

### Task 7: Local file fallback in `MokuroReaderActivity`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt`

- [ ] **Step 1: Update `loadCurrentPage()` to prefer local files**

Replace the existing `loadCurrentPage()` method:

```kotlin
private fun loadCurrentPage() {
    val filename = "page_%03d.html".format(currentPage)
    val localFile = File(applicationContext.filesDir, "mokuro/$jobId/$filename")
    if (localFile.exists()) {
        binding.webView.loadUrl("file://${localFile.absolutePath}")
    } else {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        binding.webView.loadUrl(apiClient.pageUrl(serverUrl, token, jobId, currentPage))
    }
}
```

Add the missing import at the top:
```kotlin
import java.io.File
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/MokuroReaderActivity.kt
git commit -m "feat: prefer local files over server in MokuroReaderActivity"
```

---

### Task 8: String resources and `MangaScreenModel` methods

**Files:**
- Modify: `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt`

- [ ] **Step 1: Add string resources**

In `strings.xml`, append before `</resources>`:

```xml
<string name="action_download_offline">Download offline</string>
<string name="action_delete_offline_pages">Delete offline pages</string>
<string name="mokuro_downloading_pages">Downloading %1$s — %2$d / %3$d pages</string>
<string name="mokuro_offline_ready">Ready for offline reading</string>
<string name="mokuro_offline_failed">Could not download offline pages for %1$s</string>
```

- [ ] **Step 2: Add `downloadOfflinePages` and `deleteOfflinePages` to `MangaScreenModel`**

Add after `confirmMokuroReprocess`:

```kotlin
fun downloadOfflinePages(chapters: List<Chapter>) {
    screenModelScope.launchIO {
        val jobs = successState?.mokuroJobs ?: return@launchIO
        chapters.forEach { chapter ->
            val job = jobs[chapter.id]
            if (job?.isDone == true && !job.isOfflineAvailable) {
                MokuroPageDownloadJob.enqueue(context, chapter.id, chapter.name)
            }
        }
    }
}

fun deleteOfflinePages(chapters: List<Chapter>) {
    screenModelScope.launchIO {
        val jobs = successState?.mokuroJobs ?: return@launchIO
        chapters.forEach { chapter ->
            val job = jobs[chapter.id] ?: return@forEach
            if (job.isOfflineAvailable) {
                val dir = File(context.filesDir, "mokuro/${job.jobId}")
                dir.deleteRecursively()
                upsertMokuroJob.await(job.copy(isOfflineAvailable = false))
            }
        }
    }
}
```

Add import at top of file:
```kotlin
import java.io.File
```

- [ ] **Step 3: Commit**

```bash
git add i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt
git commit -m "feat: add downloadOfflinePages and deleteOfflinePages to MangaScreenModel"
```

---

### Task 9: Wire offline actions into chapter selection menu

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/entries/components/EntryBottomActionMenu.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt` (presentation layer)
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt` (UI layer)

The existing Mokuro button (index 11, gated by `isManga`) is repurposed. Instead of `onSendToMokuroClicked`/`isMokuroRetry`, the button now shows one of four states based on the selection. No new confirm slots needed — still 12 items, 0..<12.

- [ ] **Step 1: Add offline params to `EntryBottomActionMenu`**

In `EntryBottomActionMenu.kt`, add two new parameters after `isMokuroRetry`:

```kotlin
onDownloadOfflineClicked: (() -> Unit)? = null,
onDeleteOfflineClicked: (() -> Unit)? = null,
```

Replace the existing Mokuro button block:

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

With the expanded block that handles all four states at index 11:

```kotlin
if (isManga) {
    when {
        onDeleteOfflineClicked != null -> Button(
            title = stringResource(AYMR.strings.action_delete_offline_pages),
            icon = Icons.Outlined.CloudOff,
            toConfirm = confirm[11],
            onLongClick = { onLongClickItem(11) },
            onClick = onDeleteOfflineClicked,
        )
        onDownloadOfflineClicked != null -> Button(
            title = stringResource(AYMR.strings.action_download_offline),
            icon = Icons.Outlined.CloudDownload,
            toConfirm = confirm[11],
            onLongClick = { onLongClickItem(11) },
            onClick = onDownloadOfflineClicked,
        )
        onSendToMokuroClicked != null -> {
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
    }
}
```

Add imports:
```kotlin
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
```

- [ ] **Step 2: Thread offline params through presentation `MangaScreen.kt`**

In `SharedMangaBottomActionMenu` (the private composable at the bottom of the file), add two params after `isMokuroRetry`:
```kotlin
onDownloadOfflineClicked: (() -> Unit)? = null,
onDeleteOfflineClicked: (() -> Unit)? = null,
```

Pass them to `EntryBottomActionMenu`:
```kotlin
onDownloadOfflineClicked = onDownloadOfflineClicked,
onDeleteOfflineClicked = onDeleteOfflineClicked,
```

In the public `MangaScreen` function signature, add after `isMokuroRetry`:
```kotlin
onDownloadOfflineClicked: (() -> Unit)?,
onDeleteOfflineClicked: (() -> Unit)?,
```

In `MangaScreenSmallImpl` signature, add after `isMokuroRetry`:
```kotlin
onDownloadOfflineClicked: (() -> Unit)?,
onDeleteOfflineClicked: (() -> Unit)?,
```

In `MangaScreenSmallImpl`'s `SharedMangaBottomActionMenu` call site, add:
```kotlin
onDownloadOfflineClicked = onDownloadOfflineClicked,
onDeleteOfflineClicked = onDeleteOfflineClicked,
```

In the `MangaScreen` function body where it calls `MangaScreenSmallImpl`, pass through:
```kotlin
onDownloadOfflineClicked = onDownloadOfflineClicked,
onDeleteOfflineClicked = onDeleteOfflineClicked,
```

Repeat the same three changes (signature, call site, pass-through) for `MangaScreenLargeImpl`.

- [ ] **Step 3: Compute and wire in UI `MangaScreen.kt`**

In the UI-layer `MangaScreen.kt` (`app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt`), replace the existing Mokuro callback block:

```kotlin
onSendToMokuroClicked = {
    screenModel.sendToMokuro(successState.chapters.filter { it.selected }.map { it.chapter })
    screenModel.toggleAllSelection(false)
},
isMokuroRetry = successState.chapters.filter { it.selected }.let { selected ->
    selected.isNotEmpty() && selected.all { successState.mokuroJobs[it.chapter.id]?.isFailed == true }
},
```

With the following computed vals before the `MangaScreen(...)` call, then pass them as named arguments:

```kotlin
val selectedChapters = successState.chapters.filter { it.selected }
val selectedJobs = selectedChapters.mapNotNull { successState.mokuroJobs[it.chapter.id] }
val allDone = selectedChapters.isNotEmpty() &&
    selectedChapters.all { successState.mokuroJobs[it.chapter.id]?.isDone == true }
val allOffline = allDone && selectedJobs.all { it.isOfflineAvailable }

val onDeleteOfflineClicked: (() -> Unit)? = if (allOffline) {
    {
        screenModel.deleteOfflinePages(selectedChapters.map { it.chapter })
        screenModel.toggleAllSelection(false)
    }
} else null
val onDownloadOfflineClicked: (() -> Unit)? = if (!allOffline && allDone) {
    {
        screenModel.downloadOfflinePages(selectedChapters.map { it.chapter })
        screenModel.toggleAllSelection(false)
    }
} else null
val onSendToMokuroClicked: (() -> Unit)? = if (!allDone) {
    {
        screenModel.sendToMokuro(selectedChapters.map { it.chapter })
        screenModel.toggleAllSelection(false)
    }
} else null
val isMokuroRetry = !allDone && selectedChapters.isNotEmpty() &&
    selectedChapters.all { successState.mokuroJobs[it.chapter.id]?.isFailed == true }
```

Then pass to `MangaScreen`:
```kotlin
onDeleteOfflineClicked = onDeleteOfflineClicked,
onDownloadOfflineClicked = onDownloadOfflineClicked,
onSendToMokuroClicked = onSendToMokuroClicked,
isMokuroRetry = isMokuroRetry,
```

Mixed-selection note: when some done chapters are offline and some are not, `allOffline = false` and `allDone = true`, so "Download offline" shows. `downloadOfflinePages()` guards with `!job.isOfflineAvailable` and only enqueues jobs for chapters that aren't yet offline.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/entries/components/EntryBottomActionMenu.kt \
        app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt
git commit -m "feat: add Download offline and Delete offline pages to chapter selection menu"
```

---

## Final Build Verification

- [ ] **Verify SQLDelight**

Run: `JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :data:generateDebugDatabaseInterface`
Expected: `BUILD SUCCESSFUL`

- [ ] **Manual test checklist**

1. Process a chapter → verify "Downloading offline" notification appears after "Processing done"
2. Kill the server → tap the chapter → verify it opens from local files (no network error)
3. Long-press chapter → select → verify "Download offline" button visible when done but not offline
4. Long-press chapter → select → verify "Delete offline pages" button visible when offline
5. Delete offline pages → kill server → tap chapter → verify it fails to load (back to server)
6. Long-press chapter → select not-done chapters → verify "Send to Mokuro" still shows
