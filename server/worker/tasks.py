# server/worker/tasks.py
import asyncio
import glob
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time

from api.config import settings
from api.database import get_job, update_job
from worker.celery_app import celery_app

logger = logging.getLogger(__name__)


def _run(coro):
    return asyncio.run(coro)


def fetch_images(source_url: str, dest_dir: str) -> str:
    """Run gallery-dl to fetch chapter images into dest_dir. Returns dest_dir."""
    logger.info("[gallery-dl] Fetching images from %s", source_url)
    t0 = time.time()
    result = subprocess.run(
        ["gallery-dl", "-D", dest_dir, "--filename", "{filename}.{extension}", source_url],
        capture_output=True, text=True, timeout=600,
    )
    elapsed = time.time() - t0
    if result.returncode != 0:
        logger.error("[gallery-dl] FAILED after %.1fs:\n%s", elapsed, result.stderr.strip())
        raise RuntimeError(f"gallery-dl failed: {result.stderr.strip()}")
    image_count = len(glob.glob(os.path.join(dest_dir, "**", "*.*"), recursive=True))
    logger.info("[gallery-dl] Done in %.1fs — %d files downloaded", elapsed, image_count)
    return dest_dir


def run_mokuro(image_dir: str) -> str:
    """Run Mokuro on image_dir. Returns path to the generated HTML file."""
    image_files = [f for f in os.listdir(image_dir) if not f.startswith('.')]
    logger.info("[mokuro] Starting OCR on %d images in %s", len(image_files), image_dir)
    t0 = time.time()
    result = subprocess.run(
        ["mokuro", "--disable_confirmation=True", "--legacy_html=True", image_dir],
        capture_output=False, text=True, timeout=7200,
    )
    elapsed = time.time() - t0
    if result.returncode != 0:
        logger.error("[mokuro] FAILED after %.1fs", elapsed)
        raise RuntimeError(f"Mokuro failed: non-zero exit after {elapsed:.1f}s")
    # mokuro writes <parent>/<image_dir_name>.html
    html_path = os.path.join(os.path.dirname(image_dir), os.path.basename(image_dir) + ".html")
    if not os.path.exists(html_path):
        raise RuntimeError(f"Mokuro finished but HTML not found at {html_path}")
    logger.info("[mokuro] Done in %.1fs — output at %s", elapsed, html_path)
    return html_path


def copy_output(html_path: str, image_dir: str, job_id: str) -> int:
    """Copy viewer HTML + images to the processed volume. Returns page count."""
    dest = os.path.join(settings.processed_dir, job_id)
    os.makedirs(dest, exist_ok=True)

    shutil.copy2(html_path, os.path.join(dest, "viewer.html"))

    # Copy images alongside the HTML so relative image URLs resolve
    dest_images = os.path.join(dest, os.path.basename(image_dir))
    if os.path.exists(dest_images):
        shutil.rmtree(dest_images)
    shutil.copytree(image_dir, dest_images)

    page_count = len([f for f in os.listdir(image_dir) if not f.startswith('.')])
    logger.info("[copy] Output written to %s (%d pages)", dest, page_count)
    return page_count


@celery_app.task(name="worker.tasks.process_chapter")
def process_chapter(job_id: str) -> None:
    _mod = sys.modules[__name__]

    job = _run(get_job(job_id))
    if not job:
        logger.warning("[job:%s] Not found in DB, skipping", job_id)
        return

    logger.info("[job:%s] Starting — %s", job_id, job["source_url"])
    _run(update_job(job_id, state="processing", progress=0.0))

    with tempfile.TemporaryDirectory() as tmp_dir:
        image_dir = os.path.join(tmp_dir, "images")
        os.makedirs(image_dir)

        try:
            _mod.fetch_images(job["source_url"], image_dir)
            html_path = _mod.run_mokuro(image_dir)
            page_count = _mod.copy_output(html_path, image_dir, job_id)
            _run(update_job(job_id, state="done", progress=1.0, page_count=page_count))
            logger.info("[job:%s] Complete — %d pages ready", job_id, page_count)

        except Exception as exc:
            logger.error("[job:%s] Failed: %s", job_id, exc)
            partial = os.path.join(settings.processed_dir, job_id)
            if os.path.exists(partial):
                shutil.rmtree(partial, ignore_errors=True)
            _run(update_job(job_id, state="failed", error_message=str(exc)))
