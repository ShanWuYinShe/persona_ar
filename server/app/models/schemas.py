from enum import Enum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field

class EmotionType(str, Enum):
    NEUTRAL = "NEUTRAL"       # 平静
    JOY = "JOY"               # 开心/喜悦
    SHY = "SHY"               # 害羞/脸红
    SAD = "SAD"               # 委屈/失落
    SURPRISED = "SURPRISED"   # 惊讶
    THINKING = "THINKING"     # 思考
    ANGRY = "ANGRY"           # 傲娇生气

class ActionType(str, Enum):
    IDLE = "IDLE"             # 待机呼吸
    WAVE = "WAVE"             # 挥手打招呼
    BLUSH = "BLUSH"           # 害羞捂脸
    NOD = "NOD"               # 点头赞同
    SHAKE_HEAD = "SHAKE_HEAD" # 摇头否定
    POUT = "POUT"             # 撅嘴傲娇
    BOW = "BOW"               # 鞠躬感谢
    JUMP = "JUMP"             # 开心轻跳

class TouchPart(str, Enum):
    HEAD = "HEAD"             # 摸头
    CHEEK = "CHEEK"           # 捏脸/戳脸
    HAND = "HAND"             # 牵手
    BODY = "BODY"             # 拥抱

class ChatRequest(BaseModel):
    message: str = Field(..., description="用户输入的对话内容")
    user_id: str = Field(default="user_default", description="用户唯一标识")
    session_id: Optional[str] = Field(default=None, description="会话ID")

class VoiceInteractRequest(BaseModel):
    user_id: str = Field(default="user_default", description="用户唯一标识")
    audio_base64: Optional[str] = Field(default=None, description="录制音频 Base64 数据")
    audio_format: str = Field(default="wav", description="音频格式，如 wav, pcm, mp3")
    text_prompt: Optional[str] = Field(default=None, description="客户端伴随的语音识别文本或意图提示")

class ChatResponse(BaseModel):
    text: str = Field(..., description="角色回复的文本")
    emotion: EmotionType = Field(..., description="情绪标签，用于驱动表情")
    action: ActionType = Field(..., description="动作标签，用于驱动肢体动画")
    expression: str = Field(default="SMILE", description="面部细分表情代码")
    audio_base64: Optional[str] = Field(default=None, description="TTS生成的base64音频")
    audio_format: str = Field(default="mp3", description="音频格式")
    affinity_change: int = Field(default=0, description="好感度变化值")
    current_affinity: int = Field(..., description="当前好感度累计值")
    mood: str = Field(..., description="当前心情描述")

class TouchRequest(BaseModel):
    part: TouchPart = Field(..., description="触碰的身体部位")
    user_id: str = Field(default="user_default", description="用户唯一标识")

class TouchResponse(BaseModel):
    text: str = Field(..., description="角色的反应台词")
    emotion: EmotionType = Field(..., description="反应情绪")
    action: ActionType = Field(..., description="反应动作")
    expression: str = Field(..., description="反应表情")
    affinity_change: int = Field(..., description="好感度变化")
    current_affinity: int = Field(..., description="当前总好感度")
    audio_base64: Optional[str] = Field(default=None, description="TTS音频")

class PersonaStatus(BaseModel):
    name: str = Field(..., description="角色名字")
    role_desc: str = Field(..., description="人设描述")
    affinity: int = Field(..., description="好感度值")
    affinity_level: str = Field(..., description="好感度等级 (初识/熟络/信赖/依恋)")
    mood: str = Field(..., description="当前心情")
    dialogue_count: int = Field(..., description="累计互动次数")
    recent_memories: List[str] = Field(default_factory=list, description="短期情感记忆")
