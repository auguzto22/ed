package com.termex.replay15.editor.core

import com.termex.replay15.editor.domain.*

/** Classifies the smallest operation required by the interactive engine. No category recreates the engine. */
object EditorChangeClassifier {
    enum class ChangeKind { RENDER_ONLY, TIMELINE_MAPPING, DECODER_SEEK, ACTIVE_SOURCE, STRUCTURAL_LAYER }

    fun classify(before:Project,after:Project):ChangeKind{
        if(structure(before,after))return ChangeKind.STRUCTURAL_LAYER
        val left=before.allVideos.associateBy{it.id};val right=after.allVideos.associateBy{it.id}
        if(left.keys!=right.keys)return ChangeKind.STRUCTURAL_LAYER
        if(left.any{(id,a)->val b=right.getValue(id);a.uri!=b.uri||a.proxyUri!=b.proxyUri||a.mimeType!=b.mimeType||a.image!=b.image||a.width!=b.width||a.height!=b.height})return ChangeKind.ACTIVE_SOURCE
        if(left.any{(id,a)->mapping(a,right.getValue(id))})return ChangeKind.DECODER_SEEK
        if ((listOf(MAIN_TRACK) + before.videoTracks.map { it.id }).any {
                before.visualEnabled(it) != after.visualEnabled(it)
            } || left.any { (id, a) -> a.isNullObject != right.getValue(id).isNullObject }) return ChangeKind.ACTIVE_SOURCE
        if(trackPlacement(before,after))return ChangeKind.TIMELINE_MAPPING
        return ChangeKind.RENDER_ONLY
    }

    private fun structure(a:Project,b:Project):Boolean{
        if(a.videos.map{it.id}!=b.videos.map{it.id})return true
        if(a.videoTracks.map{it.id}!=b.videoTracks.map{it.id})return true
        if(a.videoTracks.zip(b.videoTracks).any{(x,y)->x.clips.map{it.clip.id}!=y.clips.map{it.clip.id}})return true
        if(a.audio.map{it.id}!=b.audio.map{it.id})return true
        if(a.transitions.map{Triple(it.id,it.leftClipId,it.rightClipId)}!=b.transitions.map{Triple(it.id,it.leftClipId,it.rightClipId)})return true
        return false
    }
    private fun mapping(a:VideoClip,b:VideoClip)=a.inUs!=b.inUs||a.outUs!=b.outUs||a.speed!=b.speed||a.speedCurve!=b.speedCurve
    private fun trackPlacement(a:Project,b:Project):Boolean{
        if(a.transitions.map{it.durationUs}!=b.transitions.map{it.durationUs})return true
        return a.videoTracks.zip(b.videoTracks).any{(x,y)->x.clips.map{it.startUs}!=y.clips.map{it.startUs}}
    }
}
