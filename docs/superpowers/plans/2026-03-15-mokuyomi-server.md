# Mokuyomi Server Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Docker Compose server that accepts manga chapter URLs, fetches images via gallery-dl, processes them with Mokuro, and serves the resulting per-page HTML files to the Android app.

**Architecture:** FastAPI handles the REST API and file serving. Celery workers process jobs asynchronously, writing results to a shared volume. Redis is the Celery broker. SQLite persists job records permanently beyond Redis restarts.

**Tech Stack:** Python 3.11, FastAPI, Celery, Redis, SQLite (via `aiosqlite` + raw SQL), gallery-dl, Mokuro, Docker Compose, pytest, httpx

---

## File Structure

```
server/
├── docker-compose.yml           # Orchestrates api, worker, redis services
├── .env.example                 # Documents required env vars
├── Dockerfile.api               # FastAPI image
├── Dockerfile.worker            # Celery worker image (includes Mokuro + gallery-dl)
├── requirements.txt             # Shared Python dependencies
├── api/
│   ├── __init__.py
│   ├── main.py                  # FastAPI app + route registration
│   ├── auth.py                  # Bearer token dependency
│   ├── config.py                # Settings loaded from env vars
│   ├── database.py              # SQLite init, queries (create/read/update jobs)
│   └── models.py                # Pydantic request/response schemas
├── worker/
│   ├── __init__.py
│   ├── celery_app.py            # Celery instance + config
│   └── tasks.py                 # process_chapter task: gallery-dl → Mokuro → disk
└── tests/
    ├── conftest.py              # pytest fixtures (test app client, tmp db, celery mock)
    ├── test_auth.py             # Auth rejection cases
    ├── test_jobs_api.py         # POST /jobs, GET /jobs, GET /jobs/{id}/status
    ├── test_file_serving.py     # GET /jobs/{id}/pages/{filename}
    └── test_tasks.py            # Worker task logic (mocked gallery-dl + Mokuro)
```

---

## Chunk 1: Project Scaffold + Auth + Config

### Task 1: Scaffold directory structure and requirements

**Files:**
- Create: `server/requirements.txt`
- Create: `server/api/__init__.py`
- Create: `server/worker/__init__.py`
- Create: `server/tests/__init__.py`
- Create: `server/.env.example`

- [ ] **Step 1: Create requirements.txt**

```
fastapi==0.115.0
uvicorn[standard]==0.30.0
celery==5.4.0
redis==5.0.8
aiosqlite==0.20.0
httpx==0.27.0
pydantic-settings==2.4.0
pydantic==2.8.0
pytest==8.3.0
pytest-asyncio==0.23.0
```

- [ ] **Step 2: Create empty `__init__.py` files**

```bash
touch server/api/__init__.py server/worker/__init__.py server/tests/__init__.py
```

- [ ] **Step 3: Create `.env.example`**

```
MOKUYOMI_TOKEN=changeme
REDIS_URL=redis://redis:6379/0
PROCESSED_DIR=/processed
DATABASE_PATH=/data/mokuyomi.db
```

- [ ] **Step 4: Commit**

```bash
git add server/
git commit -m "chore: scaffold server directory structure"
```

---

### Task 2: Config and auth

**Files:**
- Create: `server/api/config.py`
- Create: `server/api/auth.py`
- Create: `server/api/main.py` (minimal skeleton)
- Create: `server/tests/conftest.py`
- Create: `server/tests/test_auth.py`

- [ ] **Step 1: Write conftest.py — establish shared fixtures first**

```python
# server/tests/conftest.py
import asyncio
import pytest
from unittest.mock import MagicMock

# ── Token helper ─────────────────────────────────────────────────────────────

TEST_TOKEN = "testtoken"
AUTH_HEADER = {"Authorization": f"Bearer {TEST_TOKEN}"}

@pytest.fixture(autouse=True)
def patch_settings_token(monkeypatch):
    """Patch the live settings instance so all tests see TEST_TOKEN."""
    import api.config as cfg
    monkeypatch.setattr(cfg.settings, "token", TEST_TOKEN)

# ── Database helper ───────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def tmp_db(monkeypatch, tmp_path):
    """Point the database at a fresh temp file for each test."""
    import api.config as cfg
    db_file = str(tmp_path / "test.db")
    monkeypatch.setattr(cfg.settings, "database_path", db_file)
    import api.database as db_mod
    monkeypatch.setattr(db_mod, "DB_PATH", db_file)
    asyncio.run(db_mod.init_db())
    yield db_file

# ── Celery mock ───────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def mock_celery(monkeypatch):
    """Prevent Celery from trying to connect to Redis during tests."""
    mock = MagicMock()
    monkeypatch.setattr("api.main.celery_app", mock)
    yield mock
```

- [ ] **Step 2: Write failing auth tests**

```python
# server/tests/test_auth.py
from fastapi.testclient import TestClient
from api.main import app
from tests.conftest import AUTH_HEADER

client = TestClient(app)

def test_missing_token_returns_401():
    response = client.get("/jobs")
    assert response.status_code == 401

def test_wrong_token_returns_401():
    response = client.get("/jobs", headers={"Authorization": "Bearer wrong"})
    assert response.status_code == 401

def test_correct_token_passes():
    response = client.get("/jobs", headers=AUTH_HEADER)
    assert response.status_code == 200
```

- [ ] **Step 3: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_auth.py -v
```
Expected: `ModuleNotFoundError` or `ImportError` — app does not exist yet

- [ ] **Step 4: Write config.py**

Note: fields are named to match env vars exactly (no `env_prefix`), with explicit `env` aliases for clarity.

```python
# server/api/config.py
from pydantic import Field
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    token: str = Field(default="changeme", validation_alias="MOKUYOMI_TOKEN")
    redis_url: str = Field(default="redis://localhost:6379/0", validation_alias="REDIS_URL")
    processed_dir: str = Field(default="/processed", validation_alias="PROCESSED_DIR")
    database_path: str = Field(default="/data/mokuyomi.db", validation_alias="DATABASE_PATH")

    model_config = {"env_file": ".env", "populate_by_name": True}

settings = Settings()
```

- [ ] **Step 5: Write auth.py**

```python
# server/api/auth.py
from fastapi import HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from api.config import settings

bearer = HTTPBearer(auto_error=False)

def require_auth(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
) -> str:
    if credentials is None or credentials.credentials != settings.token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return credentials.credentials
```

- [ ] **Step 6: Write minimal main.py**

```python
# server/api/main.py
from fastapi import FastAPI, Depends
from api.auth import require_auth

app = FastAPI(title="Mokuyomi Server")

@app.get("/jobs", dependencies=[Depends(require_auth)])
async def list_jobs():
    return []
```

- [ ] **Step 7: Run auth tests**

```bash
cd server && python -m pytest tests/test_auth.py -v
```
Expected: 3 PASSED

- [ ] **Step 8: Commit**

```bash
git add server/api/config.py server/api/auth.py server/api/main.py \
        server/tests/conftest.py server/tests/test_auth.py
git commit -m "feat: add bearer token auth and config"
```

---

## Chunk 2: Database Layer

### Task 3: SQLite database initialization and queries

**Files:**
- Create: `server/api/database.py`
- Modify: `server/tests/conftest.py` (already handles db fixture)

- [ ] **Step 1: Write failing database tests**

```python
# server/tests/test_jobs_api.py
import asyncio
import pytest
from api.database import create_job, get_job, list_jobs, update_job

# ── Database unit tests ───────────────────────────────────────────────────────

def test_create_and_get_job():
    job = asyncio.run(
        create_job(job_id="test-id", chapter_id="ch-1", source_url="https://example.com/ch/1")
    )
    assert job["job_id"] == "test-id"
    assert job["state"] == "pending"
    assert job["progress"] == 0.0
    assert job["error_message"] is None
    assert job["page_count"] is None

def test_update_job_state():
    asyncio.run(create_job(job_id="test-id", chapter_id="ch-1", source_url="https://example.com"))
    asyncio.run(update_job("test-id", state="processing", progress=0.5))
    job = asyncio.run(get_job("test-id"))
    assert job["state"] == "processing"
    assert job["progress"] == 0.5

def test_list_jobs_returns_all():
    asyncio.run(create_job("id-1", "ch-1", "https://example.com/1"))
    asyncio.run(create_job("id-2", "ch-2", "https://example.com/2"))
    jobs = asyncio.run(list_jobs())
    assert len(jobs) == 2
    chapter_ids = {j["chapter_id"] for j in jobs}
    assert "ch-1" in chapter_ids
    assert "ch-2" in chapter_ids
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_jobs_api.py -v
```
Expected: FAIL with `ImportError`

- [ ] **Step 3: Write database.py**

```python
# server/api/database.py
import aiosqlite
from datetime import datetime, timezone
from api.config import settings

DB_PATH = settings.database_path

CREATE_TABLE = """
CREATE TABLE IF NOT EXISTS jobs (
    job_id TEXT PRIMARY KEY,
    chapter_id TEXT NOT NULL,
    source_url TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending',
    progress REAL NOT NULL DEFAULT 0.0,
    page_count INTEGER,
    error_message TEXT,
    created_at TEXT NOT NULL
)
"""

async def init_db() -> None:
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(CREATE_TABLE)
        await db.commit()

async def create_job(job_id: str, chapter_id: str, source_url: str) -> dict:
    created_at = datetime.now(timezone.utc).isoformat()
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO jobs (job_id, chapter_id, source_url, created_at) VALUES (?, ?, ?, ?)",
            (job_id, chapter_id, source_url, created_at),
        )
        await db.commit()
    return await get_job(job_id)

async def get_job(job_id: str) -> dict | None:
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)) as cursor:
            row = await cursor.fetchone()
            return dict(row) if row else None

async def update_job(job_id: str, **kwargs) -> None:
    allowed = {"state", "progress", "page_count", "error_message"}
    fields = {k: v for k, v in kwargs.items() if k in allowed}
    if not fields:
        return
    set_clause = ", ".join(f"{k} = ?" for k in fields)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            f"UPDATE jobs SET {set_clause} WHERE job_id = ?",
            (*fields.values(), job_id),
        )
        await db.commit()

async def list_jobs() -> list[dict]:
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT job_id, chapter_id, state, page_count, created_at "
            "FROM jobs ORDER BY created_at DESC"
        ) as cursor:
            rows = await cursor.fetchall()
            return [dict(r) for r in rows]
```

- [ ] **Step 4: Run database tests**

```bash
cd server && python -m pytest tests/test_jobs_api.py -v
```
Expected: 3 PASSED

- [ ] **Step 5: Commit**

```bash
git add server/api/database.py server/tests/test_jobs_api.py
git commit -m "feat: add SQLite database layer for job records"
```

---

## Chunk 3: API Endpoints

### Task 4: Pydantic models

**Files:**
- Create: `server/api/models.py`

- [ ] **Step 1: Write failing model tests**

Create `server/tests/test_models.py`:

```python
# server/tests/test_models.py
import pytest
from pydantic import ValidationError
from api.models import SubmitJobRequest, JobCreatedResponse, JobStatusResponse, JobSummary

def test_submit_job_requires_source_url_and_chapter_id():
    with pytest.raises(ValidationError):
        SubmitJobRequest(source_url="https://example.com")  # missing chapter_id

def test_job_status_nullable_fields_accept_none():
    resp = JobStatusResponse(
        job_id="id", chapter_id="ch", state="pending",
        progress=0.0, page_count=None, error_message=None
    )
    assert resp.page_count is None
    assert resp.error_message is None

def test_job_status_nullable_fields_accept_values():
    resp = JobStatusResponse(
        job_id="id", chapter_id="ch", state="done",
        progress=1.0, page_count=24, error_message=None
    )
    assert resp.page_count == 24

def test_job_summary_shape():
    s = JobSummary(job_id="id", chapter_id="ch", state="done",
                   page_count=10, created_at="2026-03-15T00:00:00Z")
    assert s.state == "done"
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_models.py -v
```
Expected: FAIL with `ImportError`

- [ ] **Step 3: Write models.py**

```python
# server/api/models.py
from pydantic import BaseModel

class SubmitJobRequest(BaseModel):
    source_url: str
    chapter_id: str

class JobCreatedResponse(BaseModel):
    job_id: str
    chapter_id: str
    state: str

class JobStatusResponse(BaseModel):
    job_id: str
    chapter_id: str
    state: str
    progress: float
    page_count: int | None
    error_message: str | None

class JobSummary(BaseModel):
    job_id: str
    chapter_id: str
    state: str
    page_count: int | None
    created_at: str
```

- [ ] **Step 4: Run model tests**

```bash
cd server && python -m pytest tests/test_models.py -v
```
Expected: 4 PASSED

- [ ] **Step 5: Commit**

```bash
git add server/api/models.py server/tests/test_models.py
git commit -m "feat: add Pydantic API models"
```

---

### Task 5: POST /jobs and GET /jobs endpoints

**Files:**
- Modify: `server/api/main.py`
- Modify: `server/tests/test_jobs_api.py`

- [ ] **Step 1: Write failing endpoint tests**

Append to `server/tests/test_jobs_api.py`:

```python
from fastapi.testclient import TestClient
from api.main import app
from tests.conftest import AUTH_HEADER

client = TestClient(app)

# ── Endpoint tests ────────────────────────────────────────────────────────────

def test_submit_job_returns_201():
    response = client.post("/jobs", json={
        "source_url": "https://example.com/chapter/1",
        "chapter_id": "ch-001"
    }, headers=AUTH_HEADER)
    assert response.status_code == 201
    body = response.json()
    assert body["chapter_id"] == "ch-001"
    assert body["state"] == "pending"
    assert "job_id" in body

def test_list_jobs_returns_submitted():
    client.post("/jobs", json={
        "source_url": "https://example.com/1",
        "chapter_id": "ch-001"
    }, headers=AUTH_HEADER)
    response = client.get("/jobs", headers=AUTH_HEADER)
    assert response.status_code == 200
    jobs = response.json()
    assert any(j["chapter_id"] == "ch-001" for j in jobs)
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_jobs_api.py::test_submit_job_returns_201 -v
```
Expected: FAIL 404 (route not defined yet)

- [ ] **Step 3: Implement POST /jobs and GET /jobs in main.py**

```python
# server/api/main.py
import uuid
from contextlib import asynccontextmanager
from fastapi import FastAPI, Depends, HTTPException
from api.auth import require_auth
from api.database import init_db, create_job, list_jobs as db_list_jobs, get_job
from api.models import SubmitJobRequest, JobCreatedResponse, JobStatusResponse, JobSummary

# Imported here so tests can monkeypatch it via conftest
from worker.celery_app import celery_app  # noqa: F401 (patched in tests)

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield

app = FastAPI(title="Mokuyomi Server", lifespan=lifespan)

@app.post("/jobs", status_code=201, response_model=JobCreatedResponse,
          dependencies=[Depends(require_auth)])
async def submit_job(request: SubmitJobRequest):
    job_id = str(uuid.uuid4())
    job = await create_job(
        job_id=job_id,
        chapter_id=request.chapter_id,
        source_url=request.source_url,
    )
    celery_app.send_task("worker.tasks.process_chapter", args=[job_id])
    return job

@app.get("/jobs", response_model=list[JobSummary], dependencies=[Depends(require_auth)])
async def list_all_jobs():
    return await db_list_jobs()
```

- [ ] **Step 4: Run endpoint tests**

```bash
cd server && python -m pytest tests/test_jobs_api.py -v
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add server/api/main.py server/tests/test_jobs_api.py
git commit -m "feat: add POST /jobs and GET /jobs endpoints"
```

---

### Task 6: GET /jobs/{job_id}/status endpoint

**Files:**
- Modify: `server/api/main.py`
- Modify: `server/tests/test_jobs_api.py`

- [ ] **Step 1: Write failing tests**

Append to `server/tests/test_jobs_api.py`:

```python
def test_get_job_status():
    create_resp = client.post("/jobs", json={
        "source_url": "https://example.com/1",
        "chapter_id": "ch-001"
    }, headers=AUTH_HEADER)
    job_id = create_resp.json()["job_id"]

    resp = client.get(f"/jobs/{job_id}/status", headers=AUTH_HEADER)
    assert resp.status_code == 200
    body = resp.json()
    assert body["state"] == "pending"
    assert body["progress"] == 0.0
    assert body["error_message"] is None
    assert body["page_count"] is None

def test_get_nonexistent_job_returns_404():
    resp = client.get("/jobs/nonexistent-id/status", headers=AUTH_HEADER)
    assert resp.status_code == 404
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_jobs_api.py::test_get_job_status -v
```
Expected: FAIL 404 (route not defined)

- [ ] **Step 3: Add status route to main.py**

```python
# Append to server/api/main.py

@app.get("/jobs/{job_id}/status", response_model=JobStatusResponse,
         dependencies=[Depends(require_auth)])
async def get_job_status(job_id: str):
    job = await get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job
```

- [ ] **Step 4: Run tests**

```bash
cd server && python -m pytest tests/test_jobs_api.py -v
```
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add server/api/main.py server/tests/test_jobs_api.py
git commit -m "feat: add GET /jobs/{id}/status endpoint"
```

---

### Task 7: File serving endpoint

**Files:**
- Modify: `server/api/main.py`
- Create: `server/tests/test_file_serving.py`

- [ ] **Step 1: Write failing tests**

```python
# server/tests/test_file_serving.py
import pytest
from fastapi.testclient import TestClient
from api.main import app
import api.config as cfg

client = TestClient(app)
TOKEN = "testtoken"  # matches conftest patch

def test_serve_existing_page(tmp_path, monkeypatch):
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    job_dir = tmp_path / "test-job-id"
    job_dir.mkdir()
    (job_dir / "page_001.html").write_text("<html>test page</html>")

    resp = client.get(f"/jobs/test-job-id/pages/page_001.html?token={TOKEN}")
    assert resp.status_code == 200
    assert "test page" in resp.text

def test_serve_nonexistent_page_returns_404(tmp_path, monkeypatch):
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    resp = client.get(f"/jobs/fake-id/pages/page_001.html?token={TOKEN}")
    assert resp.status_code == 404

def test_missing_token_returns_401(tmp_path, monkeypatch):
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    resp = client.get("/jobs/test-id/pages/page_001.html")
    assert resp.status_code == 401

def test_path_traversal_rejected(tmp_path, monkeypatch):
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    resp = client.get(f"/jobs/test-id/pages/../../etc/passwd?token={TOKEN}")
    assert resp.status_code == 400
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_file_serving.py -v
```
Expected: FAIL 404 on first test (route not defined)

- [ ] **Step 3: Add file serving route to main.py**

```python
# Append to server/api/main.py
import os
from fastapi import Query
from fastapi.responses import FileResponse

@app.get("/jobs/{job_id}/pages/{filename}")
async def serve_page(job_id: str, filename: str, token: str = Query(...)):
    if token != celery_app and False:  # placeholder — real check below
        pass
    from api.config import settings as _s
    if token != _s.token:
        raise HTTPException(status_code=401, detail="Invalid token")
    if ".." in filename or "/" in filename:
        raise HTTPException(status_code=400, detail="Invalid filename")
    file_path = os.path.join(_s.processed_dir, job_id, filename)
    if not os.path.isfile(file_path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(file_path, media_type="text/html")
```

Clean up the placeholder comment — here is the final form of the route:

```python
@app.get("/jobs/{job_id}/pages/{filename}")
async def serve_page(job_id: str, filename: str, token: str = Query(...)):
    from api.config import settings as _s
    if token != _s.token:
        raise HTTPException(status_code=401, detail="Invalid token")
    if ".." in filename or "/" in filename:
        raise HTTPException(status_code=400, detail="Invalid filename")
    file_path = os.path.join(_s.processed_dir, job_id, filename)
    if not os.path.isfile(file_path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(file_path, media_type="text/html")
```

- [ ] **Step 4: Run file serving tests**

```bash
cd server && python -m pytest tests/test_file_serving.py -v
```
Expected: 4 PASSED

- [ ] **Step 5: Run full suite**

```bash
cd server && python -m pytest -v
```
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add server/api/main.py server/tests/test_file_serving.py
git commit -m "feat: add file serving endpoint with query-token auth and path traversal guard"
```

---

## Chunk 4: Celery Worker

### Task 8: Celery app setup

**Files:**
- Create: `server/worker/celery_app.py`

- [ ] **Step 1: Write celery_app.py**

```python
# server/worker/celery_app.py
from celery import Celery
from api.config import settings

celery_app = Celery(
    "mokuyomi",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=["worker.tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    task_soft_time_limit=1800,  # 30 min
    task_time_limit=1860,
)
```

- [ ] **Step 2: Commit**

```bash
git add server/worker/celery_app.py
git commit -m "feat: add Celery app configuration"
```

---

### Task 9: process_chapter task

**Files:**
- Create: `server/worker/tasks.py`
- Create: `server/tests/test_tasks.py`

- [ ] **Step 1: Write failing task tests**

```python
# server/tests/test_tasks.py
import asyncio
from unittest.mock import patch
import pytest
from api.database import create_job, get_job

@pytest.fixture
def seeded_job():
    asyncio.run(create_job("job-001", "ch-001", "https://example.com/chapter/1"))
    return "job-001"

def test_process_chapter_sets_done_on_success(seeded_job, tmp_path, monkeypatch):
    import api.config as cfg
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    with patch("worker.tasks.fetch_images", return_value=str(tmp_path / "imgs")), \
         patch("worker.tasks.run_mokuro", return_value=5), \
         patch("worker.tasks.copy_output"):
        from worker import tasks
        import importlib; importlib.reload(tasks)  # pick up fresh monkeypatched settings
        tasks.process_chapter("job-001")
    job = asyncio.run(get_job("job-001"))
    assert job["state"] == "done"
    assert job["progress"] == 1.0

def test_process_chapter_sets_failed_on_fetch_error(seeded_job, tmp_path, monkeypatch):
    import api.config as cfg
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    with patch("worker.tasks.fetch_images", side_effect=RuntimeError("gallery-dl failed")):
        from worker.tasks import process_chapter
        process_chapter("job-001")
    job = asyncio.run(get_job("job-001"))
    assert job["state"] == "failed"
    assert "gallery-dl failed" in job["error_message"]

def test_process_chapter_sets_failed_on_mokuro_error(seeded_job, tmp_path, monkeypatch):
    import api.config as cfg
    monkeypatch.setattr(cfg.settings, "processed_dir", str(tmp_path))
    with patch("worker.tasks.fetch_images", return_value=str(tmp_path / "imgs")), \
         patch("worker.tasks.run_mokuro", side_effect=RuntimeError("mokuro crashed")):
        from worker.tasks import process_chapter
        process_chapter("job-001")
    job = asyncio.run(get_job("job-001"))
    assert job["state"] == "failed"
    assert "mokuro crashed" in job["error_message"]
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd server && python -m pytest tests/test_tasks.py -v
```
Expected: FAIL with `ImportError`

- [ ] **Step 3: Write tasks.py**

```python
# server/worker/tasks.py
import asyncio
import glob
import os
import shutil
import subprocess
import tempfile

from api.config import settings
from api.database import get_job, update_job
from worker.celery_app import celery_app


def _run(coro):
    return asyncio.run(coro)


def fetch_images(source_url: str, dest_dir: str) -> str:
    """Run gallery-dl to fetch chapter images into dest_dir. Returns dest_dir."""
    result = subprocess.run(
        ["gallery-dl", "-d", dest_dir, source_url],
        capture_output=True, text=True, timeout=600,
    )
    if result.returncode != 0:
        raise RuntimeError(f"gallery-dl failed: {result.stderr.strip()}")
    return dest_dir


def run_mokuro(image_dir: str, output_dir: str) -> int:
    """Run Mokuro on image_dir, outputting HTML to output_dir. Returns page count."""
    result = subprocess.run(
        ["mokuro", "--output", output_dir, image_dir],
        capture_output=True, text=True, timeout=1800,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Mokuro failed: {result.stderr.strip()}")
    return len(glob.glob(os.path.join(output_dir, "*.html")))


def copy_output(src_dir: str, job_id: str) -> None:
    """Copy processed HTML files to the shared processed volume."""
    dest = os.path.join(settings.processed_dir, job_id)
    os.makedirs(dest, exist_ok=True)
    for html_file in glob.glob(os.path.join(src_dir, "*.html")):
        shutil.copy2(html_file, os.path.join(dest, os.path.basename(html_file)))


@celery_app.task(name="worker.tasks.process_chapter")
def process_chapter(job_id: str) -> None:
    job = _run(get_job(job_id))
    if not job:
        return

    _run(update_job(job_id, state="processing", progress=0.0))

    with tempfile.TemporaryDirectory() as tmp_dir:
        image_dir = os.path.join(tmp_dir, "images")
        output_dir = os.path.join(tmp_dir, "output")
        os.makedirs(image_dir)
        os.makedirs(output_dir)

        try:
            fetch_images(job["source_url"], image_dir)

            image_files = [
                f for f in os.listdir(image_dir)
                if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
            ]
            _run(update_job(job_id, page_count=len(image_files)))

            run_mokuro(image_dir, output_dir)
            copy_output(output_dir, job_id)
            _run(update_job(job_id, state="done", progress=1.0))

        except Exception as exc:
            partial = os.path.join(settings.processed_dir, job_id)
            if os.path.exists(partial):
                shutil.rmtree(partial, ignore_errors=True)
            _run(update_job(job_id, state="failed", error_message=str(exc)))
```

- [ ] **Step 4: Run task tests**

```bash
cd server && python -m pytest tests/test_tasks.py -v
```
Expected: 3 PASSED

- [ ] **Step 5: Run full test suite**

```bash
cd server && python -m pytest -v
```
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add server/worker/tasks.py server/tests/test_tasks.py
git commit -m "feat: add Celery process_chapter task with gallery-dl and Mokuro"
```

---

## Chunk 5: Docker Compose

### Task 10: Dockerfiles and Docker Compose

**Files:**
- Create: `server/Dockerfile.api`
- Create: `server/Dockerfile.worker`
- Create: `server/docker-compose.yml`

- [ ] **Step 1: Write Dockerfile.api**

```dockerfile
# server/Dockerfile.api
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY api/ ./api/
COPY worker/ ./worker/
CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 2: Write Dockerfile.worker**

```dockerfile
# server/Dockerfile.worker
FROM python:3.11-slim
WORKDIR /app
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt gallery-dl mokuro
COPY api/ ./api/
COPY worker/ ./worker/
CMD ["celery", "-A", "worker.celery_app.celery_app", "worker", "--loglevel=info"]
```

Note: `mokuro` pulls MangaOCR and its model weights (~400MB). The `model_cache` volume below persists the download across container restarts.

- [ ] **Step 3: Write docker-compose.yml**

```yaml
# server/docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    restart: unless-stopped

  api:
    build:
      context: .
      dockerfile: Dockerfile.api
    ports:
      - "8000:8000"
    environment:
      MOKUYOMI_TOKEN: ${MOKUYOMI_TOKEN}
      REDIS_URL: redis://redis:6379/0
      PROCESSED_DIR: /processed
      DATABASE_PATH: /data/mokuyomi.db
    volumes:
      - processed:/processed
      - db_data:/data
    depends_on:
      - redis
    restart: unless-stopped

  worker:
    build:
      context: .
      dockerfile: Dockerfile.worker
    environment:
      MOKUYOMI_TOKEN: ${MOKUYOMI_TOKEN}
      REDIS_URL: redis://redis:6379/0
      PROCESSED_DIR: /processed
      DATABASE_PATH: /data/mokuyomi.db
    volumes:
      - processed:/processed
      - db_data:/data
      - model_cache:/root/.cache
    depends_on:
      - redis
    restart: unless-stopped

volumes:
  processed:
  db_data:
  model_cache:
```

- [ ] **Step 4: Smoke test (requires Docker)**

```bash
cd server
echo "MOKUYOMI_TOKEN=testtoken123" > .env
docker compose build
docker compose up -d
sleep 3
curl -s -H "Authorization: Bearer testtoken123" http://localhost:8000/jobs
```
Expected: `[]`

- [ ] **Step 5: Shut down**

```bash
docker compose down
```

- [ ] **Step 6: Commit**

```bash
git add server/Dockerfile.api server/Dockerfile.worker server/docker-compose.yml server/.env.example
git commit -m "feat: add Docker Compose stack for API, worker, and Redis"
```

---

## Final: Server Complete

```bash
cd server && python -m pytest -v --tb=short
```
Expected: All tests PASS.

```bash
git tag server-v1.0
```
