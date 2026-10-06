# Script to append new transitions to transitions.json
$path = "editor-engine/src/main/assets/editor/transitions.json"
$existing = Get-Content $path -Raw | ConvertFrom-Json

$newTransitions = @(
    # ── Fade ──
    @{
        id = "smooth_fade"; version = 1; name = "Smooth Fade"; category = "Fade"
        engine = "BLEND"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 3000000
        shaderFile = "recly_transition_blend.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 5.0; default = 5.0 }
        )
    },
    @{
        id = "color_fade_dip"; version = 1; name = "Color Dip Fade"; category = "Fade"
        engine = "BLEND"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 3000000
        shaderFile = "recly_transition_blend.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 5.0; default = 1.0 }
        )
    },
    @{
        id = "cross_fade_soft"; version = 1; name = "Soft Cross Fade"; category = "Fade"
        engine = "BLEND"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 3000000
        shaderFile = "recly_transition_blend.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 5.0; default = 0.0 },
            @{ id = "softness"; name = "Suavidade"; type = "float"; min = 0.0; max = 1.0; default = 0.8 }
        )
    },

    # ── RGB ──
    @{
        id = "rgb_split"; version = 1; name = "RGB Split"; category = "RGB"
        engine = "RGB_GLITCH"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_rgb.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 0.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 3.0; default = 1.0 }
        )
    },
    @{
        id = "rgb_shift"; version = 1; name = "RGB Shift"; category = "RGB"
        engine = "RGB_GLITCH"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_rgb.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 1.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 3.0; default = 1.0 }
        )
    },
    @{
        id = "chromatic_drift"; version = 1; name = "Chromatic Drift"; category = "RGB"
        engine = "RGB_GLITCH"; defaultDurationUs = 550000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_rgb.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 2.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 3.0; default = 1.2 }
        )
    },
    @{
        id = "channel_shear"; version = 1; name = "Channel Shear"; category = "RGB"
        engine = "RGB_GLITCH"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_rgb.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 3.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 3.0; default = 1.0 }
        )
    },
    @{
        id = "prism_wipe"; version = 1; name = "Prism Wipe"; category = "RGB"
        engine = "RGB_GLITCH"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_rgb.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 4.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 3.0; default = 1.0 }
        )
    },

    # ── Film ──
    @{
        id = "film_burn"; version = 1; name = "Film Burn"; category = "Film"
        engine = "CREATIVE"; defaultDurationUs = 650000; minDurationUs = 100000; maxDurationUs = 2500000
        shaderFile = "recly_transition_film.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 0.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.1; max = 2.5; default = 1.0 }
        )
    },
    @{
        id = "film_roll"; version = 1; name = "Film Roll"; category = "Film"
        engine = "CREATIVE"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_film.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 1.0 }
        )
    },
    @{
        id = "leader_flash"; version = 1; name = "Leader Flash"; category = "Film"
        engine = "CREATIVE"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_film.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 2.0 }
        )
    },
    @{
        id = "sprocket_slip"; version = 1; name = "Sprocket Slip"; category = "Film"
        engine = "CREATIVE"; defaultDurationUs = 550000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_film.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 3.0 }
        )
    },
    @{
        id = "burn_dissolve"; version = 1; name = "Burn Dissolve"; category = "Film"
        engine = "CREATIVE"; defaultDurationUs = 700000; minDurationUs = 100000; maxDurationUs = 2500000
        shaderFile = "recly_transition_film.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 4.0 }
        )
    },

    # ── Cinematic ──
    @{
        id = "anamorphic_streak"; version = 1; name = "Anamorphic Streak"; category = "Cinematic"
        engine = "LIGHT_FLASH"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2500000
        shaderFile = "recly_transition_cinematic.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 3.0; default = 0.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.2; max = 3.0; default = 1.0 }
        )
    },
    @{
        id = "letterbox_reveal"; version = 1; name = "Letterbox Reveal"; category = "Cinematic"
        engine = "CREATIVE"; defaultDurationUs = 650000; minDurationUs = 100000; maxDurationUs = 2500000
        shaderFile = "recly_transition_cinematic.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 3.0; default = 1.0 }
        )
    },
    @{
        id = "flare_sweep"; version = 1; name = "Lens Flare Sweep"; category = "Cinematic"
        engine = "LIGHT_FLASH"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2500000
        shaderFile = "recly_transition_cinematic.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 3.0; default = 2.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.2; max = 2.5; default = 1.0 }
        )
    },
    @{
        id = "cine_shutter"; version = 1; name = "Cinematic Shutter"; category = "Cinematic"
        engine = "CREATIVE"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_cinematic.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 3.0; default = 3.0 }
        )
    },

    # ── Social ──
    @{
        id = "social_swipe_up"; version = 1; name = "Stories Swipe Up"; category = "Social"
        engine = "TRANSFORM"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_social.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 0.0 }
        )
    },
    @{
        id = "stories_flip"; version = 1; name = "Stories Card Flip"; category = "Social"
        engine = "PERSPECTIVE_3D"; defaultDurationUs = 550000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_social.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 1.0 }
        )
    },
    @{
        id = "heart_iris"; version = 1; name = "Like Heart Reveal"; category = "Social"
        engine = "MASK_WIPE"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_social.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 2.0 }
        )
    },
    @{
        id = "bubble_pop_wipe"; version = 1; name = "Bubble Pop Reveal"; category = "Social"
        engine = "MASK_WIPE"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_social.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 3.0 }
        )
    },
    @{
        id = "notification_slide"; version = 1; name = "Card Slide In"; category = "Social"
        engine = "TRANSFORM"; defaultDurationUs = 500000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_social.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 4.0 }
        )
    },

    # ── Gaming ──
    @{
        id = "pixel_dissolve_arcade"; version = 1; name = "8-Bit Pixel Dissolve"; category = "Gaming"
        engine = "CREATIVE"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_gaming.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 0.0 }
        )
    },
    @{
        id = "scanline_wipe"; version = 1; name = "Scanline Wipe"; category = "Gaming"
        engine = "CREATIVE"; defaultDurationUs = 550000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_gaming.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 1.0 }
        )
    },
    @{
        id = "energy_blast"; version = 1; name = "Energy Blast"; category = "Gaming"
        engine = "LIGHT_FLASH"; defaultDurationUs = 550000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_gaming.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 2.0 },
            @{ id = "intensity"; name = "Intensidade"; type = "float"; min = 0.2; max = 2.5; default = 1.0 }
        )
    },
    @{
        id = "level_clear_wipe"; version = 1; name = "Level Clear Sweep"; category = "Gaming"
        engine = "CREATIVE"; defaultDurationUs = 600000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_gaming.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 3.0 }
        )
    },
    @{
        id = "cyber_grid_reveal"; version = 1; name = "Cyber Grid Reveal"; category = "Gaming"
        engine = "CREATIVE"; defaultDurationUs = 650000; minDurationUs = 100000; maxDurationUs = 2000000
        shaderFile = "recly_transition_gaming.frag"
        parameters = @(
            @{ id = "mode"; name = "Modo"; type = "float"; min = 0.0; max = 4.0; default = 4.0 }
        )
    }
)

# Merge avoiding duplicates
$existingIds = $existing | ForEach-Object { $_.id }
$filteredNew = $newTransitions | Where-Object { $_.id -notin $existingIds }

$combined = @($existing) + @($filteredNew)

# Verify shader files exist
foreach ($t in $combined) {
    $shaderPath = "editor-engine/src/main/assets/editor/transitions/$($t.shaderFile)"
    if (-not (Test-Path $shaderPath)) {
        throw "Shader file not found: $shaderPath for transition $($t.id)"
    }
}

$jsonOut = $combined | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($path, $jsonOut, [System.Text.Encoding]::UTF8)

Write-Host "Updated transitions.json: total $($combined.Count) transitions."

