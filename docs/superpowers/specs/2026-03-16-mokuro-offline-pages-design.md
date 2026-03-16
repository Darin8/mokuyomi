# Mokuro Offline Page Storage — Design Spec

## Goal

Download processed Mokuro HTML pages to the phone automatically when processing completes, enabling offline reading when the home server is unreachable.

## Background

The current `MokuroReaderActivity` fetches every page live from the FastAPI server (`/jobs/{jobId}/pages/page_NNN.html?token=…`). Since the server runs on the user's home PC, chapters are unreadable away from home. This feature adds automatic local caching of pages after processing completes.

## Architecture

Four self-contained units:

### 1. DB schema change

Add `is_offline_available INTEGER NOT NULL DEFAULT 0` to the `mokuro_jobs` SQLDelight table.

`MokuroJob` domain model gains `isOfflineAvailable: Boolean`.

### 2. `MokuroPageDownloadJob` (new WorkManager CoroutineWorker)

- **Input:** `chapterId` (Long), `jobId` (String), `pageCount` (Int), `chapterName` (String), `serverUrl` (String), `token` (String)
- **Storage path:** `context.filesDir/mokuro/{jobId}/page_001.html` … `page_NNN.html`
- **Behaviour:**
  - Runs as a foreground service with a progress notification
  - Downloads pages 1..pageCount sequentially using blocking OkHttp `execute()` on `Dispatchers.IO`
  - Each page fetched from `{serverUrl}/jobs/{jobId}/pages/page_NNN.html?token={token}`
  - On success: upserts `isOfflineAvailable = true` to DB, shows "Ready for offline reading" notification
  - On any failure: deletes the partial `filesDir/mokuro/{jobId}/` directory, leaves `isOfflineAvailable = false`, shows "Could not download offline pages for {chapterName}" notification, returns `Result.failure()`
- **Enqueued by:** `MokuroPollingJob` immediately after receiving "done" from the poller, using `ExistingWorkPolicy.KEEP` (don't re-download if already queued)
- **Retry:** not automatic — user triggers via "Download offline" button

### 3. Reader fallback

`MokuroReaderActivity.loadCurrentPage()` checks for `filesDir/mokuro/{jobId}/page_NNN.html` before using the server URL. If the local file exists, loads via `file://{absolutePath}`. Otherwise falls back to the existing server URL construction. No state needed — file presence is the source of truth.

### 4. Chapter menu actions

In `EntryBottomActionMenu` / `MangaScreen`, add two new states for selected chapters where `isDone == true`:

- **"Download offline"** — shown when `isDone && !isOfflineAvailable`. Enqueues `MokuroPageDownloadJob` manually. Visible for single or batch selection.
- **"Delete offline pages"** — shown when `isOfflineAvailable`. Deletes `filesDir/mokuro/{jobId}/` and upserts `isOfflineAvailable = false`.

These replace the "Send to Mokuro" / "Retry Mokuro" states for done chapters (a done chapter cannot be re-submitted).

## Data Flow

```
MokuroPollingJob gets "done"
  → notifier.showDone()
  → MokuroPageDownloadJob.enqueue(chapterId, jobId, pageCount, chapterName, serverUrl, token)

MokuroPageDownloadJob runs (foreground service)
  → for page in 1..pageCount:
       GET {serverUrl}/jobs/{jobId}/pages/page_NNN.html?token={token}
       write to filesDir/mokuro/{jobId}/page_NNN.html
       update progress notification
  → on success:
       upsertMokuroJob(job.copy(isOfflineAvailable = true))
       notifier.showOfflineReady(chapterId, chapterName)
  → on failure:
       delete filesDir/mokuro/{jobId}/
       notifier.showOfflineFailed(chapterId, chapterName)
       Result.failure()

MokuroReaderActivity.loadCurrentPage()
  → localFile = File(filesDir, "mokuro/$jobId/page_NNN.html")
  → if localFile.exists(): webView.loadUrl("file://${localFile.absolutePath}")
  → else: webView.loadUrl(apiClient.pageUrl(serverUrl, token, jobId, currentPage))

"Delete offline pages" tapped
  → delete File(filesDir, "mokuro/$jobId/") recursively
  → upsertMokuroJob(job.copy(isOfflineAvailable = false))
```

## Error Handling

| Scenario | Behaviour |
|---|---|
| Network drops mid-download | Job catches IOException, deletes partial dir, shows failure notification, returns `Result.failure()` |
| Storage full | Same as network drop |
| Server unreachable at read time (local file missing) | Reader falls back to server URL; fails to load if offline — same as today |
| User deletes offline pages | Dir removed, `isOfflineAvailable = false`, reader falls back to server URL |
| Download job enqueued twice for same chapter | `ExistingWorkPolicy.KEEP` — second enqueue is a no-op |

## Notifications

Three new notification states in `MokuroNotifier`:

- **Progress:** "Downloading {chapterName} — {n} / {total} pages" (ongoing, updates in-place)
- **Offline ready:** "Ready for offline reading" (replaces or follows existing "done" notification)
- **Offline failed:** "Could not download offline pages for {chapterName}"

Uses the existing `CHANNEL_MOKURO_PROGRESS` channel and `notifId(chapterId)` scheme.

## String Resources

New entries in `i18n-aniyomi/.../strings.xml`:

```xml
<string name="action_download_offline">Download offline</string>
<string name="action_delete_offline_pages">Delete offline pages</string>
<string name="mokuro_downloading_pages">Downloading %1$s — %2$d / %3$d pages</string>
<string name="mokuro_offline_ready">Ready for offline reading</string>
<string name="mokuro_offline_failed">Could not download offline pages for %1$s</string>
```

## DB Migration

SQLDelight handles schema evolution via migration files. A new migration file adds the column:

```sql
ALTER TABLE mokuro_jobs ADD COLUMN is_offline_available INTEGER NOT NULL DEFAULT 0;
```

## Files Created / Modified

| File | Change |
|---|---|
| `data/src/main/sqldelight/data/mokuro_jobs.sq` | Add `is_offline_available` column |
| `data/src/main/sqldelight/data/migrations/N.sqm` | ALTER TABLE migration |
| `domain/src/main/java/tachiyomi/domain/mokuro/model/MokuroJob.kt` | Add `isOfflineAvailable: Boolean` |
| `data/src/main/java/tachiyomi/data/mokuro/MokuroJobRepositoryImpl.kt` | Map new column |
| `app/.../data/mokuro/MokuroPageDownloadJob.kt` | New WorkManager job |
| `app/.../data/mokuro/MokuroPollingJob.kt` | Enqueue download job after "done" |
| `app/.../data/mokuro/MokuroNotifier.kt` | Add progress/offline-ready/offline-failed methods |
| `app/.../data/notification/Notifications.kt` | No change needed (reuses existing channel) |
| `app/.../ui/reader/mokuro/MokuroReaderActivity.kt` | Local file fallback in `loadCurrentPage` |
| `app/.../ui/entries/manga/MangaScreenModel.kt` | `deleteOfflinePages()` method |
| `app/.../ui/entries/manga/MangaScreen.kt` | Download/delete menu items |
| `app/.../presentation/entries/components/EntryBottomActionMenu.kt` | New button states |
| `i18n-aniyomi/.../strings.xml` | 5 new strings |

## Out of Scope

- Configurable storage location (always `filesDir`)
- Storage usage display
- Wi-Fi-only download option
- Batch "download all done chapters" action
