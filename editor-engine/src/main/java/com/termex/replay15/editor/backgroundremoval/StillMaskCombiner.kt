package com.termex.replay15.editor.backgroundremoval

/**
 * Pure (Android-free) merge of ML Kit subject masks into one full-image confidence matte.
 *
 * Subject segmentation separates every salient object (person, chair, plant...) with sharp,
 * full-resolution edges, but it does not know which of them the user wants to keep. The selfie
 * model knows where the *person* is, but only as a coarse 256 px blob. Combining them keeps the
 * subjects that are mostly a person and takes their edges from the subject masks; furniture or
 * plants that merely touch the person are dropped. When the photo has no person at all (a pet,
 * a product), every subject is kept, which is the plain "foreground" behaviour.
 */
object StillMaskCombiner {
    /** One subject as reported by ML Kit: a confidence mask covering its own bounding box. */
    class Subject(
        val startX: Int,
        val startY: Int,
        val width: Int,
        val height: Int,
        val mask: FloatArray,
    ) {
        init {
            require(width > 0 && height > 0 && mask.size == width * height)
        }
    }

    /** Minimum share of the image the person mask must cover for the photo to count as "has a person". */
    const val PERSON_PRESENCE_FRACTION = 0.02f
    /** A subject is kept when at least this share of its (confident) pixels lies on the person mask. */
    const val PERSON_OVERLAP_TO_KEEP = 0.35f

    /**
     * @param person selfie confidence mask ([personWidth] x [personHeight]) covering the whole image,
     *   or null when the person model failed.
     * @return a row-major [imageWidth] x [imageHeight] matte, or null when nothing should be kept
     *   (the caller then falls back to the person mask alone).
     */
    fun combine(
        imageWidth: Int,
        imageHeight: Int,
        subjects: List<Subject>,
        person: FloatArray?,
        personWidth: Int,
        personHeight: Int,
    ): FloatArray? {
        require(imageWidth > 0 && imageHeight > 0)
        if (subjects.isEmpty()) return null
        val personMask = person?.takeIf { personWidth > 0 && personHeight > 0 && it.size == personWidth * personHeight }
        val hasPerson = personMask != null &&
            personMask.count { it > 0.5f } >= PERSON_PRESENCE_FRACTION * personMask.size

        val kept = if (!hasPerson) subjects else subjects.filter { subject ->
            personOverlap(subject, imageWidth, imageHeight, personMask!!, personWidth, personHeight) >= PERSON_OVERLAP_TO_KEEP
        }
        if (kept.isEmpty()) return null

        val out = FloatArray(imageWidth * imageHeight)
        for (subject in kept) {
            for (sy in 0 until subject.height) {
                val y = subject.startY + sy
                if (y !in 0 until imageHeight) continue
                for (sx in 0 until subject.width) {
                    val x = subject.startX + sx
                    if (x !in 0 until imageWidth) continue
                    val value = subject.mask[sy * subject.width + sx].coerceIn(0f, 1f)
                    val index = y * imageWidth + x
                    if (value > out[index]) out[index] = value
                }
            }
        }
        return out
    }

    /** Confidence-weighted share of the subject's confident pixels that the person model also claims. */
    fun personOverlap(
        subject: Subject,
        imageWidth: Int,
        imageHeight: Int,
        person: FloatArray,
        personWidth: Int,
        personHeight: Int,
    ): Float {
        var weighted = 0.0
        var total = 0.0
        for (sy in 0 until subject.height) {
            val y = subject.startY + sy
            if (y !in 0 until imageHeight) continue
            for (sx in 0 until subject.width) {
                val x = subject.startX + sx
                if (x !in 0 until imageWidth) continue
                val m = subject.mask[sy * subject.width + sx]
                if (m <= 0.5f) continue
                val p = sampleBilinear(person, personWidth, personHeight, (x + .5f) / imageWidth, (y + .5f) / imageHeight)
                weighted += m * p
                total += m
            }
        }
        return if (total > 0.0) (weighted / total).toFloat() else 0f
    }

    /** Bilinear lookup with normalized coordinates (texel centres at (i + .5) / size). */
    fun sampleBilinear(mask: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = (u * w - .5f).coerceIn(0f, (w - 1).toFloat())
        val y = (v * h - .5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0; val fy = y - y0
        val top = mask[y0 * w + x0] * (1f - fx) + mask[y0 * w + x1] * fx
        val bottom = mask[y1 * w + x0] * (1f - fx) + mask[y1 * w + x1] * fx
        return (top * (1f - fy) + bottom * fy).coerceIn(0f, 1f)
    }
}
