# server/worker/celery_app.py
from celery import Celery
from api.config import settings

celery_app = Celery(
    "mokuyomi",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=["worker.tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    task_soft_time_limit=1800,  # 30 min
    task_time_limit=1860,
)
