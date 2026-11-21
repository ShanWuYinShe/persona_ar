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

def test_store_models_api():
    # 测试获取商城列表
    res = client.get("/api/v1/store/models")
    assert res.status_code == 200
    data = res.json()
    assert len(data) >= 4
    hoshino = next(m for m in data if m["id"] == "hoshino")
    assert hoshino["author"] != ""
    assert hoshino["rating"] >= 4.5

    # 测试获取单个模型
    res_single = client.get("/api/v1/store/models/luna")
    assert res_single.status_code == 200
    assert res_single.json()["id"] == "luna"

def test_memories_api():
    # 测试获取默认情感回忆
    res = client.get("/api/v1/persona/memories?user_id=user_default")
    assert res.status_code == 200
    mems = res.json()
    assert len(mems) >= 1

    # 测试新增一条长期回忆
    new_mem = client.post("/api/v1/persona/memories", json={
        "user_id": "user_default",
        "persona_id": "hoshino",
        "category": "SHARED_MOMENT",
        "content": "今天指挥官给星野展示了窗外的雨滴，一起听着雨声很惬意。",
        "importance": 5
    })
    assert new_mem.status_code == 200
    assert "雨滴" in new_mem.json()["content"]

def test_websocket_continuous():
    with client.websocket_connect("/api/v1/ws/continuous") as ws:
        # 验证服务端就绪帧
        ready_frame = ws.receive_json()
        assert ready_frame["type"] == "ready"

        # 发送打断信号
        ws.send_json({"type": "speech_start", "timestamp": 123456})
        ack_frame = ws.receive_json()
        assert ack_frame["type"] == "interrupt_ack"

        # 发送对话文本
        ws.send_json({"type": "text_message", "text": "星野，你喜欢现实世界的花园吗？"})
        thinking_frame = ws.receive_json()
        assert thinking_frame["type"] == "thinking_start"

        resp_frame = ws.receive_json()
        assert resp_frame["type"] == "response_complete"
        assert "text" in resp_frame
        assert "emotion" in resp_frame
        assert "action" in resp_frame
