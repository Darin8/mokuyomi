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
