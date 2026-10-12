pub fn body(name: &str) -> Option<&'static str> {
    match name {
        "recly_transition_3d.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_3d.frag")),
        "recly_transition_blend.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_blend.frag")),
        "recly_transition_blur.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_blur.frag")),
        "recly_transition_cinematic.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_cinematic.frag")),
        "recly_transition_creative.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_creative.frag")),
        "recly_transition_distortion.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_distortion.frag")),
        "recly_transition_film.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_film.frag")),
        "recly_transition_gaming.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_gaming.frag")),
        "recly_transition_glitch.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_glitch.frag")),
        "recly_transition_light.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_light.frag")),
        "recly_transition_luma.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_luma.frag")),
        "recly_transition_rgb.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_rgb.frag")),
        "recly_transition_social.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_social.frag")),
        "recly_transition_transform.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_transform.frag")),
        "recly_transition_whip.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_whip.frag")),
        "recly_transition_wipe.frag" => Some(include_str!("../../editor-engine/src/main/assets/editor/transitions/recly_transition_wipe.frag")),
        _ => None,
    }
}
