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
