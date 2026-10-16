package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.MAX_STICKER_ANIMATION_US
import com.termex.replay15.editor.domain.MAX_STICKER_LOOP_US
import com.termex.replay15.editor.domain.MIN_STICKER_ANIMATION_US
import com.termex.replay15.editor.domain.MIN_STICKER_LOOP_US
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.StickerAnimationKind
import com.termex.replay15.editor.domain.StickerAnimationType
import com.termex.replay15.editor.domain.StickerAnimations
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Schema-31 appendix: animações de entrada, saída e loop dos stickers (painel Animações).
 *
 * Os tipos são gravados pelo nome do enum, não pela posição, para que animações novas possam
 * entrar em qualquer ponto da lista sem quebrar projetos salvos. Um nome desconhecido vira
 * "Nenhum" em vez de impedir o projeto de abrir.
 */
internal object StickerAnimationCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.stickers.size)
        project.stickers.forEach { sticker ->
            val a = sticker.animations
            out.writeUTF(sticker.id)
            out.writeUTF(a.enter.name); out.writeLong(a.enterUs)
            out.writeUTF(a.exit.name); out.writeLong(a.exitUs)
            out.writeUTF(a.loop.name); out.writeLong(a.loopUs)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val count = input.readInt().also { require(it in 0..24) { "Projeto incompativel" } }
        val entries = HashMap<String, StickerAnimations>(count)
        repeat(count) {
            val id = input.readUTF()
            fun type(kind: StickerAnimationKind): StickerAnimationType =
                StickerAnimationType.fromId(input.readUTF()).takeIf { it.kind == kind } ?: StickerAnimationType.NONE
            val enter = type(StickerAnimationKind.IN)
            val enterUs = input.readLong().coerceIn(MIN_STICKER_ANIMATION_US, MAX_STICKER_ANIMATION_US)
            val exit = type(StickerAnimationKind.OUT)
            val exitUs = input.readLong().coerceIn(MIN_STICKER_ANIMATION_US, MAX_STICKER_ANIMATION_US)
            val loop = type(StickerAnimationKind.LOOP)
            val loopUs = input.readLong().coerceIn(MIN_STICKER_LOOP_US, MAX_STICKER_LOOP_US)
            entries[id] = StickerAnimations(enter, enterUs, exit, exitUs, loop, loopUs)
        }
        if (entries.isEmpty()) return project
        return project.copy(stickers = project.stickers.map { sticker ->
            entries[sticker.id]?.let { sticker.copy(animations = it) } ?: sticker
        })
    }
}
