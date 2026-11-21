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

def test_list_personas_and_switch():
    # 获取角色列表
    res = client.get("/api/v1/personas?user_id=test_user")
    assert res.status_code == 200
    data = res.json()
    assert len(data) >= 3
    ids = [p["id"] for p in data]
    assert "hoshino" in ids
    assert "luna" in ids
    assert "airi" in ids

    # 切换到露娜 (luna)
    res_switch = client.post("/api/v1/persona/switch", json={
        "user_id": "test_user",
        "persona_id": "luna"
    })
    assert res_switch.status_code == 200
    assert "露娜" in res_switch.json()["active_persona"]

    # 验证打招呼与触碰具有露娜独特的语气
    res_chat = client.post("/api/v1/chat/interact", json={
        "message": "你好",
        "user_id": "test_user"
    })
    assert res_chat.status_code == 200
    assert "使魔" in res_chat.json()["text"]

def test_chat_interaction():
    res = client.post("/api/v1/chat/interact", json={
        "message": "你好呀！",
        "user_id": "test_user_2"
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
        "user_id": "test_user_2"
    })
    assert res.status_code == 200
    data = res.json()
    assert "text" in data
    assert data["emotion"] in ["SHY", "JOY"]

def test_voice_interaction():
    res = client.post("/api/v1/voice/interact", json={
        "user_id": "test_user_3",
        "audio_base64": "UklGRi4AAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=",
        "audio_format": "wav",
        "text_prompt": "星野，能听到我的对讲吗？"
    })
    assert res.status_code == 200
    data = res.json()
    assert "text" in data
    assert "emotion" in data
    assert "action" in data
    assert data["current_affinity"] > 0
