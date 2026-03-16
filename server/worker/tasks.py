# server/worker/tasks.py
import asyncio
import glob
import os
import shutil
import subprocess
import sys
import tempfile

from api.config import settings
from api.database import get_job, update_job
from worker.celery_app import celery_app


def _run(coro):
    return asyncio.run(coro)


def fetch_images(source_url: str, dest_dir: str) -> str:
    """Run gallery-dl to fetch chapter images into dest_dir. Returns dest_dir."""
    result = subprocess.run(
        ["gallery-dl", "-d", dest_dir, source_url],
        capture_output=True, text=True, timeout=600,
    )
    if result.returncode != 0:
        raise RuntimeError(f"gallery-dl failed: {result.stderr.strip()}")
    return dest_dir


def run_mokuro(image_dir: str, output_dir: str) -> int:
    """Run Mokuro on image_dir, outputting HTML to output_dir. Returns page count."""
    result = subprocess.run(
        ["mokuro", "--output", output_dir, image_dir],
        capture_output=True, text=True, timeout=1800,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Mokuro failed: {result.stderr.strip()}")
    return len(glob.glob(os.path.join(output_dir, "*.html")))


def copy_output(src_dir: str, job_id: str) -> None:
    """Copy processed HTML files to the shared processed volume."""
    dest = os.path.join(settings.processed_dir, job_id)
    os.makedirs(dest, exist_ok=True)
    for html_file in glob.glob(os.path.join(src_dir, "*.html")):
        shutil.copy2(html_file, os.path.join(dest, os.path.basename(html_file)))


@celery_app.task(name="worker.tasks.process_chapter")
def process_chapter(job_id: str) -> None:
    _mod = sys.modules[__name__]

    job = _run(get_job(job_id))
    if not job:
        return

    _run(update_job(job_id, state="processing", progress=0.0))

    with tempfile.TemporaryDirectory() as tmp_dir:
        image_dir = os.path.join(tmp_dir, "images")
        output_dir = os.path.join(tmp_dir, "output")
        os.makedirs(image_dir)
        os.makedirs(output_dir)

        try:
            _mod.fetch_images(job["source_url"], image_dir)

            image_files = [
                f for f in os.listdir(image_dir)
                if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
            ]
            _run(update_job(job_id, page_count=len(image_files)))

            _mod.run_mokuro(image_dir, output_dir)
            _mod.copy_output(output_dir, job_id)
            _run(update_job(job_id, state="done", progress=1.0))

        except Exception as exc:
            partial = os.path.join(settings.processed_dir, job_id)
            if os.path.exists(partial):
                shutil.rmtree(partial, ignore_errors=True)
            _run(update_job(job_id, state="failed", error_message=str(exc)))
