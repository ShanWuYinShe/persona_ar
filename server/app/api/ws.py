import json
import logging
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from app.persona.engine import persona_engine, PERSONA_REGISTRY
from app.tts.synthesizer import tts_synthesizer

logger = logging.getLogger(__name__)

ws_router = APIRouter(prefix="/api/v1/ws")

@ws_router.websocket("/continuous")
async def websocket_continuous_endpoint(websocket: WebSocket):
    """
    全双工低延迟连续对话与随时打断通道 (Full-duplex Low-latency Barge-in Channel)
    """
    await websocket.accept()
    user_id = "user_default"
    active_pid = persona_engine.get_active_persona_id(user_id)
    logger.info(f"WebSocket client connected for user {user_id}, active persona: {active_pid}")

    try:
        # 发送初始就绪欢迎帧
        await websocket.send_text(json.dumps({
            "type": "ready",
            "active_persona": active_pid,
            "message": "全双工低延迟连麦通道已就绪，随时开口说话可瞬间打断"
        }))

        while True:
            data_text = await websocket.receive_text()
            try:
                msg = json.loads(data_text)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type")

            if msg_type == "speech_start":
                # 用户开口说话，触发瞬时打断 ACK
                await websocket.send_text(json.dumps({
                    "type": "interrupt_ack",
                    "timestamp": msg.get("timestamp", 0)
                }))

            elif msg_type in ("text_message", "speech_end"):
                # 用户断句完成，提交发言文本或音频指令
                user_text = msg.get("text", "")
                if not user_text and msg.get("audio_base64"):
                    user_text = "（指挥官温和的对讲声音）"
                if not user_text:
                    user_text = "星野在听吗？"

                # 广播思考态
                await websocket.send_text(json.dumps({
                    "type": "thinking_start"
                }))

                # 情感大脑推理
                text, emotion, action, expr, delta = await persona_engine.chat(user_id, user_text)
                state = persona_engine.get_user_state(user_id)

                # 角色专属 TTS 合成
                active_persona = PERSONA_REGISTRY.get(active_pid, PERSONA_REGISTRY["hoshino"])
                tts_synthesizer.voice = active_persona.voice
                audio_base64 = await tts_synthesizer.synthesize_to_base64(text)

                # 推送情感与动作状态
                await websocket.send_text(json.dumps({
                    "type": "response_complete",
                    "text": text,
                    "emotion": emotion,
                    "action": action,
                    "expression": expr,
                    "current_affinity": state.affinity,
                    "mood": state.mood,
                    "audio_base64": audio_base64
                }))

            elif msg_type == "ping":
                await websocket.send_text(json.dumps({"type": "pong"}))

    except WebSocketDisconnect:
        logger.info(f"WebSocket client disconnected: {user_id}")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
        try:
            await websocket.close()
        except:
            pass
