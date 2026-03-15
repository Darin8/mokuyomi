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
