package com.termex.replay15.editor

import com.termex.replay15.editor.core.EditorChangeClassifier
import com.termex.replay15.editor.domain.MaskKeyframe
import com.termex.replay15.editor.domain.MaskPathPoint
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.MaskType
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.TimedVideoClip
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoTrack
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.RenderStateEvaluator
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayerMaskTest {
    private fun video(id: String = "video", mask: MaskState? = null) = VideoClip(
        id = id,
        uri = "content://video/$id",
        name = "$id.mp4",
        sourceUs = 10 * SECOND,
        width = 1920,
        height = 1080,
        mask = mask,
    )

    @Test fun maskKeyframesInterpolateEveryRequiredGeometryValue() {
        val state = MaskState.default(MaskType.HEART).copy(
            keyframes = listOf(
                MaskKeyframe(0L, .2f, .3f, .4f, .5f, 170f, .02f, -.1f),
                MaskKeyframe(SECOND, .8f, .7f, 1f, .9f, -170f, .12f, .1f),
            ),
        )
        val middle = state.at(SECOND / 2)
        assertEquals(.5f, middle.centerX, .001f)
        assertEquals(.5f, middle.centerY, .001f)
        assertEquals(.7f, middle.width, .001f)
        assertEquals(.7f, middle.height, .001f)
        assertEquals(180f, middle.rotation, .001f)
        assertEquals(.07f, middle.feather, .001f)
        assertEquals(0f, middle.expansion, .001f)
        assertEquals(MaskType.HEART, middle.type)
        assertEquals(0, middle.keyframes.size)
    }

    @Test fun masksRoundTripForVideoTextStickerAndNestedImageClip() {
        val custom = MaskState.default(MaskType.CUSTOM_PATH).copy(
            centerX = .4f,
            centerY = .6f,
            opacity = .8f,
            inverted = true,
            customPath = listOf(MaskPathPoint(.1f, .2f), MaskPathPoint(.9f, .2f), MaskPathPoint(.5f, .9f)),
            keyframes = listOf(MaskKeyframe(0L, .4f, .6f, .75f, .75f, 0f, .04f, 0f)),
        )
        val track = TrackingTrack("track-main", listOf(
            TrackingPoint(0L, .4f, .5f, .3f, .3f, 1f),
            TrackingPoint(2 * SECOND, .6f, .5f, .35f, .35f, .8f),
        ))
        val main = video(mask = custom).copy(trackingTracks = listOf(track), mask = custom.copy(trackingTrackId = track.id))
        val overlay = video("image", MaskState.default(MaskType.STAR)).copy(image = true)
        val text = TextClip(text = "Masked", startUs = 0L, endUs = 3 * SECOND,
            mask = MaskState.default(MaskType.LINEAR))
        val sticker = StickerClip(uri = "content://image/sticker", name = "sticker", startUs = 0L,
            endUs = 3 * SECOND, mask = MaskState.default(MaskType.ELLIPSE))
        val project = Project(
            videos = listOf(main),
            videoTracks = listOf(VideoTrack("overlay", listOf(TimedVideoClip(0L, overlay)))),
            texts = listOf(text),
            stickers = listOf(sticker),
        )
        val bytes = ByteArrayOutputStream()
        ProjectCodec.write(project, bytes)
        assertEquals(project, ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test fun previewEvaluatesVideoMaskAtResolvedSourceTimeWithoutStructuralChange() {
        val animated = MaskState.default().copy(keyframes = listOf(
            MaskKeyframe(0L, .25f, .5f, .4f, .4f, 0f, 0f, 0f),
            MaskKeyframe(2 * SECOND, .75f, .5f, .8f, .8f, 90f, .1f, .1f),
        ))
        val before = Project(videos = listOf(video(mask = animated)))
        val snapshot = ActiveClipResolver(before).resolve(SECOND)
        val render = RenderStateEvaluator.evaluate(snapshot, before, 1L, 1L, 720, 1280)
        assertEquals(.5f, render.layers.single().mask!!.centerX, .001f)
        val after = before.copy(videos = listOf(before.videos.single().copy(mask = animated.copy(opacity = .4f))))
        assertEquals(EditorChangeClassifier.ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(before, after))
    }

    @Test fun oneCommittedMaskEditCreatesOneUndoStep() {
        val original = Project(videos = listOf(video()))
        val history = ProjectHistory(original)
        val changed = original.copy(videos = listOf(original.videos.single().copy(mask = MaskState.default())))
        history.beginEdit()
        history.updateEdit(changed)
        history.updateEdit(changed.copy(videos = listOf(changed.videos.single().copy(
            mask = changed.videos.single().mask!!.copy(centerX = .8f),
        ))))
        history.commitEdit()
        history.undo()
        assertEquals(original, history.current)
    }

    @Test fun invalidGeometryAndUnsortedKeyframesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MaskState.default().copy(width = 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            MaskState.default().copy(keyframes = listOf(
                MaskKeyframe(SECOND, .5f, .5f, .5f, .5f, 0f, 0f, 0f),
                MaskKeyframe(0L, .5f, .5f, .5f, .5f, 0f, 0f, 0f),
            ))
        }
    }
}
