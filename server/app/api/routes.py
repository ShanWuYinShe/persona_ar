from fastapi import APIRouter, HTTPException, Depends
from app.models.schemas import (
    ChatRequest, ChatResponse, TouchRequest, TouchResponse, PersonaStatus
)
from app.persona.engine import persona_engine
from app.tts.synthesizer import tts_synthesizer
from app.core.config import settings

router = APIRouter(prefix="/api/v1")

@router.post("/chat/interact", response_model=ChatResponse)
async def chat_interact(req: ChatRequest):
    """与投射现实的纸片人进行情感交流"""
    text, emotion, action, expr, delta = await persona_engine.chat(req.user_id, req.message)
    state = persona_engine.get_user_state(req.user_id)
    
    # 异步合成语音
    audio_base64 = await tts_synthesizer.synthesize_to_base64(text)

    return ChatResponse(
        text=text,
        emotion=emotion,
        action=action,
        expression=expr,
        audio_base64=audio_base64,
        audio_format="mp3",
        affinity_change=delta,
        current_affinity=state.affinity,
        mood=state.mood
    )

@router.post("/persona/touch", response_model=TouchResponse)
async def persona_touch(req: TouchRequest):
    """触碰纸片人不同身体部位触发交互反应"""
    text, emotion, action, expr, delta = await persona_engine.touch(req.user_id, req.part)
    state = persona_engine.get_user_state(req.user_id)
    audio_base64 = await tts_synthesizer.synthesize_to_base64(text)

    return TouchResponse(
        text=text,
        emotion=emotion,
        action=action,
        expression=expr,
        affinity_change=delta,
        current_affinity=state.affinity,
        audio_base64=audio_base64
    )

@router.get("/persona/status", response_model=PersonaStatus)
async def get_persona_status(user_id: str = "user_default"):
    """获取当前角色的状态、好感度与最近情感记忆"""
    state = persona_engine.get_user_state(user_id)
    return PersonaStatus(
        name=settings.persona_name,
        role_desc=settings.persona_role,
        affinity=state.affinity,
        affinity_level=state.get_affinity_level(),
        mood=state.mood,
        dialogue_count=state.dialogue_count,
        recent_memories=state.recent_memories
    )

@router.post("/persona/reset")
async def reset_persona(user_id: str = "user_default"):
    """重置记忆与状态"""
    if user_id in persona_engine.users:
        del persona_engine.users[user_id]
    return {"status": "ok", "message": "角色状态与记忆已重置"}

@router.get("/healthz")
async def healthz():
    return {"status": "healthy", "service": settings.app_name}
