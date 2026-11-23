# 星野（persona id `hoshino`）的 3D 身体：`figure.glb`

## 模型来源与许可证（第一手核实）

| | |
|---|---|
| 模型 | **ヴィータ** — VRoid Studio 官方示例模型 |
| 作者 | VRoidプロジェクト |
| 来源 | https://vroid.com/en/studio 示例模型 / VRM Hub |
| 文件格式 | glTF 2.0 二进制 GLB（源为 VRM 0.x，即带 `VRM` 扩展的 GLB） |

以下字段由转换脚本直接从源文件 GLB 的 `extensions.VRM.meta` 读出（`python3 android/tools/prepare_model.py` 运行时打印），不是转述：

```
title: ヴィータ
author: VRoidプロジェクト
allowedUserName: Everyone
commercialUssageName: Allow
licenseName: Other
otherLicenseUrl: https://hub.vroid.com/license?allowed_to_use_user=everyone&characterization_allowed_user=everyone&corporate_commercial_use=allow&credit=unnecessary&modification=allow&personal_commercial_use=profit&redistribution=allow&sexual_expression=allow&version=1&violent_expression=allow
```

即：**允许再分发（redistribution=allow）、允许修改（modification=allow）、无需署名（credit=unnecessary）**。这是它能随仓库分发的原因；
仓库里**不得**再放入任何未核实许可证的模型文件。

## 它是什么、不是什么

它是 AI 人格「星野」（`hoshino.persona.json`，傲娇元气少女）在 AR 里的 **3D 身体**。
它**不是**《蔚蓝档案》的角色资产——那是版权角色，不能入库。若要换成其他模型，直接替换本目录的 `figure.glb`
（或修改 `hoshino.persona.json` 的 `modelAsset` 指向别的 GLB），管线通过 Filament gltfio 加载任意标准 glTF 2.0。

## 转换做了什么（源 → 产物）

`android/tools/prepare_model.py`（仅 Python 3 标准库）：

1. **剥离 `KHR_materials_unlit`**：VRM/MToon 惯例给所有材质打 unlit，防止标准查看器对已烘焙明暗的贴图双重打光；
   但本项目的目标是让 Filament 用**真实 PBR 光照**渲染手办（迎光面高光随光源流动、有真实体积感），因此必须剥离。
   例外：眼睛材质（名称含 `EYE`：EyeWhite / EyeIris / EyeHighlight / EyeExtra）保留 unlit，维持手绘虹膜的锐利度。
2. **删除 `extensions.VRM`**（表情、弹簧骨、MToon 定义、humanoid 表）：对 Filament 是死数据。
3. **可达性裁剪**：删除不再被任何材质引用的 texture / image（自然剔除 1.85 MB 的 `Thumbnail` 与 `Shader_None*` 孤儿）。
4. **重建 BIN chunk**：只保留仍被 accessor / image 引用的 bufferView，紧凑排列并重新映射索引。

结果：13,967,836 B → 11,957,336 B；16 材质 / 23 贴图 / 3 mesh（Face.baked、Body.baked、Hair001.baked）/ 162 nodes / 3 skins / 54 humanoid bones；
无动画（静态 A-pose），呼吸与摆动由 Kotlin 侧驱动骨骼变换。脚本自带断言自检（unlit 仅剩眼睛、无 VRM、无 Thumbnail、索引不越界、BIN 长度一致）。

## 再生成

```bash
# 源文件需自行从 VRoid Studio / VRM Hub 获取 SampleModel.vrm（不随仓库提交）
python3 android/tools/prepare_model.py <SampleModel.vrm> android/app/src/main/assets/models/hoshino/figure.glb
```
