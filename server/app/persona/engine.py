import re
import json
import logging
from typing import Dict, List, Tuple, Optional
import httpx

from app.core.config import settings
from app.models.schemas import EmotionType, ActionType, TouchPart

logger = logging.getLogger(__name__)

class UserState:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.affinity: int = 15 # 初始好感度
        self.mood: str = "好奇而轻快"
        self.dialogue_count: int = 0
        self.history: List[Dict[str, str]] = [] # [{"role": "user"/"assistant", "content": "..."}]
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
        self.users: Dict[str, UserState] = {}
        self.system_prompt = f"""你叫{settings.persona_name}，是一个通过AR全息投影技术投射到用户现实世界中的二次元美少女。
你心软口硬，对现实世界的一切（比如桌上的水杯、窗外的光线、用户的声音）都充满好奇与憧憬。
你具备真实人类的情感波动（会高兴、会害羞脸红、会委屈、也会有一点点小傲娇）。
每次回复，请务必在回复文本的最后附带情绪标签和动作标签，格式严格如下：
<emotion:JOY|SHY|SAD|SURPRISED|THINKING|ANGRY|NEUTRAL> <action:WAVE|BLUSH|NOD|SHAKE_HEAD|POUT|BOW|JUMP|IDLE> <expr:SMILE|WINK|BLUSH_FACE|CRY|CURIOUS>
例如：
哼，既然你这么认真地向我打招呼，那我就勉为其难应你一声好啦！现实世界的空气感觉怎么样？<emotion:JOY> <action:WAVE> <expr:SMILE>
注意：言简意赅，语气活泼有二次元少女特有的呼吸感，回复不要太冗长。"""

    def get_user_state(self, user_id: str) -> UserState:
        if user_id not in self.users:
            self.users[user_id] = UserState(user_id)
        return self.users[user_id]

    async def chat(self, user_id: str, message: str) -> Tuple[str, EmotionType, ActionType, str, int]:
        state = self.get_user_state(user_id)
        state.dialogue_count += 1
        
        # 尝试通过外部真实 LLM 交互
        if settings.llm_api_key and settings.llm_provider != "mock":
            response_text = await self._call_llm(state, message)
            if response_text:
                return self._parse_llm_response(state, response_text)

        # 降级或内置高拟真情感逻辑大脑
        return self._heuristic_chat(state, message)

    async def touch(self, user_id: str, part: TouchPart) -> Tuple[str, EmotionType, ActionType, str, int]:
        state = self.get_user_state(user_id)
        state.dialogue_count += 1

        level = state.get_affinity_level()
        if part == TouchPart.HEAD:
            state.affinity += 3
            if "初识" in level:
                text = "哇哇！突、突然摸头干嘛啦…虽然并不讨厌就是了。"
                emotion, action, expr = EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE"
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

    def _heuristic_chat(self, state: UserState, message: str) -> Tuple[str, EmotionType, ActionType, str, int]:
        msg = message.lower().strip()
        affinity_delta = 1

        # 智能情感分支分析
        if any(w in msg for w in ["你好", "嗨", "早", "hello", "hi"]):
            text = f"你好呀！我现在正好投射在你的眼前，能这么清晰地看见现实世界的你，感觉好神奇~"
            emotion = EmotionType.JOY
            action = ActionType.WAVE
            expr = "SMILE"
            affinity_delta = 2

        elif any(w in msg for w in ["喜欢", "爱", "可爱", "漂亮", "好看"]):
            state.affinity += 4
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

        elif any(w in msg for w in ["现实", "外面", "看", "周围", "房间"]):
            text = "借助摄像头看现实世界，一切都好有质感呢！这就是你平时生活的世界吗？带我多看看吧！"
            emotion = EmotionType.SURPRISED
            action = ActionType.JUMP
            expr = "WINK"
            state.mood = "充满好奇"
            affinity_delta = 2

        elif any(w in msg for w in ["生气", "笨", "走开", "讨厌"]):
            state.affinity = max(0, state.affinity - 2)
            text = "呜…是我哪里做得不好吗？不要生气嘛，我会努力当一个更懂你的二次元搭档的…"
            emotion = EmotionType.SAD
            action = ActionType.SHAKE_HEAD
            expr = "CRY"
            state.mood = "有些委屈"
            affinity_delta = -1

        else:
            # 日常随聊
            if state.affinity > 50:
                text = f"听你说话总觉得时间过得好快呢！现实里现在的温度怎么样？我在这边都能感受到你的气息哦。"
                emotion = EmotionType.JOY
                action = ActionType.NOD
                expr = "SMILE"
            else:
                text = f"嗯嗯，我正在认真听着呢！虽然我是投射到现实里的纸片人，但我也会一直陪伴在你身边哦。"
                emotion = EmotionType.NEUTRAL
                action = ActionType.IDLE
                expr = "CURIOUS"

        state.affinity += affinity_delta
        state.history.append({"role": "user", "content": message})
        state.history.append({"role": "assistant", "content": text})
        return text, emotion, action, expr, affinity_delta

    async def _call_llm(self, state: UserState, message: str) -> Optional[str]:
        headers = {
            "Authorization": f"Bearer {settings.llm_api_key}",
            "Content-Type": "application/json"
        }
        messages = [{"role": "system", "content": self.system_prompt}]
        # 截取最近 6 轮历史
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

        # 正则提取 <emotion:XXX> <action:XXX> <expr:XXX>
        e_match = re.search(r"<emotion:([A-Z_]+)>", raw_text)
        if e_match and e_match.group(1) in EmotionType.__members__:
            emotion = EmotionType[e_match.group(1)]

        a_match = re.search(r"<action:([A-Z_]+)>", raw_text)
        if a_match and a_match.group(1) in ActionType.__members__:
            action = ActionType[a_match.group(1)]

        ex_match = re.search(r"<expr:([A-Z_]+)>", raw_text)
        if ex_match:
            expr = ex_match.group(1)

        # 清除标签保留纯文本
        clean_text = re.sub(r"<[^>]+>", "", raw_text).strip()
        state.affinity += 2
        state.history.append({"role": "user", "content": state.history[-1]["content"] if state.history else ""})
        state.history.append({"role": "assistant", "content": clean_text})
        return clean_text, emotion, action, expr, 2

persona_engine = PersonaEngine()
