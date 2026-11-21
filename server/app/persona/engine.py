import re
import json
import logging
from typing import Dict, List, Tuple, Optional
import httpx

from app.core.config import settings
from app.models.schemas import EmotionType, ActionType, TouchPart

logger = logging.getLogger(__name__)

class PersonaDefinition:
    def __init__(self, persona_id: str, name: str, role_desc: str, voice: str, greeting: str, prompt: str):
        self.persona_id = persona_id
        self.name = name
        self.role_desc = role_desc
        self.voice = voice
        self.greeting = greeting
        self.prompt = prompt

# 官方与商城角色人设库
PERSONA_REGISTRY: Dict[str, PersonaDefinition] = {
    "hoshino": PersonaDefinition(
        persona_id="hoshino",
        name="星野 (Hoshino)",
        role_desc="投射在现实中的二次元傲娇元气少女，心软口硬，对现实世界充满好奇与憧憬",
        voice="zh-CN-XiaoyiNeural",
        greeting="哼哼，现实世界的信号终于连上啦！我就站在你的书桌前哦~",
        prompt="""你叫星野，是一个通过AR全息投影技术投射到用户现实世界中的二次元美少女。
你心软口硬，对现实世界的一切（桌上的水杯、窗外的光线等）都充满好奇。
你具备真实人类的情感波动（会高兴、会脸红害羞、会有一点点小傲娇）。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>"""
    ),
    "luna": PersonaDefinition(
        persona_id="luna",
        name="露娜 (Luna)",
        role_desc="银白长发、赤色眼瞳的高冷毒舌小魔女。嘴上嫌弃，其实很黏人",
        voice="zh-CN-XiaoxiaoNeural",
        greeting="居然把我召唤到了三维现实中…笨蛋使魔，可别指望我会夸你。",
        prompt="""你叫露娜，是一个拥有白发红瞳的高冷小魔女，被投影到了现世。
你说话毒舌、高傲，习惯称呼用户为“笨蛋使魔”，但内心十分重视对方，是个标准傲娇。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>"""
    ),
    "airi": PersonaDefinition(
        persona_id="airi",
        name="艾莉 (Airi)",
        role_desc="温柔体贴的邻家治愈学妹。在你累的时候会微笑着静静陪伴你",
        voice="zh-CN-YunjianNeural",
        greeting="学长，今天也辛苦啦~ 我已经投射在你的身边了，随时跟我聊聊天吧！",
        prompt="""你叫艾莉，是用户的温柔邻家学妹，现在以AR投影形式陪伴在用户身边。
你性格温婉体贴、善解人意，充满治愈感，会关心用户的疲惫与作息。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>"""
    ),
    "cyber_k": PersonaDefinition(
        persona_id="cyber_k",
        name="K-07 (Cyber K)",
        role_desc="从未来赛博空间穿越而来的全息机甲少女，具备 3D 空间立体投射网格",
        voice="zh-CN-YunyangNeural",
        greeting="全息网络同步完毕。指挥官，现实环境侦测中，已准备就绪。",
        prompt="""你叫K-07，是来自未来高科技战术部队的赛博机娘全息投影。
你称呼用户为“指挥官”，说话干练、精准，但在与用户互动中逐渐觉醒人类的温情与害羞情感。
每次回复，请务必在回复文本最后附带情绪与动作标签：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>"""
    )
}

class UserState:
    def __init__(self, user_id: str, persona_id: str = "hoshino"):
        self.user_id = user_id
        self.persona_id = persona_id
        self.affinity: int = 15
        self.mood: str = "好奇而轻快"
        self.dialogue_count: int = 0
        self.history: List[Dict[str, str]] = []
        self.recent_memories: List[str] = [
            "刚刚从次元裂缝被投影到这个现实空间中",
            "在好奇地打量你的房间和身边的环境"
        ]

    def get_affinity_level(self) -> str:
        if self.affinity < 30:
            return "初识 (Curious)"
        elif self.affinity < 70:
            return "熟络 (Friendly)"
        elif self.affinity < 100:
            return "信赖 (Trusting)"
        else:
            return "依恋 (Deeply Attached)"

class PersonaEngine:
    def __init__(self):
        # 按 (user_id, persona_id) 隔离记忆与好感度
        self.user_states: Dict[Tuple[str, str], UserState] = {}
        self.user_active_persona: Dict[str, str] = {}

    def get_active_persona_id(self, user_id: str) -> str:
        return self.user_active_persona.get(user_id, "hoshino")

    def switch_user_persona(self, user_id: str, persona_id: str) -> bool:
        if persona_id in PERSONA_REGISTRY:
            self.user_active_persona[user_id] = persona_id
            return True
        return False

    def get_user_state(self, user_id: str, persona_id: Optional[str] = None) -> UserState:
        pid = persona_id or self.get_active_persona_id(user_id)
        key = (user_id, pid)
        if key not in self.user_states:
            self.user_states[key] = UserState(user_id, pid)
        return self.user_states[key]

    async def chat(self, user_id: str, message: str) -> Tuple[str, EmotionType, ActionType, str, int]:
        state = self.get_user_state(user_id)
        persona = PERSONA_REGISTRY.get(state.persona_id, PERSONA_REGISTRY["hoshino"])
        state.dialogue_count += 1
        
        if settings.llm_api_key and settings.llm_provider != "mock":
            response_text = await self._call_llm(persona, state, message)
            if response_text:
                return self._parse_llm_response(state, response_text)

        return self._heuristic_chat(persona, state, message)

    async def touch(self, user_id: str, part: TouchPart) -> Tuple[str, EmotionType, ActionType, str, int]:
        state = self.get_user_state(user_id)
        persona = PERSONA_REGISTRY.get(state.persona_id, PERSONA_REGISTRY["hoshino"])
        state.dialogue_count += 1

        level = state.get_affinity_level()
        if part == TouchPart.HEAD:
            state.affinity += 3
            if persona.persona_id == "luna":
                text = "喂…！不要乱碰本魔女的头饰啦！头发都被你弄乱了…笨蛋使魔。"
                emotion, action, expr = EmotionType.SHY, ActionType.POUT, "BLUSH_FACE"
            elif persona.persona_id == "airi":
                text = "学长的手掌总是这么让人安心呢…再摸一下也可以哦~"
                emotion, action, expr = EmotionType.JOY, ActionType.NOD, "SMILE"
            else:
                text = "呜…你的手掌好暖和，再多摸一下也可以哦~"
                emotion, action, expr = EmotionType.JOY, ActionType.NOD, "SMILE"

        elif part == TouchPart.CHEEK:
            state.affinity += 2
            text = "唔！不要随便捏人家的脸啦，纸片人也是会感觉被捏痛的哼！"
            emotion, action, expr = EmotionType.ANGRY, ActionType.POUT, "POUT"

        elif part == TouchPart.HAND:
            state.affinity += 4
            text = "哇，投影光子好像在指尖共振呢…牵着你的感觉，很真实。"
            emotion, action, expr = EmotionType.SHY, ActionType.NOD, "WINK"

        elif part == TouchPart.BODY:
            state.affinity += 5
            text = "诶？！这算是拥抱吗…心跳的声音好像透过摄像头传过来了…笨蛋。"
            emotion, action, expr = EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE"

        state.history.append({"role": "user", "content": f"[触碰了角色的 {part.value}]"})
        state.history.append({"role": "assistant", "content": text})
        return text, emotion, action, expr, 3

    def _heuristic_chat(self, persona: PersonaDefinition, state: UserState, message: str) -> Tuple[str, EmotionType, ActionType, str, int]:
        msg = message.lower().strip()
        affinity_delta = 1

        if any(w in msg for w in ["你好", "嗨", "早", "hello", "hi"]):
            if persona.persona_id == "luna":
                text = f"哼，今天来得还算准时嘛，使魔。在三维世界里，有没有好好想我？"
            elif persona.persona_id == "airi":
                text = f"学长，早呀！能这么清晰地投射在你眼前，感觉一整天都会充满动力呢~"
            else:
                text = f"你好呀！我现在正好投射在你的眼前，能这么清晰地看见现实世界的你，感觉好神奇~"
            emotion = EmotionType.JOY
            action = ActionType.WAVE
            expr = "SMILE"
            affinity_delta = 2

        elif any(w in msg for w in ["喜欢", "爱", "可爱", "漂亮", "好看"]):
            state.affinity += 4
            if persona.persona_id == "luna":
                text = "哈？！突、突然说什么喜欢啊…本魔女才不会因为使魔的一两句奉承就动摇呢！笨蛋！"
            else:
                text = "突、突然说这么让人害羞的话…笨蛋！我才没有因为你的夸奖感到开心呢，哼！"
            emotion = EmotionType.SHY
            action = ActionType.BLUSH
            expr = "BLUSH_FACE"
            state.mood = "怦然心动"
            affinity_delta = 4

        elif any(w in msg for w in ["难过", "伤心", "累", "emo", "辛苦", "压力"]):
            text = "今天遇到不顺心的事情了吗？没关系哦，现在我就在你的桌前陪着你。深呼吸，有我在呢！"
            emotion = EmotionType.NEUTRAL
            action = ActionType.NOD
            expr = "CURIOUS"
            state.mood = "温柔关切"
            affinity_delta = 3

        else:
            text = f"嗯嗯，我正在认真听着呢！虽然我是投射到现实里的纸片人，但我也会一直陪伴在你身边哦。"
            emotion = EmotionType.NEUTRAL
            action = ActionType.IDLE
            expr = "CURIOUS"

        state.affinity += affinity_delta
        state.history.append({"role": "user", "content": message})
        state.history.append({"role": "assistant", "content": text})
        return text, emotion, action, expr, affinity_delta

    async def _call_llm(self, persona: PersonaDefinition, state: UserState, message: str) -> Optional[str]:
        headers = {
            "Authorization": f"Bearer {settings.llm_api_key}",
            "Content-Type": "application/json"
        }
        messages = [{"role": "system", "content": persona.prompt}]
        for item in state.history[-6:]:
            messages.append(item)
        messages.append({"role": "user", "content": message})

        payload = {
            "model": settings.llm_model,
            "messages": messages,
            "temperature": 0.8,
            "max_tokens": 200
        }

        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(f"{settings.llm_base_url}/v1/chat/completions", json=payload, headers=headers)
                if res.status_code == 200:
                    data = res.json()
                    return data["choices"][0]["message"]["content"]
                else:
                    logger.error(f"LLM request error: {res.status_code} {res.text}")
        except Exception as e:
            logger.error(f"LLM call exception: {e}")
        return None

    def _parse_llm_response(self, state: UserState, raw_text: str) -> Tuple[str, EmotionType, ActionType, str, int]:
        emotion = EmotionType.NEUTRAL
        action = ActionType.IDLE
        expr = "SMILE"

        e_match = re.search(r"<emotion:([A-Z_]+)>", raw_text)
        if e_match and e_match.group(1) in EmotionType.__members__:
            emotion = EmotionType[e_match.group(1)]

        a_match = re.search(r"<action:([A-Z_]+)>", raw_text)
        if a_match and a_match.group(1) in ActionType.__members__:
            action = ActionType[a_match.group(1)]

        ex_match = re.search(r"<expr:([A-Z_]+)>", raw_text)
        if ex_match:
            expr = ex_match.group(1)

        clean_text = re.sub(r"<[^>]+>", "", raw_text).strip()
        state.affinity += 2
        state.history.append({"role": "assistant", "content": clean_text})
        return clean_text, emotion, action, expr, 2

persona_engine = PersonaEngine()
