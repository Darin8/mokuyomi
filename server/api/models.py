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
