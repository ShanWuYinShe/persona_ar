import base64
import logging
import asyncio
import io
from typing import Optional
from app.core.config import settings

logger = logging.getLogger(__name__)

class TTSSynthesizer:
    def __init__(self, voice: Optional[str] = None):
        self.voice = voice or settings.tts_voice
        self.enabled = settings.tts_enabled

    async def synthesize_to_base64(self, text: str) -> Optional[str]:
        """将文字合成为语音并返回Base64编码的MP3"""
        if not self.enabled or not text.strip():
            return None
        
        try:
            import edge_tts
            communicate = edge_tts.Communicate(text, self.voice)
            audio_data = bytearray()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_data.extend(chunk["data"])
            
            if audio_data:
                return base64.b64encode(audio_data).decode("utf-8")
        except Exception as e:
            logger.warning(f"Edge-TTS synthesis failed or offline: {e}. Falling back to client-side TTS.")
            return None
        return None

tts_synthesizer = TTSSynthesizer()
