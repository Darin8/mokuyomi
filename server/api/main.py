# server/api/main.py
import os
import uuid
from contextlib import asynccontextmanager
from fastapi import FastAPI, Depends, HTTPException, Query
from fastapi.responses import FileResponse
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

@app.get("/jobs/{job_id}/status", response_model=JobStatusResponse,
         dependencies=[Depends(require_auth)])
async def get_job_status(job_id: str):
    job = await get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job

@app.get("/jobs/{job_id}/files/{filename:path}")
async def serve_file(job_id: str, filename: str, token: str = Query(None)):
    from api.config import settings as _s
    # Only require token for the HTML viewer; images are protected by the unguessable UUID job_id
    if filename.endswith(".html") and token != _s.token:
        raise HTTPException(status_code=401, detail="Invalid token")
    if ".." in filename:
        raise HTTPException(status_code=400, detail="Invalid filename")
    file_path = os.path.join(_s.processed_dir, job_id, filename)
    if not os.path.isfile(file_path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(file_path)
