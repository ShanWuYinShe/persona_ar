import os
from pydantic import BaseModel

class Settings(BaseModel):
    app_name: str = "Persona AR Emotional Brain"
    host: str = os.getenv("HOST", "0.0.0.0")
    port: int = int(os.getenv("PORT", "8000"))
    
    # 角色基础设定
    persona_name: str = os.getenv("PERSONA_NAME", "星野 (Hoshino)")
    persona_role: str = os.getenv("PERSONA_ROLE", "现实投影的二次元傲娇元气少女，心软口硬，对现实世界充满好奇")
    
    # LLM 配置
    llm_provider: str = os.getenv("LLM_PROVIDER", "auto") # auto, deepseek, openai, mock
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com")
    llm_model: str = os.getenv("LLM_MODEL", "deepseek-chat")
    
    # TTS 配置
    tts_enabled: bool = os.getenv("TTS_ENABLED", "true").lower() in ("true", "1", "yes")
    tts_voice: str = os.getenv("TTS_VOICE", "zh-CN-XiaoyiNeural") # 微软 Edge-TTS 少女甜美声线

settings = Settings()
