from fastapi import APIRouter, HTTPException, Depends
from typing import List
from app.models.schemas import (
    ChatRequest, ChatResponse, TouchRequest, TouchResponse, PersonaStatus
)
from app.persona.engine import persona_engine, PERSONA_REGISTRY
from app.tts.synthesizer import tts_synthesizer
from app.core.config import settings
from pydantic import BaseModel

router = APIRouter(prefix="/api/v1")

class SwitchPersonaRequest(BaseModel):
    user_id: str = "user_default"
    persona_id: str

@router.post("/chat/interact", response_model=ChatResponse)
async def chat_interact(req: ChatRequest):
    """与投射现实的纸片人进行情感交流"""
    text, emotion, action, expr, delta = await persona_engine.chat(req.user_id, req.message)
    state = persona_engine.get_user_state(req.user_id)
    
    # 获取当前角色的专属音色进行 TTS
    active_pid = persona_engine.get_active_persona_id(req.user_id)
    active_persona = PERSONA_REGISTRY.get(active_pid, PERSONA_REGISTRY["hoshino"])
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
    """获取当前激活角色的状态、好感度与最近情感记忆"""
    active_pid = persona_engine.get_active_persona_id(user_id)
    persona = PERSONA_REGISTRY.get(active_pid, PERSONA_REGISTRY["hoshino"])
    state = persona_engine.get_user_state(user_id, active_pid)
    return PersonaStatus(
        name=persona.name,
        role_desc=persona.role_desc,
        affinity=state.affinity,
        affinity_level=state.get_affinity_level(),
        mood=state.mood,
        dialogue_count=state.dialogue_count,
        recent_memories=state.recent_memories
    )

@router.get("/personas")
async def list_personas(user_id: str = "user_default"):
    """获取所有支持的角色模型列表（包含官方与工坊）"""
    active_pid = persona_engine.get_active_persona_id(user_id)
    result = []
    for pid, p in PERSONA_REGISTRY.items():
        state = persona_engine.get_user_state(user_id, pid)
        result.append({
            "id": p.persona_id,
            "name": p.name,
            "role_desc": p.role_desc,
            "greeting": p.greeting,
            "is_active": pid == active_pid,
            "affinity": state.affinity,
            "affinity_level": state.get_affinity_level()
        })
    return result

@router.post("/persona/switch")
async def switch_persona(req: SwitchPersonaRequest):
    """切换当前激活的角色模型"""
    success = persona_engine.switch_user_persona(req.user_id, req.persona_id)
    if not success:
        raise HTTPException(status_code=404, detail="Persona not found")
    persona = PERSONA_REGISTRY[req.persona_id]
    return {
        "status": "ok",
        "active_persona": persona.name,
        "greeting": persona.greeting
    }

@router.post("/persona/reset")
async def reset_persona(user_id: str = "user_default"):
    """重置记忆与状态"""
    to_delete = [k for k in persona_engine.user_states.keys() if k[0] == user_id]
    for k in to_delete:
        del persona_engine.user_states[k]
    return {"status": "ok", "message": "角色状态与记忆已重置"}

@router.get("/healthz")
async def healthz():
    return {"status": "healthy", "service": settings.app_name}
