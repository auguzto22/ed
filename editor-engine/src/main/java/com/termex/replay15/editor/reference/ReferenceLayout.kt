package com.termex.replay15.editor.reference

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The "layout" layer of a reference: what sits on top of the footage (titles, cards, images,
 * stickers) and footage that interrupts the main shot (B-roll). The editing-effects layer
 * ([ReferenceEvent]) only describes how the footage is cut and moved; without this layer a
 * talking-head reference whose language is mostly graphics looked like "minimal editing".
 *
 * Only structure is stored (kind, box, timing, style). Reference pixels are never copied into
 * a project; reference text is kept only as a label so the user recognises the slot.
 */
enum class ReferenceLayoutKind(val label: String) {
    TITLE("Titulo"), CAPTION("Legenda"), CARD("Card"), OVERLAY_IMAGE("Imagem sobreposta"),
    STICKER("Sticker"), BROLL("B-roll"),
}

enum class ReferenceLayoutSource { LOCAL, GEMINI }

/** Normalized box: center x/y and size, all relative to the frame (0..1). */
data class LayoutBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    init { require(listOf(x, y, width, height).all { it.isFinite() } && width > 0f && height > 0f) }
    val left get() = x - width / 2f
    val right get() = x + width / 2f
    val top get() = y - height / 2f
    val bottom get() = y + height / 2f
    val area get() = width * height
    fun contains(px: Float, py: Float) = px in left..right && py in top..bottom

    fun iou(other: LayoutBox): Float {
        val w = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f)
        val h = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
        val inter = w * h
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clamped(): LayoutBox {
        val l = left.coerceIn(0f, 1f); val r = right.coerceIn(0f, 1f); val t = top.coerceIn(0f, 1f); val b = bottom.coerceIn(0f, 1f)
        return LayoutBox((l + r) / 2f, (t + b) / 2f, (r - l).coerceAtLeast(.01f), (b - t).coerceAtLeast(.01f))
    }

    companion object {
        fun fromEdges(left: Float, top: Float, right: Float, bottom: Float) =
            LayoutBox((left + right) / 2f, (top + bottom) / 2f, (right - left).coerceAtLeast(.01f), (bottom - top).coerceAtLeast(.01f))
        val FULL = LayoutBox(.5f, .5f, 1f, 1f)
    }
}

enum class LayoutFont { SANS, CONDENSED, SERIF, HANDWRITTEN, MONO }

data class LayoutStyle(
    val textColor: Int = -1, val backgroundColor: Int = 0, val bold: Boolean = true,
    val uppercase: Boolean = false, val font: LayoutFont = LayoutFont.SANS, val outline: Boolean = true,
)

data class ReferenceLayoutElement(
    val kind: ReferenceLayoutKind, val startUs: Long, val endUs: Long, val box: LayoutBox,
    val style: LayoutStyle = LayoutStyle(), val label: String = "", val confidence: Float = .5f,
    val source: ReferenceLayoutSource = ReferenceLayoutSource.LOCAL,
) {
    init { require(startUs >= 0 && endUs > startUs && confidence in 0f..1f && label.length <= 200) }
    val durationUs get() = endUs - startUs
    fun overlapsInTime(other: ReferenceLayoutElement, toleranceUs: Long = 0L) =
        startUs < other.endUs + toleranceUs && other.startUs < endUs + toleranceUs
}

/** Observed text line (from OCR), normalized to the frame. */
data class TextLineObservation(
    val timeUs: Long, val text: String, val box: LayoutBox, val fillColor: Int, val backgroundColor: Int?,
    val darkBorder: Boolean,
)

/** Pure, testable detection of layout elements from per-frame cell grids and OCR lines. */
object ReferenceLayoutDetector {
    const val GRID_COLUMNS = 12
    const val GRID_ROWS = 12

    /**
     * Titles: text that is NOT the caption track (upper area or big type), held for a while.
     * Tracked by position, not by exact text: OCR reads the same title slightly differently in
     * every frame ("VOTE", "VOTE NO LULA", "OTE NO"), but the box stays put.
     */
    fun titles(lines: List<TextLineObservation>, durationUs: Long, stepUs: Long, persistentTexts: Set<String>): List<ReferenceLayoutElement> {
        val candidates = lines.filter { line ->
            val key = normalize(line.text)
            key.count(Char::isLetterOrDigit) >= 2 && key !in persistentTexts && !key.startsWith("@") && !key.contains("tiktok") &&
                (line.box.y < .45f || line.box.height >= .065f)
        }
        // 1) Lines shown together and stacked are one block (multi-line titles, card text).
        val blocks = candidates.groupBy { it.timeUs }.flatMap { (_, sameTime) ->
            val merged = mutableListOf<MutableList<TextLineObservation>>()
            sameTime.sortedBy { it.box.top }.forEach { line ->
                val block = merged.firstOrNull { group -> group.any { other ->
                    val stacked = line.box.top - other.box.bottom < .03f && min(line.box.right, other.box.right) > max(line.box.left, other.box.left) - .05f &&
                        abs(line.box.height - other.box.height) < max(line.box.height, other.box.height) * .8f
                    val sameRow = abs(line.box.y - other.box.y) < max(line.box.height, other.box.height) * .6f &&
                        max(line.box.left, other.box.left) - min(line.box.right, other.box.right) < .15f
                    stacked || sameRow } }
                if (block == null) merged += mutableListOf(line) else block += line
            }
            merged
        }
        // 2) Blocks that stay in the same place over consecutive samples are one title.
        val tracks = mutableListOf<MutableList<List<TextLineObservation>>>()
        blocks.sortedBy { it.first().timeUs }.forEach { block ->
            val box = union(block.map { it.box }); val time = block.first().timeUs
            val track = tracks.lastOrNull { track ->
                val last = track.last()
                time - last.first().timeUs <= stepUs * 2 + stepUs / 2 && time != last.first().timeUs &&
                    (union(last.map { it.box }).iou(box) > .25f || (abs(union(last.map { it.box }).y - box.y) < .03f &&
                        abs(union(last.map { it.box }).x - box.x) < .25f))
            }
            if (track == null) tracks += mutableListOf(block) else track += block
        }
        // 3) Partial reads of one title can start parallel tracks; fold tracks that share time and place.
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in tracks.indices) for (j in i + 1 until tracks.size) {
                val a = tracks[i]; val b = tracks[j]
                val aStart = a.first().first().timeUs; val aEnd = a.last().first().timeUs
                val bStart = b.first().first().timeUs; val bEnd = b.last().first().timeUs
                val concurrent = bStart <= aEnd && aStart <= bEnd
                val words = { t: List<List<TextLineObservation>> -> t.flatten().flatMap { normalize(it.text).split(' ') }.filter { it.length >= 3 }.toSet() }
                val near = concurrent || (bStart <= aEnd + stepUs * 2 && aStart <= bEnd + stepUs * 2 && words(a).intersect(words(b)).isNotEmpty())
                val boxA = union(a.flatten().map { it.box }); val boxB = union(b.flatten().map { it.box })
                val place = boxA.iou(boxB) > .15f || (abs(boxA.y - boxB.y) < .04f && min(boxA.right, boxB.right) > max(boxA.left, boxB.left))
                if (near && place) {
                    val combined = (a + b).groupBy { it.first().timeUs }.toSortedMap().values.map { it.flatten() }
                    tracks[i] = combined.toMutableList(); tracks.removeAt(j); changed = true; break@loop
                }
            }
        }
        val elements = tracks.mapNotNull { track ->
            val start = (track.first().first().timeUs - stepUs / 2).coerceAtLeast(0L)
            val end = (track.last().first().timeUs + stepUs / 2).coerceAtMost(durationUs)
            val big = track.any { block -> block.any { it.box.height >= .055f } }
            val letters = track.flatten().maxOf { line -> line.text.count(Char::isLetterOrDigit) }
            if (end <= start || letters < 3 || (track.size < 2 && !(big && letters >= 4)) || (end - start < 400_000L && !big)) return@mapNotNull null
            val texts = track.map { block -> block.sortedWith(compareBy({ (it.box.y * 30).toInt() }, { it.box.left })).joinToString(" ") { it.text.trim() } }
            // The fullest read is the most reliable label; partial reads are shorter.
            val counts = texts.groupingBy { normalize(it) }.eachCount()
            val key = counts.filterValues { it >= 2 }.keys.maxByOrNull { it.length } ?: counts.maxBy { it.value }.key
            val label = texts.first { normalize(it) == key }
            val lengths = texts.map { it.length }.sorted(); val typical = lengths[lengths.size / 2]
            val boxes = track.filterIndexed { i, _ -> texts[i].length >= typical }.map { block -> union(block.map { it.box }) }
            val box = LayoutBox.fromEdges(boxes.map { it.left }.median(), boxes.map { it.top }.median(),
                boxes.map { it.right }.median(), boxes.map { it.bottom }.median()).clamped()
            val all = track.flatten()
            val labelLetters = label.filter(Char::isLetter)
            val upper = labelLetters.isNotEmpty() && labelLetters.count(Char::isUpperCase) >= labelLetters.length * .8f
            val backgrounds = all.mapNotNull { it.backgroundColor }
            val bg = if (backgrounds.size >= all.size / 2 && backgrounds.isNotEmpty()) backgrounds.sorted()[backgrounds.size / 2] else 0
            val lineCount = track.map { it.size }.sorted()[track.size / 2]
            val condensed = box.width / label.split(Regex("\\s+")).size.coerceAtLeast(1) < .11f
            ReferenceLayoutElement(if (bg != 0 || lineCount >= 3) ReferenceLayoutKind.CARD else ReferenceLayoutKind.TITLE, start, end.coerceAtLeast(start + 1), box,
                LayoutStyle(all.map { it.fillColor }.sorted()[all.size / 2], bg, true, upper,
                    if (condensed) LayoutFont.CONDENSED else LayoutFont.SANS, all.count { it.darkBorder } >= all.size / 2),
                label.take(120), (.45f + track.size * .06f).coerceAtMost(.85f))
        }
        return elements.sortedBy { it.startUs }
    }

    private fun union(boxes: List<LayoutBox>) = LayoutBox.fromEdges(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })

    /**
     * Static graphics: a region that changes abruptly while the rest of the frame keeps going,
     * then stays still. Region "presence" is decided by a graphic score (contrast + stillness):
     * the footage under a banner moves, a banner does not.
     *
     * @param cells per frame: [GRID_ROWS * GRID_COLUMNS] luma values followed by the same amount
     * of chroma values, all 0..1.
     */
    fun staticOverlays(times: List<Long>, cells: List<FloatArray>, durationUs: Long): List<ReferenceLayoutElement> {
        val n = GRID_ROWS * GRID_COLUMNS
        if (times.size < 6 || cells.any { it.size < n * 2 }) return emptyList()
        val diffs = (1 until cells.size).map { i -> FloatArray(n) { c -> cellDiff(cells[i - 1], cells[i], c, n) } }
        val regions = mutableListOf<Pair<Int, Set<Int>>>() // change index (frame i) -> cells
        for (i in 2 until cells.size - 2) {
            val d = diffs[i - 1]
            val changed = (0 until n).filter { d[it] > .12f }.toSet()
            val fraction = changed.size / n.toFloat()
            if (fraction < .03f || fraction > .75f) continue
            val rest = (0 until n).filterNot { it in changed }.map { d[it] }
            if (rest.median() > .035f) continue
            components(changed).filter { it.size >= 3 }.forEach { component ->
                // Stable before and after: a graphic popped in/out, not a moving subject.
                val before = component.map { cellDiff(cells[i - 2], cells[i - 1], it, n) }.average()
                val after = component.map { cellDiff(cells[i], cells[(i + 2).coerceAtMost(cells.lastIndex)], it, n) }.average()
                if (before < .05 && after < .05) regions += i to component
            }
        }
        if (regions.isEmpty()) return emptyList()
        // Group change events that touch the same area.
        val groups = mutableListOf<MutableList<Pair<Int, Set<Int>>>>()
        regions.forEach { region ->
            val box = boxOf(region.second)
            val group = groups.firstOrNull { g -> g.any { boxOf(it.second).iou(box) > .35f } }
            if (group == null) groups += mutableListOf(region) else group += region
        }
        val result = mutableListOf<ReferenceLayoutElement>()
        groups.forEach { group ->
            val area = group.flatMap { it.second }.toSet()
            val box = boxOf(area)
            // Too big = scene change; tiny = platform watermark hopping between corners.
            if (box.area > .7f || box.area < .05f) return@forEach
            val boundaries = (listOf(0) + group.map { it.first }.distinct().sorted() + listOf(cells.size)).distinct()
            val segments = boundaries.zipWithNext().filter { (a, b) -> b - a >= 2 }
            if (segments.size < 2) return@forEach
            val scores = segments.map { (a, b) -> graphicScore(cells.subList(a, b), area, n) }
            val low = scores.min(); val high = scores.max()
            if (high - low < .04f) return@forEach
            segments.forEachIndexed { index, (a, b) ->
                if (scores[index] >= low + (high - low) * .6f) {
                    val start = times[a].let { if (a == 0) 0L else it }
                    val end = if (b >= times.size) durationUs else times[b]
                    if (end - start >= 300_000L) result += ReferenceLayoutElement(ReferenceLayoutKind.OVERLAY_IMAGE, start, end, box,
                        LayoutStyle(), "", (.4f + (high - low)).coerceAtMost(.75f))
                }
            }
        }
        return result.sortedBy { it.startUs }.take(16)
    }

    /** A shot between two detected scene changes, with its average histogram and cell grid. */
    class Shot(val startUs: Long, val endUs: Long, val histogram: FloatArray, val cells: FloatArray) {
        val durationUs get() = endUs - startUs
    }

    /** How different two shots look: color distribution plus spatial layout (titles on top of the same shot barely move this). */
    fun shotDistance(a: Shot, b: Shot): Float {
        val n = GRID_ROWS * GRID_COLUMNS
        val structural = if (a.cells.size >= n && b.cells.size >= n) {
            // Central band only: overlays mostly live at the top/bottom edges.
            val rows = (GRID_ROWS / 4) until (GRID_ROWS - GRID_ROWS / 4)
            rows.flatMap { r -> (0 until GRID_COLUMNS).map { c -> r * GRID_COLUMNS + c } }.map { abs(a.cells[it] - b.cells[it]) }.average().toFloat()
        } else 0f
        return histogramDistance(a.histogram, b.histogram) * .5f + structural * 2f
    }

    /**
     * B-roll: short shots that look nothing like the main (dominant) shot. The opening shot is
     * never treated as B-roll: titles and banners over the main shot change its colors a lot.
     */
    fun broll(shots: List<Shot>, durationUs: Long): List<ReferenceLayoutElement> {
        if (shots.size < 2) return emptyList()
        val main = shots.maxBy { it.durationUs }
        val mainShots = shots.filter { it === main || shotDistance(it, main) < .3f || it.startUs == 0L }
        if (mainShots.sumOf { it.durationUs } < durationUs * .4f) return emptyList()
        return shots.filter { shot -> shot !in mainShots && shot.durationUs in 150_000L..(durationUs * .35f).toLong() }.map { shot ->
            ReferenceLayoutElement(ReferenceLayoutKind.BROLL, shot.startUs, shot.endUs, LayoutBox.FULL, LayoutStyle(), "",
                (.45f + shotDistance(shot, main) * .4f).coerceAtMost(.8f))
        }
    }

    /** Gemini (semantic) wins where both saw the same thing; local fills what Gemini missed. */
    fun merge(local: List<ReferenceLayoutElement>, gemini: List<ReferenceLayoutElement>): List<ReferenceLayoutElement> {
        val kept = local.filter { element ->
            gemini.none { other ->
                other.overlapsInTime(element, 300_000L) && (other.box.iou(element.box) > .2f ||
                    (other.kind == ReferenceLayoutKind.BROLL && element.kind == ReferenceLayoutKind.BROLL))
            }
        }
        // Local static regions that contain a detected title are the title's card, not a second slot.
        val deduped = kept.filter { element ->
            element.kind != ReferenceLayoutKind.OVERLAY_IMAGE || kept.none { title ->
                title !== element && title.kind in setOf(ReferenceLayoutKind.TITLE, ReferenceLayoutKind.CARD) &&
                    title.overlapsInTime(element) && title.box.iou(element.box) > .35f
            }
        }
        return (gemini + deduped).sortedBy { it.startUs }.take(40)
    }

    /** True when only part of the frame changed and the rest held still: a graphic, not a cut. */
    fun localizedChange(a: FloatArray, b: FloatArray): Boolean {
        val n = GRID_ROWS * GRID_COLUMNS
        if (a.size < n * 2 || b.size < n * 2) return false
        val diffs = (0 until n).map { cellDiff(a, b, it, n) }
        val changed = diffs.count { it > .12f }
        val rest = diffs.filter { it <= .12f }
        return changed in (n * .03f).toInt()..(n * .5f).toInt() && rest.median() < .035f
    }

    fun normalize(text: String) = text.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}@#]+"), " ").trim()

    private fun cellDiff(a: FloatArray, b: FloatArray, cell: Int, n: Int) =
        abs(a[cell] - b[cell]) + abs(a[n + cell] - b[n + cell]) * .5f

    private fun graphicScore(frames: List<FloatArray>, area: Set<Int>, n: Int): Float {
        // Stillness: graphics do not move between samples.
        val motion = frames.zipWithNext().map { (a, b) -> area.map { cellDiff(a, b, it, n) }.average().toFloat() }.average().toFloat().takeIf { it.isFinite() } ?: 0f
        // Contrast: graphics have hard, saturated edges inside the region.
        val contrast = frames.map { f ->
            val values = area.map { f[it] }; val chroma = area.map { f[n + it] }
            (values.max() - values.min()) * .6f + chroma.average().toFloat() * .4f
        }.average().toFloat()
        return contrast - motion * 4f
    }

    private fun components(cells: Set<Int>): List<Set<Int>> {
        val remaining = cells.toMutableSet(); val result = mutableListOf<Set<Int>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first(); val component = mutableSetOf(seed); val queue = ArrayDeque(listOf(seed)); remaining -= seed
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst(); val r = c / GRID_COLUMNS; val col = c % GRID_COLUMNS
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = r + dr; val nc = col + dc
                    if (nr !in 0 until GRID_ROWS || nc !in 0 until GRID_COLUMNS) continue
                    val next = nr * GRID_COLUMNS + nc
                    if (next in remaining) { remaining -= next; component += next; queue += next }
                }
            }
            result += component
        }
        return result
    }

    fun boxOf(cells: Set<Int>): LayoutBox {
        val rows = cells.map { it / GRID_COLUMNS }; val cols = cells.map { it % GRID_COLUMNS }
        return LayoutBox.fromEdges(cols.min() / GRID_COLUMNS.toFloat(), rows.min() / GRID_ROWS.toFloat(),
            (cols.max() + 1) / GRID_COLUMNS.toFloat(), (rows.max() + 1) / GRID_ROWS.toFloat())
    }

    fun histogramDistance(a: FloatArray, b: FloatArray): Float =
        a.indices.sumOf { abs(a[it] - (b.getOrNull(it) ?: 0f)).toDouble() }.toFloat() / 2f
}

/** Parses the structured description Gemini returns for a reference video. */
object ReferenceGeminiParser {
    data class Result(
        val elements: List<ReferenceLayoutElement>, val captions: CaptionStyleProfile?, val punchInsUs: List<Long>,
        val notes: String,
    )

    fun parse(raw: String, durationUs: Long): Result {
        val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = JSONObject(json.substring(json.indexOf('{').coerceAtLeast(0), json.lastIndexOf('}') + 1))
        val elements = root.optJSONArray("elements")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { element(it, durationUs) } }
        }.orEmpty()
        val captions = root.optJSONObject("captions")?.takeIf { it.optBoolean("present") }?.let { c ->
            val style = c.optJSONObject("style") ?: JSONObject()
            val font = font(style.optString("font"))
            CaptionStyleProfile(
                present = true, x = .5f, y = c.optDouble("position_y", .72).toFloat().coerceIn(.08f, .92f),
                relativeHeight = c.optDouble("text_height", .045).toFloat().coerceIn(.02f, .14f),
                fillColor = color(style.optString("textColor")) ?: -1,
                outlineColor = 0xFF000000.toInt(), hasBackground = color(style.optString("backgroundColor")) != null,
                bold = style.optBoolean("bold", true), italic = false, family = font.name.lowercase(),
                closestFont = textFont(font, style.optBoolean("bold", true)), fontMatchConfidence = .5f,
                animation = animation(c.optString("animation")), averageWords = c.optDouble("words_per_caption", 2.0).toFloat(),
                medianWords = c.optDouble("words_per_caption", 2.0).toInt().coerceIn(1, 12), confidence = .6f,
                uppercase = style.optBoolean("uppercase", false),
            )
        }
        val punch = root.optJSONObject("editing")?.optJSONArray("punch_ins")?.let { array ->
            (0 until array.length()).mapNotNull { array.optDouble(it, Double.NaN).takeIf(Double::isFinite) }
                .map { (it * 1_000_000).toLong() }.filter { it in 0 until durationUs }
        }.orEmpty()
        val notes = root.optJSONObject("editing")?.optString("notes").orEmpty().take(400)
        return Result(elements.sortedBy { it.startUs }.take(30), captions, punch.distinct().sorted().take(30), notes)
    }

    private fun element(json: JSONObject, durationUs: Long): ReferenceLayoutElement? {
        val kind = when (json.optString("kind").lowercase()) {
            "title", "headline", "text" -> ReferenceLayoutKind.TITLE
            "card", "news_card", "screenshot" -> ReferenceLayoutKind.CARD
            "overlay_image", "image", "banner", "logo" -> ReferenceLayoutKind.OVERLAY_IMAGE
            "sticker", "emoji" -> ReferenceLayoutKind.STICKER
            "broll", "b-roll", "b_roll", "cutaway", "photo_insert" -> ReferenceLayoutKind.BROLL
            "caption", "subtitle" -> return null // the caption track is described separately
            else -> return null
        }
        val start = (json.optDouble("start", Double.NaN) * 1_000_000).takeIf(Double::isFinite)?.toLong() ?: return null
        val end = (json.optDouble("end", Double.NaN) * 1_000_000).takeIf(Double::isFinite)?.toLong() ?: return null
        val s = start.coerceIn(0L, durationUs - 1); val e = end.coerceIn(s + 1, durationUs)
        if (e - s < 150_000L) return null
        val b = json.optJSONObject("box")
        val box = if (kind == ReferenceLayoutKind.BROLL || b == null) LayoutBox.FULL else runCatching {
            LayoutBox(b.getDouble("x").toFloat(), b.getDouble("y").toFloat(), b.getDouble("w").toFloat().coerceIn(.02f, 1f),
                b.getDouble("h").toFloat().coerceIn(.01f, 1f)).clamped()
        }.getOrNull() ?: return null
        val style = json.optJSONObject("style") ?: JSONObject()
        val bg = color(style.optString("backgroundColor")) ?: 0
        return ReferenceLayoutElement(kind, s, e, box,
            LayoutStyle(color(style.optString("textColor")) ?: -1, bg, style.optBoolean("bold", true), style.optBoolean("uppercase", false),
                font(style.optString("font")), style.optBoolean("outline", bg == 0)),
            json.optString("text").ifBlank { json.optString("description") }.trim().take(120),
            json.optDouble("confidence", .7).toFloat().coerceIn(.2f, .95f), ReferenceLayoutSource.GEMINI)
    }

    fun color(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if (!hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
        val rgb = hex.substring(0, 6).toLong(16).toInt()
        val alpha = if (hex.length == 8) hex.substring(6, 8).toInt(16) else 255
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    fun font(value: String?): LayoutFont = when (value?.lowercase()?.trim()) {
        "condensed", "impact", "display" -> LayoutFont.CONDENSED
        "serif" -> LayoutFont.SERIF
        "handwritten", "script", "cursive" -> LayoutFont.HANDWRITTEN
        "mono", "monospace" -> LayoutFont.MONO
        else -> LayoutFont.SANS
    }

    fun textFont(font: LayoutFont, bold: Boolean) = when (font) {
        LayoutFont.CONDENSED -> com.termex.replay15.editor.domain.TextFont.BEBAS
        LayoutFont.SERIF -> com.termex.replay15.editor.domain.TextFont.PLAYFAIR
        LayoutFont.HANDWRITTEN -> com.termex.replay15.editor.domain.TextFont.CAVEAT
        LayoutFont.MONO -> com.termex.replay15.editor.domain.TextFont.SPACE_MONO
        LayoutFont.SANS -> if (bold) com.termex.replay15.editor.domain.TextFont.MONTSERRAT else com.termex.replay15.editor.domain.TextFont.OUTFIT
    }

    private fun animation(value: String?) = when (value?.lowercase()?.trim()) {
        "pop", "word_pop" -> com.termex.replay15.editor.domain.TextAnimation.WORD_POP
        "karaoke", "highlight" -> com.termex.replay15.editor.domain.TextAnimation.CURRENT_WORD_HIGHLIGHT
        "fade" -> com.termex.replay15.editor.domain.TextAnimation.FADE
        "typewriter" -> com.termex.replay15.editor.domain.TextAnimation.TYPEWRITER
        else -> com.termex.replay15.editor.domain.TextAnimation.POP
    }

    const val PROMPT = """
You analyse a short vertical social-media video that a user wants to use as an EDITING TEMPLATE.
Describe its structure, not its message. List every element that sits on top of the main footage
or interrupts it, with timing in seconds and positions normalized to the frame (0..1, x/y is the
CENTER of the box, w/h its size; y=0 is the top).

Kinds:
- "title": large text that is not the spoken-word caption (hooks, headlines, labels).
- "card": a block with its own background (news screenshot, quote card, lower third with text).
- "overlay_image": a picture, banner, logo or graphic overlaid on part of the frame.
- "sticker": small emoji/sticker/animated graphic.
- "broll": a full-frame cutaway (photo or other footage) that replaces the main shot.
Do NOT list platform watermarks (TikTok/Instagram logos, @usernames).

Captions: describe the spoken-word caption track if there is one.
Editing: list the times of punch-in zooms / jump-cut zooms (sudden framing changes on the same shot).

Return ONLY JSON:
{"elements":[{"kind":"title","start":0.0,"end":3.5,"box":{"x":0.5,"y":0.08,"w":0.85,"h":0.07},
  "text":"exact visible text, if any","description":"short neutral description",
  "style":{"textColor":"#FFFFFF","backgroundColor":null,"bold":true,"uppercase":true,"font":"condensed|sans|serif|handwritten|mono","outline":true},
  "confidence":0.9}],
 "captions":{"present":true,"position_y":0.62,"text_height":0.035,"words_per_caption":2,"animation":"pop|karaoke|fade|typewriter|none",
  "style":{"textColor":"#FFFFFF","backgroundColor":null,"bold":true,"uppercase":true,"font":"sans"}},
 "editing":{"punch_ins":[3.4],"notes":"one sentence on pacing"}}
"""
}

internal fun ReferenceLayoutElement.toJson(): JSONObject = JSONObject().apply {
    put("kind", kind.name); put("startUs", startUs); put("endUs", endUs)
    put("box", JSONObject().put("x", box.x.toDouble()).put("y", box.y.toDouble()).put("w", box.width.toDouble()).put("h", box.height.toDouble()))
    put("style", JSONObject().put("textColor", style.textColor).put("backgroundColor", style.backgroundColor).put("bold", style.bold)
        .put("uppercase", style.uppercase).put("font", style.font.name).put("outline", style.outline))
    put("label", label); put("confidence", confidence.toDouble()); put("source", source.name)
}

internal fun layoutElementFromJson(json: JSONObject): ReferenceLayoutElement {
    val b = json.getJSONObject("box"); val s = json.optJSONObject("style") ?: JSONObject()
    return ReferenceLayoutElement(ReferenceLayoutKind.valueOf(json.getString("kind")), json.getLong("startUs"), json.getLong("endUs"),
        LayoutBox(b.getDouble("x").toFloat(), b.getDouble("y").toFloat(), b.getDouble("w").toFloat(), b.getDouble("h").toFloat()),
        LayoutStyle(s.optInt("textColor", -1), s.optInt("backgroundColor", 0), s.optBoolean("bold", true), s.optBoolean("uppercase", false),
            runCatching { LayoutFont.valueOf(s.optString("font")) }.getOrDefault(LayoutFont.SANS), s.optBoolean("outline", true)),
        json.optString("label"), json.optDouble("confidence", .5).toFloat().coerceIn(0f, 1f),
        runCatching { ReferenceLayoutSource.valueOf(json.optString("source")) }.getOrDefault(ReferenceLayoutSource.LOCAL))
}

internal fun layoutFromJson(array: JSONArray?): List<ReferenceLayoutElement> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull { runCatching { layoutElementFromJson(array.getJSONObject(it)) }.getOrNull() }
