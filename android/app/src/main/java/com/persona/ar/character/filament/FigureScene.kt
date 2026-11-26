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
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
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
import com.persona.ar.character.animation.HumanoidBone
import com.persona.ar.character.animation.PoseSolver
import com.persona.ar.data.model.TouchPart
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 单个 AR 视图的 Filament 3D 场景：
 * - 6DoF 虚拟相机与真实 CameraX 视场角对齐；
 * - 世界空间锁定主方向光 + 柔和近距补光 + 实时更新 2-Band SH & 动态环境反射 Cubemap IBL；
 * - 扩大无切边的 Shadow Catcher 接触阴影地面；
 * - 3D 骨骼实时投影精准部位拾取（适应 360° 任意角度与站/坐动作）。
 */
class FigureScene(
    private val context: Context,
    private val engine: Engine = FilamentEngineHolder.engine()
) {
    companion object {
        private const val TAG = "FigureScene"

        const val DEFAULT_VERTICAL_FOV_DEGREES = 62.0
        private const val CAMERA_NEAR = 0.02
        private const val CAMERA_FAR = 10.0

        const val DEFAULT_FIGURE_HEIGHT_METERS = 0.22f
        /** 接触阴影地面半边长系数：足够宽广以防台灯斜射影子被四边形矩形截断 */
        private const val GROUND_HALF_EXTENT_RATIO = 2.8f

        private const val SHADOW_CATCHER_ASSET = "materials/shadow_catcher.filamat"
        private const val SHADOW_MAP_SIZE = 2048
        private const val SHADOW_FAR_METERS = 3.5f

        // 曝光与自然光影分配：削弱相机正面直射闪光，保留手办面部与衣褶立体层次
        private const val EV_BASE = 7.5
        private const val EV_RANGE = 2.4
        private const val SHUTTER_SECONDS = 1.0 / 60.0
        private const val ISO = 100.0
        private const val LUX_PER_EV = 3.77f
        private const val KEY_LIGHT_SHARE = 0.56f
        private const val FLASH_LIGHT_SHARE = 0.08f
        private const val AMBIENT_SHARE = 0.44f

        private const val ENV_CUBE_SIZE = 16
        private const val ENV_CUBE_LEVELS = 5
        private const val IBL_UPDATE_INTERVAL_NANOS = 220_000_000L
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
    private val assetPivot = FloatArray(3)
    private val headEntities = HashSet<Int>()
    private val readyRenderables = IntArray(64)

    // ---- 骨骼动画 / 蒙皮 / 表情 ----
    private var rig: GltfRig? = null
    private var poseSolver: PoseSolver? = null
    private var animator: FigureAnimator? = null
    private val animationInput = AnimationInput()
    private val skinnedRenderables = ArrayList<SkinnedRenderable>()
    private val faceRenderables = ArrayList<FaceRenderable>()
    private val boundMeshEntities = HashSet<Int>()
    private val boundFaceEntities = HashSet<Int>()
    private var rigBindComplete = false
    private var lastAnimFrameNanos = 0L

    private class SkinnedRenderable(
        val rci: Int,
        val mesh: GltfRig.MeshNode,
        val boneMatrices: FloatArray
    )

    private class FaceRenderable(
        val rci: Int,
        val faceRig: FaceRig,
        val weights: FloatArray
    )

    class ModelAsset(val buffer: ByteBuffer, val rig: GltfRig?)

    // ---- 灯光与动态 IBL ----
    private val keyLightEntity = engine.entityManager.create()
    private val flashLightEntity = engine.entityManager.create()
    private val keyLightInstance: Int
    private val flashLightInstance: Int
    private var indirectLight: IndirectLight? = null
    private var envCubemapTexture: Texture? = null
    private var envCubemapFailed = false
    private val lastEnvSnapshot = FloatArray(15)
    private var lastIblUpdateNanos = 0L
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
    private val tmpJointLocal = FloatArray(3)
    private val tmpJointWorld = FloatArray(3)
    private val pickHandler = Handler(Looper.getMainLooper())

    // 实时缓存各关键骨骼屏幕像素坐标，供精准触碰与头顶特效定位
    private val jointScreenX = HashMap<HumanoidBone, Float>(12)
    private val jointScreenY = HashMap<HumanoidBone, Float>(12)
    @Volatile var latestHeadScreenX: Float = 0.5f; private set
    @Volatile var latestHeadScreenY: Float = 0.35f; private set

    val surfaceCallback: UiHelper.RendererCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
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
        view.setAntiAliasing(View.AntiAliasing.NONE)
        view.dithering = View.Dithering.TEMPORAL
        view.setPostProcessingEnabled(true)
        view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply {
            enabled = true
            radius = 0.16f
            power = 1.6f
            intensity = 0.85f
            quality = View.QualityLevel.MEDIUM
        })
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = 0.10f
            resolution = 360
            quality = View.QualityLevel.LOW
            highlight = 1000.0f
        }

        // 自然写实色彩分级：轻微收敛过度二次元艳丽感，使手办高光与暗部融入手机实拍动态范围
        colorGrading = ColorGrading.Builder()
            .quality(ColorGrading.QualityLevel.HIGH)
            .toneMapper(ACESToneMapper())
            .contrast(1.02f)
            .vibrance(1.02f)
            .saturation(0.96f)
            .build(engine)
        view.colorGrading = colorGrading

        // 主方向光：世界锁定台灯/自然光，投射细腻柔和阴影
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.92f)
            .intensity(1_000f)
            .direction(0.25f, -0.85f, 0.45f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = SHADOW_MAP_SIZE
                shadowFar = SHADOW_FAR_METERS
                shadowNearHint = 0.08f
                shadowFarHint = 1.8f
                maxShadowDistance = SHADOW_FAR_METERS + 0.5f
                screenSpaceContactShadows = false
                shadowBulbRadius = 0.055f
                constantBias = 0.001f
                normalBias = 1.0f
                polygonOffsetConstant = 0.5f
                polygonOffsetSlope = 1.5f
            })
            .build(engine, keyLightEntity)
        keyLightInstance = lightManager.getInstance(keyLightEntity)

        // 极柔和相机近距补光（模拟屏幕与近距环境漫散光，不冲平立体明暗）
        LightManager.Builder(LightManager.Type.POINT)
            .color(0.96f, 0.98f, 1.0f)
            .intensityCandela(200f)
            .falloff(3.0f)
            .position(0f, 0f, 0f)
            .castShadows(false)
            .build(engine, flashLightEntity)
        flashLightInstance = lightManager.getInstance(flashLightEntity)
        scene.addEntity(keyLightEntity)
        scene.addEntity(flashLightEntity)

        // 初始 2-Band IBL 与环境反射贴图
        envCubemapTexture = runCatching { buildEnvCubemapTexture() }.getOrNull()
        val initialSh = FigureLighting.compute2BandSH()
        indirectLight = buildIndirectLight(initialSh, 1_000f)
        scene.indirectLight = indirectLight

        buildGround()
    }

    private fun buildEnvCubemapTexture(): Texture {
        return Texture.Builder()
            .width(ENV_CUBE_SIZE)
            .height(ENV_CUBE_SIZE)
            .levels(ENV_CUBE_LEVELS)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT)
            .build(engine)
    }

    private fun buildIndirectLight(sh2Band: FloatArray, intensity: Float): IndirectLight {
        val builder = IndirectLight.Builder()
            .irradiance(2, sh2Band)
            .intensity(intensity)
        val tex = envCubemapTexture
        if (tex != null && !envCubemapFailed) {
            runCatching { builder.reflections(tex) }
                .onFailure { envCubemapFailed = true }
        }
        return builder.build(engine)
    }

    // ===================================================================== 资产

    fun readAssetBuffer(assetPath: String): ByteBuffer {
        val stream = context.assets.open(assetPath)
        val bytes = stream.use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        return buffer
    }

    fun loadModelAsset(assetPath: String): ModelAsset {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val parsedRig = GltfRigParser.parse(bytes)
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        return ModelAsset(buffer, parsedRig)
    }

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

        val box = newAsset.boundingBox
        val center = box.center
        val half = box.halfExtent
        assetPivot[0] = center[0]
        assetPivot[1] = center[1] - half[1]
        assetPivot[2] = center[2]
        assetScale = DEFAULT_FIGURE_HEIGHT_METERS / (2f * half[1]).coerceAtLeast(1e-4f)

        headEntities.clear()
        headEntities.addAll(newAsset.getEntitiesByPrefix("Face").toList())
        headEntities.addAll(newAsset.getEntitiesByPrefix("Hair").toList())
        boundMeshEntities.clear()
        boundFaceEntities.clear()
        skinnedRenderables.clear()
        faceRenderables.clear()
        rigBindComplete = false
        bindRig(newAsset)
    }

    private fun bindRig(a: FilamentAsset) {
        val rig = rig ?: return
        val rcm = engine.renderableManager
        for (mesh in rig.meshNodes) {
            var entity = a.getFirstEntityByName(mesh.name)
            if (entity == 0) {
                entity = a.getEntitiesByPrefix(mesh.name).toList().firstOrNull { it != 0 } ?: 0
            }
            if (entity == 0) continue
            if (entity in boundMeshEntities) continue
            val rci = rcm.getInstance(entity)
            if (rci == 0) continue
            boundMeshEntities.add(entity)
            val joints = rig.skins[mesh.skinIndex].joints.size
            skinnedRenderables.add(SkinnedRenderable(rci, mesh, FloatArray(joints * 16)))
        }
        for (entity in a.getRenderableEntities()) {
            if (entity in boundFaceEntities) continue
            val names = runCatching { a.getMorphTargetNames(entity) }.getOrNull() ?: continue
            if (names.isEmpty() || names.none { it.contains("Fcl_") }) {
                boundFaceEntities.add(entity)
                continue
            }
            val rci = rcm.getInstance(entity)
            if (rci == 0) continue
            boundFaceEntities.add(entity)
            faceRenderables.add(FaceRenderable(rci, FaceRig(names), FloatArray(names.size)))
        }
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
        }
        if (rig != null && !rigBindComplete) bindRig(a)
        val lights = a.lightEntities
        if (lights.isNotEmpty()) scene.addEntities(lights)
    }

    // ===================================================================== 地面

    private fun buildGround() {
        val materialBuffer = readAssetBuffer(SHADOW_CATCHER_ASSET)
        groundMaterial = Material.Builder().payload(materialBuffer).build(engine)
        groundInstance = groundMaterial!!.createInstance()

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

    fun render(frame: FigureFrameState): Boolean {
        val sc = swapChain ?: return false
        resourceLoader.asyncUpdateLoad()
        assetLoader.gc()
        asset?.let { populateScene(it) }

        updateCamera(frame)
        updateLights(frame)
        updateGround(frame)
        updateFigure(frame)
        val dt = updateAnimation(frame)
        updateJointScreenProjections()
        lastAnimDt = dt

        if (!renderer.beginFrame(sc, frame.timeNanos)) {
            return false
        }
        renderer.render(view)
        renderer.endFrame()
        return true
    }

    private var lastAnimDt = 1f / 60f

    private fun updateCamera(f: FigureFrameState) {
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()
        val vFov = f.effectiveVerticalFovDegrees.toDouble().coerceIn(35.0, 88.0)
        camera.setProjection(vFov, aspect, CAMERA_NEAR, CAMERA_FAR, Camera.Fov.VERTICAL)

        if (f.isWorldAnchored) {
            camRight[0] = f.cameraRightX; camRight[1] = f.cameraRightY; camRight[2] = f.cameraRightZ
            camUp[0] = f.cameraUpX; camUp[1] = f.cameraUpY; camUp[2] = f.cameraUpZ
            camForward[0] = f.cameraForwardX; camForward[1] = f.cameraForwardY; camForward[2] = f.cameraForwardZ

            val cx = f.cameraPosX.toDouble()
            val cy = f.cameraPosY.toDouble()
            val cz = f.cameraPosZ.toDouble()
            camera.lookAt(
                cx, cy, cz,
                cx + camForward[0], cy + camForward[1], cz + camForward[2],
                camUp[0].toDouble(), camUp[1].toDouble(), camUp[2].toDouble()
            )

            footWorld[0] = f.worldAnchorX
            footWorld[1] = f.worldAnchorY
            footWorld[2] = f.worldAnchorZ
        } else {
            val pitch = Math.toRadians(f.cameraPitchDegrees.toDouble().coerceIn(0.0, 85.0))
            val roll = Math.toRadians(f.cameraRollDegrees.toDouble().coerceIn(-18.0, 18.0))
            val fx = 0.0; val fy = -sin(pitch); val fz = -cos(pitch)
            val ux0 = 0.0; val uy0 = cos(pitch); val uz0 = -sin(pitch)
            val rx0 = 1.0; val ry0 = 0.0; val rz0 = 0.0
            val cr = cos(roll); val sr = sin(roll)
            val ux = ux0 * cr + rx0 * sr; val uy = uy0 * cr + ry0 * sr; val uz = uz0 * cr + rz0 * sr
            val rx = rx0 * cr - ux0 * sr; val ry = ry0 * cr - uy0 * sr; val rz = rz0 * cr - uz0 * sr
            camForward[0] = fx.toFloat(); camForward[1] = fy.toFloat(); camForward[2] = fz.toFloat()
            camUp[0] = ux.toFloat(); camUp[1] = uy.toFloat(); camUp[2] = uz.toFloat()
            camRight[0] = rx.toFloat(); camRight[1] = ry.toFloat(); camRight[2] = rz.toFloat()
            camera.lookAt(0.0, 0.0, 0.0, fx, fy, fz, ux, uy, uz)

            val d = f.distanceMeters.toDouble().coerceIn(0.2, 2.0)
            val tanHalfFov = tan(Math.toRadians(vFov) / 2.0)
            val ndcX = 2.0 * f.screenX - 1.0
            val ndcY = 1.0 - 2.0 * f.screenY
            val camX = tanHalfFov * aspect * ndcX * d
            val camY = tanHalfFov * ndcY * d
            footWorld[0] = (camX * rx + camY * ux + d * fx).toFloat()
            footWorld[1] = (camX * ry + camY * uy + d * fy).toFloat()
            footWorld[2] = (camX * rz + camY * uz + d * fz).toFloat()
        }

        val ev = EV_BASE + EV_RANGE * f.envLuminance.toDouble().coerceIn(0.18, 1.35)
        val aperture = sqrt(2.0.pow(ev) * SHUTTER_SECONDS * (ISO / 100.0))
        camera.setExposure(aperture.toFloat(), SHUTTER_SECONDS.toFloat(), ISO.toFloat())
    }

    private fun updateLights(f: FigureFrameState) {
        val lum = f.envLuminance.coerceIn(0.18f, 1.35f)
        val ev = EV_BASE + EV_RANGE * lum
        val lux = (LUX_PER_EV * 2.0.pow(ev)).toFloat()
        val temp = f.ambientColorTemp.coerceIn(-1f, 1f)
        val strength = f.lightDirectionalStrength.coerceIn(0f, 1f)

        // 1. 世界空间锁定主方向光：绕模型走动时影子稳固留在现实桌面方向
        if (f.hasWorldLightDir) {
            lightManager.setDirection(keyLightInstance, f.worldLightDirX, f.worldLightDirY, f.worldLightDirZ)
        } else {
            val dirX = f.lightDirectionX.coerceIn(-1f, 1f)
            val down = (-f.lightDirectionY).coerceIn(0f, 1f)
            var hx = -camForward[0]; var hz = -camForward[2]
            val hl = sqrt(hx * hx + hz * hz).coerceAtLeast(1e-4f)
            hx /= hl; hz /= hl
            var rhx = camRight[0]; var rhz = camRight[2]
            val rl = sqrt(rhx * rhx + rhz * rhz).coerceAtLeast(1e-4f)
            rhx /= rl; rhz /= rl
            val back = 0.85f - 0.45f * down
            var dx = dirX * strength * rhx + back * hx
            var dy = -(1.05f + 1.10f * down)
            var dz = dirX * strength * rhz + back * hz
            val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            dx /= dl; dy /= dl; dz /= dl
            lightManager.setDirection(keyLightInstance, dx, dy, dz)
        }
        lightManager.setIntensity(keyLightInstance, lux * KEY_LIGHT_SHARE * (0.68f + 0.32f * strength))
        lightManager.setColor(
            keyLightInstance,
            ((1.0f + 0.08f * temp) * f.ambientTintRed).coerceIn(0.5f, 1.4f),
            (0.98f * f.ambientTintGreen).coerceIn(0.5f, 1.4f),
            ((0.94f - 0.08f * temp) * f.ambientTintBlue).coerceIn(0.5f, 1.4f)
        )

        // 2. 极柔和近距补光（位于真实 6DoF 相机处，绝不破坏立体暗部轮廓）
        lightManager.setPosition(flashLightInstance, f.cameraPosX, f.cameraPosY, f.cameraPosZ)
        val d2 = (f.distanceMeters * f.distanceMeters).coerceAtLeast(0.05f)
        lightManager.setIntensityCandela(flashLightInstance, lux * FLASH_LIGHT_SHARE * (0.6f + 0.6f * (1f - lum)) * d2)
        lightManager.setColor(
            flashLightInstance,
            (0.96f - 0.03f * temp) * f.ambientTintRed,
            0.98f * f.ambientTintGreen,
            (1.0f + 0.03f * temp) * f.ambientTintBlue
        )

        // 3. 实时动态 IBL：将 6 方向环境光色（含桌面局部反射）更新至 2-Band 球谐与反射 Cubemap
        val envIntensity = lux * AMBIENT_SHARE
        val currentSnapshot = floatArrayOf(
            f.ambientColorTemp,
            f.ambientTintRed, f.ambientTintGreen, f.ambientTintBlue,
            f.envTopRed, f.envTopGreen, f.envTopBlue,
            (f.envBottomRed * 0.5f + f.localSurfaceTintRed * 0.5f),
            (f.envBottomGreen * 0.5f + f.localSurfaceTintGreen * 0.5f),
            (f.envBottomBlue * 0.5f + f.localSurfaceTintBlue * 0.5f),
            f.envLeftRed, f.envLeftGreen, f.envLeftBlue,
            f.envRightRed, f.envRightGreen
        )
        if ((f.timeNanos - lastIblUpdateNanos) > IBL_UPDATE_INTERVAL_NANOS &&
            FigureLighting.hasSignificantShift(currentSnapshot, lastEnvSnapshot, 0.035f)
        ) {
            lastIblUpdateNanos = f.timeNanos
            System.arraycopy(currentSnapshot, 0, lastEnvSnapshot, 0, currentSnapshot.size)
            refreshDynamicIbl(f, envIntensity)
        } else {
            indirectLight?.setIntensity(envIntensity)
        }
    }

    private fun refreshDynamicIbl(f: FigureFrameState, intensity: Float) {
        val botR = f.envBottomRed * 0.45f + f.localSurfaceTintRed * 0.55f
        val botG = f.envBottomGreen * 0.45f + f.localSurfaceTintGreen * 0.55f
        val botB = f.envBottomBlue * 0.45f + f.localSurfaceTintBlue * 0.55f

        val tex = envCubemapTexture
        if (tex != null && !envCubemapFailed) {
            runCatching {
                val topRgb = floatArrayOf(f.envTopRed, f.envTopGreen, f.envTopBlue)
                val botRgb = floatArrayOf(botR, botG, botB)
                val leftRgb = floatArrayOf(f.envLeftRed, f.envLeftGreen, f.envLeftBlue)
                val rightRgb = floatArrayOf(f.envRightRed, f.envRightGreen, f.envRightBlue)
                val globalRgb = floatArrayOf(f.ambientTintRed, f.ambientTintGreen, f.ambientTintBlue)
                for (lvl in 0 until ENV_CUBE_LEVELS) {
                    val sz = ENV_CUBE_SIZE shr lvl
                    val faceBytes = ByteArray(sz * sz * 4)
                    for (face in 0 until 6) {
                        FigureLighting.fillEnvironmentCubemapFace(
                            face, sz, lvl, topRgb, botRgb, leftRgb, rightRgb, globalRgb, faceBytes
                        )
                        val buf = ByteBuffer.allocateDirect(faceBytes.size).order(ByteOrder.nativeOrder())
                        buf.put(faceBytes).flip()
                        val desc = Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE)
                        tex.setImage(engine, lvl, 0, 0, face, sz, sz, 1, desc)
                    }
                }
            }.onFailure {
                envCubemapFailed = true
                Log.w(TAG, "Dynamic cubemap update failed, fallback to SH-only IBL: ${it.message}")
            }
        }

        val sh = FigureLighting.compute2BandSHFromProbe(
            ambientColorTemp = f.ambientColorTemp,
            tintRed = f.ambientTintRed,
            tintGreen = f.ambientTintGreen,
            tintBlue = f.ambientTintBlue,
            topR = f.envTopRed, topG = f.envTopGreen, topB = f.envTopBlue,
            bottomR = botR, bottomG = botG, bottomB = botB,
            leftR = f.envLeftRed, leftG = f.envLeftGreen, leftB = f.envLeftBlue,
            rightR = f.envRightRed, rightG = f.envRightGreen, rightB = f.envRightBlue
        )
        val newIbl = buildIndirectLight(sh, intensity)
        val oldIbl = indirectLight
        indirectLight = newIbl
        scene.indirectLight = newIbl
        oldIbl?.let { engine.destroyIndirectLight(it) }
    }

    private fun updateGround(f: FigureFrameState) {
        val lum = f.envLuminance.coerceIn(0.18f, 1.35f)
        val halfExtent = DEFAULT_FIGURE_HEIGHT_METERS * f.scale * GROUND_HALF_EXTENT_RATIO
        Matrix.setIdentityM(mGround, 0)
        Matrix.translateM(mGround, 0, footWorld[0], footWorld[1], footWorld[2])
        Matrix.scaleM(mGround, 0, halfExtent, 1f, halfExtent)
        if (groundTransformInstance != 0) transformManager.setTransform(groundTransformInstance, mGround)
        val baseStrength = (0.58f + 0.16f * (1f - lum)) * (1f - currentAirborne * 0.7f)
        val strength = if (f.anchored && (!f.isWorldAnchored || f.isInView)) baseStrength else 0f
        groundInstance?.setParameter("strength", strength)
    }

    private fun updateFigure(f: FigureFrameState) {
        if (assetRootInstance == 0) return
        val k = if (f.anchored && (!f.isWorldAnchored || f.isInView)) assetScale * f.scale else 0f
        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, footWorld[0], footWorld[1], footWorld[2])
        Matrix.rotateM(mModel, 0, f.yawDegrees + 180f, 0f, 1f, 0f)
        Matrix.scaleM(mModel, 0, k, k, k)
        Matrix.translateM(mModel, 0, -assetPivot[0], -assetPivot[1], -assetPivot[2])
        transformManager.setTransform(assetRootInstance, mModel)
    }

    private var currentAirborne = 0f

    private fun updateAnimation(f: FigureFrameState): Float {
        val solver = poseSolver ?: return 1f / 60f
        val animator = animator ?: return 1f / 60f

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

        if (!f.anchored) return dt
        solver.solve(animator.angles, animator.hipsOffsetX, animator.hipsOffsetY, animator.hipsOffsetZ)

        val rcm = engine.renderableManager
        for (sr in skinnedRenderables) {
            solver.computeSkinMatrices(sr.mesh, sr.boneMatrices)
            rcm.setBonesAsMatrices(sr.rci, sr.boneMatrices)
        }
        for (fr in faceRenderables) {
            fr.faceRig.fillWeightsSmooth(
                fr.weights,
                animator.faceExpression,
                animator.faceEmotion,
                animator.blinkAmount,
                animator.mouthOpen,
                dt
            )
            rcm.setMorphWeights(fr.rci, fr.weights)
        }
        return dt
    }

    private fun updateJointScreenProjections() {
        val solver = poseSolver ?: return
        val r = rig ?: return
        camera.getProjectionMatrix(projMatrix)
        camera.getViewMatrix(viewMatrix)

        val trackedBones = arrayOf(
            HumanoidBone.HEAD, HumanoidBone.NECK, HumanoidBone.CHEST,
            HumanoidBone.HIPS, HumanoidBone.HAND_L, HumanoidBone.HAND_R,
            HumanoidBone.LOWER_ARM_L, HumanoidBone.LOWER_ARM_R
        )
        for (bone in trackedBones) {
            val node = r.humanoid[bone] ?: continue
            solver.worldPosition(node, tmpJointLocal)
            // local -> world via mModel (列主序)
            val lx = tmpJointLocal[0]; val ly = tmpJointLocal[1]; val lz = tmpJointLocal[2]
            tmpJointWorld[0] = mModel[0] * lx + mModel[4] * ly + mModel[8] * lz + mModel[12]
            tmpJointWorld[1] = mModel[1] * lx + mModel[5] * ly + mModel[9] * lz + mModel[13]
            tmpJointWorld[2] = mModel[2] * lx + mModel[6] * ly + mModel[10] * lz + mModel[14]
            val sx = projectScreenX(tmpJointWorld[0], tmpJointWorld[1], tmpJointWorld[2])
            val sy = projectScreenY(tmpJointWorld[0], tmpJointWorld[1], tmpJointWorld[2])
            jointScreenX[bone] = sx
            jointScreenY[bone] = sy
        }

        val hx = jointScreenX[HumanoidBone.HEAD]
        val hy = jointScreenY[HumanoidBone.HEAD]
        if (hx != null && hy != null) {
            latestHeadScreenX = (hx / viewportWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
            latestHeadScreenY = (hy / viewportHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    private fun elapsedSince(startNanos: Long, nowNanos: Long): Float {
        if (startNanos <= 0L || nowNanos < startNanos) return Float.MAX_VALUE
        return ((nowNanos - startNanos) / 1_000_000_000.0).toFloat()
    }

    // ===================================================================== 精准 3D 骨骼拾取

    fun requestPick(x: Int, y: Int, onResult: (TouchPart?) -> Unit) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            onResult(null)
            return
        }
        val px = x.coerceIn(0, viewportWidth - 1)
        val pickY = (viewportHeight - 1 - y).coerceIn(0, viewportHeight - 1)
        view.pick(px, pickY, pickHandler) { result ->
            val entity = result.renderable
            if (entity == 0 || entity == groundEntity || asset == null) {
                onResult(null)
            } else {
                onResult(classifyPart(entity, x.toFloat(), y.toFloat()))
            }
        }
    }

    /**
     * 基于实时 3D 骨骼关节点投影 + 命中实体类型精准判别触碰部位：
     * 无论手机绕到正面、侧面、背面，或角色在做挥手/坐姿等动作，均指哪点哪。
     */
    private fun classifyPart(entity: Int, px: Float, py: Float): TouchPart {
        val headX = jointScreenX[HumanoidBone.HEAD]
        val headY = jointScreenY[HumanoidBone.HEAD]
        val hipsY = jointScreenY[HumanoidBone.HIPS]
        val handLX = jointScreenX[HumanoidBone.HAND_L]
        val handLY = jointScreenY[HumanoidBone.HAND_L]
        val handRX = jointScreenX[HumanoidBone.HAND_R]
        val handRY = jointScreenY[HumanoidBone.HAND_R]

        if (headX != null && headY != null && hipsY != null) {
            val torsoSpan = abs(hipsY - headY).coerceAtLeast(45f)
            val handRadius = torsoSpan * 0.34f
            val distHandL = if (handLX != null && handLY != null) hypot(px - handLX, py - handLY) else Float.MAX_VALUE
            val distHandR = if (handRX != null && handRY != null) hypot(px - handRX, py - handRY) else Float.MAX_VALUE

            if (entity !in headEntities && minOf(distHandL, distHandR) <= handRadius) {
                return TouchPart.HAND
            }
            if (entity in headEntities || hypot(px - headX, py - headY) <= torsoSpan * 0.42f) {
                // HEAD 关节大约在颈颅交界中心，上方为摸头 (HEAD)，偏下脸颊处为戳脸 (CHEEK)
                return if (py > headY - torsoSpan * 0.08f) TouchPart.CHEEK else TouchPart.HEAD
            }
            return TouchPart.BODY
        }

        return if (entity in headEntities) TouchPart.HEAD else TouchPart.BODY
    }

    private fun projectScreenX(x: Float, y: Float, z: Float): Float {
        val vx = (viewMatrix[0] * x + viewMatrix[4] * y + viewMatrix[8] * z + viewMatrix[12]).toFloat()
        val vy = (viewMatrix[1] * x + viewMatrix[5] * y + viewMatrix[9] * z + viewMatrix[13]).toFloat()
        val vz = (viewMatrix[2] * x + viewMatrix[6] * y + viewMatrix[10] * z + viewMatrix[14]).toFloat()
        val vw = (viewMatrix[3] * x + viewMatrix[7] * y + viewMatrix[11] * z + viewMatrix[15]).toFloat()
        val cx = projMatrix[0] * vx + projMatrix[4] * vy + projMatrix[8] * vz + projMatrix[12] * vw
        val cw = projMatrix[3] * vx + projMatrix[7] * vy + projMatrix[11] * vz + projMatrix[15] * vw
        val ndcX = cx / cw
        return ((ndcX + 1.0) / 2.0 * viewportWidth).toFloat()
    }

    private fun projectScreenY(x: Float, y: Float, z: Float): Float {
        val vx = (viewMatrix[0] * x + viewMatrix[4] * y + viewMatrix[8] * z + viewMatrix[12]).toFloat()
        val vy = (viewMatrix[1] * x + viewMatrix[5] * y + viewMatrix[9] * z + viewMatrix[13]).toFloat()
        val vz = (viewMatrix[2] * x + viewMatrix[6] * y + viewMatrix[10] * z + viewMatrix[14]).toFloat()
        val vw = (viewMatrix[3] * x + viewMatrix[7] * y + viewMatrix[11] * z + viewMatrix[15]).toFloat()
        val cy = projMatrix[1] * vx + projMatrix[5] * vy + projMatrix[9] * vz + projMatrix[13] * vw
        val cw = projMatrix[3] * vx + projMatrix[7] * vy + projMatrix[11] * vz + projMatrix[15] * vw
        val ndcY = cy / cw
        return ((1.0 - ndcY) / 2.0 * viewportHeight).toFloat()
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

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
        envCubemapTexture?.let { engine.destroyTexture(it) }
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
