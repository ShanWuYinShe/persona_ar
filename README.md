# 次元回响 (Persona AR)

[![Powered by DeepSeek Harness](https://img.shields.io/badge/powered_by-dsh-4D6BFE.svg)](https://github.com/deepseek-ai/deepseek-harness)

**Persona AR** 是一款基于增强现实 (AR) 与专属拟人情感大模型的 Android 应用程序。借助手机摄像头将二次元纸片人“星野”投射在现实世界中（桌面、地面、床头等），并结合专属情感后端服务，实现如同真实人类般富有情绪波动、好感度羁绊与身体触碰反馈的深度陪伴交流。

---

## 🌟 核心特性

### 1. 现实空间投射与 AR 双模无缝切换
- **2.5D 精致动态立绘透视模式 (Billboard AR)**：
  - 借助 CameraX 实现超低延迟的真实世界背景透视。
  - 细腻的二次元动态立绘渲染：包含自然呼吸浮动、眼神与周期性眨眼、动态腮红红晕、情绪表情差分（开朗、害羞、委屈、傲娇噘嘴）。
  - **现实桌面投影阴影**：脚底生成带羽化的环境光椭圆阴影，让纸片人稳稳立于真实世界桌面或地面。
  - **陀螺仪重力微视差 (Parallax Effect)**：晃动手机时，纸片人产生空间视差浮动，强化破壁而出的立体沉浸感。
  - **手势自由操控**：单指任意拖拽摆放位置、双指缩放调整大小比例。
- **3D 真实空间平面检测模式 (ARCore 3D)**：
  - 支持 Google ARCore 水平地面/桌面识别与空间特征网格扫描，提供 360 度视角旋转与空间锚定。
  - 可以在设置中心随时一键切换两种视觉模式。

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
- **自然声音合成**：支持微软 Edge-TTS 少女声线输出，同时内置端侧 TTS 朗读兜底。
- **实时口型开合**：音频播放时，算法实时计算声音振幅频谱，动态驱动纸片人嘴巴开合，栩栩如生。
- **语音输入**：支持长按麦克风对讲，带有实时拾音跳动脉冲波形展示。

---

## 🏗️ 项目架构

```
persona_ar/
├── android/                     # Android AR 客户端
│   ├── app/src/main/
│   │   ├── java/com/persona/ar/
│   │   │   ├── ar/             # AR 模式切换、CameraX 透视背景、ARCore 适配与陀螺仪
│   │   │   ├── audio/          # 音频播放、TTS 兜底与口型振幅采样、麦克风拾音
│   │   │   ├── character/      # 2.5D 精致动态立绘 Canvas 渲染器、3D 网格底座、触碰热区
│   │   │   ├── data/           # 数据模型、Retrofit API Client、Repository
│   │   │   ├── ui/             # Jetpack Compose UI、MVI ViewModel、状态管理
│   │   │   │   └── components/ # 聊天气泡浮窗、对讲输入栏、好感度胶囊、设置面板
│   │   │   ├── MainActivity.kt
│   │   │   └── PersonaApplication.kt
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── server/                      # 专属情感后端服务 (Python FastAPI)
│   ├── app/
│   │   ├── api/                # 对话互动、触碰反应、状态查询 API 路由
│   │   ├── core/               # 配置管理 (模型密钥、角色人设、TTS 声音)
│   │   ├── models/             # Pydantic 结构化数据定义
│   │   ├── persona/            # 拟人性格提示词工程、记忆回溯、情绪动作状态机
│   │   └── tts/                # Edge-TTS 自然语音流合成
│   ├── tests/                  # 服务端自动化单元测试 (Pytest)
│   ├── Dockerfile
│   └── requirements.txt
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

#### 方式 B：本地 Python 运行
```bash
cd server
pip install -r requirements.txt
python run.py
```
服务将在 `http://0.0.0.0:8000` 启动，健康检查端点为 `http://localhost:8000/healthz`。

> **提示**：服务内置了高拟真二次元情感大脑与离线规则库，即使不配置外部 API Key 也能开箱畅快体验！如需接入真实外部大语言模型，只需在 `docker-compose.yml` 或环境变量中填入 `LLM_API_KEY`（支持 DeepSeek / OpenAI 等）。

### 2. 下载预编译 APK（推荐）

项目的 [GitHub Releases](../../releases) 提供每个正式版本（`v*` tag）的 Release APK：
下载 `persona-ar-vX.Y.Z.apk` 直接安装即可（默认使用 Android 调试密钥签名，可正常安装；
如需正式分发密钥请联系维护者）。

### 3. 本地构建与运行 Android 客户端

1. 打开 Android Studio，选择 **Open** 并导入 `android` 目录。
2. 确保已连接真机或启动 Android 模拟器（推荐 API 26+）。
3. 运行构建：
   - 如果使用 Android 模拟器，App 默认自动连接宿主机 `http://10.0.2.2:8000/`。
   - 如果使用真机运行，请确保手机与电脑处于同一局域网 Wi-Fi，在 App 顶部齿轮【设置】中将服务器地址修改为 `http://<电脑局域网IP>:8000/`。
4. 构建 Release APK：`cd android && ./gradlew assembleRelease`，产物在 `app/build/outputs/apk/release/app-release.apk`。

---

## 📱 交互操作指南

| 操作 | 交互效果 |
| :--- | :--- |
| **单指在屏幕上拖动** | 自由摆放纸片人在现实中的位置（支持摆放在桌面或地面） |
| **双指捏合 / 展开** | 放大或缩小纸片人投射尺寸 |
| **轻触头部** | 触发摸头反应（害羞脸红、好感度提升） |
| **轻触脸颊** | 触发捏脸反应（嘟嘴傲娇、生气特效） |
| **轻触双手** | 触发牵手反应（光子共振、眨眼微笑） |
| **按住底部麦克风** | 实时拾音对讲，松开即刻发送语音给星野 |
| **点击顶部 AR 胶囊** | 在【2.5D 精致立绘】与【3D 空间追踪】模式间无缝切换 |
| **点击齿轮图标** | 打开设置，配置服务器地址与系统参数 |
