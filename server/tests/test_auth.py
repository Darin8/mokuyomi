from fastapi.testclient import TestClient
from api.main import app
from tests.conftest import AUTH_HEADER

client = TestClient(app)

def test_missing_token_returns_401():
    response = client.get("/jobs")
    assert response.status_code == 401

def test_wrong_token_returns_401():
    response = client.get("/jobs", headers={"Authorization": "Bearer wrong"})
    assert response.status_code == 401

def test_correct_token_passes():
    response = client.get("/jobs", headers=AUTH_HEADER)
    assert response.status_code == 200
