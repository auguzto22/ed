package com.termex.replay15.editor.render

import android.content.Context
import com.recly.editor.engine.R

internal object EffectShaderHeaders {
    private var cachedVertexHeader: String? = null
    private var cachedFragmentHeader: String? = null
    private var cachedFragmentFooter: String? = null

    fun getVertexHeader(context: Context): String =
        cachedVertexHeader ?: synchronized(this) {
            cachedVertexHeader ?: context.resources.openRawResource(R.raw.studio_vertex)
                .bufferedReader().use { it.readText() }.also { cachedVertexHeader = it }
        }

    fun getFragmentHeader(context: Context): String =
        cachedFragmentHeader ?: synchronized(this) {
            cachedFragmentHeader ?: context.resources.openRawResource(R.raw.effect_header)
                .bufferedReader().use { it.readText() }.also { cachedFragmentHeader = it }
        }

    fun getFragmentFooter(context: Context): String =
        cachedFragmentFooter ?: synchronized(this) {
            cachedFragmentFooter ?: context.resources.openRawResource(R.raw.effect_footer)
                .bufferedReader().use { it.readText() }.also { cachedFragmentFooter = it }
        }
}
