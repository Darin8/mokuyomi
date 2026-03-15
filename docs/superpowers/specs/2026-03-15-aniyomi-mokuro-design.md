# EasyManga — Aniyomi Fork with Mokuro Integration

**Date:** 2026-03-15
**Status:** Approved

---

## Overview

EasyManga is a fork of the Aniyomi Android app that adds a Mokuro processing pipeline and a specialized reader for Japanese manga learners. Users can send manga chapters to a personal server for OCR processing via Mokuro, then read the processed chapters in-app with tap-to-dictionary lookups and one-tap Anki card creation.

---

## Goals

- Minimal changes to Aniyomi core — stay as close to upstream as possible
- Personal server only — no multi-user requirements
- Fully offline dictionary lookups (no network call per tap)
- End-to-end flow: browse → process → read → study

---

## Architecture

```
[Aniyomi Fork (Android)]
       |
       | "Send to Mokuro" → POST /jobs {source_url, chapter_id}
       |
[FastAPI Server]
       |
       | enqueues job
       |
[Celery Worker]
       | 1. Fetches chapter images via gallery-dl
       | 2. Runs Mokuro on images → one HTML file per page
       | 3. Stores output to /processed/{job_id}/page_001.html, page_002.html, ...
       |
[Redis]  ← active job queue and broker
       |
[Server SQLite] ← permanent job records (survives Redis restart)
       |
[FastAPI Server]
       | GET /jobs/{job_id}/status → {state, progress, page_count, error_message}
       | GET /jobs/{job_id}/pages/{page_filename} → HTML file (auth via query token)
       | GET /jobs → list all job records
       |
[Aniyomi Fork]
       | Polls status, then opens MokuroReaderActivity
       | Loads pages one at a time by index (page_001.html, page_002.html, ...)
       | On word tap → JMdict lookup → dictionary popup
       | From popup → AnkiDroid API → flashcard
```

---

## Server

### Stack

| Component | Technology |
|---|---|
| API | FastAPI (Python) |
| Job queue | Celery |
| Broker / state | Redis |
| Persistent records | SQLite |
| Manga fetching | gallery-dl |
| OCR processing | Mokuro |
| Deployment | Docker Compose |

### Services (Docker Compose)

1. **FastAPI** — REST API, the only service the app communicates with
2. **Celery Worker** — processes jobs asynchronously
3. **Redis** — Celery broker and active job state
4. **Shared volume** — mounted by both FastAPI and Celery Worker for processed file access

### API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/jobs` | Submit a chapter for processing. Returns job record. |
| GET | `/jobs/{job_id}/status` | Returns job state and progress. |
| GET | `/jobs/{job_id}/pages/{filename}` | Serves a processed page HTML file (query-token auth). |
| GET | `/jobs` | Lists all job records. |

### Authentication

All API endpoints require a bearer token in the `Authorization: Bearer <token>` header. Configured via environment variable in Docker Compose.

**WebView file serving** — the `GET /jobs/{job_id}/pages/{filename}` endpoint uses a **query-parameter token** (`?token=<bearer_token>`) instead of a header, because Android WebView cannot inject arbitrary headers into page-load requests. The token value is the same bearer token stored in app settings. The URL is constructed in Kotlin before being passed to the WebView.

### POST /jobs — Request & Response Schema

**Request body:**
```json
{
  "source_url": "https://mangasource.example/chapter/12345",
  "chapter_id": "aniyomi-internal-chapter-id-string"
}
```

- `source_url`: The direct chapter URL as provided by the Aniyomi source extension. Must be a URL that `gallery-dl` can resolve to individual page images.
- `chapter_id`: The Aniyomi-internal chapter identifier (string). Stored by the server for reconciliation — not used for fetching. Returned in all job responses so the app can match server records to local chapters.

**Response body (on success, HTTP 201):**
```json
{
  "job_id": "uuid-v4-string",
  "chapter_id": "aniyomi-internal-chapter-id-string",
  "state": "pending"
}
```

**Response body (on error, HTTP 422):**
```json
{
  "detail": "source_url is not supported by gallery-dl"
}
```

### GET /jobs/{job_id}/status — Response Schema

```json
{
  "job_id": "uuid-v4-string",
  "chapter_id": "aniyomi-internal-chapter-id-string",
  "state": "pending | processing | done | failed",
  "progress": 0.75,
  "page_count": 24,
  "error_message": null
}
```

- `state`: One of `pending`, `processing`, `done`, `failed`
- `progress`: Float 0.0–1.0, representing fraction of pages processed by Mokuro. `0.0` while pending or before first page completes. `1.0` on done.
- `page_count`: Total number of pages in the chapter. `null` until gallery-dl completes fetching.
- `error_message`: Human-readable error string when `state` is `failed`, otherwise `null`.

### GET /jobs — Response Schema

```json
[
  {
    "job_id": "uuid-v4-string",
    "chapter_id": "aniyomi-internal-chapter-id-string",
    "state": "done",
    "page_count": 24,
    "created_at": "2026-03-15T12:00:00Z"
  }
]
```

### Failure Handling

**What triggers `failed` state:**
- `gallery-dl` cannot fetch the source URL (unsupported source, network error, 404)
- Mokuro exits with a non-zero return code
- Processing exceeds a configurable timeout (default: 30 minutes)

**Server behavior on failure:**
- Celery worker catches the exception, writes `error_message` to SQLite, sets state to `failed`
- Processed partial output (if any) is deleted from disk
- The SQLite record is retained for debugging

**App behavior on failure:**
- Polling service receives `failed` state
- Notification updates to show "Processing failed: {error_message}"
- Chapter badge shows a distinct error indicator (red icon) instead of the success badge
- Long-press menu on the chapter shows "Retry Mokuro" which re-submits `POST /jobs` and creates a new `job_id`, replacing the old record in the Room DB

**gallery-dl source limitations:**
- `gallery-dl` supports a defined set of manga sources. Many Aniyomi extension sources are not supported.
- When a submission fails due to an unsupported source, the error message clearly states this: "Source not supported by gallery-dl."
- This is a known limitation of the personal-server approach and is not worked around in v1. A list of tested supported sources is maintained in the project README.

### Job Processing Flow

1. Celery worker picks up job from Redis queue, sets state to `processing`
2. `gallery-dl` fetches chapter images to a temp directory; `page_count` written to SQLite when complete
3. Mokuro runs on the image directory, outputting one HTML file per page (`page_001.html`, `page_002.html`, etc.); `progress` updated per page
4. Output copied to `/processed/{job_id}/` on shared volume
5. SQLite record updated with state `done`, `page_count`; `created_at` is written at job submission (step 1)
6. Redis job state updated to `done`

### Persistence

- **Redis** — ephemeral, tracks active job queue and in-progress state
- **SQLite** — permanent record of all jobs

**Server SQLite schema (`jobs` table):**

| Column | Type | Description |
|---|---|---|
| `job_id` | TEXT (PK) | UUID v4 |
| `chapter_id` | TEXT | Aniyomi chapter identifier |
| `source_url` | TEXT | Original submission URL |
| `state` | TEXT | `pending`, `processing`, `done`, `failed` |
| `progress` | REAL | 0.0–1.0 |
| `page_count` | INTEGER | Nullable until fetch completes |
| `error_message` | TEXT | Nullable, populated on failure |
| `created_at` | TEXT | ISO 8601 timestamp, written at job submission |

---

## Android App (Aniyomi Fork)

### Modifications

Everything outside the three areas below is left untouched (library, sources, browse, anime, etc.).

#### 1. Server Settings Screen

- New entry in Aniyomi's existing settings
- Fields: server URL, bearer token
- Stored in existing Aniyomi SharedPreferences system

#### 2. "Send to Mokuro" Action

- Added to the chapter list long-press context menu
- POSTs to `POST /jobs` with `source_url` and `chapter_id`
- If chapter already has a `done` record in Room DB, shows a confirmation dialog before re-submitting ("This chapter is already processed. Reprocess?")
- If chapter already has a `failed` record, shows "Retry Mokuro" with no confirmation needed
- Saves returned `job_id` to local Room DB (`mokuro_jobs` table)
- Background polling service starts, polling every 5 seconds while state is `pending` or `processing`; polling stops when state is `done` or `failed`, or after a 35-minute client-side timeout
- Persistent notification shows chapter name and progress bar (driven by `progress` float from status endpoint)
- On `done`: notification updates to "Ready to read", badge appears on chapter
- On `failed`: notification updates to "Processing failed: {error_message}", error badge appears on chapter

#### 3. Mokuro Reader Mode

- Chapters with state `done` show a green badge in the chapter list
- Chapters with state `failed` show a red badge
- Opening a processed (`done`) chapter launches `MokuroReaderActivity` instead of the standard reader
- Standard reader remains unchanged for unprocessed chapters

### Local Database (Room)

New table `mokuro_jobs`:

| Column | Type | Description |
|---|---|---|
| `chapter_id` | String (PK) | Aniyomi chapter identifier |
| `job_id` | String | Server job UUID |
| `state` | String | `pending`, `processing`, `done`, `failed` |
| `page_count` | Int? | Nullable until done |
| `error_message` | String? | Nullable, populated on failed |
| `server_url` | String | Server base URL at time of submission |
| `created_at` | Long | Unix timestamp millis |

**Sync behavior:** On first app launch after install (detected by empty `mokuro_jobs` table), call `GET /jobs` and populate Room DB from server records. On subsequent launches, Room DB is the primary source of truth; no automatic sync is performed. User can trigger a manual sync from the settings screen.

**Conflict resolution on sync:** Server record wins. If server shows `done` but Room shows `failed` (or vice versa), server state is written to Room.

---

## Mokuro Reader (`MokuroReaderActivity`)

### Mokuro Output Format

Mokuro outputs one self-contained HTML file per manga page. Each file embeds the page image and overlays `<span>` elements with Japanese text for each detected word. Files are named `page_001.html`, `page_002.html`, etc. (zero-padded to 3 digits).

The app loads one page at a time. Page navigation (next/prev) is handled natively in Kotlin — the activity increments the page index and loads the new URL into the WebView.

### Three Layers

#### Layer 1 — WebView (base)

- Full-screen WebView loads one Mokuro page HTML at a time from:
  `{server_url}/jobs/{job_id}/pages/page_{index}.html?token={bearer_token}`
- JavaScript enabled
- A JavaScript interface named `MokuroInterface` is injected into every page load via `WebView.addJavascriptInterface`
- After page load, the app injects a JS snippet that attaches `onclick` listeners to all `<span>` elements with Japanese text. On tap, the listener calls:
  ```javascript
  MokuroInterface.onWordTap(word, sentenceContext)
  ```
  where `word` is the text of the tapped span, and `sentenceContext` is the concatenated text of all sibling spans within the same panel container (the nearest parent `div` or `p` of the tapped span).
- Kotlin `@JavascriptInterface` method `onWordTap(word: String, context: String)` receives the callback on the main thread

#### Layer 2 — Dictionary Popup (middle)

- Bottom sheet appearing on word tap (triggered by `onWordTap` callback)
- Looks up `word` in a **bundled JMdict SQLite database** (~50MB, shipped with APK as an asset, copied to app's internal storage on first launch)
- Lookup strategy: exact match first; if no result, try deinflection (plain form lookup for verbs/adjectives) using a bundled deinflection table
- Fully offline — no network call per lookup
- Displays:
  - Word + reading (furigana)
  - Part of speech
  - Top 3 English definitions
- JMdict is a static asset — updates are delivered via app update. This is acknowledged as a known tradeoff for v1.

#### Layer 3 — Anki Export (top)

- "Add to Anki" button inside the dictionary popup
- Calls AnkiDroid API via Android Intent (`com.ichi2.anki.api`)
- Card fields:
  - **Front:** tapped word + `sentenceContext` (the surrounding panel text passed from JS)
  - **Back:** reading + top 3 definitions
- Uses AnkiDroid's standard `addNote` API — no AnkiConnect server needed
- If AnkiDroid is not installed, button shows a toast: "AnkiDroid is not installed"

---

## Data Flow

### First-Time Processing

1. Long-press chapter → "Send to Mokuro"
2. App POSTs `{source_url, chapter_id}` to server, saves `job_id` + `pending` state to Room DB
3. Background service polls `/jobs/{job_id}/status` every 5 seconds; updates Room DB and notification progress bar
4. Server fetches images via gallery-dl, runs Mokuro page-by-page, writes output + SQLite record
5. App receives `done` state: stops polling, shows success notification, renders green badge on chapter

### Reading a Processed Chapter

1. Tap chapter with green badge → `MokuroReaderActivity` opens at page 1
2. WebView loads `page_001.html` from server with query-token auth
3. App injects JS onclick listeners after page load completes
4. User taps word → `MokuroInterface.onWordTap(word, context)` fires in Kotlin
5. JMdict lookup runs on background thread; bottom sheet appears with results
6. User taps "Add to Anki" → AnkiDroid Intent → card created with word + context on front, reading + definitions on back

### Re-opening the App

1. App checks local Room DB for processed chapters → renders badges
2. If first launch after install (empty Room DB): syncs from `GET /jobs`, server state wins

---

## Tech Summary

| Layer | Technology |
|---|---|
| Android app | Aniyomi fork (Kotlin) |
| Reader | WebView + JS interface (`MokuroInterface`) |
| Dictionary | Bundled JMdict SQLite (~50MB asset) + deinflection table |
| Flashcards | AnkiDroid API (Intent) |
| Server API | FastAPI (Python) |
| Job queue | Celery + Redis |
| Manga fetching | gallery-dl |
| OCR processing | Mokuro |
| Job persistence | Server SQLite |
| App persistence | Room (SQLite) |
| Deployment | Docker Compose |

---

## Known Limitations (v1)

1. **gallery-dl source coverage** — not all Aniyomi sources are supported. Failed jobs clearly report this. Supported sources documented in README.
2. **JMdict update cadence** — dictionary bundled with APK, updated only on app releases.
3. **APK size** — bundling JMdict (~50MB) will require the app to be sideloaded or distributed outside the Play Store (consistent with Aniyomi's existing distribution model).
4. **No re-submission deduplication on server** — if the same chapter is re-submitted, a new job is created. Old processed files are not automatically deleted.
