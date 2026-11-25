package com.persona.ar.character.filament

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.google.android.filament.ACESToneMapper
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Fence
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.persona.ar.character.animation.AnimationInput
import com.persona.ar.character.animation.FaceRig
import com.persona.ar.character.animation.FigureAnimator
import com.persona.ar.character.animation.GltfRig
import com.persona.ar.character.animation.GltfRigParser
import com.persona.ar.character.animation.PoseSolver
import com.persona.ar.data.model.TouchPart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 单个 AR 视图的 Filament 场景：渲染器 / 场景 / 视图 / 虚拟相机 / 双光源 / 环境光 / 接触阴影地面 / glTF 手办。
 *
 * 光照模型（与 CameraEnvironmentalAnalyzer 提供的现实环境参数对齐）：
 * - **方向光 (key)**：充当「台灯」，唯一投射阴影的光源 —— shadow catcher 平面只支持方向光阴影；
 *   方向由环境主光源方位解算，默认从角色后上方打向相机，使影子落在角色与相机之间清晰可见。
 * - **点光源 (flash)**：与虚拟相机同轴的「闪光灯」，Filament 物理点光源自带 1/r² 衰减，
 *   让手办迎光面产生随手机晃动而流动的曲面高光；不投影（点光源阴影不会出现在 shadow catcher 上，且立方体阴影贴图昂贵）。
 * - **环境光 (IBL)**：单频带球谐常数项承载现实环境的 RGB 色渗与照度。
 * - **曝光**：由环境照度映射 EV100，再按物理相机模型 (1/60s、ISO100) 反推光圈；各光源强度按同一 EV 的
 *   「正确曝光所需总照度」分摊，保证手办在任何环境亮度下都曝光得当、而明暗对比随环境变化。
 *
 * 所有方法必须在主线程调用（渲染循环跑在 Choreographer 上）。生命周期照抄官方 ModelViewer。
 */
class FigureScene(
    private val context: Context,
    private val engine: Engine = FilamentEngineHolder.engine()
) {
    companion object {
        private const val TAG = "FigureScene"

        /** 虚拟相机竖屏垂直视场角：按典型手机主摄 ~26mm 等效焦距估读，与 CameraX 预览透视尽量一致 */
        const val CAMERA_VERTICAL_FOV_DEGREES = 62.0
        private const val CAMERA_NEAR = 0.02
        private const val CAMERA_FAR = 8.0

        /** 手办默认身高 (米)：约 1/8 比例手办，再乘双指手势 scaleFactor (0.4..2.2) */
        const val DEFAULT_FIGURE_HEIGHT_METERS = 0.22f
        /** 接触阴影地面半边长 = 手办身高 × 此比例（需覆盖方向光投下的影子范围） */
        private const val GROUND_HALF_EXTENT_RATIO = 1.05f

        private const val SHADOW_CATCHER_ASSET = "materials/shadow_catcher.filamat"
        private const val SHADOW_MAP_SIZE = 2048
        private const val SHADOW_FAR_METERS = 2.5f

        // 曝光模型：EV100 = EV_BASE + EV_RANGE × 环境照度；快门 / ISO 固定，由 EV 反推光圈
        private const val EV_BASE = 7.5
        private const val EV_RANGE = 2.5
        private const val SHUTTER_SECONDS = 1.0 / 60.0
        private const val ISO = 100.0
        /** 「该 EV 下正确曝光」所需的总照度 (lux)：18% 灰 L = 0.216·2^EV，E = L·π/0.18 */
        private const val LUX_PER_EV = 3.77f
        private const val KEY_LIGHT_SHARE = 0.45f
        private const val FLASH_LIGHT_SHARE = 0.30f
        private const val AMBIENT_SHARE = 0.40f
    }

    // ---- 核心对象 ----
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = engine.entityManager.create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private var swapChain: SwapChain? = null
    private var viewportWidth = 1
    private var viewportHeight = 1

    // ---- gltfio ----
    private val materialProvider: MaterialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine, true)
    private var asset: FilamentAsset? = null
    private var assetRootInstance = 0
    private var assetScale = 1f
    /** 手办枢轴：包围盒中心的 XZ + 最低点 Y（脚底），模型被平移到脚底落于原点 */
    private val assetPivot = FloatArray(3)
    private val headEntities = HashSet<Int>()
    private val bodyEntities = HashSet<Int>()
    private val readyRenderables = IntArray(64)

    // ---- 骨骼动画 / 蒙皮 / 表情（gltfio 1.81 需应用侧计算蒙皮矩阵并显式上传）----
    private var rig: GltfRig? = null
    private var poseSolver: PoseSolver? = null
    private var animator: FigureAnimator? = null
    private val animationInput = AnimationInput()
    private val skinnedRenderables = ArrayList<SkinnedRenderable>()
    private val faceRenderables = ArrayList<FaceRenderable>()
    private val boundMeshEntities = HashSet<Int>()
    private val boundFaceEntities = HashSet<Int>()
    /** 所有蒙皮网格与 renderable 都已完成绑定决策；false 时每帧补绑（应对异步资源延迟就绪） */
    private var rigBindComplete = false
    private var lastAnimFrameNanos = 0L
    private var currentAirborne = 0f
    private var lastPitchDegrees = 0f
    private var lastRollDegrees = 0f

    /** 一个蒙皮渲染实例（Face/Body/Hair 各一个）：每帧上传 joints*16 蒙皮矩阵 */
    private class SkinnedRenderable(
        val rci: Int,
        val mesh: GltfRig.MeshNode,
        val boneMatrices: FloatArray
    )

    /** 一个带 VRM morph 表情的渲染实例：每帧上传表情/口型/眨眼权重 */
    private class FaceRenderable(
        val rci: Int,
        val faceRig: FaceRig,
        val weights: FloatArray
    )

    /** 模型资产：GLB 直接字节缓冲 + 解析出的骨骼拓扑（读盘/解析在 IO 线程完成） */
    class ModelAsset(val buffer: ByteBuffer, val rig: GltfRig?)

    // ---- 灯光 ----
    private val keyLightEntity = engine.entityManager.create()
    private val flashLightEntity = engine.entityManager.create()
    private val keyLightInstance: Int
    private val flashLightInstance: Int
    private var indirectLight: IndirectLight? = null
    private var colorGrading: ColorGrading? = null

    // ---- 接触阴影地面 ----
    private val groundEntity = engine.entityManager.create()
    private var groundMaterial: Material? = null
    private var groundInstance: MaterialInstance? = null
    private var groundVertexBuffer: VertexBuffer? = null
    private var groundIndexBuffer: IndexBuffer? = null
    private var groundTransformInstance = 0

    // ---- 每帧复用缓冲 ----
    private val transformManager: TransformManager = engine.transformManager
    private val lightManager: LightManager = engine.lightManager
    private val camRight = FloatArray(3)
    private val camUp = FloatArray(3)
    private val camForward = FloatArray(3)
    private val footWorld = FloatArray(3)
    private val mModel = FloatArray(16)
    private val mGround = FloatArray(16)
    private val projMatrix = DoubleArray(16)
    private val viewMatrix = DoubleArray(16)
    /** Filament 的 pick 只接受 Executor 或 Handler（不能用任意对象占位）；回调需回主线程更新触摸反馈 */
    private val pickHandler = Handler(Looper.getMainLooper())

    /** UiHelper 回调：Surface 生命周期 → SwapChain 管理（透明 + 4x MSAA） */
    val surfaceCallback: UiHelper.RendererCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            // 透明 SwapChain：清屏 alpha=0，叠加在 CameraX 预览之上
            swapChain = engine.createSwapChain(surface, SwapChain.CONFIG_TRANSPARENT or SwapChain.CONFIG_MSAA_4_SAMPLES)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            viewportWidth = width
            viewportHeight = height
            view.viewport = Viewport(0, 0, width, height)
            // 等待在途帧处理完毕，避免 Surface 尺寸变化与在途帧竞争（照抄官方 ModelViewer）
            val fence = engine.createFence()
            fence.wait(Fence.Mode.FLUSH, Fence.WAIT_FOR_EVER)
            engine.destroyFence(fence)
        }
    }

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        renderer.clearOptions = Renderer.ClearOptions().apply {
            setClearColor(0.0, 0.0, 0.0, 0.0)
            clear = true
            discard = false
        }
        view.setShadowingEnabled(true)
        view.shadowType = View.ShadowType.PCF
        view.setAntiAliasing(View.AntiAliasing.NONE) // 已启用 4x MSAA
        view.dithering = View.Dithering.TEMPORAL
        view.setPostProcessingEnabled(true)
        view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply {
            enabled = true
            radius = 0.25f
            power = 2.0f
            intensity = 1.0f
            quality = View.QualityLevel.MEDIUM
        })
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = 0.16f
            resolution = 360
            quality = View.QualityLevel.MEDIUM
            highlight = 1000.0f
        }

        // 电影级 ACES 色彩分级与色调映射：高光平滑过渡，增强二次元角色色彩饱和与对比
        colorGrading = ColorGrading.Builder()
            .quality(ColorGrading.QualityLevel.HIGH)
            .toneMapper(ACESToneMapper())
            .contrast(1.05f)
            .vibrance(1.08f)
            .build(engine)
        view.colorGrading = colorGrading

        // 方向光：台灯，唯一投影光源。阴影贴图覆盖 ~2.5m 的桌面级场景，开启屏幕空间接触阴影形成脚底深黑接触线
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.92f)
            .intensity(1_000f)
            .direction(0.2f, -1.0f, 0.6f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = SHADOW_MAP_SIZE
                shadowFar = SHADOW_FAR_METERS
                shadowNearHint = 0.1f
                shadowFarHint = 1.5f
                maxShadowDistance = SHADOW_FAR_METERS + 0.5f
                screenSpaceContactShadows = false
                shadowBulbRadius = 0.045f
                constantBias = 0.001f
                normalBias = 1.0f
                polygonOffsetConstant = 0.5f
                polygonOffsetSlope = 1.5f
            })
            .build(engine, keyLightEntity)
        keyLightInstance = lightManager.getInstance(keyLightEntity)

        // 点光源：相机同轴闪光灯，物理 1/r² 衰减，不投影
        LightManager.Builder(LightManager.Type.POINT)
            .color(0.96f, 0.98f, 1.0f)
            .intensityCandela(1_000f)
            .falloff(3.0f)
            .position(0f, 0f, 0f)
            .castShadows(false)
            .build(engine, flashLightEntity)
        flashLightInstance = lightManager.getInstance(flashLightEntity)
        scene.addEntity(keyLightEntity)
        scene.addEntity(flashLightEntity)

        // 环境光：2-Band 球谐定向光照（4 组基函数共 12 系数）：
        // L00 为基础漫反射常数项；L1,-1 为垂直 Y 轴偶极子（天顶偏冷白光，桌面偏暖地表色渗），
        // 消除单频带平面感，让手办的下巴底、发梢内侧与裙摆下方产生真实的半球环境漫反射。
        val initialSh = FigureLighting.compute2BandSH()
        indirectLight = IndirectLight.Builder()
            .irradiance(2, initialSh)
            .intensity(1_000f)
            .build(engine)
        scene.indirectLight = indirectLight

        buildGround()
    }

    // ===================================================================== 资产

    /** 从 assets 读取任意二进制资产到直接字节缓冲（供 IO 线程调用） */
    fun readAssetBuffer(assetPath: String): ByteBuffer {
        val stream = context.assets.open(assetPath)
        val bytes = stream.use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        return buffer
    }

    /**
     * 读取 GLB 并解析骨骼拓扑（IO 线程调用）：返回直接字节缓冲 + [GltfRig]。
     * 解析失败时 rig 为 null，退化为静态手办渲染，不影响其余功能。
     */
    fun loadModelAsset(assetPath: String): ModelAsset {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val parsedRig = GltfRigParser.parse(bytes)
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        return ModelAsset(buffer, parsedRig)
    }

    /** 安装 GLB 手办（主线程）：解析 → 异步上传贴图 → 建立骨骼/蒙皮/表情绑定。重复调用先销毁旧资产。 */
    fun installAsset(model: ModelAsset) {
        destroyAsset()
        val newAsset = assetLoader.createAsset(model.buffer)
        if (newAsset == null) {
            Log.e(TAG, "createAsset 返回 null，GLB 解析失败")
            return
        }
        asset = newAsset
        resourceLoader.asyncBeginLoad(newAsset)
        newAsset.releaseSourceData()
        assetRootInstance = transformManager.getInstance(newAsset.root)
        rig = model.rig
        poseSolver = model.rig?.let { PoseSolver(it) }
        animator = model.rig?.let { FigureAnimator(it) }
        lastAnimFrameNanos = 0L

        // 枢轴与缩放：包围盒中心 XZ + 脚底 Y，使脚底落在落脚点、身高归一到默认手办身高
        val box = newAsset.boundingBox
        val center = box.center
        val half = box.halfExtent
        assetPivot[0] = center[0]
        assetPivot[1] = center[1] - half[1]
        assetPivot[2] = center[2]
        assetScale = DEFAULT_FIGURE_HEIGHT_METERS / (2f * half[1]).coerceAtLeast(1e-4f)

        // 部位映射：VRoid 导出的网格节点名 Face / Hair001 → 头部，Body → 身体
        headEntities.clear()
        bodyEntities.clear()
        headEntities.addAll(newAsset.getEntitiesByPrefix("Face").toList())
        headEntities.addAll(newAsset.getEntitiesByPrefix("Hair").toList())
        bodyEntities.addAll(newAsset.getEntitiesByPrefix("Body").toList())
        boundMeshEntities.clear()
        boundFaceEntities.clear()
        skinnedRenderables.clear()
        faceRenderables.clear()
        rigBindComplete = false
        bindRig(newAsset)

        Log.i(TAG, "asset installed: bbox=${half[0] * 2}x${half[1] * 2}x${half[2] * 2}m scale=$assetScale " +
                "head=${headEntities.size} body=${bodyEntities.size} rigNodes=${model.rig?.nodeCount ?: 0} " +
                "skinned=${skinnedRenderables.size} face=${faceRenderables.size}")
    }

    /**
     * 绑定骨骼蒙皮与 VRM morph 表情渲染实例。
     *
     * 关键：gltfio 1.81 起蒙皮矩阵不再自动从 TransformManager 推导，必须由应用侧按
     * inverse(renderableWorld) · jointWorld · inverseBindMatrix 计算后上传，否则模型永远停在
     * 绑定 T-pose（上一版「T-pose 手臂放不下来、动作不生效」的根因即此）。
     */
    private fun bindRig(a: FilamentAsset) {
        val rig = rig ?: return
        val rcm = engine.renderableManager
        for (mesh in rig.meshNodes) {
            var entity = a.getFirstEntityByName(mesh.name)
            if (entity == 0) {
                entity = a.getEntitiesByPrefix(mesh.name).toList().firstOrNull { it != 0 } ?: 0
            }
            if (entity == 0) {
                Log.w(TAG, "蒙皮网格实体未找到: ${mesh.name}")
                continue
            }
            if (entity in boundMeshEntities) continue
            val rci = rcm.getInstance(entity)
            if (rci == 0) continue // 渲染实例尚未就绪：不要记为已绑定，等 populateScene 重试
            boundMeshEntities.add(entity)
            val joints = rig.skins[mesh.skinIndex].joints.size
            skinnedRenderables.add(SkinnedRenderable(rci, mesh, FloatArray(joints * 16)))
            Log.i(TAG, "skinned bound: ${mesh.name} joints=$joints rci=$rci")
        }
        for (entity in a.getRenderableEntities()) {
            if (entity in boundFaceEntities) continue
            val names = runCatching { a.getMorphTargetNames(entity) }.getOrNull()
            // 取名字失败（实例尚未就绪）时保留重试机会；空数组则是确定没有 morph，直接记已处理。
            if (names == null) continue
            if (names.isEmpty() || names.none { it.contains("Fcl_") }) {
                boundFaceEntities.add(entity)
                continue
            }
            val rci = rcm.getInstance(entity)
            if (rci == 0) continue // 同上：渲染实例未就绪，不能提前标记
            boundFaceEntities.add(entity)
            faceRenderables.add(FaceRenderable(rci, FaceRig(names), FloatArray(names.size)))
            Log.i(TAG, "face bound: ${a.getName(entity)} morphs=${names.size}")
        }
        // 只有「每个蒙皮网格都已创建绑定 + 每个 renderable 都已做出表情绑定决策」才算完成：
        // 若只判断列表非空，Face/Body 已绑而 Hair 尚未就绪时会漏掉补绑，Hair 将永远停在绑定姿势。
        val allMeshesBound = rig.meshNodes.all { mesh ->
            skinnedRenderables.any { it.mesh.nodeIndex == mesh.nodeIndex }
        }
        val renderables = a.getRenderableEntities()
        val allRenderablesSettled = renderables.isNotEmpty() && renderables.all { it in boundFaceEntities }
        rigBindComplete = allMeshesBound && allRenderablesSettled
    }
    private fun destroyAsset() {
        val a = asset ?: return
        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()
        assetLoader.destroyAsset(a)
        asset = null
        assetRootInstance = 0
        headEntities.clear()
        bodyEntities.clear()
        skinnedRenderables.clear()
        faceRenderables.clear()
        boundMeshEntities.clear()
        boundFaceEntities.clear()
        rigBindComplete = false
        rig = null
        poseSolver = null
        animator = null
        lastAnimFrameNanos = 0L
    }

    /** 把已就绪的 renderable 加入场景（贴图异步上传完成后逐批出现），并为其开启接触阴影接收 */
    private fun populateScene(a: FilamentAsset) {
        val rcm = engine.renderableManager
        while (true) {
            val count = a.popRenderables(readyRenderables)
            if (count == 0) break
            for (i in 0 until count) {
                val ri = rcm.getInstance(readyRenderables[i])
                if (ri != 0) rcm.setScreenSpaceContactShadows(ri, true)
            }
            scene.addEntities(readyRenderables.copyOf(count))
            diagRenderablesAdded += count
        }
        // 个别渲染实例就绪较晚时补绑定（骨骼蒙皮 / morph 表情）
        if (rig != null && !rigBindComplete) bindRig(a)
        val lights = a.lightEntities
        if (lights.isNotEmpty()) scene.addEntities(lights)
    }

    // ===================================================================== 地面

    private fun buildGround() {
        val materialBuffer = readAssetBuffer(SHADOW_CATCHER_ASSET)
        groundMaterial = Material.Builder().payload(materialBuffer).build(engine)
        groundInstance = groundMaterial!!.createInstance()

        // 单位四边形（半边长 1，XZ 平面，+Y 朝上），TANGENTS 为绕 X 轴 -90° 旋转四元数使局部 +Z 对齐世界 +Y 法线，消除斜视角 Shadow Acne
        val positions = ByteBuffer.allocateDirect(4 * 3 * 4).order(ByteOrder.nativeOrder())
            .putFloat(-1f).putFloat(0f).putFloat(-1f)
            .putFloat(1f).putFloat(0f).putFloat(-1f)
            .putFloat(1f).putFloat(0f).putFloat(1f)
            .putFloat(-1f).putFloat(0f).putFloat(1f)
        positions.flip()
        val tangents = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder())
        val invSqrt2 = 0.70710678f
        repeat(4) { tangents.putFloat(-invSqrt2).putFloat(0f).putFloat(0f).putFloat(invSqrt2) }
        tangents.flip()
        val indices = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder())
            .putShort(0).putShort(2).putShort(1)
            .putShort(0).putShort(3).putShort(2)
        indices.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        groundVertexBuffer = vertexBuffer
        vertexBuffer.setBufferAt(engine, 0, positions)
        vertexBuffer.setBufferAt(engine, 1, tangents)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        groundIndexBuffer = indexBuffer
        indexBuffer.setBuffer(engine, indices)

        val groundMatInstance = groundInstance ?: return
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 0.001f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 6)
            .material(0, groundMatInstance)
            .culling(false)
            .receiveShadows(true)
            .castShadows(false)
            .build(engine, groundEntity)
        groundTransformInstance = transformManager.getInstance(groundEntity)
        scene.addEntity(groundEntity)
    }

    // ===================================================================== 渲染

    /** 渲染一帧。返回是否真正提交了帧（SwapChain 未就绪时返回 false）。 */
    fun render(frame: FigureFrameState): Boolean {
        val sc = swapChain ?: return false
        // 允许资源加载器完成已就绪的贴图，并回收不再引用的实体
        resourceLoader.asyncUpdateLoad()
        assetLoader.gc()
        asset?.let { populateScene(it) }

        updateCamera(frame)
        updateLights(frame)
        updateGround(frame)
        updateFigure(frame)
        updateAnimation(frame)

        if (!renderer.beginFrame(sc, frame.timeNanos)) {
            diagFramesSkipped++
            return false
        }
        renderer.render(view)
        renderer.endFrame()
        diagFramesSubmitted++
        logDiagnostics(frame.timeNanos)
        return true
    }

    private fun logDiagnostics(now: Long) {
        if (now - diagLastLogNanos < 1_000_000_000L) return
        diagLastLogNanos = now
        val progress = asset?.let { resourceLoader.asyncGetLoadProgress() } ?: -1f
        Log.d(
            TAG,
            "frames submitted=$diagFramesSubmitted skipped=$diagFramesSkipped renderables=$diagRenderablesAdded " +
                    "loadProgress=$progress viewport=${viewportWidth}x${viewportHeight} anchored=${asset != null} " +
                    "skinned=${skinnedRenderables.size} face=${faceRenderables.size} " +
                    "airborne=$currentAirborne pitch=$lastPitchDegrees roll=$lastRollDegrees " +
                    "foot=[${footWorld[0]}, ${footWorld[1]}, ${footWorld[2]}]"
        )
    }

    /**
     * 相机与落脚点解算。相机位于世界原点，按 IMU 俯仰 / 横滚朝向；手办落脚点用针孔模型从屏幕坐标反投影：
     * 在距离 d 处，屏幕归一化 (sx, sy) 对应相机系 (x, y, -d)，再经相机基向量变换到世界系。
     * 手办在世界系中始终竖直站立（Y 朝上），因此手机倾斜时产生真实的 3D 视差。
     */
    private fun updateCamera(f: FigureFrameState) {
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()
        camera.setProjection(CAMERA_VERTICAL_FOV_DEGREES, aspect, CAMERA_NEAR, CAMERA_FAR, Camera.Fov.VERTICAL)

        lastPitchDegrees = f.cameraPitchDegrees
        lastRollDegrees = f.cameraRollDegrees

        if (f.isWorldAnchored) {
            val fx = f.cameraForwardX.toDouble()
            val fy = f.cameraForwardY.toDouble()
            val fz = f.cameraForwardZ.toDouble()
            val ux = f.cameraUpX.toDouble()
            val uy = f.cameraUpY.toDouble()
            val uz = f.cameraUpZ.toDouble()

            camForward[0] = f.cameraForwardX
            camForward[1] = f.cameraForwardY
            camForward[2] = f.cameraForwardZ
            camUp[0] = f.cameraUpX
            camUp[1] = f.cameraUpY
            camUp[2] = f.cameraUpZ

            val rx = uy * fz - uz * fy
            val ry = uz * fx - ux * fz
            val rz = ux * fy - uy * fx
            camRight[0] = rx.toFloat()
            camRight[1] = ry.toFloat()
            camRight[2] = rz.toFloat()

            camera.lookAt(0.0, 0.0, 0.0, fx, fy, fz, ux, uy, uz)

            // 曝光：EV100 由环境照度决定；快门 1/60、ISO100，反推光圈 N = sqrt(2^EV · t)
            val ev = EV_BASE + EV_RANGE * f.envLuminance.toDouble().coerceIn(0.2, 1.35)
            val aperture = sqrt(2.0.pow(ev) * SHUTTER_SECONDS * (ISO / 100.0))
            camera.setExposure(aperture.toFloat(), SHUTTER_SECONDS.toFloat(), ISO.toFloat())

            // 落脚点固定在物理世界坐标系
            footWorld[0] = f.worldAnchorX
            footWorld[1] = f.worldAnchorY
            footWorld[2] = f.worldAnchorZ
        } else {
            // 俯仰跟随 IMU（低头看桌面时相机下俯），但横滚做「重力锁定」夹紧：
            // 与 2.5D 立绘路径的 gravityRoll 策略保持一致，避免用户侧握手机时整个世界（含手办）被 roll 转成横躺。
            // 限幅 18°：小角度保留真实侧倾动感，大角度（横握/平放）时手办仍然保持屏幕竖直可读。
            val pitch = Math.toRadians(f.cameraPitchDegrees.toDouble().coerceIn(0.0, 85.0))
            val roll = Math.toRadians(f.cameraRollDegrees.toDouble().coerceIn(-18.0, 18.0))
            // 平视：forward=(0,0,-1) up=(0,1,0) right=(1,0,0)；俯仰 p 后绕 X 轴向下转
            val fx = 0.0; val fy = -sin(pitch); val fz = -cos(pitch)
            val ux0 = 0.0; val uy0 = cos(pitch); val uz0 = -sin(pitch)
            val rx0 = 1.0; val ry0 = 0.0; val rz0 = 0.0
            // 横滚 r：up / right 绕 forward 旋转
            val cr = cos(roll); val sr = sin(roll)
            val ux = ux0 * cr + rx0 * sr; val uy = uy0 * cr + ry0 * sr; val uz = uz0 * cr + rz0 * sr
            val rx = rx0 * cr - ux0 * sr; val ry = ry0 * cr - uy0 * sr; val rz = rz0 * cr - uz0 * sr
            camForward[0] = fx.toFloat(); camForward[1] = fy.toFloat(); camForward[2] = fz.toFloat()
            camUp[0] = ux.toFloat(); camUp[1] = uy.toFloat(); camUp[2] = uz.toFloat()
            camRight[0] = rx.toFloat(); camRight[1] = ry.toFloat(); camRight[2] = rz.toFloat()
            camera.lookAt(0.0, 0.0, 0.0, fx, fy, fz, ux, uy, uz)

            // 曝光：EV100 由环境照度决定；快门 1/60、ISO100，反推光圈 N = sqrt(2^EV · t)
            val ev = EV_BASE + EV_RANGE * f.envLuminance.toDouble().coerceIn(0.2, 1.35)
            val aperture = sqrt(2.0.pow(ev) * SHUTTER_SECONDS * (ISO / 100.0))
            camera.setExposure(aperture.toFloat(), SHUTTER_SECONDS.toFloat(), ISO.toFloat())

            // 落脚点反投影
            val d = f.distanceMeters.toDouble().coerceIn(0.2, 2.0)
            val tanHalfFov = tan(Math.toRadians(CAMERA_VERTICAL_FOV_DEGREES) / 2.0)
            val ndcX = 2.0 * f.screenX - 1.0
            val ndcY = 1.0 - 2.0 * f.screenY
            val camX = tanHalfFov * aspect * ndcX * d
            val camY = tanHalfFov * ndcY * d
            // world = camX·right + camY·up + d·forward
            footWorld[0] = (camX * rx + camY * ux + d * fx).toFloat()
            footWorld[1] = (camX * ry + camY * uy + d * fy).toFloat()
            footWorld[2] = (camX * rz + camY * uz + d * fz).toFloat()
        }
    }

    private fun updateLights(f: FigureFrameState) {
        val lum = f.envLuminance.coerceIn(0.2f, 1.35f)
        val ev = EV_BASE + EV_RANGE * lum
        val lux = (LUX_PER_EV * 2.0.pow(ev)).toFloat()
        val temp = f.ambientColorTemp.coerceIn(-1f, 1f)

        // 方向光（台灯）：方向 = 环境主光源的左右方位 (相机水平右向) + 俯仰高度 + 从角色后上方打向相机
        val dirX = f.lightDirectionX.coerceIn(-1f, 1f)
        val down = (-f.lightDirectionY).coerceIn(0f, 1f) // 0 低角度掠射 .. 1 顶光
        val strength = f.lightDirectionalStrength.coerceIn(0f, 1f)
        // 「朝向相机」的水平向量 = forward 水平分量取反
        var hx = -camForward[0]; var hz = -camForward[2]
        var hl = sqrt(hx * hx + hz * hz)
        if (hl < 1e-5f) { hx = 0f; hz = 1f; hl = 1f }
        hx /= hl; hz /= hl
        var rhx = camRight[0]; var rhz = camRight[2]
        var rl = sqrt(rhx * rhx + rhz * rhz)
        if (rl < 1e-5f) { rhx = 1f; rhz = 0f; rl = 1f }
        rhx /= rl; rhz /= rl
        val back = 0.85f - 0.45f * down
        var dx = dirX * strength * rhx + back * hx
        var dy = -(1.05f + 1.10f * down)
        var dz = dirX * strength * rhz + back * hz
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= dl; dy /= dl; dz /= dl
        lightManager.setDirection(keyLightInstance, dx, dy, dz)
        lightManager.setIntensity(keyLightInstance, lux * KEY_LIGHT_SHARE * (0.7f + 0.3f * strength))
        lightManager.setColor(
            keyLightInstance,
            (1.0f + 0.06f * temp) * f.ambientTintRed,
            0.97f * f.ambientTintGreen,
            (0.93f - 0.06f * temp) * f.ambientTintBlue
        )

        // 点光源（闪光灯）：位置 = 相机；强度按「在落脚点距离处产生 E = lux·share」反推 candela（物理 1/r² 衰减由 Filament 处理），
        // 暗环境中闪光灯占比更高，复现「暗室里手机闪光打亮手办」的对比
        lightManager.setPosition(flashLightInstance, 0f, 0f, 0f)
        val d2 = (f.distanceMeters * f.distanceMeters).coerceAtLeast(0.04f)
        lightManager.setIntensityCandela(flashLightInstance, lux * FLASH_LIGHT_SHARE * (0.6f + 0.8f * (1f - lum)) * d2)
        lightManager.setColor(
            flashLightInstance,
            (0.95f - 0.03f * temp), 0.97f, (1.0f + 0.03f * temp)
        )

        // 环境光 (IBL)
        indirectLight?.setIntensity(lux * AMBIENT_SHARE)
    }

    private fun updateGround(f: FigureFrameState) {
        val lum = f.envLuminance.coerceIn(0.2f, 1.35f)
        val halfExtent = DEFAULT_FIGURE_HEIGHT_METERS * f.scale * GROUND_HALF_EXTENT_RATIO
        Matrix.setIdentityM(mGround, 0)
        Matrix.translateM(mGround, 0, footWorld[0], footWorld[1], footWorld[2])
        Matrix.scaleM(mGround, 0, halfExtent, 1f, halfExtent)
        if (groundTransformInstance != 0) transformManager.setTransform(groundTransformInstance, mGround)
        // 阴影最大不透明度：暗环境更实，悬空脱离支撑时平滑消散；手办出画时同步隐去
        val baseStrength = (0.56f + 0.14f * (1f - lum)) * f.surfaceSupportConfidence.coerceIn(0f, 1f) *
                (1f - currentAirborne * 0.7f)
        val strength = if (!f.isWorldAnchored || f.isInView) baseStrength else 0f
        groundInstance?.setParameter("strength", strength)
    }

    private fun updateFigure(f: FigureFrameState) {
        if (assetRootInstance == 0) return
        val k = if (f.anchored && (!f.isWorldAnchored || f.isInView)) assetScale * f.scale else 0f
        lastScale = f.scale
        lastScreenX = f.screenX
        lastStance = f.stance
        // M = T(foot) · Ry(yaw + 180°) · S(k) · T(-pivot)
        // 逐条 translate/rotate/scale 调用等价于 M = T1·R·S·T2（左乘右序），故必须先 T 再 R 再 S 最后 -pivot
        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, footWorld[0], footWorld[1], footWorld[2])
        // VRM/glTF 角色正面朝 +Z；Filament 相机在原点朝 -Z，故补 180° 面向用户
        Matrix.rotateM(mModel, 0, f.yawDegrees + 180f, 0f, 1f, 0f)
        Matrix.scaleM(mModel, 0, k, k, k)
        // 先把模型枢轴平移到原点（脚底中心），再做 T/R/S
        Matrix.translateM(mModel, 0, -assetPivot[0], -assetPivot[1], -assetPivot[2])
        transformManager.setTransform(assetRootInstance, mModel)
    }

    /**
     * 全身骨骼动画与蒙皮上传（每帧）：
     * 1. FigureAnimator 把体态 / 待机 / 情绪 / 动作 / 触摸反应 / 表情合成为逐骨骼角度；
     * 2. PoseSolver 做正向运动学，按 gltfio 同款公式计算蒙皮矩阵；
     * 3. 通过 setBonesAsMatrices 上传骨骼矩阵、setMorphWeights 上传表情/口型/眨眼权重。
     *
     * 时间统一使用 Choreographer 的帧时间（System.nanoTime 同源），动作起止时刻由 ViewModel 触发时记录，
     * 因此动作时长与帧率解耦，慢帧也不会把动作「抽快」。
     */
    private fun updateAnimation(f: FigureFrameState) {
        val solver = poseSolver ?: return
        val animator = animator ?: return

        val dt = if (lastAnimFrameNanos == 0L) 1f / 60f
        else ((f.timeNanos - lastAnimFrameNanos).coerceIn(0L, 200_000_000L) / 1e9f)
        lastAnimFrameNanos = f.timeNanos

        animationInput.dtSeconds = dt
        animationInput.timeSeconds = (f.timeNanos % 3_600_000_000_000L) / 1_000_000_000f
        animationInput.action = f.action
        animationInput.actionElapsed = elapsedSince(f.actionStartedAtNanos, f.timeNanos)
        animationInput.emotion = f.emotion
        animationInput.expression = f.expression
        animationInput.lipSync = f.lipSyncAmplitude
        animationInput.speaking = f.lipSyncAmplitude > 0.03f
        animationInput.stance = f.stance
        animationInput.gazeX = f.gazeX
        animationInput.gazeY = f.gazeY
        animationInput.inertialSway = f.inertialSwayAngle
        animationInput.touchReaction = f.touchReaction
        animationInput.touchElapsed = elapsedSince(f.touchReactionStartedAtNanos, f.timeNanos)
        animator.update(animationInput)
        currentAirborne = animator.airborne

        if (!f.anchored) return
        solver.solve(animator.angles, animator.hipsOffsetX, animator.hipsOffsetY, animator.hipsOffsetZ)

        val rcm = engine.renderableManager
        for (sr in skinnedRenderables) {
            solver.computeSkinMatrices(sr.mesh, sr.boneMatrices)
            rcm.setBonesAsMatrices(sr.rci, sr.boneMatrices)
        }
        for (fr in faceRenderables) {
            fr.faceRig.fillWeights(
                fr.weights,
                animator.faceExpression,
                animator.faceEmotion,
                animator.blinkAmount,
                animator.mouthOpen
            )
            rcm.setMorphWeights(fr.rci, fr.weights)
        }
    }

    /** 距离动作/触摸开始的秒数；未开始（<=0）返回 Float.MAX_VALUE */
    private fun elapsedSince(startNanos: Long, nowNanos: Long): Float {
        if (startNanos <= 0L || nowNanos < startNanos) return Float.MAX_VALUE
        return ((nowNanos - startNanos) / 1_000_000_000.0).toFloat()
    }
    // ===================================================================== 拾取

    /**
     * 请求对手办做 GPU 拾取：(x, y) 为 TextureView 像素坐标（左上原点）。结果异步回调，
     * 未命中手办（打到地面或背景）时回调 null。
     */
    fun requestPick(x: Int, y: Int, onResult: (TouchPart?) -> Unit) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            onResult(null)
            return
        }
        val px = x.coerceIn(0, viewportWidth - 1)
        // Filament 的 pick 以视口左下角为原点，Compose 触摸坐标以左上角为原点，故翻转 Y；
        // classifyPart 仍接收原始左上原点坐标，保持部位判定的屏幕语义一致
        val pickY = (viewportHeight - 1 - y).coerceIn(0, viewportHeight - 1)
        view.pick(px, pickY, pickHandler) { result ->
            val entity = result.renderable
            if (entity == 0 || entity == groundEntity || asset == null) {
                onResult(null)
            } else {
                onResult(classifyPart(entity, x, y))
            }
        }
    }

    /** 按命中网格与屏幕位置推断触碰部位：结合站姿与坐姿体态自适应平滑插值判断摸头、戳脸、牵手与抱抱 */
    private fun classifyPart(entity: Int, px: Int, py: Int): TouchPart {
        val sitK = animator?.currentSitBlend ?: (if (lastStance == com.persona.ar.character.skeleton.SkeletonStance.SITTING) 1f else 0f)
        val effectiveHeightRatio = 1.0f - 0.22f * sitK
        val headRatio = 0.88f - 0.20f * sitK

        // 手办顶部与头部中心投影到屏幕
        val topY = projectY(footWorld[0], footWorld[1] + DEFAULT_FIGURE_HEIGHT_METERS * effectiveHeightRatio * lastScale, footWorld[2])
        val headCenterY = projectY(footWorld[0], footWorld[1] + DEFAULT_FIGURE_HEIGHT_METERS * headRatio * lastScale, footWorld[2])
        val footScreenY = projectY(footWorld[0], footWorld[1], footWorld[2])
        if (entity in headEntities) {
            return if (py > (headCenterY + topY) / 2f + (footScreenY - topY) * 0.04f) TouchPart.CHEEK else TouchPart.HEAD
        }
        val height = (footScreenY - topY).coerceAtLeast(1f)
        val relativeY = (py - topY) / height
        val centerX = viewportWidth * lastScreenX
        val relativeX = kotlin.math.abs(px - centerX) / height
        val handYMin = 0.42f - 0.10f * sitK
        val handYMax = 0.70f - 0.08f * sitK
        val handXMin = 0.16f - 0.04f * sitK
        return if (relativeY in handYMin..handYMax && relativeX > handXMin) TouchPart.HAND else TouchPart.BODY
    }

    private var lastScale = 1f
    private var lastScreenX = 0.5f
    private var lastStance = com.persona.ar.character.skeleton.SkeletonStance.STANDING

    // 诊断计数（每秒节流输出一次，便于现场排查「模型不显示」类问题）
    private var diagFramesSubmitted = 0
    private var diagFramesSkipped = 0
    private var diagRenderablesAdded = 0
    private var diagLastLogNanos = 0L

    private fun projectY(x: Float, y: Float, z: Float): Float {
        camera.getProjectionMatrix(projMatrix)
        camera.getViewMatrix(viewMatrix)
        val vx = (viewMatrix[0] * x + viewMatrix[4] * y + viewMatrix[8] * z + viewMatrix[12]).toFloat()
        val vy = (viewMatrix[1] * x + viewMatrix[5] * y + viewMatrix[9] * z + viewMatrix[13]).toFloat()
        val vz = (viewMatrix[2] * x + viewMatrix[6] * y + viewMatrix[10] * z + viewMatrix[14]).toFloat()
        val vw = (viewMatrix[3] * x + viewMatrix[7] * y + viewMatrix[11] * z + viewMatrix[15]).toFloat()
        val cx = projMatrix[0] * vx + projMatrix[4] * vy + projMatrix[8] * vz + projMatrix[12] * vw
        val cy = projMatrix[1] * vx + projMatrix[5] * vy + projMatrix[9] * vz + projMatrix[13] * vw
        val cw = projMatrix[3] * vx + projMatrix[7] * vy + projMatrix[11] * vz + projMatrix[15] * vw
        val ndcY = cy / cw
        return ((1.0 - ndcY) / 2.0 * viewportHeight).toFloat()
    }

    // ===================================================================== 销毁

    fun destroy() {
        destroyAsset()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoader.destroy()

        scene.removeEntities(intArrayOf(keyLightEntity, flashLightEntity, groundEntity))
        engine.destroyEntity(keyLightEntity)
        engine.destroyEntity(flashLightEntity)
        engine.destroyEntity(groundEntity)
        groundInstance?.let { engine.destroyMaterialInstance(it) }
        groundMaterial?.let { engine.destroyMaterial(it) }
        groundVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        groundIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        indirectLight?.let { engine.destroyIndirectLight(it) }
        colorGrading?.let {
            engine.destroyColorGrading(it)
            colorGrading = null
        }
        view.colorGrading = null

        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyEntity(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }
}
