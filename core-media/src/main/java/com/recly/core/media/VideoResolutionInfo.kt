package com.recly.core.media

data class VideoResolutionInfo(
    val width: Int,
    val height: Int,
    val label: String,
    val defaultFps: Int = 60,
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1.0f

    val isLandscape: Boolean
        get() = width >= height

    val longSide: Int
        get() = maxOf(width, height)

    val shortSide: Int
        get() = minOf(width, height)

    companion object {
        val RES_720P = VideoResolutionInfo(1280, 720, "720p HD")
        val RES_1080P = VideoResolutionInfo(1920, 1080, "1080p FHD")
        val RES_1440P = VideoResolutionInfo(2560, 1440, "1440p 2K")
        val RES_4K = VideoResolutionInfo(3840, 2160, "4K UHD")
    }
}
