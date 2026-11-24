# PersonaAR 项目规范 (AGENTS.md)

## 项目架构与技术栈
- 根目录包含两个核心组件：
  - `android/`: Android AR 客户端，基于 Kotlin、Jetpack Compose、CameraX、ARCore。
  - `server/`: 专属情感大脑服务，基于 Go（版本以 `server/go.mod` 的 `go` 行为准）、Gin Web Framework、Gorilla WebSocket、磁盘 SHA256 TTS 缓存与长期记忆持久化。
- 部署：根目录提供 `docker-compose.yml` 容器化编排。

## Android 构建工具链（三者版本必须两两在官方测试范围内）
- Gradle `9.6.0`（wrapper 固定）、AGP `9.3.1`、Kotlin `2.4.21`，运行在 JDK 17+（CI 用 17，本机可用 Android Studio 自带 JBR）。
- AGP 9 起 Kotlin 编译内置于 AGP：模块**不再**应用 `org.jetbrains.kotlin.android`；Kotlin 版本由根 `build.gradle.kts` 的 `buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }` 指定。
- `buildFeatures.compose = true` 时**必须**应用 `org.jetbrains.kotlin.plugin.compose`（版本与 KGP 一致）；不再使用 `kotlinCompilerExtensionVersion` / `kotlinOptions`，jvmTarget 跟随 `compileOptions.targetCompatibility`。
- `compileSdk = 37`：Filament 1.81 的 AAR 声明 `minCompileSdk=37`；SDK 仓库只发布次版本命名的 `platforms;android-37.0`（无 `platforms;android-37`），故 AGP 需 ≥ 8.9，CI 需显式 `sdkmanager --install platforms;android-37.0`。

## 3D 手办渲染管线（Filament）
- 渲染层：`com.persona.ar.character.filament` — `FilamentEngineHolder`（进程级 Engine）、`FigureScene`（每视图场景/相机/双光源/IBL/接触阴影）、`FilamentFigureView`（Compose + TextureView + Choreographer 渲染循环 + GPU 拾取）。
- 选 **Filament 1.81.0**（`filament-android` / `gltfio-android` / `filament-utils-android`）：其绑定依赖 `kotlin-stdlib 2.4.0`，须配 Kotlin ≥ 2.3。
- 光照：**方向光**（台灯，唯一投影光源）+ **相机同轴点光源**（闪光灯，物理 1/r² 衰减，不投影）+ **IBL 单频带**（环境色渗）；曝光按环境照度映射 EV100。
- 接触阴影：`android/app/src/main/materials/shadow_catcher.mat` 经 matc 编译为 `assets/materials/shadow_catcher.filamat`（`unlit` + `shadowMultiplier` + `transparent`），**仅支持方向光阴影**；顶点需 `position` + `tangents`。
- 3D 资产：`assets/models/<persona>/figure.glb`，由 `android/tools/prepare_model.py` 从 VRM 转换（剥离 `KHR_materials_unlit` 走 PBR 受光，眼睛材质保留 unlit；删 VRM 扩展与孤儿贴图）。模型清单用 `PersonaModelManifest.modelAsset` 声明；无 3D 资产的人格自动回退 2.5D Canvas 渲染。
- 模型入库前必须核实并记录许可证（见 `assets/models/hoshino/README.md`）；禁止提交未核实许可证的模型。
- 骨骼姿态修正：Filament `TransformManager` 矩阵是相对父节点的完整 4×4，旋转必须按 `T · R` 重组（不能 `R · restMatrix`，否则绕原点旋转把骨骼甩飞）。
- 骨骼动画（`com.persona.ar.character.animation`）：`GltfRigParser` 从 GLB 读取节点层级 / skins / inverseBindMatrices；`FigureAnimator` 叠加体态（站姿 / 桌沿垂腿坐）、待机呼吸、情绪体态、全部 `ActionType`（WAVE/NOD/SHAKE_HEAD/BOW/JUMP/POUT/BLUSH/YAWN/SLEEP/SMUG/SIT）、4 种触摸反应与面部表情；`PoseSolver` 做 FK 并按 gltfio 同款公式算蒙皮矩阵。
- **gltfio 1.81 起蒙皮矩阵不再从 TransformManager 自动推导**：必须每帧 `RenderableManager.setBonesAsMatrices(rci, bones)` 显式上传 `inverse(renderableWorld) · jointWorld · inverseBindMatrix`，否则模型永远停在 T-pose（表现就是「手臂放不下来 / 动作不生效」）。
- 表情与口型：Face 网格带 VRM morph（`ALL_/BRW_/EYE_/MTH_*`），用 `RenderableManager.setMorphWeights` 驱动表情预设、随机眨眼与 `MTH_A` 口型；口型振幅来自 `AudioPlayer` 的 `Visualizer` 真实音频 RMS，失败时回退模拟波形。
- 动作时序：时长统一定义在 `ActionTiming`；`PersonaViewModel` 以 `System.nanoTime()` 记录动作开始时刻，`FigureScene` 用 Choreographer 帧时间计算进度，动作播完自动回落 IDLE。设置页「3D 动作演出预览」是所有动作的真机回归入口。
- 3D 蒙皮/表情/动作相关改动必须在 JVM 单测（`android/app/src/test/.../animation`）覆盖纯数学与动画曲线，`./gradlew :app:testDebugUnitTest` 全绿；CI 已加入该任务。

## 语音合成（TTS）
- 默认 `TTS_PROVIDER=edge`：使用微软 Edge 在线神经网络语音（免费、无需 key），Go 依赖 `github.com/wujunwei928/edge-tts-go` 固定在包含 Sec-MS-GEC v2 修复的主分支提交（旧 tag 的 v1 令牌会被服务端 403 拒绝，升级前先跑一次真实网络冒烟）。
- 情绪韵律（rate/pitch/volume）与好感度微调集中在 `server/internal/tts/prosody.go`；合成前文本清洗在 `text_clean.go`（只影响语音，不改动返回给客户端的显示文本）。
- 缓存目录 `server/data/tts_cache/v2/<provider>/<format>/`，key 含 voice/emotion/rate/pitch/volume/清洗后文本；OpenAI 兼容模式还含 base_url/model/voice，切换音色或模型后不会复用旧音频。命中前校验文件大小与音频头，损坏或旧版假缓存自动清理。合成失败必须返回 `nil` 让客户端回退端侧 TTS，**禁止返回占位假音频**。
- 可选 `TTS_PROVIDER=openai` + `TTS_BASE_URL/TTS_API_KEY/TTS_MODEL/TTS_OPENAI_VOICE` 接入 OpenAI 兼容 `/audio/speech`；未配置 key 自动回退 edge。容器需出网访问 `speech.platform.bing.com`，受限网络用 `TTS_PROXY` 注入代理。

## 验证分工与秒级检查
- 重任务（全量构建、打包、E2E、Docker 镜像构建）由 GitHub Actions 执行，不在本地执行。
- 本地秒级验证命令：
  - 后端单元测试：`cd server && go test -v ./...`
  - 代码规范与 Lint：`~/.agents/bin/skill-lint AGENTS.md`

## 敏感凭据规范
- 任何真实 API Token（如 `LLM_API_KEY`）严禁写入代码、文档、commit 记录或日志；统一通过环境变量或 `.env` 注入。

## PR 证据图
- UI 改动的 Before/After 截图用 `gh` 的 `--attach` 上传，**不提交进仓库**（`.github/pr-evidence/` 不得重建）。
- 大小上限以 GitHub 附件为准：图片 10MB / 视频 100MB / 单次 50 个。
- 压缩（可选，只为加载速度）：`~/.agents/bin/pr-evidence` 的 `shrink`；上传：`gh pr create --body-file <正文> --attach 'before.png#改前' --attach 'after.png#改后'`。
- 建完 PR 用 `pr-evidence check --body <正文> --post` 复核附件地址真实可访问。
