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
    monkeypatch.setattr("api.main.celery_app", mock, raising=False)
    yield mock
