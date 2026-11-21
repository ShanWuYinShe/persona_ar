import base64
import logging
import asyncio
import io
import os
import hashlib
from typing import Optional
from app.core.config import settings

logger = logging.getLogger(__name__)

class TTSSynthesizer:
    def __init__(self, voice: Optional[str] = None):
        self.voice = voice or settings.tts_voice
        self.enabled = settings.tts_enabled
        self.cache_dir = os.path.join(os.getcwd(), "data", "tts_cache")
        os.makedirs(self.cache_dir, exist_ok=True)

    def _get_cache_path(self, text: str, voice: str) -> str:
        key = f"{voice}_{text.strip()}".encode("utf-8")
        hash_str = hashlib.sha256(key).hexdigest()
        return os.path.join(self.cache_dir, f"{hash_str}.mp3")

    async def synthesize_to_base64(self, text: str) -> Optional[str]:
        """将文字合成为语音并返回Base64编码的MP3，内置毫秒级磁盘缓存机制"""
        if not self.enabled or not text.strip():
            return None
        
        target_voice = self.voice
        cache_file = self._get_cache_path(text, target_voice)

        # 1. 尝试从本地持久化缓存命中，响应时间 < 2ms
        if os.path.exists(cache_file):
            try:
                with open(cache_file, "rb") as f:
                    audio_bytes = f.read()
                    if audio_bytes:
                        return base64.b64encode(audio_bytes).decode("utf-8")
            except Exception as e:
                logger.warning(f"Failed to read TTS cache {cache_file}: {e}")

        # 2. 未命中缓存，调用 Edge-TTS 进行异步云端合成
        try:
            import edge_tts
            communicate = edge_tts.Communicate(text, target_voice)
            audio_data = bytearray()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_data.extend(chunk["data"])
            
            if audio_data:
                # 写入本地缓存以备下次瞬间秒开
                try:
                    with open(cache_file, "wb") as f:
                        f.write(audio_data)
                except Exception as ce:
                    logger.warning(f"Failed to save TTS cache {cache_file}: {ce}")

                return base64.b64encode(audio_data).decode("utf-8")
        except Exception as e:
            logger.warning(f"Edge-TTS synthesis failed or offline: {e}. Falling back to client-side TTS.")
            return None
        return None

tts_synthesizer = TTSSynthesizer()
