import pytest
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_healthz():
    res = client.get("/healthz")
    assert res.status_code == 200
    assert res.json()["status"] == "healthy"

def test_persona_status():
    res = client.get("/api/v1/persona/status?user_id=test_user")
    assert res.status_code == 200
    data = res.json()
    assert "affinity" in data
    assert data["name"] != ""
    assert data["affinity_level"] != ""

def test_chat_interaction():
    res = client.post("/api/v1/chat/interact", json={
        "message": "你好呀星野！",
        "user_id": "test_user"
    })
    assert res.status_code == 200
    data = res.json()
    assert "text" in data
    assert "emotion" in data
    assert "action" in data
    assert data["current_affinity"] > 0

def test_touch_interaction():
    res = client.post("/api/v1/persona/touch", json={
        "part": "HEAD",
        "user_id": "test_user"
    })
    assert res.status_code == 200
    data = res.json()
    assert "摸头" in data["text"] or "突然" in data["text"] or "暖和" in data["text"]
    assert data["emotion"] in ["SHY", "JOY"]
