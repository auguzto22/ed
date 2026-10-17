package com.termex.replay15.editor.scene3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log
import com.google.android.filament.ACESToneMapper
import com.google.android.filament.AgxToneMapper
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.FilmicToneMapper
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.PBRNeutralToneMapper
import com.google.android.filament.PixelBufferDescriptor
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.IBLPrefilterContext
import com.google.android.filament.utils.Utils
import com.termex.replay15.editor.domain.Environment3D
import com.termex.replay15.editor.domain.MODEL_TARGET
import com.termex.replay15.editor.domain.Scene3DClip
import com.termex.replay15.editor.domain.StudioPreset
import com.termex.replay15.editor.domain.ToneMapping
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * Offscreen Filament renderer for [Scene3DClip] layers.
 *
 * Every Filament call runs on one private thread (Filament engines are single-threaded). Frames
 * are rendered into a headless swap chain and read back into a caller-owned ARGB_8888 bitmap,
 * premultiplied, top row first: the same format as the editor's sticker/text overlays, so preview
 * (GpuPreviewRenderer) and export (Media3 BitmapOverlay) composite it identically.
 */
class Scene3DRenderer : AutoCloseable {
    enum class Quality { PREVIEW, EXPORT }

    class ModelInfo(val animationCount: Int, val animationSeconds: Float)

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Scene3D").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private var core: Core? = null // Scene3D thread only

    /**
     * Draws every layer of [scenes] active at [projectTimeUs] into [target] (cleared first).
     * Blocking. Returns false when nothing was drawn (no active layer, or a layer failed).
     */
    fun render(scenes: List<Scene3DClip>, projectTimeUs: Long, target: Bitmap, quality: Quality): Boolean {
        val active = scenes.filter { it.isActiveAt(projectTimeUs) }
        if (active.isEmpty() || closed.get()) return false
        require(target.config == Bitmap.Config.ARGB_8888 && target.isMutable)
        return try {
            executor.submit(Callable { core().draw(active, projectTimeUs, target, quality) }).get(RENDER_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            Log.e(TAG, "3D frame failed at $projectTimeUs", failure)
            false
        }
    }

    /** Loads [modelPath] with Filament (and keeps it cached). Throws when the model cannot be used. */
    fun inspect(modelPath: String): ModelInfo {
        check(!closed.get()) { "Renderizador 3D encerrado" }
        return executor.submit(Callable { core().model(modelPath).let { ModelInfo(it.animationCount, it.animationSeconds) } })
            .get(LOAD_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /** Loads an environment image to check it is usable. */
    fun inspectEnvironment(path: String) {
        check(!closed.get()) { "Renderizador 3D encerrado" }
        executor.submit(Callable { core().environment(Environment3D(environmentPath = path)) }).get(LOAD_TIMEOUT_S, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { runCatching { core?.release() }.onFailure { Log.e(TAG, "release", it) }; core = null }
        executor.shutdown()
    }

    private var coreFailure: Throwable? = null // Scene3D thread only

    /** Creates the engine once; a device that cannot start Filament is not retried every frame. */
    private fun core(): Core {
        core?.let { return it }
        coreFailure?.let { throw IllegalStateException("O 3D não está disponível neste aparelho", it) }
        return try { Core().also { core = it } } catch (failure: Throwable) { coreFailure = failure; throw failure }
    }

    private class LoadedModel(
        val asset: FilamentAsset,
        val main: FilamentInstance,
        val mirror: FilamentInstance,
        val normalization: FloatArray,
        val animationCount: Int,
        val animationSeconds: Float,
        /** Keeps the memory-mapped file alive while Filament may still reference it. */
        val source: ByteBuffer,
    )

    private class LoadedEnvironment(val cubemap: Texture, val reflections: Texture, val ibl: IndirectLight, val skybox: Skybox,
        val look: StudioEnvironment.Look)

    private class LoadedFloor(val asset: FilamentAsset)

    private class Core {
        val engine: Engine
        val renderer: Renderer
        val scene: Scene
        val view: View
        val cameraEntity: Int
        val camera: Camera
        val materials: UbershaderProvider
        val assetLoader: AssetLoader
        val resourceLoader: ResourceLoader
        val sun: Int
        var swapChain: SwapChain? = null
        var swapWidth = 0
        var swapHeight = 0
        val models = LinkedHashMap<String, LoadedModel>(4, .75f, true)
        val failedModels = HashMap<String, String>()
        val environments = LinkedHashMap<String, LoadedEnvironment>(4, .75f, true)
        val floors = LinkedHashMap<String, LoadedFloor>(4, .75f, true)
        val gradings = HashMap<ToneMapping, ColorGrading>()
        var prefilter: IBLPrefilterContext? = null
        val inScene = ArrayList<Int>()
        var pixels: ByteBuffer? = null
        var flipped: ByteBuffer? = null
        var layer: Bitmap? = null
        val world = FloatArray(16)
        val mirrorMatrix = FloatArray(16)
        val floorMatrix = FloatArray(16)

        init {
            // gltfio-jni is also a dependency of filament-utils-jni; loading it first by name keeps
            // the JNI lookup explicit. Both calls load each shared library only once.
            Gltfio.init()
            Utils.init()
            engine = Engine.create()
            renderer = engine.createRenderer()
            scene = engine.createScene()
            cameraEntity = EntityManager.get().create()
            camera = engine.createCamera(cameraEntity)
            view = engine.createView()
            view.setScene(scene)
            view.setCamera(camera)
            view.setPostProcessingEnabled(true)
            view.setShadowingEnabled(true)
            materials = UbershaderProvider(engine)
            assetLoader = AssetLoader(engine, materials, EntityManager.get())
            resourceLoader = ResourceLoader(engine, true)
            sun = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, .98f, .95f)
                .intensity(50_000f)
                .direction(0f, -1f, 0f)
                .sunAngularRadius(2.5f)
                .castShadows(true)
                .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 2048 })
                .build(engine, sun)
        }

        fun draw(active: List<Scene3DClip>, projectTimeUs: Long, target: Bitmap, quality: Quality): Boolean {
            val width = target.width; val height = target.height
            target.eraseColor(Color.TRANSPARENT)
            if (active.size == 1) return drawOne(active[0], projectTimeUs, target, quality)
            // Several overlapping layers: render each one and stack them in timeline order.
            val scratch = layer?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { layer?.recycle(); layer = it }
            val canvas = Canvas(target)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var any = false
            active.forEach { clip ->
                scratch.eraseColor(Color.TRANSPARENT)
                if (drawOne(clip, projectTimeUs, scratch, quality)) { canvas.drawBitmap(scratch, 0f, 0f, paint); any = true }
            }
            return any
        }

        private fun drawOne(clip: Scene3DClip, projectTimeUs: Long, target: Bitmap, quality: Quality): Boolean {
            val model = runCatching { model(clip.modelPath) }.getOrElse {
                Log.e(TAG, "Model ${clip.modelPath} unavailable", it); return false
            }
            val environment = runCatching { environment(clip.environment) }.getOrElse {
                Log.e(TAG, "Environment unavailable, falling back to the preset", it)
                environment(clip.environment.copy(environmentPath = ""))
            }
            val env = clip.environment
            val local = clip.localTime(projectTimeUs)
            val objectMatrix = clip.objectMatrix(MODEL_TARGET, local)
            val stage = env.lightRotation

            // Contents of the scene for this layer.
            inScene.forEach(scene::remove); inScene.clear()
            fun add(entities: IntArray) { entities.forEach { scene.addEntity(it); inScene += it } }
            val floorAsset = if (env.floor) floor(clip).asset else null
            add(model.main.entities)
            if (floorAsset != null && env.floorReflection > .01f) add(model.mirror.entities)
            floorAsset?.let { add(it.entities) }
            scene.addEntity(sun); inScene += sun

            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(model.main.root), SceneLayout.world(objectMatrix, model.normalization, stage, false, world))
            tm.setTransform(tm.getInstance(model.mirror.root), SceneLayout.world(objectMatrix, model.normalization, stage, true, mirrorMatrix))
            floorAsset?.let { tm.setTransform(tm.getInstance(it.root), SceneLayout.floor(FLOOR_RADIUS, floorMatrix)) }

            if (model.animationCount > 0) {
                val seconds = SceneLayout.animationTime(clip.animationSeconds(local), model.animationSeconds)
                listOf(model.main, model.mirror).forEach { instance ->
                    instance.animator.applyAnimation(0, seconds)
                    instance.animator.updateBoneMatrices()
                }
            }

            val rcm = engine.renderableManager
            model.main.entities.forEach { entity ->
                if (rcm.hasComponent(entity)) rcm.getInstance(entity).let {
                    rcm.setCastShadows(it, env.shadows); rcm.setReceiveShadows(it, true); rcm.setScreenSpaceContactShadows(it, env.shadows)
                }
            }
            model.mirror.entities.forEach { entity ->
                if (rcm.hasComponent(entity)) rcm.getInstance(entity).let {
                    rcm.setCastShadows(it, false); rcm.setReceiveShadows(it, false); rcm.setScreenSpaceContactShadows(it, false)
                }
            }

            val look = environment.look
            val lm = engine.lightManager
            val light = lm.getInstance(sun)
            lm.setDirection(light, -look.keyDirection[0], -look.keyDirection[1], -look.keyDirection[2])
            lm.setIntensity(light, look.keyIntensity * env.lightIntensity)
            lm.setShadowCaster(light, env.shadows)
            environment.ibl.setIntensity(StudioEnvironment.IBL_INTENSITY * env.lightIntensity)
            scene.setIndirectLight(environment.ibl)
            scene.setSkybox(if (env.showBackground) environment.skybox else null)

            val width = target.width; val height = target.height
            ensureSwapChain(width, height)
            view.setViewport(Viewport(0, 0, width, height))
            view.setBlendMode(if (env.showBackground) View.BlendMode.OPAQUE else View.BlendMode.TRANSLUCENT)
            view.setColorGrading(grading(env.toneMapping))
            view.setBloomOptions(View.BloomOptions().apply { enabled = env.bloom > 0f; strength = env.bloom * .5f })
            view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply {
                enabled = true
                this.quality = if (quality == Quality.EXPORT) View.QualityLevel.HIGH else View.QualityLevel.LOW
            })
            view.setMultiSampleAntiAliasingOptions(View.MultiSampleAntiAliasingOptions().apply {
                enabled = quality == Quality.EXPORT; sampleCount = 4
            })
            view.setAntiAliasing(View.AntiAliasing.FXAA)
            renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) })

            // The camera on air: the main camera or the shot that cuts in at this time, with its parents.
            val (eye, centre, up) = SceneLayout.camera(clip.worldCamera(local), stage)
            camera.setLensProjection(clip.focalLengthAt(local).toDouble(), width.toDouble() / height, NEAR, FAR)
            camera.lookAt(eye[0], eye[1], eye[2], centre[0], centre[1], centre[2], up[0], up[1], up[2])
            camera.setExposure(16f, 1f / 125f, 100f * 2f.pow(env.exposure))

            return renderAndRead(target)
        }

        private fun renderAndRead(target: Bitmap): Boolean {
            val width = target.width; val height = target.height
            val bytes = width * height * 4
            val buffer = pixels?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { pixels = it }
            buffer.clear()
            val done = CountDownLatch(1)
            val chain = swapChain ?: return false
            var began = false
            for (attempt in 0 until 4) {
                if (renderer.beginFrame(chain, System.nanoTime())) { began = true; break }
                // The GPU is still busy with an earlier frame: wait for it and try again.
                engine.flushAndWait()
            }
            if (!began) return false
            renderer.render(view)
            renderer.readPixels(0, 0, width, height, PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE,
                1, 0, 0, 0, INLINE, Runnable { done.countDown() }))
            renderer.endFrame()
            // The read-back callback fires on Filament's driver thread once the GPU has finished.
            var read = false
            for (attempt in 0 until READBACK_ATTEMPTS) {
                engine.flushAndWait()
                if (done.await(READBACK_WAIT_MS, TimeUnit.MILLISECONDS)) { read = true; break }
            }
            if (!read) {
                // Filament may still write into this buffer later: never reuse it.
                pixels = null
                return false
            }
            // GL reads bottom row first; bitmaps start at the top.
            val rows = flipped?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { flipped = it }
            rows.clear()
            val stride = width * 4
            for (row in height - 1 downTo 0) {
                buffer.limit((row + 1) * stride).position(row * stride)
                rows.put(buffer)
            }
            rows.flip()
            target.copyPixelsFromBuffer(rows)
            return true
        }

        private fun ensureSwapChain(width: Int, height: Int) {
            if (swapChain != null && swapWidth == width && swapHeight == height) return
            swapChain?.let(engine::destroySwapChain)
            swapChain = engine.createSwapChain(width, height, SwapChainFlags.CONFIG_TRANSPARENT or SwapChainFlags.CONFIG_READABLE)
            swapWidth = width; swapHeight = height
        }

        fun model(path: String): LoadedModel {
            models[path]?.let { return it }
            failedModels[path]?.let { error(it) }
            try {
                val file = File(path)
                require(file.isFile) { "Arquivo do modelo não encontrado" }
                val source = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
                // Filament fills the array; its Java signature declares non-null elements.
                @Suppress("UNCHECKED_CAST")
                val instances = arrayOfNulls<FilamentInstance>(2) as Array<FilamentInstance>
                val asset = assetLoader.createInstancedAsset(source, instances)
                    ?: error("O Filament não conseguiu ler este modelo")
                resourceLoader.loadResources(asset)
                asset.releaseSourceData()
                val main: FilamentInstance? = instances[0]; val mirror: FilamentInstance? = instances[1]
                if (main == null || mirror == null) { assetLoader.destroyAsset(asset); error("O Filament não conseguiu ler este modelo") }
                val box = asset.boundingBox
                val animator = main.animator
                val count = animator.animationCount
                val loaded = LoadedModel(asset, main, mirror, SceneLayout.normalization(box.center, box.halfExtent), count,
                    if (count > 0) animator.getAnimationDuration(0) else 0f, source)
                models[path] = loaded
                while (models.size > MAX_MODELS) {
                    val eldest = models.entries.first()
                    if (eldest.key == path) break
                    models.remove(eldest.key)
                    destroyAsset(eldest.value.asset)
                }
                return loaded
            } catch (failure: Throwable) {
                failedModels[path] = failure.message ?: "Modelo inválido"
                throw failure
            }
        }

        private fun destroyAsset(asset: FilamentAsset) {
            val entities = asset.entities.toSet()
            entities.forEach(scene::remove)
            inScene.removeAll(entities)
            assetLoader.destroyAsset(asset)
        }

        fun environment(env: Environment3D): LoadedEnvironment {
            val key = env.environmentPath.ifBlank { "preset:${env.preset.name}" }
            environments[key]?.let { return it }
            val context = prefilter ?: IBLPrefilterContext(engine).also { prefilter = it }
            val equirect = if (env.environmentPath.isBlank()) uploadEquirect(StudioEnvironment.pixels(env.preset),
                StudioEnvironment.WIDTH, StudioEnvironment.HEIGHT) else loadPanorama(env.environmentPath)
            val toCube = IBLPrefilterContext.EquirectangularToCubemap(context)
            val cubemap = try { toCube.run(equirect) } finally { toCube.destroy(); engine.destroyTexture(equirect) }
            val specular = IBLPrefilterContext.SpecularFilter(context)
            val reflections = try { specular.run(cubemap) } finally { specular.destroy() }
            val ibl = IndirectLight.Builder().reflections(reflections).intensity(StudioEnvironment.IBL_INTENSITY).build(engine)
            val skybox = Skybox.Builder().environment(cubemap).build(engine)
            val look = if (env.environmentPath.isBlank()) StudioEnvironment.look(env.preset) else StudioEnvironment.overheadKey
            val loaded = LoadedEnvironment(cubemap, reflections, ibl, skybox, look)
            environments[key] = loaded
            while (environments.size > MAX_ENVIRONMENTS) {
                val eldest = environments.entries.first()
                if (eldest.key == key) break
                environments.remove(eldest.key)
                destroyEnvironment(eldest.value)
            }
            return loaded
        }

        private fun destroyEnvironment(environment: LoadedEnvironment) {
            if (scene.indirectLight === environment.ibl) scene.setIndirectLight(null)
            if (scene.skybox === environment.skybox) scene.setSkybox(null)
            engine.destroyIndirectLight(environment.ibl)
            engine.destroySkybox(environment.skybox)
            engine.destroyTexture(environment.reflections)
            engine.destroyTexture(environment.cubemap)
        }

        private fun loadPanorama(path: String): Texture {
            val file = File(path)
            require(file.isFile) { "Imagem de ambiente não encontrada" }
            if (file.name.lowercase().endsWith(".hdr")) {
                val bytes = file.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
                buffer.flip()
                return HDRLoader.createTexture(engine, buffer) ?: error("Não foi possível ler este arquivo HDR")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem de ambiente inválida" }
            var sample = 1
            while (bounds.outWidth / sample > MAX_PANORAMA_WIDTH) sample *= 2
            val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Imagem de ambiente inválida")
            try {
                val w = bitmap.width; val h = bitmap.height
                val argb = IntArray(w * h)
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                val linear = FloatArray(w * h * 3)
                val table = FloatArray(256) { srgbToLinear(it / 255f) }
                for (i in argb.indices) {
                    val c = argb[i]
                    linear[i * 3] = table[(c shr 16) and 0xFF]; linear[i * 3 + 1] = table[(c shr 8) and 0xFF]; linear[i * 3 + 2] = table[c and 0xFF]
                }
                return uploadEquirect(linear, w, h)
            } finally { bitmap.recycle() }
        }

        private fun uploadEquirect(linear: FloatArray, width: Int, height: Int): Texture {
            val texture = Texture.Builder().width(width).height(height).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGB16F).build(engine)
            val buffer = ByteBuffer.allocateDirect(linear.size * 2).order(ByteOrder.nativeOrder())
            HalfFloat.encode(linear, buffer.asShortBuffer())
            // Texture.setImage only takes the nested (deprecated) descriptor type.
            @Suppress("DEPRECATION")
            texture.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGB, Texture.Type.HALF))
            return texture
        }

        private fun floor(clip: Scene3DClip): LoadedFloor {
            val env = clip.environment
            val look = environment(env).look
            val reflection = (env.floorReflection * 20f).toInt() / 20f
            val key = "${env.environmentPath.ifBlank { env.preset.name }}|$reflection"
            floors[key]?.let { return it }
            val bytes = FloorMesh.glb(look.floorColor, look.floorRoughness, reflection)
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buffer.flip()
            val asset = assetLoader.createAsset(buffer) ?: error("Falha ao criar o chão")
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            val rcm = engine.renderableManager
            asset.entities.forEach { entity ->
                if (rcm.hasComponent(entity)) rcm.getInstance(entity).let { rcm.setCastShadows(it, false); rcm.setReceiveShadows(it, true) }
            }
            val loaded = LoadedFloor(asset)
            floors[key] = loaded
            while (floors.size > MAX_FLOORS) {
                val eldest = floors.entries.first()
                if (eldest.key == key) break
                floors.remove(eldest.key)
                destroyAsset(eldest.value.asset)
            }
            return loaded
        }

        private fun grading(mapping: ToneMapping): ColorGrading = gradings.getOrPut(mapping) {
            val mapper: ToneMapper = when (mapping) {
                ToneMapping.PBR_NEUTRAL -> PBRNeutralToneMapper()
                ToneMapping.AGX -> AgxToneMapper()
                ToneMapping.ACES -> ACESToneMapper()
                ToneMapping.FILMIC -> FilmicToneMapper()
            }
            ColorGrading.Builder().toneMapper(mapper).build(engine)
        }

        fun release() {
            inScene.forEach(scene::remove); inScene.clear()
            scene.setIndirectLight(null); scene.setSkybox(null)
            view.setColorGrading(null)
            models.values.forEach { assetLoader.destroyAsset(it.asset) }; models.clear()
            floors.values.forEach { assetLoader.destroyAsset(it.asset) }; floors.clear()
            environments.values.forEach(::destroyEnvironment); environments.clear()
            gradings.values.forEach(engine::destroyColorGrading); gradings.clear()
            prefilter?.destroy(); prefilter = null
            resourceLoader.destroy()
            assetLoader.destroy()
            materials.destroyMaterials()
            materials.destroy()
            engine.destroyEntity(sun); EntityManager.get().destroy(sun)
            swapChain?.let(engine::destroySwapChain); swapChain = null
            engine.destroyRenderer(renderer)
            engine.destroyView(view)
            engine.destroyScene(scene)
            engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
            engine.destroy()
            layer?.recycle(); layer = null
        }
    }

    private companion object {
        const val TAG = "Scene3DRenderer"
        const val RENDER_TIMEOUT_S = 20L
        const val LOAD_TIMEOUT_S = 90L
        const val READBACK_ATTEMPTS = 20
        const val READBACK_WAIT_MS = 100L
        const val NEAR = .05
        const val FAR = 400.0
        const val FLOOR_RADIUS = 25f
        const val MAX_MODELS = 3
        const val MAX_ENVIRONMENTS = 3
        const val MAX_FLOORS = 4
        const val MAX_PANORAMA_WIDTH = 4096
        /** Runs Filament's read-back callback on whichever thread delivers it. */
        val INLINE = Executor { it.run() }

        fun srgbToLinear(c: Float): Float = if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f)
    }
}
