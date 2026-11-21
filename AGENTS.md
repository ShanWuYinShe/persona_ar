# PersonaAR 项目规范 (AGENTS.md)

## 项目架构与技术栈
- 根目录包含两个核心组件：
  - `android/`: Android AR 客户端，基于 Kotlin、Jetpack Compose、CameraX、ARCore。
  - `server/`: 专属情感大脑服务，基于 Python 3、FastAPI、Pydantic、Edge-TTS。
- 部署：根目录提供 `docker-compose.yml` 容器化编排。

## 验证分工与秒级检查
- 重任务（全量构建、打包、E2E、Docker 镜像构建）由 GitHub Actions 执行，不在本地执行。
- 本地秒级验证命令：
  - 后端单元测试：`cd server && PYTHONPATH=. uv run --with pytest --with fastapi --with httpx --with pydantic pytest tests/`
  - 代码规范与 Lint：`~/.agents/bin/skill-lint AGENTS.md`

## 敏感凭据规范
- 任何真实 API Token（如 `LLM_API_KEY`）严禁写入代码、文档、commit 记录或日志；统一通过环境变量或 `.env` 注入。
