import time
from typing import List, Dict, Optional
from pydantic import BaseModel, Field

class MemoryCategory:
    USER_FACT = "USER_FACT"               # 用户事实偏好（如：喜欢喝冰美式、熬夜工作）
    SHARED_MOMENT = "SHARED_MOMENT"       # 现实世界共同经历（如：一起看了窗外的晚霞）
    EMOTIONAL_PROMISE = "EMOTIONAL_PROMISE"# 彼此的约定（如：下次还要一起合影）

class MemoryItem(BaseModel):
    id: str
    user_id: str
    persona_id: str
    category: str
    content: str
    importance: int = Field(default=3, ge=1, le=5) # 1-5 级别重要度
    timestamp: float = Field(default_factory=time.time)

class MemoryManager:
    """
    纸片人长期情感记忆沉淀引擎 (Episodic & Semantic Memory Manager)
    """
    def __init__(self):
        # user_id -> List[MemoryItem]
        self._memories: Dict[str, List[MemoryItem]] = {}
        self._init_default_memories()

    def _init_default_memories(self):
        default_user = "user_default"
        self._memories[default_user] = [
            MemoryItem(
                id="mem_init_1",
                user_id=default_user,
                persona_id="hoshino",
                category=MemoryCategory.SHARED_MOMENT,
                content="第一次从二次元空间成功投射到指挥官的现实书桌前，感受到了现实世界的微风。",
                importance=5
            ),
            MemoryItem(
                id="mem_init_2",
                user_id=default_user,
                persona_id="hoshino",
                category=MemoryCategory.USER_FACT,
                content="指挥官在办公桌前经常对着屏幕忙碌，累的时候需要我的元气应援。",
                importance=4
            ),
            MemoryItem(
                id="mem_init_3",
                user_id=default_user,
                persona_id="hoshino",
                category=MemoryCategory.EMOTIONAL_PROMISE,
                content="约定好无论现实世界有多疲惫，回到这个房间时星野都会第一个微笑着迎接你。",
                importance=5
            )
        ]

    def add_memory(self, user_id: str, persona_id: str, content: str, category: str = MemoryCategory.SHARED_MOMENT, importance: int = 3) -> MemoryItem:
        if user_id not in self._memories:
            self._memories[user_id] = []
        
        mem_id = f"mem_{int(time.time() * 1000)}"
        item = MemoryItem(
            id=mem_id,
            user_id=user_id,
            persona_id=persona_id,
            category=category,
            content=content,
            importance=importance
        )
        self._memories[user_id].append(item)
        return item

    def get_memories(self, user_id: str, persona_id: Optional[str] = None, limit: int = 10) -> List[MemoryItem]:
        user_list = self._memories.get(user_id, [])
        if persona_id:
            filtered = [m for m in user_list if m.persona_id == persona_id]
        else:
            filtered = user_list
        return sorted(filtered, key=lambda x: (x.importance, x.timestamp), reverse=True)[:limit]

    def get_context_prompt_fragment(self, user_id: str, persona_id: str) -> str:
        """提取最关键的情感回忆片段，注入到 LLM 系统上下文中"""
        memories = self.get_memories(user_id, persona_id, limit=4)
        if not memories:
            return ""
        
        lines = ["你对指挥官的珍贵情感回忆："]
        for idx, m in enumerate(memories, 1):
            lines.append(f"{idx}. {m.content}")
        return "\n".join(lines)

memory_manager = MemoryManager()
