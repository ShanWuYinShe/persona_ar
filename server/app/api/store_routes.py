from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional
from app.models.schemas import (
    StoreModelManifest, MemoryItemResponse, CreateMemoryRequest
)
from app.persona.memory import memory_manager, MemoryCategory
from app.persona.engine import persona_engine, PERSONA_REGISTRY

router = APIRouter(prefix="/api/v1")

# 云端模型工坊资产库
STORE_MODELS_DB = [
    StoreModelManifest(
        id="hoshino",
        name="星野 (Hoshino)",
        author="Persona Studio Official",
        version="1.2.0",
        model_type="SPRITE_2_5D",
        description="投射在现实中的二次元傲娇元气少女，心软口硬，对现实世界充满好奇与依恋。",
        preview_color_hex="#FF7B61FF",
        hair_color_hex="#6B4E71",
        eye_color_hex="#5D54A8",
        skirt_color_hex="#3B4A6B",
        default_voice="zh-CN-XiaoyiNeural",
        greeting_text="哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
        tags=["元气", "傲娇", "双马尾", "官方看板娘"],
        downloads_count=12890,
        rating=4.9
    ),
    StoreModelManifest(
        id="luna",
        name="露娜 (Luna)",
        author="SilverStar Works",
        version="1.0.4",
        model_type="SPRITE_2_5D",
        description="银白长发、赤色眼瞳的高冷毒舌小魔女。嘴上嫌弃，其实很黏人。",
        preview_color_hex="#FF5277",
        hair_color_hex="#E0E2EB",
        eye_color_hex="#FF2E63",
        skirt_color_hex="#1E1A29",
        default_voice="zh-CN-XiaoxiaoNeural",
        greeting_text="居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
        tags=["白毛", "红瞳", "高冷", "工坊热门"],
        downloads_count=9840,
        rating=4.8
    ),
    StoreModelManifest(
        id="airi",
        name="艾莉 (Airi)",
        author="WarmHeart Lab",
        version="1.1.0",
        model_type="SPRITE_2_5D",
        description="温柔体贴的邻家治愈学妹。在你累的时候会微笑着静静陪伴你。",
        preview_color_hex="#00E676",
        hair_color_hex="#8D6E63",
        eye_color_hex="#43A047",
        skirt_color_hex="#FFF3E0",
        default_voice="zh-CN-YunjianNeural",
        greeting_text="学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
        tags=["治愈", "学妹", "温柔", "陪伴"],
        downloads_count=15300,
        rating=5.0
    ),
    StoreModelManifest(
        id="cyber_k",
        name="K-07 (Cyber K)",
        author="Neon Future 3D",
        version="2.0.0",
        model_type="GLTF_3D",
        description="从未来赛博空间穿越而来的全息机甲少女，具备 3D 空间立体投射网格。",
        preview_color_hex="#00E5FF",
        hair_color_hex="#00E5FF",
        eye_color_hex="#76FF03",
        skirt_color_hex="#212121",
        default_voice="zh-CN-YunyangNeural",
        greeting_text="全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
        tags=["3D模型", "赛博朋克", "机甲", "ARCore原生"],
        downloads_count=8400,
        rating=4.7
    )
]

@router.get("/store/models", response_model=List[StoreModelManifest])
async def list_store_models():
    """获取云端契约工坊与模型商城的全部纸片人模型"""
    return STORE_MODELS_DB

@router.get("/store/models/{model_id}", response_model=StoreModelManifest)
async def get_store_model(model_id: str):
    """获取指定纸片人模型的完整资产配置清单"""
    for m in STORE_MODELS_DB:
        if m.id == model_id:
            return m
    raise HTTPException(status_code=404, detail="Model not found in store")

@router.post("/store/models/publish", response_model=StoreModelManifest)
async def publish_store_model(manifest: StoreModelManifest):
    """向纸片人契约工坊发布社区创作的新模型"""
    for idx, existing in enumerate(STORE_MODELS_DB):
        if existing.id == manifest.id:
            STORE_MODELS_DB[idx] = manifest
            return manifest
    STORE_MODELS_DB.append(manifest)
    return manifest

# ----------------- 长期情感记忆管理 API -----------------

@router.get("/persona/memories", response_model=List[MemoryItemResponse])
async def list_memories(user_id: str = "user_default", persona_id: Optional[str] = None):
    """获取纸片人对指挥官的长期情感回忆"""
    items = memory_manager.get_memories(user_id=user_id, persona_id=persona_id)
    return [
        MemoryItemResponse(
            id=item.id,
            user_id=item.user_id,
            persona_id=item.persona_id,
            category=item.category,
            content=item.content,
            importance=item.importance,
            timestamp=item.timestamp
        ) for item in items
    ]

@router.post("/persona/memories", response_model=MemoryItemResponse)
async def create_memory(req: CreateMemoryRequest):
    """主动向记忆池沉淀一条与纸片人的珍贵现实回忆"""
    item = memory_manager.add_memory(
        user_id=req.user_id,
        persona_id=req.persona_id,
        content=req.content,
        category=req.category,
        importance=req.importance
    )
    return MemoryItemResponse(
        id=item.id,
        user_id=item.user_id,
        persona_id=item.persona_id,
        category=item.category,
        content=item.content,
        importance=item.importance,
        timestamp=item.timestamp
    )
