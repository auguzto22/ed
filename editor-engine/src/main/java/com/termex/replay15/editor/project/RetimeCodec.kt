package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.CubicBezier
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SPEED_SEGMENT_EASINGS
import com.termex.replay15.editor.speed.MotionBlurConfig
import com.termex.replay15.editor.speed.MotionBlurQuality
import com.termex.replay15.editor.speed.MotionBlurSource
import com.termex.replay15.editor.speed.MotionExportQuality
import com.termex.replay15.editor.speed.SlowMotionMode
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Apêndice do schema 31: o novo sistema de velocidade.
 *
 * Por clipe (faixa principal; as faixas de sobreposição usam o mesmo envelope aninhado): o
 * easing e a Bézier de cada ponto da curva (o schema 9 guarda só tempo e velocidade), o modo de
 * câmera lenta, o motion blur e o silêncio do áudio retemporizado. No fim, a qualidade de
 * movimento da exportação. Arquivos anteriores ficam com os padrões: curvas lineares (o
 * comportamento antigo), modo automático e blur desligado.
 */
internal object RetimeCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.videos.size)
        project.videos.forEach { clip ->
            out.writeUTF(clip.id)
            out.writeInt(clip.speedCurve.size)
            clip.speedCurve.forEach { point ->
                out.writeInt(point.easing.ordinal)
                with(point.bezier) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) }
            }
            out.writeInt(clip.slowMotion.ordinal)
            with(clip.motionBlur) {
                out.writeBoolean(enabled); out.writeFloat(shutterAngle); out.writeFloat(intensity)
                out.writeInt(quality.ordinal); out.writeInt(source.ordinal)
            }
            out.writeBoolean(clip.retimedAudioMuted)
        }
        out.writeInt(project.export.motionQuality.ordinal)
    }

    fun read(project: Project, input: DataInputStream): Project {
        fun ordinal(size: Int) = input.readInt().also { require(it in 0 until size) { "Projeto incompativel" } }
        val count = input.readInt()
        require(count == project.videos.size) { "Projeto incompativel" }
        val videos = project.videos.map { clip ->
            require(input.readUTF() == clip.id) { "Projeto incompativel" }
            val points = input.readInt()
            require(points == clip.speedCurve.size) { "Projeto incompativel" }
            val curve = clip.speedCurve.map { point ->
                val easing = Easing.entries[ordinal(Easing.entries.size)]
                require(easing in SPEED_SEGMENT_EASINGS) { "Projeto incompativel" }
                point.copy(easing = easing, bezier = CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()))
            }
            val mode = SlowMotionMode.entries[ordinal(SlowMotionMode.entries.size)]
            val blur = MotionBlurConfig(
                enabled = input.readBoolean(), shutterAngle = input.readFloat(), intensity = input.readFloat(),
                quality = MotionBlurQuality.entries[ordinal(MotionBlurQuality.entries.size)],
                source = MotionBlurSource.entries[ordinal(MotionBlurSource.entries.size)],
            )
            clip.copy(speedCurve = curve, slowMotion = mode, motionBlur = blur, retimedAudioMuted = input.readBoolean())
        }
        val quality = MotionExportQuality.entries[ordinal(MotionExportQuality.entries.size)]
        return project.copy(videos = videos, export = project.export.copy(motionQuality = quality))
    }
}
