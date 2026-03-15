# Mokuyomi Android Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Mokuro processing, a WebView-based Mokuro reader with tap-to-dictionary, and AnkiDroid export to the Aniyomi fork, with minimal changes to existing code.

**Architecture:** Follow Aniyomi's existing clean-architecture pattern (domain → data → app layers). New state is stored in a SQLDelight table (`mokuro_jobs`). A WorkManager worker polls job status. The Mokuro reader is a new Activity with a WebView and a JavaScript bridge. JMdict is bundled as a SQLite asset copied to internal storage on first use.

**Tech Stack:** Kotlin, Jetpack Compose, SQLDelight (package `tachiyomi.data`), WorkManager, OkHttp with `Call.await()` extension, WebView + JavaScript bridge, SQLite (JMdict asset), AnkiDroid API (Intent-based), Voyager (navigation)

---

## Prerequisites

Before starting:
- Confirm WorkManager is in `app/build.gradle.kts` (`androidx.work:work-runtime-ktx`)
- Add AnkiDroid API: `implementation("com.ichi2.anki:api:1.1.0")` to `app/build.gradle.kts`
- Download a JMdict SQLite file (e.g. from `scriptin/jmdict-simplified` releases — SQLite build). Place at `app/src/main/assets/jmdict.db`. The SQL queries in this plan assume the `scriptin` schema (`entries`, `kanji`, `reading`, `sense`, `gloss` tables). Inspect with `sqlite3 jmdict.db .schema` and adjust column names if using a different build.

---

## File Structure

### New files

```
data/src/main/sqldelight/data/
└── mokuro_jobs.sq                          # SQLDelight schema
data/src/main/sqldelight/migrations/
└── 33.sqm                                  # Migration adding mokuro_jobs table

domain/src/main/java/tachiyomi/domain/mokuro/
├── model/MokuroJob.kt
├── repository/MokuroRepository.kt
└── interactor/
    ├── GetMokuroJob.kt
    ├── GetAllMokuroJobs.kt
    └── UpsertMokuroJob.kt

data/src/main/java/tachiyomi/data/mokuro/
└── MokuroRepositoryImpl.kt

app/src/main/java/eu/kanade/tachiyomi/mokuro/
├── MokuroPreferences.kt
├── api/
│   ├── MokuroApiModels.kt
│   └── MokuroApiClient.kt
└── MokuroPollWorker.kt

app/src/main/java/eu/kanade/presentation/more/settings/screen/
└── SettingsMokuroScreen.kt

app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/
├── MokuroReaderActivity.kt
├── MokuroReaderViewModel.kt
├── dictionary/
│   ├── JmdictEntry.kt
│   └── JmdictDatabase.kt
├── ui/
│   └── DictionaryBottomSheet.kt
└── anki/
    └── AnkiExporter.kt
```

### Modified files

```
data/build.gradle.kts                               # No explicit version bump needed — SQLDelight counts migrations
app/build.gradle.kts                                # Add AnkiDroid API dependency
app/src/main/java/eu/kanade/domain/DomainModule.kt  # Register repository, interactors, prefs, client
app/src/main/java/eu/kanade/presentation/entries/manga/components/MangaChapterListItem.kt
app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt
app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt
app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt
app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt
app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt
app/src/main/AndroidManifest.xml
i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml   # New string keys for Mokuro
```

---

## Chunk 1: Domain + Data Layer

### Task 1: SQLDelight schema and migration

**Files:**
- Create: `data/src/main/sqldelight/data/mokuro_jobs.sq`
- Create: `data/src/main/sqldelight/migrations/33.sqm`

- [ ] **Step 1: Write mokuro_jobs.sq**

```sql
-- data/src/main/sqldelight/data/mokuro_jobs.sq

CREATE TABLE mokuro_jobs (
    chapter_id TEXT NOT NULL PRIMARY KEY,
    job_id TEXT NOT NULL,
    state TEXT NOT NULL,
    page_count INTEGER,
    error_message TEXT,
    server_url TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

selectByChapterId:
SELECT * FROM mokuro_jobs WHERE chapter_id = :chapterId;

selectAll:
SELECT * FROM mokuro_jobs;

upsert:
INSERT OR REPLACE INTO mokuro_jobs
(chapter_id, job_id, state, page_count, error_message, server_url, created_at)
VALUES (:chapterId, :jobId, :state, :pageCount, :errorMessage, :serverUrl, :createdAt);

deleteByChapterId:
DELETE FROM mokuro_jobs WHERE chapter_id = :chapterId;
```

- [ ] **Step 2: Write migration 33.sqm**

The latest existing migration is `32.sqm`. SQLDelight counts migrations automatically — no explicit `schemaVersion` to update.

```sql
-- data/src/main/sqldelight/migrations/33.sqm
CREATE TABLE mokuro_jobs (
    chapter_id TEXT NOT NULL PRIMARY KEY,
    job_id TEXT NOT NULL,
    state TEXT NOT NULL,
    page_count INTEGER,
    error_message TEXT,
    server_url TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
```

- [ ] **Step 3: Build to verify SQLDelight generates code**

```bash
cd /home/darin8/Projects/mokuyomi && ./gradlew :data:generateMangaDatabaseInterface
```
Expected: BUILD SUCCESSFUL. Verify `tachiyomi.data.Mokuro_jobs` appears in generated sources.

- [ ] **Step 4: Commit**

```bash
git add data/src/main/sqldelight/data/mokuro_jobs.sq \
        data/src/main/sqldelight/migrations/33.sqm
git commit -m "feat: add mokuro_jobs SQLDelight schema and migration 33"
```

---

### Task 2: Domain model and repository interface

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt`
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/repository/MokuroRepository.kt`

- [ ] **Step 1: Write MokuroJob.kt**

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt
package tachiyomi.domain.mokuro.model

data class MokuroJob(
    val chapterId: String,
    val jobId: String,
    val state: String, // "pending" | "processing" | "done" | "failed"
    val pageCount: Int?,
    val errorMessage: String?,
    val serverUrl: String,
    val createdAt: Long,
) {
    val isDone get() = state == "done"
    val isFailed get() = state == "failed"
    val isInProgress get() = state == "pending" || state == "processing"
}
```

- [ ] **Step 2: Write MokuroRepository.kt**

```kotlin
// domain/src/main/java/tachiyomi/domain/mokuro/repository/MokuroRepository.kt
package tachiyomi.domain.mokuro.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob

interface MokuroRepository {
    suspend fun getByChapterId(chapterId: String): MokuroJob?
    fun getAll(): Flow<List<MokuroJob>>
    suspend fun upsert(job: MokuroJob)
}
```

- [ ] **Step 3: Build domain module**

```bash
./gradlew :domain:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/
git commit -m "feat: add MokuroJob domain model and repository interface"
```

---

### Task 3: Interactors

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetMokuroJob.kt`
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/GetAllMokuroJobs.kt`
- Create: `domain/src/main/java/tachiyomi/domain/mokuro/interactor/UpsertMokuroJob.kt`

- [ ] **Step 1: Write interactors**

```kotlin
// GetMokuroJob.kt
package tachiyomi.domain.mokuro.interactor

import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroRepository

class GetMokuroJob(private val repository: MokuroRepository) {
    suspend fun await(chapterId: String): MokuroJob? = repository.getByChapterId(chapterId)
}
```

```kotlin
// GetAllMokuroJobs.kt
package tachiyomi.domain.mokuro.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroRepository

class GetAllMokuroJobs(private val repository: MokuroRepository) {
    fun subscribe(): Flow<List<MokuroJob>> = repository.getAll()
}
```

```kotlin
// UpsertMokuroJob.kt
package tachiyomi.domain.mokuro.interactor

import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroRepository

class UpsertMokuroJob(private val repository: MokuroRepository) {
    suspend fun await(job: MokuroJob) = repository.upsert(job)
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :domain:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/mokuro/interactor/
git commit -m "feat: add Mokuro domain interactors"
```

---

### Task 4: Repository implementation + DI registration

**Files:**
- Create: `data/src/main/java/tachiyomi/data/mokuro/MokuroRepositoryImpl.kt`
- Modify: `app/src/main/java/eu/kanade/domain/DomainModule.kt`

- [ ] **Step 1: Write MokuroRepositoryImpl.kt**

The generated SQLDelight type for `mokuro_jobs` table in package `tachiyomi.data` is `tachiyomi.data.Mokuro_jobs`. The accessor on the database handler lambda is `mokuroJobsQueries`.

```kotlin
// data/src/main/java/tachiyomi/data/mokuro/MokuroRepositoryImpl.kt
package tachiyomi.data.mokuro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Mokuro_jobs
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroRepository

class MokuroRepositoryImpl(
    private val handler: MangaDatabaseHandler,
) : MokuroRepository {

    override suspend fun getByChapterId(chapterId: String): MokuroJob? =
        handler.awaitOneOrNull { mokuroJobsQueries.selectByChapterId(chapterId) }
            ?.toMokuroJob()

    override fun getAll(): Flow<List<MokuroJob>> =
        handler.subscribeToList { mokuroJobsQueries.selectAll() }
            .map { rows -> rows.map { it.toMokuroJob() } }

    override suspend fun upsert(job: MokuroJob) {
        handler.await {
            mokuroJobsQueries.upsert(
                chapterId = job.chapterId,
                jobId = job.jobId,
                state = job.state,
                pageCount = job.pageCount?.toLong(),
                errorMessage = job.errorMessage,
                serverUrl = job.serverUrl,
                createdAt = job.createdAt,
            )
        }
    }

    private fun Mokuro_jobs.toMokuroJob() = MokuroJob(
        chapterId = chapter_id,
        jobId = job_id,
        state = state,
        pageCount = page_count?.toInt(),
        errorMessage = error_message,
        serverUrl = server_url,
        createdAt = created_at,
    )
}
```

- [ ] **Step 2: Register in DomainModule.kt**

Open `app/src/main/java/eu/kanade/domain/DomainModule.kt`. Find a logical grouping (e.g. near the manga chapter registrations) and add:

```kotlin
// Repository (singleton — wraps a shared DB handler)
addSingletonFactory<MokuroRepository> { MokuroRepositoryImpl(get()) }

// Interactors (new instance per injection — stateless wrappers)
addFactory { GetMokuroJob(get()) }
addFactory { GetAllMokuroJobs(get()) }
addFactory { UpsertMokuroJob(get()) }
```

Ensure `MokuroRepository`, `MokuroRepositoryImpl`, `GetMokuroJob`, `GetAllMokuroJobs`, `UpsertMokuroJob` are all imported.

- [ ] **Step 3: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add data/src/main/java/tachiyomi/data/mokuro/ \
        app/src/main/java/eu/kanade/domain/DomainModule.kt
git commit -m "feat: add MokuroRepositoryImpl and DI bindings"
```

---

## Chunk 2: Preferences + API Client

### Task 5: MokuroPreferences

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPreferences.kt`
- Modify: `app/src/main/java/eu/kanade/domain/DomainModule.kt`

- [ ] **Step 1: Write MokuroPreferences.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPreferences.kt
package eu.kanade.tachiyomi.mokuro

import tachiyomi.core.common.preference.PreferenceStore

class MokuroPreferences(private val preferenceStore: PreferenceStore) {
    fun serverUrl() = preferenceStore.getString("mokuro_server_url", "")
    fun bearerToken() = preferenceStore.getString("mokuro_bearer_token", "")
}
```

- [ ] **Step 2: Register in DomainModule.kt**

```kotlin
addSingletonFactory { MokuroPreferences(get()) }
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPreferences.kt \
        app/src/main/java/eu/kanade/domain/DomainModule.kt
git commit -m "feat: add MokuroPreferences for server URL and token"
```

---

### Task 6: API client

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/mokuro/api/MokuroApiModels.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/mokuro/api/MokuroApiClient.kt`
- Modify: `app/src/main/java/eu/kanade/domain/DomainModule.kt`

- [ ] **Step 1: Write MokuroApiModels.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/mokuro/api/MokuroApiModels.kt
package eu.kanade.tachiyomi.mokuro.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
```

- [ ] **Step 2: Write MokuroApiClient.kt**

Uses `Call.await()` from the existing `eu.kanade.tachiyomi.network.OkHttpExtensions` — a suspend extension that does not block threads.

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/mokuro/api/MokuroApiClient.kt
package eu.kanade.tachiyomi.mokuro.api

import eu.kanade.tachiyomi.network.await
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
    private val jsonMedia = "application/json".toMediaType()

    suspend fun submitJob(
        serverUrl: String,
        token: String,
        sourceUrl: String,
        chapterId: String,
    ): JobCreatedResponse {
        val body = json.encodeToString(SubmitJobRequest(sourceUrl, chapterId))
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("Submit failed: ${response.code}")
            json.decodeFromString(response.body!!.string())
        }
    }

    suspend fun getStatus(
        serverUrl: String,
        token: String,
        jobId: String,
    ): JobStatusResponse {
        val request = Request.Builder()
            .url("$serverUrl/jobs/$jobId/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("Status failed: ${response.code}")
            json.decodeFromString(response.body!!.string())
        }
    }

    suspend fun listJobs(
        serverUrl: String,
        token: String,
    ): List<JobSummary> {
        val request = Request.Builder()
            .url("$serverUrl/jobs")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("List failed: ${response.code}")
            json.decodeFromString(response.body!!.string())
        }
    }

    fun pageUrl(serverUrl: String, token: String, jobId: String, pageIndex: Int): String {
        val page = "page_%03d.html".format(pageIndex)
        return "$serverUrl/jobs/$jobId/pages/$page?token=$token"
    }
}
```

- [ ] **Step 3: Register in DomainModule.kt**

`OkHttpClient` is not registered directly — access it via `NetworkHelper` which is registered as a singleton.

```kotlin
addSingletonFactory { MokuroApiClient(get<NetworkHelper>().client, get()) }
```

Import `eu.kanade.tachiyomi.network.NetworkHelper`.

- [ ] **Step 4: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/mokuro/api/ \
        app/src/main/java/eu/kanade/domain/DomainModule.kt
git commit -m "feat: add Mokuro API client using OkHttp Call.await()"
```

---

## Chunk 3: WorkManager Polling Worker

### Task 7: MokuroPollWorker + notification channel

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPollWorker.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt`
- Modify: `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`

- [ ] **Step 1: Add notification channel**

Open `Notifications.kt`. Add the channel constant near other channel constants:

```kotlin
const val CHANNEL_MOKURO = "mokuro_processing_channel"
```

Inside `createChannels()`, add to the existing `notificationManager.createNotificationChannelsCompat(listOf(...))` call:

```kotlin
buildNotificationChannel(CHANNEL_MOKURO, IMPORTANCE_LOW) {
    setName(context.stringResource(AYMR.strings.channel_mokuro_processing))
},
```

Add the string to `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`:

```xml
<string name="channel_mokuro_processing">Mokuro Processing</string>
```

- [ ] **Step 2: Write MokuroPollWorker.kt**

Note: WorkManager enforces a minimum backoff of 10 seconds regardless of what you set. The "5 second" polling described in the spec is a target — actual intervals will be ~10 seconds minimum. The 35-minute client timeout uses `runAttemptCount >= 210` (210 × 10s ≈ 35 min).

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPollWorker.kt
package eu.kanade.tachiyomi.mokuro

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.mokuro.api.MokuroApiClient
import tachiyomi.domain.mokuro.interactor.GetMokuroJob
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class MokuroPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val apiClient: MokuroApiClient = Injekt.get()
    private val prefs: MokuroPreferences = Injekt.get()
    private val getMokuroJob: GetMokuroJob = Injekt.get()
    private val upsertMokuroJob: UpsertMokuroJob = Injekt.get()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure()
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: chapterId

        val serverUrl = prefs.serverUrl().get()
        val token = prefs.bearerToken().get()
        val notifId = chapterId.hashCode()

        val status = try {
            apiClient.getStatus(serverUrl, token, jobId)
        } catch (e: Exception) {
            return Result.retry()
        }

        val existing = getMokuroJob.await(chapterId) ?: return Result.failure()
        upsertMokuroJob.await(
            existing.copy(
                state = status.state,
                pageCount = status.pageCount,
                errorMessage = status.errorMessage,
            )
        )

        return when (status.state) {
            "done" -> {
                showNotification(notifId, chapterName, "Ready to read", progress = null)
                Result.success()
            }
            "failed" -> {
                showNotification(notifId, chapterName, "Failed: ${status.errorMessage}", progress = null)
                Result.success()
            }
            else -> {
                // 210 retries × ~10s minimum = ~35 minutes client timeout
                if (runAttemptCount >= 210) {
                    upsertMokuroJob.await(existing.copy(state = "failed", errorMessage = "Timed out"))
                    showNotification(notifId, chapterName, "Processing timed out", progress = null)
                    return Result.success()
                }
                val progressInt = (status.progress * 100).toInt()
                showNotification(notifId, chapterName, "Processing… $progressInt%", progress = progressInt)
                Result.retry()
            }
        }
    }

    private fun showNotification(id: Int, title: String, text: String, progress: Int?) {
        val builder = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_MOKURO)
            .setSmallIcon(R.drawable.ic_tachiyomi)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(progress != null)
        if (progress != null) builder.setProgress(100, progress, false)
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, builder.build())
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHAPTER_NAME = "chapter_name"

        fun enqueue(context: Context, jobId: String, chapterId: String, chapterName: String) {
            val request = OneTimeWorkRequestBuilder<MokuroPollWorker>()
                .setInputData(workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_CHAPTER_ID to chapterId,
                    KEY_CHAPTER_NAME to chapterName,
                ))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("mokuro_poll_$chapterId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/mokuro/MokuroPollWorker.kt \
        app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt \
        i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat: add MokuroPollWorker for background job polling"
```

---

## Chunk 4: Settings Screen + Chapter List Integration

### Task 8: Mokuro settings screen

**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`
- Modify: `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`

- [ ] **Step 1: Write SettingsMokuroScreen.kt**

```kotlin
// app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt
package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.mokuro.MokuroPreferences
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsMokuroScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_category_mokuro

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = Injekt.get<MokuroPreferences>()
        return listOf(
            Preference.PreferenceGroup(
                title = "Server",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        pref = prefs.serverUrl(),
                        title = "Server URL",
                        subtitle = "e.g. http://192.168.1.10:8000",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        pref = prefs.bearerToken(),
                        title = "Bearer Token",
                        subtitle = "Matches MOKUYOMI_TOKEN on your server",
                    ),
                ),
            ),
        )
    }
}
```

- [ ] **Step 2: Add string resources**

Add to `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`:

```xml
<string name="pref_category_mokuro">Mokuro Server</string>
<string name="pref_mokuro_summary">Configure your Mokuro processing server</string>
```

- [ ] **Step 3: Add to SettingsMainScreen items list**

Open `SettingsMainScreen.kt`. Find the `private val items = listOf(...)` block. Add an entry at the end (before the closing parenthesis of `listOf`):

```kotlin
Item(
    titleRes = AYMR.strings.pref_category_mokuro,
    subtitleRes = AYMR.strings.pref_mokuro_summary,
    icon = Icons.Outlined.CloudSync,
    screen = SettingsMokuroScreen,
),
```

Import `androidx.compose.material.icons.outlined.CloudSync` and `SettingsMokuroScreen`.

- [ ] **Step 4: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt \
        app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt \
        i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat: add Mokuro settings screen with server URL and token fields"
```

---

### Task 9: Chapter list badge + "Send to Mokuro" action

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/entries/manga/components/MangaChapterListItem.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt`

- [ ] **Step 1: Add mokuroState parameter to MangaChapterListItem**

Read `MangaChapterListItem.kt` fully before editing. Add a `mokuroState: String? = null` parameter. Add a small icon after the existing content row:

```kotlin
// Parameter (add after existing params):
mokuroState: String? = null,

// Inside composable body, after title/date row:
if (mokuroState != null) {
    val (tint, icon) = when (mokuroState) {
        "done" -> MaterialTheme.colorScheme.primary to Icons.Outlined.AutoStories
        "failed" -> MaterialTheme.colorScheme.error to Icons.Outlined.ErrorOutline
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) to Icons.Outlined.HourglassTop
    }
    Icon(
        imageVector = icon,
        contentDescription = "Mokuro: $mokuroState",
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}
```

- [ ] **Step 2: Load mokuro state map + sendToMokuro in MangaScreenModel**

Read `MangaScreenModel.kt` fully before editing. Make these additions:

Add DI injections at the class level:

```kotlin
private val getAllMokuroJobs: GetAllMokuroJobs = Injekt.get()
private val upsertMokuroJob: UpsertMokuroJob = Injekt.get()
private val apiClient: MokuroApiClient = Injekt.get()
private val mokuroPrefs: MokuroPreferences = Injekt.get()
```

Add `mokuroJobs: Map<String, String> = emptyMap()` to the `State` data class.

In `init {}`, subscribe to changes:

```kotlin
screenModelScope.launch {
    getAllMokuroJobs.subscribe().collectLatest { jobs ->
        mutableState.update { it.copy(mokuroJobs = jobs.associate { j -> j.chapterId to j.state }) }
    }
}
```

Add `sendToMokuro` function. Note: context is obtained from `Injekt.get<Application>()`.

```kotlin
fun sendToMokuro(chapters: List<Chapter>) {
    val serverUrl = mokuroPrefs.serverUrl().get()
    val token = mokuroPrefs.bearerToken().get()
    if (serverUrl.isBlank() || token.isBlank()) {
        // TODO: emit snackbar event "Configure Mokuro server in Settings first"
        return
    }
    screenModelScope.launch {
        val app: Application = Injekt.get()
        chapters.forEach { chapter ->
            // If chapter is already done, caller should show confirmation dialog first
            try {
                val response = apiClient.submitJob(serverUrl, token, chapter.url, chapter.id.toString())
                upsertMokuroJob.await(
                    MokuroJob(
                        chapterId = chapter.id.toString(),
                        jobId = response.jobId,
                        state = "pending",
                        pageCount = null,
                        errorMessage = null,
                        serverUrl = serverUrl,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                MokuroPollWorker.enqueue(app, response.jobId, chapter.id.toString(), chapter.name)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Failed to submit chapter ${chapter.id} to Mokuro: $e" }
            }
        }
    }
}
```

Also expose a `showMokuroConfirmDialog: Boolean` in `State` and handle the confirmation for `done`-state chapters. When `sendToMokuro` is called, check if any of the selected chapters are already `done` — if so, update state to show a confirm dialog. Only proceed after user confirms.

- [ ] **Step 3: Pass mokuroState to list items and add action button**

In `presentation/entries/manga/MangaScreen.kt`:

1. Pass `mokuroJobs` through from the screen state to `sharedChapterItems` and then into each `MangaChapterListItem` call as `mokuroState = mokuroJobs[item.chapter.id.toString()]`.

2. In `SharedMangaBottomActionMenu`, add parameter `onSendToMokuroClicked: (() -> Unit)?` and add a button inside `EntryBottomActionMenu` only when non-null (follow the existing pattern of `onDownloadClicked`).

- [ ] **Step 4: Intercept chapter tap in ui-layer MangaScreen**

In `eu.kanade.tachiyomi.ui.entries.manga.MangaScreen.kt`, modify `openChapter`:

```kotlin
private fun openChapter(context: Context, chapter: Chapter) {
    val mokuroState = screenModel.state.value.mokuroJobs[chapter.id.toString()]
    if (mokuroState == "done") {
        context.startActivity(MokuroReaderActivity.newIntent(context, chapter.id.toString()))
    } else {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }
}
```

- [ ] **Step 5: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/entries/manga/components/MangaChapterListItem.kt \
        app/src/main/java/eu/kanade/presentation/entries/manga/MangaScreen.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreen.kt
git commit -m "feat: add mokuro badge to chapter list and Send to Mokuro action"
```

---

## Chunk 5: First-Launch Sync

### Task 10: Sync from server on first launch

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt`

When the local `mokuro_jobs` table is empty (first launch after install or reinstall), sync from the server's `GET /jobs` endpoint. This recovers state without requiring re-submission.

- [ ] **Step 1: Add sync logic to MangaScreenModel init**

```kotlin
// In MangaScreenModel init {}
screenModelScope.launch {
    val hasLocalJobs = getAllMokuroJobs.subscribe()
        .first()
        .isNotEmpty()
    if (!hasLocalJobs) {
        val serverUrl = mokuroPrefs.serverUrl().get()
        val token = mokuroPrefs.bearerToken().get()
        if (serverUrl.isNotBlank() && token.isNotBlank()) {
            try {
                val jobs = apiClient.listJobs(serverUrl, token)
                jobs.forEach { summary ->
                    upsertMokuroJob.await(
                        MokuroJob(
                            chapterId = summary.chapterId,
                            jobId = summary.jobId,
                            state = summary.state,
                            pageCount = summary.pageCount,
                            errorMessage = null,
                            serverUrl = serverUrl,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "First-launch sync failed: $e" }
            }
        }
    }
}
```

Import `kotlinx.coroutines.flow.first`.

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/entries/manga/MangaScreenModel.kt
git commit -m "feat: add first-launch sync from server GET /jobs"
```

---

## Chunk 6: Mokuro Reader Activity

### Task 11: MokuroReaderActivity + WebView + JS bridge

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderActivity.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write MokuroReaderViewModel.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderViewModel.kt
package eu.kanade.tachiyomi.ui.mokuro

import androidx.lifecycle.ViewModel
import eu.kanade.tachiyomi.mokuro.MokuroPreferences
import eu.kanade.tachiyomi.mokuro.api.MokuroApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.mokuro.interactor.GetMokuroJob
import tachiyomi.domain.mokuro.model.MokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DictResult(
    val word: String,
    val reading: String,
    val partOfSpeech: String,
    val definitions: List<String>,
    val sentenceContext: String,
)

class MokuroReaderViewModel : ViewModel() {
    private val getMokuroJob: GetMokuroJob = Injekt.get()
    private val apiClient: MokuroApiClient = Injekt.get()
    private val prefs: MokuroPreferences = Injekt.get()

    private val _pageIndex = MutableStateFlow(1)
    val pageIndex = _pageIndex.asStateFlow()

    private val _job = MutableStateFlow<MokuroJob?>(null)
    val job = _job.asStateFlow()

    private val _dictResult = MutableStateFlow<DictResult?>(null)
    val dictResult = _dictResult.asStateFlow()

    suspend fun loadJob(chapterId: String) {
        _job.value = getMokuroJob.await(chapterId)
    }

    fun currentPageUrl(): String? {
        val j = _job.value ?: return null
        return apiClient.pageUrl(prefs.serverUrl().get(), prefs.bearerToken().get(), j.jobId, _pageIndex.value)
    }

    fun nextPage() {
        val count = _job.value?.pageCount ?: return
        if (_pageIndex.value < count) _pageIndex.value++
    }

    fun prevPage() {
        if (_pageIndex.value > 1) _pageIndex.value--
    }

    fun setDictResult(result: DictResult?) {
        _dictResult.value = result
    }
}
```

- [ ] **Step 2: Write MokuroReaderActivity.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderActivity.kt
package eu.kanade.tachiyomi.ui.mokuro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.mokuro.dictionary.JmdictDatabase
import eu.kanade.tachiyomi.ui.mokuro.ui.DictionaryBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MokuroReaderActivity : BaseActivity() {

    private val viewModel: MokuroReaderViewModel by viewModels()
    private lateinit var webView: WebView
    private lateinit var jmdict: JmdictDatabase

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID) ?: run { finish(); return }
        jmdict = JmdictDatabase(this)

        webView = WebView(this).also { setContentView(it) }
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(MokuroJsInterface(), "MokuroInterface")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectTapListeners()
            }
        }

        lifecycleScope.launch {
            viewModel.loadJob(chapterId)
            loadCurrentPage()
        }

        lifecycleScope.launch {
            viewModel.dictResult.collectLatest { result ->
                if (result != null) {
                    DictionaryBottomSheet.show(supportFragmentManager, result)
                }
            }
        }
    }

    private fun loadCurrentPage() {
        val url = viewModel.currentPageUrl() ?: return
        webView.loadUrl(url)
    }

    private fun injectTapListeners() {
        val js = """
            (function() {
                document.querySelectorAll('span').forEach(function(span) {
                    if (!span.textContent.trim()) return;
                    span.onclick = function(e) {
                        e.stopPropagation();
                        var word = span.textContent.trim();
                        var panel = span.closest('div') || span.closest('p') || document.body;
                        var context = Array.from(panel.querySelectorAll('span'))
                            .map(function(s) { return s.textContent.trim(); }).join('');
                        MokuroInterface.onWordTap(word, context);
                    };
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class MokuroJsInterface {
        @JavascriptInterface
        fun onWordTap(word: String, sentenceContext: String) {
            lifecycleScope.launch {
                val entry = withContext(Dispatchers.IO) { jmdict.lookup(word) }
                val result = entry?.let {
                    DictResult(
                        word = it.word,
                        reading = it.reading,
                        partOfSpeech = it.partOfSpeech,
                        definitions = it.definitions,
                        sentenceContext = sentenceContext,
                    )
                }
                viewModel.setDictResult(result)
            }
        }
    }

    fun goNextPage() { viewModel.nextPage(); loadCurrentPage() }
    fun goPrevPage() { viewModel.prevPage(); loadCurrentPage() }

    companion object {
        private const val EXTRA_CHAPTER_ID = "chapter_id"
        fun newIntent(context: Context, chapterId: String) =
            Intent(context, MokuroReaderActivity::class.java)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
    }
}
```

- [ ] **Step 3: Register in AndroidManifest.xml**

```xml
<activity
    android:name=".ui.mokuro.MokuroReaderActivity"
    android:configChanges="keyboard|keyboardHidden|orientation|screenSize"
    android:screenOrientation="landscape"
    android:theme="@style/Theme.Tachiyomi.NoActionBar" />
```

- [ ] **Step 4: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderActivity.kt \
        app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/MokuroReaderViewModel.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add MokuroReaderActivity with WebView and JS bridge"
```

---

## Chunk 7: Dictionary + Anki

### Task 12: JMdict database

**Files:**
- Create: `app/src/main/assets/jmdict.db` (manual step — see Prerequisites)
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/dictionary/JmdictEntry.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/dictionary/JmdictDatabase.kt`

- [ ] **Step 1: Write JmdictEntry.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/dictionary/JmdictEntry.kt
package eu.kanade.tachiyomi.ui.mokuro.dictionary

data class JmdictEntry(
    val word: String,
    val reading: String,
    val partOfSpeech: String,
    val definitions: List<String>,
)
```

- [ ] **Step 2: Write JmdictDatabase.kt**

The asset is copied to internal storage on first access using a `synchronized` block to prevent concurrent copy on configuration change.

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/dictionary/JmdictDatabase.kt
package eu.kanade.tachiyomi.ui.mokuro.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class JmdictDatabase(context: Context) {

    private val db: SQLiteDatabase

    init {
        val dbFile = File(context.filesDir, "jmdict.db")
        synchronized(LOCK) {
            if (!dbFile.exists()) {
                context.assets.open("jmdict.db").use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * Look up [word] — exact kanji match first, then reading match.
     * Returns top result with up to 3 definitions, or null if not found.
     * IMPORTANT: The SQL below assumes the scriptin/jmdict-simplified SQLite schema.
     * Run `sqlite3 jmdict.db .schema` and adjust if using a different build.
     */
    fun lookup(word: String): JmdictEntry? =
        lookupByKanji(word) ?: lookupByReading(word)

    private fun lookupByKanji(word: String): JmdictEntry? {
        val c = db.rawQuery(
            """
            SELECT e.id, k.text, r.text
            FROM entries e
            JOIN kanji k ON k.entry_id = e.id
            LEFT JOIN reading r ON r.entry_id = e.id AND r.position = 0
            WHERE k.text = ? LIMIT 1
            """,
            arrayOf(word),
        )
        return c.use { if (!it.moveToFirst()) null else buildEntry(it.getLong(0), it.getString(1), it.getString(2) ?: it.getString(1)) }
    }

    private fun lookupByReading(word: String): JmdictEntry? {
        val c = db.rawQuery(
            """
            SELECT e.id, r.text
            FROM entries e
            JOIN reading r ON r.entry_id = e.id
            WHERE r.text = ? LIMIT 1
            """,
            arrayOf(word),
        )
        return c.use { if (!it.moveToFirst()) null else buildEntry(it.getLong(0), it.getString(1), it.getString(1)) }
    }

    private fun buildEntry(entryId: Long, word: String, reading: String): JmdictEntry {
        val defs = db.rawQuery(
            "SELECT text FROM gloss WHERE entry_id = ? AND lang = 'eng' LIMIT 3",
            arrayOf(entryId.toString()),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
        val pos = db.rawQuery(
            "SELECT part_of_speech FROM sense WHERE entry_id = ? LIMIT 1",
            arrayOf(entryId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
        return JmdictEntry(word, reading, pos, defs)
    }

    companion object {
        private val LOCK = Any()
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/dictionary/ \
        app/src/main/assets/jmdict.db
git commit -m "feat: add JMdict SQLite dictionary lookup"
```

---

### Task 13: Dictionary bottom sheet UI

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/ui/DictionaryBottomSheet.kt`

DictResult is passed via `Bundle` arguments to survive fragment recreation.

- [ ] **Step 1: Write DictionaryBottomSheet.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/ui/DictionaryBottomSheet.kt
package eu.kanade.tachiyomi.ui.mokuro.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.kanade.tachiyomi.ui.mokuro.DictResult
import eu.kanade.tachiyomi.ui.mokuro.anki.AnkiExporter

class DictionaryBottomSheet : BottomSheetDialogFragment() {

    private val word get() = requireArguments().getString(ARG_WORD)!!
    private val reading get() = requireArguments().getString(ARG_READING)!!
    private val partOfSpeech get() = requireArguments().getString(ARG_POS)!!
    private val definitions get() = requireArguments().getStringArrayList(ARG_DEFS)!!
    private val sentenceContext get() = requireArguments().getString(ARG_CONTEXT)!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    DictionaryContent(
                        word = word,
                        reading = reading,
                        partOfSpeech = partOfSpeech,
                        definitions = definitions,
                        onAddToAnki = {
                            AnkiExporter(requireContext()).addCard(
                                front = "$word\n$sentenceContext",
                                back = "$reading\n${definitions.joinToString("\n")}",
                            )
                            dismiss()
                        },
                        onDismiss = { dismiss() },
                    )
                }
            }
        }

    companion object {
        private const val ARG_WORD = "word"
        private const val ARG_READING = "reading"
        private const val ARG_POS = "pos"
        private const val ARG_DEFS = "defs"
        private const val ARG_CONTEXT = "context"

        fun show(fm: FragmentManager, result: DictResult) {
            DictionaryBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_WORD, result.word)
                    putString(ARG_READING, result.reading)
                    putString(ARG_POS, result.partOfSpeech)
                    putStringArrayList(ARG_DEFS, ArrayList(result.definitions))
                    putString(ARG_CONTEXT, result.sentenceContext)
                }
            }.show(fm, "dict")
        }
    }
}

@Composable
private fun DictionaryContent(
    word: String,
    reading: String,
    partOfSpeech: String,
    definitions: List<String>,
    onAddToAnki: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text(word, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(reading, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(partOfSpeech, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        definitions.forEachIndexed { i, def ->
            Text("${i + 1}. $def", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddToAnki, modifier = Modifier.weight(1f)) { Text("Add to Anki") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/ui/DictionaryBottomSheet.kt
git commit -m "feat: add dictionary bottom sheet with Bundle-safe argument passing"
```

---

### Task 14: AnkiDroid export

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/anki/AnkiExporter.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add AnkiDroid dependency to app/build.gradle.kts**

```kotlin
implementation("com.ichi2.anki:api:1.1.0")
```

Sync gradle:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i anki
```
Expected: `com.ichi2.anki:api:1.1.0` in output

- [ ] **Step 2: Write AnkiExporter.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/anki/AnkiExporter.kt
package eu.kanade.tachiyomi.ui.mokuro.anki

import android.content.Context
import android.widget.Toast
import com.ichi2.anki.api.AddContentApi

class AnkiExporter(private val context: Context) {

    fun addCard(front: String, back: String) {
        if (AddContentApi.getAnkiDroidPackageName(context).isNullOrEmpty()) {
            Toast.makeText(context, "AnkiDroid is not installed", Toast.LENGTH_SHORT).show()
            return
        }

        val api = AddContentApi(context)
        val deckId = api.findDeckIdByName("Mokuyomi") ?: api.addNewDeck("Mokuyomi")
        val modelId = api.findModelIdByName("Basic") ?: run {
            Toast.makeText(context, "Basic note type not found in AnkiDroid", Toast.LENGTH_SHORT).show()
            return
        }

        api.addNote(modelId, deckId, mapOf("Front" to front, "Back" to back), null)
        Toast.makeText(context, "Card added to Anki", Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 3: Add permission to AndroidManifest.xml**

```xml
<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />
```

- [ ] **Step 4: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/mokuro/anki/AnkiExporter.kt \
        app/build.gradle.kts \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add AnkiDroid export via AddContentApi"
```

---

## Final: Android Complete

Full debug build:

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL — `app/build/outputs/apk/debug/app-debug.apk`

Smoke test on device or emulator:
1. Settings → Mokuro Server → enter server URL + token
2. Browse to any manga chapter list
3. Long-press a chapter → "Send to Mokuro" — notification should appear
4. Wait for processing → green badge on chapter
5. Tap chapter → MokuroReaderActivity opens with Mokuro HTML
6. Tap a word → dictionary bottom sheet appears
7. Tap "Add to Anki" → card in AnkiDroid

```bash
git tag android-v1.0
```
