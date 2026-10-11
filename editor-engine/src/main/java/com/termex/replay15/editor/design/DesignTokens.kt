package com.termex.replay15.editor.design

import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Centralized design system for Recly.
 * All visual values — colors, typography, spacing, elevation, motion — live here.
 * No magic numbers should exist outside this file.
 *
 * Philosophy: dark, minimal, cinematic. Think film editing suite — not social media app.
 */
object DesignTokens {

    // Workaround: 0xFFFFFFFF as const Int (= -1). 0x7FFFFFFF + 0x80000000 fits in Long, truncates to Int.
    private const val INT_MAX_SIGNED = (0x7FFFFFFF + 0x80000000).toInt()  // 0xFFFFFFFF = -1

    // ── Surface ─────────────────────────────────────────────────────────────────

    /** Root background. Near-black, slightly warm. */
    const val BG = 0xFF080808.toInt()

    /** Secondary background layers. */
    const val BG_SECONDARY = 0xFF0D0D0F.toInt()
    const val BG_TERTIARY = 0xFF111113.toInt()

    /** Standard surface, used for panels, sheets, dialogs. */
    const val SURFACE = 0xFF141416.toInt()
    const val SURFACE_ELEVATED = 0xFF18181B.toInt()
    const val SURFACE_HIGHLIGHT = 0xFF1E1E22.toInt()

    /** Card surfaces — used for selectable items, grids, lists. */
    const val CARD = 0xFF18181B.toInt()
    const val CARD_ELEVATED = 0xFF222226.toInt()
    const val CARD_HIGHLIGHT = 0xFF28282C.toInt()

    // ── Borders ─────────────────────────────────────────────────────────────────

    /** Translucent borders. 8–18% white on dark surface. */
    const val BORDER_SUBTLE = 0x14FFFFFF
    const val BORDER_MEDIUM = 0x1FFFFFFF
    const val BORDER_BRIGHT = 0x2EFFFFFF
    const val BORDER_ACTIVE = INT_MAX_SIGNED

    // ── Text ────────────────────────────────────────────────────────────────────

    const val TEXT_PRIMARY = 0xFFF5F5F5
    const val TEXT_SECONDARY = 0xFFA5A5AA
    const val TEXT_DISABLED = 0xFF66666C
    const val TEXT_INVERSE = 0xFF080808

    /** Accent text — used for selected/highlighted labels. */
    const val TEXT_ACCENT = INT_MAX_SIGNED

    // ── Semantic Colors ─────────────────────────────────────────────────────────

    const val ACCENT_WHITE = INT_MAX_SIGNED
    const val ACCENT_SILVER = 0xFFD4D4D8
    const val ACCENT_LIGHT = 0xFFE4E4E7

    const val RED = 0xFFEF4444
    const val RED_DARK = 0xFFDC2626
    const val RED_DIM = 0xFF7F1D1D

    const val GREEN = 0xFF10B981
    const val GREEN_DIM = 0xFF064E3B

    const val YELLOW = 0xFFFACC15
    const val YELLOW_DIM = 0xFF78350F

    const val BLUE = 0xFF60A5FA
    const val BLUE_DIM = 0xFF1E3A5F

    const val ORANGE = 0xFFFB923C

    /** Overlay used for selection state and scrim. */
    const val SELECTION_OVERLAY = 0x33FFFFFF
    const val SCRIM = 0x80000000
    const val SCRIM_LIGHT = 0x40000000

    // ── Shadows ─────────────────────────────────────────────────────────────────

    /** Elevation shadow for cards. Applied via OutlineProvider or elevation. */
    const val SHADOW_1 = 0x1A000000
    const val SHADOW_2 = 0x33000000
    const val SHADOW_3 = 0x4D000000

    // ── Corner Radii ─────────────────────────────────────────────────────────────

    /** Small: chips, tags, small buttons. */
    const val RADIUS_SM = 8

    /** Medium: inputs, cards, pills. */
    const val RADIUS_MD = 14

    /** Large: panels, sheets, bottom sheets. */
    const val RADIUS_LG = 20

    /** Extra large: modal dialogs. */
    const val RADIUS_XL = 24

    /** Full circle. */
    const val RADIUS_FULL = 9999

    // ── Spacing ─────────────────────────────────────────────────────────────────

    /** 4dp base unit. */
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_5 = 20
    const val SPACE_6 = 24
    const val SPACE_8 = 32
    const val SPACE_10 = 40
    const val SPACE_12 = 48

    // ── Touch Targets ────────────────────────────────────────────────────────────

    /** Minimum interactive element size. Follows Android accessibility guidelines. */
    const val TOUCH_MIN = 44
    const val TOUCH_COMFORTABLE = 48
    const val TOUCH_LARGE = 56

    // ── Icon Sizes ──────────────────────────────────────────────────────────────

    const val ICON_SM = 16
    const val ICON_MD = 20
    const val ICON_LG = 24
    const val ICON_XL = 32
    const val ICON_XXL = 48

    // ── Typography ──────────────────────────────────────────────────────────────

    object Type {
        const val CAPTION_SIZE = 10f
        const val LABEL_SIZE = 12f
        const val BODY_SIZE = 14f
        const val SUBHEAD_SIZE = 15f
        const val HEADLINE_SIZE = 17f
        const val TITLE_SIZE = 20f
        const val DISPLAY_SIZE = 28f
        const val HERO_SIZE = 34f
    }

    object Font {
        const val LIGHT = 300
        const val REGULAR = 400
        const val MEDIUM = 500
        const val SEMIBOLD = 600
        const val BOLD = 700
        const val BLACK = 900
    }

    // ── Z-Index / Elevation ─────────────────────────────────────────────────────

    const val ELEVATION_NONE = 0f
    const val ELEVATION_1 = 1f
    const val ELEVATION_2 = 4f
    const val ELEVATION_3 = 8f
    const val ELEVATION_4 = 12f

    // ── Motion ──────────────────────────────────────────────────────────────────

    /** Near-instant feedback for touch. */
    const val MOTION_TOUCH = 60L

    /** Press/selection feedback. */
    const val MOTION_PRESS = 80L

    /** Micro-interactions: check, select, toggle. */
    const val MOTION_SELECT = 120L

    /** Toolbar icon changes, tab switches. */
    const val MOTION_TOOLBAR = 160L

    /** Panel open/close, bottom sheet. */
    const val MOTION_PANEL = 240L

    /** Full-page transitions. */
    const val MOTION_PAGE = 320L

    /** Spring for bouncy elements. */
    val INTERPOLATOR_SPRING = OvershootInterpolator(1.2f)

    /** Standard ease-out for enter animations. */
    val INTERPOLATOR_EASE_OUT = DecelerateInterpolator(1.5f)

    /** Standard ease-in-out for scrub-sensitive animations. */
    val INTERPOLATOR_LINEAR = null  // Use linear for time-critical scrubbing

    // ── Grid ───────────────────────────────────────────────────────────────────

    /** Default thumbnail grid columns in picker. */
    const val GRID_COLUMNS_MEDIA = 3
    const val GRID_COLUMNS_FONT = 4
    const val GRID_COLUMNS_TRANSITION = 3
    const val GRID_COLUMNS_EFFECT = 3
    const val GRID_COLUMNS_STICKER = 4

    /** Thumbnail size for media grid (will be overridden by actual screen width / columns). */
    const val THUMB_SIZE_MIN = 96

    // ── Blur ────────────────────────────────────────────────────────────────────

    /** Blur radius for frosted glass surfaces. */
    const val BLUR_RADIUS_SM = 16
    const val BLUR_RADIUS_MD = 24
    const val BLUR_RADIUS_LG = 40

    // ── Alpha ──────────────────────────────────────────────────────────────────

    const val ALPHA_DISABLED = 0.38f
    const val ALPHA_MUTED = 0.6f
    const val ALPHA_VISIBLE = 0.8f
    const val ALPHA_OPAQUE = 1.0f

    // ── Animation Constants ─────────────────────────────────────────────────────

    const val SELECTION_SCALE_PRESSED = 0.94f
    const val SELECTION_SCALE_NORMAL = 1.0f
    const val SCALE_IN = 1.05f
    const val SCALE_OUT = 0.95f
}

// Extension convenience for dp conversion
fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
fun Float.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
