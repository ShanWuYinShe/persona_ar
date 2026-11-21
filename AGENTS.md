# PersonaAR 项目规范 (AGENTS.md)

## 项目架构与技术栈
- 根目录包含两个核心组件：
  - `android/`: Android AR 客户端，基于 Kotlin、Jetpack Compose、CameraX、ARCore。
  - `server/`: 专属情感大脑服务，基于 Go 1.24、Gin Web Framework、Gorilla WebSocket、磁盘 SHA256 TTS 缓存与长期记忆持久化。
- 部署：根目录提供 `docker-compose.yml` 容器化编排。

## 验证分工与秒级检查
- 重任务（全量构建、打包、E2E、Docker 镜像构建）由 GitHub Actions 执行，不在本地执行。
- 本地秒级验证命令：
  - 后端单元测试：`cd server && go test -v ./...`
  - 代码规范与 Lint：`~/.agents/bin/skill-lint AGENTS.md`

## 敏感凭据规范
- 任何真实 API Token（如 `LLM_API_KEY`）严禁写入代码、文档、commit 记录或日志；统一通过环境变量或 `.env` 注入。

## PR 证据图
- UI 改动的 Before/After 截图用 `gh` 的 `--attach` 上传，**不提交进仓库**（`.github/pr-evidence/` 不得重建）。
- 压缩：`~/.agents/bin/pr-evidence` 的 `shrink`；上传：`gh pr create --body-file <正文> --attach 'before.png#改前' --attach 'after.png#改后'`。
- 建完 PR 用 `pr-evidence check --body <正文> --post` 复核附件地址真实可访问。
