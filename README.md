# 次元回响 (Persona AR)

[![Powered by DeepSeek Harness](https://img.shields.io/badge/powered_by-dsh-4D6BFE.svg)](https://github.com/deepseek-ai/deepseek-harness)

**Persona AR** 是一款基于增强现实 (AR) 与专属拟人情感大模型的 Android 应用程序。借助手机摄像头将二次元纸片人“星野”投射在现实世界中（桌面、地面、床头等），并结合专属情感后端服务，实现如同真实人类般富有情绪波动、好感度羁绊与身体触碰反馈的深度陪伴交流。

---

## 🌟 核心特性

### 1. 纯 3D 物理空间定位与现实光影融合
- **真 3D 手办空间引擎 (Filament + 全动作骨骼 + 6DoF 视觉惯性追踪)**：
  - 离线 Filament PBR 渲染 VRM 手办：世界坐标系方向光真实投射 PCSS 柔和接触阴影、6 方向环境光实时更新 2-Band IBL 球谐与环境反射立方体贴图、相机同轴柔光补光与按环境照度自动 EV100 曝光。
  - **视觉-惯性 6DoF 360° 空间定位**：零 GMS 依赖的 `UniversalSensorSurfaceTracker` 融合 `GAME_ROTATION_VECTOR` 重力校平与关键帧锁定 2 点 RANSAC 稀疏光流追踪，静止持握零漂移、拿着手机绕模型走动 360° 可稳定观察正面、侧面与背面。
  - **射线-桌面求交精准拖拽**：单指拖拽严格沿着物理桌面水平面射线求交重定位，指哪拖到哪，松手稳停、零回弹。
  - **全身骨骼动作与平滑混合**：支持挥手、点头、摇头、鞠躬、跳跃、噘嘴、害羞捂脸、打哈欠、打瞌睡、得意叉腰、桌沿垂腿坐，以及基于 3D 骨骼屏幕投影精确拾取的摸头/戳脸/牵手/拥抱触摸反应；全关节四元数 Slerp 交叉淡入消除动作切帧突变。
  - **VRM 表情 + 真实口型**：41 个 blend shape 平滑驱动微笑/害羞/生气/惊讶/困倦与随机双连眨眼，`Visualizer` 采样真实语音振幅驱动嘴型开合。

### 2. 模拟真实人类的情感交互大脑
- **多维度情绪与动作驱动**：
  - 情感状态机包含 7 种情绪：`JOY`（喜悦）、`SHY`（害羞）、`SAD`（委屈）、`SURPRISED`（惊讶）、`THINKING`（思考）、`ANGRY`（傲娇生气）、`NEUTRAL`（平静）。
  - 自动触发肢体动作：`WAVE`（挥手）、`BLUSH`（害羞捂脸）、`NOD`（点头）、`POUT`（噘嘴）、`JUMP`（开心轻跳）。
- **身体部位触碰交互 (Touch Reaction)**：
  - 支持直接点击角色身体部位，配合物理震动反馈：
    - **摸头 (HEAD)**：害羞闭眼享受，好感度大幅上升。
    - **戳脸 (CHEEK)**：嘟嘴傲娇反抗，吐槽“纸片人被捏也是会痛的哼”。
    - **牵手 (HAND)**：光子共振，脸红心跳。
    - **拥抱 (BODY)**：深层情感羁绊反馈。
- **好感度与羁绊成长系统 (Affinity System)**：
  - 随着日常对话与陪伴，好感度逐步进阶：**初识 (Curious) → 熟络 (Friendly) → 信赖 (Trusting) → 依恋 (Deeply Attached)**。
  - 语气、态度与反应随好感度显著变化，真正模拟真实恋爱/养成陪伴感。

### 3. 拟人语音与实时口型同步 (Lip-sync)
- **真人级神经网络语音**：后端默认使用微软 Edge 神经网络 TTS（免费无需 key，四角色分别对应女声 `zh-CN-XiaoyiNeural` / `zh-CN-XiaoxiaoNeural` / `zh-TW-HsiaoYuNeural` / `zh-TW-HsiaoChenNeural`），并按 9 种情绪与羁绊等级实时调整语速/音高/音量（喜悦轻快、害羞软糯、困倦放慢…），合成前自动清洗 emoji/舞台说明与 XML 特殊字符；磁盘 SHA256 缓存命中后毫秒级返回。
- **真实口型开合**：Android `Visualizer` 采集播放中的真实音频波形 RMS 驱动 3D 手办 VRM 口型 morph 与头部微动；失败时自动回退端侧 TTS。
- **全双工连麦与实时打断**：支持长按麦克风对讲与连续连麦，内置 320ms 预卷环形音频缓冲（`VoiceAudioBuffer`）与服务端轮次取消栅栏，开口瞬间毫秒级中断前轮播报与上游推理。

---

## 🏗️ 项目架构

```
persona_ar/
├── android/                     # Android AR 客户端
│   ├── app/src/main/
│   │   ├── java/com/persona/ar/
│   │   │   ├── ar/             # 6DoF 视觉惯性追踪、CameraX 真实内参/光学探针、ARCore 可选适配
│   │   │   ├── audio/          # 音频播放、TTS 兜底与口型振幅采样、麦克风拾音
│   │   │   ├── character/      # 纯 3D Filament 渲染管线、骨骼动画、VRM 表情与漫画 VFX
│   │   │   ├── data/           # 数据模型、Retrofit API Client、Repository
│   │   │   ├── ui/             # Jetpack Compose UI、MVI ViewModel、状态管理
│   │   │   │   └── components/ # 聊天气泡浮窗、对讲输入栏、好感度胶囊、设置面板
│   │   │   ├── MainActivity.kt
│   │   │   └── PersonaApplication.kt
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── server/                      # 专属情感后端服务 (Go + Gin + Gorilla WebSocket)
│   ├── cmd/server/             # 服务入口
│   ├── internal/
│   │   ├── api/                # 对话/语音/触碰/记忆/商城/ASR/WS 全双工路由
│   │   ├── persona/            # 角色人设、情绪动作状态机、长期记忆
│   │   ├── llm/                # OpenAI 兼容大模型接入与内置拟人规则降级
│   │   ├── asr/                # Whisper 兼容语音识别与内置降级
│   │   └── tts/                # 微软 Edge 神经网络语音 + 情绪韵律 + 版本化缓存
│   ├── internal/test/          # Go 单元测试
│   ├── Dockerfile
│   └── go.mod
├── docker-compose.yml           # 一键容器化部署
└── README.md
```

---

## 🚀 快速启动指南

### 1. 启动专属情感后端服务

#### 方式 A：Docker 一键拉起（推荐）
```bash
docker compose up -d
```

#### 方式 B：本地 Go 运行
```bash
cd server
go run ./cmd/server
```
服务将在 `http://0.0.0.0:8000` 启动，健康检查端点为 `http://localhost:8000/healthz`。

> **提示**：服务内置了高拟真二次元情感大脑与离线规则库，即使不配置外部 API Key 也能开箱畅快体验！如需接入真实外部大语言模型，只需在 `.env` 或环境变量中传入 `LLM_API_KEY`（`docker-compose.yml` 通过 `${VAR:-default}` 透传，无需改文件），同时挂载 `./server/data:/app/data` 持久化好感度、回忆手账与语音缓存；ASR 相关变量同样支持 `ASR_PROVIDER` / `ASR_API_KEY` / `ASR_LANGUAGE` 等配置。
>
> **语音**：默认使用微软 Edge 神经网络 TTS（`TTS_PROVIDER=edge`，免费无需 key，需服务端可访问 `speech.platform.bing.com`；受限网络可设 `TTS_PROXY`）。也可用 `TTS_PROVIDER=openai` + `TTS_BASE_URL/TTS_API_KEY/TTS_MODEL/TTS_OPENAI_VOICE` 接入任意 OpenAI 兼容 `/audio/speech` 服务。合成失败时客户端自动回退端侧 TTS。

### 2. 下载预编译 APK（推荐）

项目的 [GitHub Releases](../../releases) 提供每个正式版本（`v*` tag）的 Release APK：
下载 `persona-ar-vX.Y.Z.apk` 直接安装即可（默认使用 Android 调试密钥签名，可正常安装；
如需正式分发密钥请联系维护者）。

### 3. 本地构建与运行 Android 客户端

1. 打开 Android Studio，选择 **Open** 并导入 `android` 目录。
2. 选择运行设备（**真机优先**）：
   - 先枚举设备：`adb devices -l` 中只有状态为 `device` 的设备可用；选中后所有命令固定加 `-s <serial>`。完整设备判定流程见用户级 skill `android-emulator-debug`。
   - 本项目 **ARCore 是可选能力**：`AndroidManifest.xml` 中 `android.hardware.camera.ar` 声明 `required="false"`、`com.google.ar.core` 元数据为 `optional`，依赖 `com.google.ar:core`（版本见 `android/app/build.gradle.kts`）。设备有可用 ARCore 时走物理平面检测与空间锚定，否则自动使用基于 IMU 与稀疏光流的通用 6DoF 空间追踪器，纯 3D 手办与环绕观察照常运行。
   - 因此 **ARCore 物理平面 / 空间锚定必须在支持 ARCore 的真机上验收**：`adb -s <serial> shell pm list features | grep android.hardware.camera.ar` 有输出，且设备可用 Google Play AR 服务（应用内由 `ArCoreApk.checkAvailability()` 判定，降级时 logcat 会打印 `ARSessionManager` 的 Universal Sensor 日志）。其余部分（Filament 3D 渲染与光影融合、动作表情、6DoF 视觉惯性追踪与拖拽、语音交互）普通真机满足 API 26+ 与 ABI 匹配即可验证；无 ARCore 的真机只能验证这些降级路径。
   - 没有符合条件的真机时才用模拟器（推荐 API 26+），并记录原因；模拟器只能验证非 AR 部分，虚拟相机与模拟传感器画面不能替代真机 AR 透视与空间追踪结论。
3. 运行构建：
   - 如果使用 Android 模拟器，App 默认自动连接宿主机 `http://10.0.2.2:8000/`。
   - 如果使用真机运行，请确保手机与电脑处于同一局域网 Wi-Fi，在 App 顶部齿轮【设置】中将服务器地址修改为 `http://<电脑局域网IP>:8000/`。
4. 构建 Release APK：`cd android && ./gradlew assembleRelease`，产物在 `app/build/outputs/apk/release/app-release.apk`。

---

## 📱 交互操作指南

| 操作 | 交互效果 |
| :--- | :--- |
| **单指在屏幕上拖动** | 沿真实物理桌面水平面精确拖拽重定位 3D 手办（松手稳停不乱跑） |
| **双指捏合 / 展开** | 放大或缩小 3D 手办在现实中的比例 |
| **绕手办周围走动** | 360° 从正面、侧面与背面立体观察 3D 手办 |
| **轻触头部** | 触发摸头反应（害羞脸红、好感度提升） |
| **轻触脸颊** | 触发捏脸反应（嘟嘴傲娇、生气特效） |
| **轻触双手** | 触发牵手反应（光子共振、眨眼微笑） |
| **按住底部麦克风** | 实时拾音对讲，松开即刻发送语音给星野 |
| **点击顶部重放按钮** | 清除当前锚点，重新轻触桌面放置 3D 模型 |
| **点击齿轮图标** | 打开设置：服务器地址、3D 动作演出预览（一键触发全部动作/坐姿切换）与系统参数 |