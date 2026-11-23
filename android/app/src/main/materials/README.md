# Filament 材质（`.mat` 源 + `.filamat` 编译产物）

## shadow_catcher —— AR 接触阴影接收面

一块平放在桌面、自身完全透明的四边形，**只显示角色被方向光投下的阴影**，叠在 CameraX 相机画面之上，
让 gltfio 渲染的 3D 手办看起来真实站在桌面上。

- 原理（Filament v1.81.0 `docs/Materials.md` → “Lighting: shadowMultiplier”）：
  `shadowMultiplier` 仅在 `unlit` 着色模型可用。着色器 `shaders/src/surface_shading_unlit.fs` 中：
  `finalColor = baseColor * (1.0 - visibility)` —— 受光处 `visibility = 1` → 颜色归零（隐形）；阴影处 → 黑色、
  不透明度 = `baseColor.a`。方向光开启 `ShadowOptions.screenSpaceContactShadows = true` 时还会叠乘屏幕空间
  接触阴影，形成脚底「零距离深黑接触线」。
- **限制**：仅支持**方向光**阴影。相机同轴点光源（闪光灯高光）的阴影不会出现在此平面上，故管线中由方向光
  充当「台灯」投地面阴影，点光源不投影。
- 参数 `strength`（float，默认 1.0）：阴影最大不透明度，Kotlin 侧按环境照度实时 `setParameter`。
- 顶点要求：`position` + `tangents`（程序化四边形需提供 TANGENT 属性）。

### 材质语言注意（v1.81.0）
非采样器参数在 fragment 中必须通过 `materialParams.<name>` 结构体字段访问（采样器用 `materialParams_<name>` 前缀）。
写成裸名会报 `'strength' : undeclared identifier`。

### 再生成
matc 来自 Filament 官方 macOS 发行包 `filament-v1.81.0-mac.tgz` 的 `bin/matc`（arm64），不随仓库提交。

```bash
curl -L -o /tmp/filament-mac.tgz \
  https://github.com/google/filament/releases/download/v1.81.0/filament-v1.81.0-mac.tgz
tar -xzf /tmp/filament-mac.tgz -C /tmp
/tmp/filament/bin/matc -p mobile \
  -o android/app/src/main/assets/materials/shadow_catcher.filamat \
  android/app/src/main/materials/shadow_catcher.mat
/tmp/filament/bin/matinfo android/app/src/main/assets/materials/shadow_catcher.filamat
```

`.filamat` 是编译产物，运行时用 `Material.Builder().payload(ByteBuffer).build(engine)` 加载。
`-p mobile` 目标同时覆盖 Android 的 OpenGL ES 与 Vulkan 后端。
