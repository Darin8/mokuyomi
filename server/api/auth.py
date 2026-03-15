from fastapi import HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from api.config import settings

bearer = HTTPBearer(auto_error=False)

def require_auth(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
) -> str:
    if credentials is None or credentials.credentials != settings.token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return credentials.credentials
