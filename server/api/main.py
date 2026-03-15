from fastapi import FastAPI, Depends
from api.auth import require_auth

app = FastAPI(title="Mokuyomi Server")

@app.get("/jobs", dependencies=[Depends(require_auth)])
async def list_jobs():
    return []
