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
