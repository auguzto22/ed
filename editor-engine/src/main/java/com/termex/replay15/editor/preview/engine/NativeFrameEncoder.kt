package com.termex.replay15.editor.preview.engine

import android.graphics.Color
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.chromaKeyState
import org.json.JSONArray
import org.json.JSONObject

/** Serializes the immutable frame plan across JNI. No Project or Android object enters Rust. */
internal object NativeFrameEncoder {
    private fun a(vararg values: Number): JSONArray = JSONArray().apply { values.forEach(::put) }

    fun encode(
        state: PreviewRenderState,
        textures: Map<ActiveClipResolver.ClipKey, Int>,
        sizes: Map<ActiveClipResolver.ClipKey, Pair<Int, Int>>,
        stickerTexture: Int,
        textTexture: Int,
        camera: FloatArray?,
        effectValues: (PreviewRenderState.Effect) -> Map<String, Float>,
        transitionValues: (PreviewRenderState.Transition) -> Map<String, Float>,
        transitionProgram: (PreviewRenderState.Transition) -> String,
        transitionDebugMode: Int,
    ): String {
        val layers = JSONArray()
        state.layers.forEach { layer ->
            val preset = layer.filter
            val chroma = layer.grade.chromaKeyState()
            val size = sizes[layer.key] ?: (layer.sourceWidth to layer.sourceHeight)
            val effects = JSONArray()
            layer.effects.filter { it.intensity > 0f }.forEach { effect ->
                effects.put(JSONObject()
                    .put("program", "${effect.assetId}:${effect.version}")
                    .put("intensity", effect.intensity)
                    .put("blendMode", effect.blendMode.toFloat())
                    .put("mask", a(effect.maskShape.toFloat(), effect.maskFeather,
                        if (effect.maskInvert) 1 else 0, effect.maskOpacity))
                    .put("maskTransform", a(effect.maskX, effect.maskY,
                        Math.toRadians(effect.maskRotation.toDouble()).toFloat(), effect.maskSize))
                    .put("maskAspect", effect.maskAspect)
                    .put("time", layer.sourceTimeUs / 1_000_000f)
                    .put("params", JSONObject(effectValues(effect).mapKeys { "p_${it.key}" })))
            }
            layers.put(JSONObject()
                .put("key", "${layer.key.trackId}:${layer.key.clipId}")
                .put("texture", textures[layer.key] ?: 0)
                .put("width", size.first.coerceAtLeast(2))
                .put("height", size.second.coerceAtLeast(2))
                .put("sourceWidth", layer.sourceWidth.coerceAtLeast(1))
                .put("sourceHeight", layer.sourceHeight.coerceAtLeast(1))
                .put("zoom", layer.zoom)
                .put("x", layer.x)
                .put("y", layer.y)
                .put("rotation", layer.rotation)
                .put("fixedRotation", layer.fixedRotation)
                .put("flip", layer.flip)
                .put("opacity", layer.opacity)
                .put("edgeFade", JSONArray(layer.edgeFade))
                .put("crop", a(layer.crop.left, layer.crop.top, layer.crop.right, layer.crop.bottom))
                .put("adjust", a(layer.brightness + preset.brightness * layer.filterStrength,
                    layer.contrast + preset.contrast * layer.filterStrength,
                    (layer.saturation + preset.saturation * layer.filterStrength) / 100f,
                    (layer.lightness + preset.lightness * layer.filterStrength) / 100f))
                .put("color", a((layer.hue + preset.hue * layer.filterStrength) / 360f,
                    1f + layer.temperature * .18f, 1f + layer.temperature * .03f,
                    1f - layer.temperature * .18f))
                .put("preset", a(preset.red, preset.green, preset.blue,
                    when (preset.name) { "MONO" -> 1; "NEGATIVE" -> 2; else -> 0 }))
                .put("grade", a(layer.grade.exposure, layer.grade.shadows,
                    layer.grade.highlights, layer.grade.vignette))
                .put("chroma", a(Color.red(chroma.keyColor) / 255f,
                    Color.green(chroma.keyColor) / 255f, Color.blue(chroma.keyColor) / 255f,
                    if (chroma.enabled) chroma.similarity else 0f))
                .put("keyEdge", a(chroma.smoothness, chroma.spill, chroma.edgeFeather))
                .put("effects", effects)
                .put("mask", layer.mask?.let(::maskJson) ?: JSONObject.NULL))
        }
        val transition = state.transition?.let { active ->
            JSONObject().put("left", "${active.left.trackId}:${active.left.clipId}")
                .put("right", "${active.right.trackId}:${active.right.clipId}")
                .put("program", transitionProgram(active))
                .put("progress", active.progress)
                .put("debugMode", transitionDebugMode)
                .put("params", JSONObject(transitionValues(active)))
        }
        return JSONObject()
            .put("width", state.width)
            .put("height", state.height)
            .put("background", a(Color.red(state.backgroundColor) / 255f,
                Color.green(state.backgroundColor) / 255f,
                Color.blue(state.backgroundColor) / 255f, 1f))
            .put("fit", state.canvasFill == CanvasFill.FIT)
            .put("layers", layers)
            .put("transition", transition ?: JSONObject.NULL)
            .put("stickerTexture", stickerTexture)
            .put("textTexture", textTexture)
            .put("camera", camera?.let { JSONArray().apply { it.forEach(::put) } } ?: JSONObject.NULL)
            .toString()
    }

    private fun maskJson(mask: MaskState): JSONObject = JSONObject()
        .put("centerSize", a(mask.centerX, mask.centerY, mask.width, mask.height))
        .put("params", a(Math.toRadians(mask.rotation.toDouble()).toFloat(),
            mask.feather, mask.expansion, mask.opacity))
        .put("meta", a(mask.type.ordinal + 1, if (mask.inverted) 1 else 0, mask.customPath.size))
        .put("path", JSONArray().apply {
            repeat(MaskState.MAX_MASK_PATH_POINTS) { index ->
                val point = mask.customPath.getOrNull(index)
                put(a(point?.x ?: .5f, point?.y ?: .5f))
            }
        })
}
