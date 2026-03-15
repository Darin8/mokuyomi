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
