from pydantic import Field
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    token: str = Field(default="changeme", validation_alias="MOKUYOMI_TOKEN")
    redis_url: str = Field(default="redis://localhost:6379/0", validation_alias="REDIS_URL")
    processed_dir: str = Field(default="/data/processed", validation_alias="PROCESSED_DIR")
    database_path: str = Field(default="/data/mokuyomi.db", validation_alias="DATABASE_PATH")

    model_config = {"env_file": ".env", "populate_by_name": True}

settings = Settings()
