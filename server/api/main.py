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
