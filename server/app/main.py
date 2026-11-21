from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.api.routes import router as api_router
from app.api.store_routes import router as store_router
from app.api.ws import ws_router
from app.core.config import settings

app = FastAPI(
    title=settings.app_name,
    description="Persona AR 现实投影纸片人专属情感大脑服务",
    version="1.0.0"
)

# 跨域配置，允许局域网内的 Android 设备或本地测试直接访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router)
app.include_router(store_router)
app.include_router(ws_router)
app.add_api_route("/healthz", api_router.routes[-1].endpoint, methods=["GET"])
