#!/usr/bin/env python3
"""
generate_atlases.py
Generates high quality, calibrated base preview photo and compiles WebP atlases
for Recly's Filter, Effect, and Transition libraries using pure Python and PIL.
"""

import json
import math
import os
import random
from PIL import Image, ImageDraw, ImageFilter

INV_SQRT3 = 1.0 / math.sqrt(3.0)
ONE_THIRD = 1.0 / 3.0

VIDEO_FILTERS = [
    {"name": "ORIGINAL", "label": "Original", "b": 0.0, "c": 0.0, "s": 0.0, "h": 0.0, "l": 0.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "MONO", "label": "Preto e branco", "b": 0.0, "c": 0.0, "s": -100.0, "h": 0.0, "l": 0.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 1},
    {"name": "NEGATIVE", "label": "Negativo", "b": 0.0, "c": 0.0, "s": 0.0, "h": 0.0, "l": 0.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 2},
    {"name": "CINEMA", "label": "Cinema", "b": -0.03, "c": 0.12, "s": 10.0, "h": 0.0, "l": -4.0, "r": 1.06, "g": 0.99, "blue": 0.92, "mode": 0},
    {"name": "VIBRANT", "label": "Vibrante", "b": 0.0, "c": 0.10, "s": 35.0, "h": 0.0, "l": 0.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "WARM", "label": "Quente", "b": 0.0, "c": 0.0, "s": 5.0, "h": 0.0, "l": 3.0, "r": 1.12, "g": 1.03, "blue": 0.86, "mode": 0},
    {"name": "COOL", "label": "Frio", "b": 0.0, "c": 0.0, "s": 3.0, "h": 0.0, "l": 2.0, "r": 0.88, "g": 1.02, "blue": 1.14, "mode": 0},
    {"name": "VINTAGE", "label": "Vintage", "b": 0.03, "c": -0.12, "s": -30.0, "h": -5.0, "l": 8.0, "r": 1.08, "g": 1.0, "blue": 0.9, "mode": 0},
    {"name": "GOLDEN", "label": "Golden hour", "b": 0.06, "c": 0.08, "s": 18.0, "h": -5.0, "l": 8.0, "r": 1.15, "g": 1.04, "blue": 0.8, "mode": 0},
    {"name": "ROSE", "label": "Rose", "b": 0.04, "c": -0.02, "s": 8.0, "h": -8.0, "l": 7.0, "r": 1.14, "g": 0.94, "blue": 1.03, "mode": 0},
    {"name": "TEAL_ORANGE", "label": "Teal & Orange", "b": -0.03, "c": 0.16, "s": 25.0, "h": -6.0, "l": -2.0, "r": 1.1, "g": 0.98, "blue": 1.08, "mode": 0},
    {"name": "NIGHT", "label": "Noite", "b": -0.12, "c": 0.20, "s": -8.0, "h": -5.0, "l": -12.0, "r": 0.82, "g": 0.93, "blue": 1.15, "mode": 0},
    {"name": "FADE", "label": "Fade", "b": 0.09, "c": -0.22, "s": -20.0, "h": 0.0, "l": 10.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "MATTE", "label": "Matte", "b": 0.05, "c": -0.18, "s": -5.0, "h": 0.0, "l": 4.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "RETRO", "label": "Retro", "b": 0.06, "c": 0.03, "s": -18.0, "h": 10.0, "l": 8.0, "r": 1.1, "g": 0.96, "blue": 0.82, "mode": 0},
    {"name": "DREAM", "label": "Sonho", "b": 0.12, "c": -0.12, "s": -12.0, "h": -8.0, "l": 12.0, "r": 1.06, "g": 0.97, "blue": 1.08, "mode": 0},
    {"name": "TROPICAL", "label": "Tropical", "b": 0.05, "c": 0.12, "s": 40.0, "h": -3.0, "l": 8.0, "r": 0.98, "g": 1.08, "blue": 1.03, "mode": 0},
    {"name": "URBAN", "label": "Urbano", "b": -0.06, "c": 0.20, "s": -25.0, "h": 0.0, "l": -6.0, "r": 0.95, "g": 0.98, "blue": 1.05, "mode": 0},
    {"name": "FOOD", "label": "Comida", "b": 0.08, "c": 0.16, "s": 32.0, "h": -3.0, "l": 7.0, "r": 1.12, "g": 1.05, "blue": 0.92, "mode": 0},
    {"name": "PORTRAIT", "label": "Retrato", "b": 0.06, "c": 0.06, "s": -5.0, "h": -5.0, "l": 8.0, "r": 1.08, "g": 0.99, "blue": 0.98, "mode": 0},
    {"name": "AUTUMN", "label": "Outono", "b": 0.04, "c": 0.12, "s": 22.0, "h": 12.0, "l": 4.0, "r": 1.16, "g": 1.02, "blue": 0.78, "mode": 0},
    {"name": "ICE", "label": "Gelo", "b": 0.06, "c": 0.08, "s": -4.0, "h": 5.0, "l": 8.0, "r": 0.86, "g": 1.02, "blue": 1.18, "mode": 0},
    {"name": "NEON", "label": "Neon", "b": -0.05, "c": 0.25, "s": 48.0, "h": 0.0, "l": -4.0, "r": 0.98, "g": 1.05, "blue": 1.1, "mode": 0},
    {"name": "DOCUMENTARY", "label": "Documentario", "b": -0.04, "c": 0.22, "s": -60.0, "h": 0.0, "l": -6.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "SUNSET", "label": "Por do sol", "b": 0.05, "c": 0.10, "s": 28.0, "h": -12.0, "l": 4.0, "r": 1.18, "g": 0.96, "blue": 0.78, "mode": 0},
    {"name": "LAVENDER", "label": "Lavanda", "b": 0.08, "c": -0.05, "s": 5.0, "h": 8.0, "l": 10.0, "r": 1.04, "g": 0.93, "blue": 1.14, "mode": 0},
    {"name": "EMERALD", "label": "Esmeralda", "b": -0.02, "c": 0.12, "s": 24.0, "h": -10.0, "l": 0.0, "r": 0.9, "g": 1.12, "blue": 1.02, "mode": 0},
    {"name": "NOIR", "label": "Noir", "b": -0.08, "c": 0.28, "s": -100.0, "h": 0.0, "l": -10.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "SOFT", "label": "Pele suave", "b": 0.10, "c": -0.08, "s": -10.0, "h": -4.0, "l": 10.0, "r": 1.08, "g": 1.0, "blue": 0.98, "mode": 0},
    {"name": "DRAMA", "label": "Drama", "b": -0.08, "c": 0.34, "s": -18.0, "h": 0.0, "l": -8.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0},
    {"name": "LOW_LIGHT", "label": "Baixa luz", "b": 0.18, "c": 0.08, "s": 8.0, "h": 0.0, "l": 8.0, "r": 1.03, "g": 1.03, "blue": 1.08, "mode": 0},
    {"name": "CLEAN", "label": "Clean", "b": 0.07, "c": 0.05, "s": 8.0, "h": 0.0, "l": 6.0, "r": 1.0, "g": 1.0, "blue": 1.0, "mode": 0}
]

def create_base_image(width=128, height=128):
    img = Image.new("RGB", (width, height), (0, 0, 0))
    draw = ImageDraw.Draw(img)

    # 1. Sky gradient (y: 0 to 45)
    for y in range(45):
        t = y / 44.0
        r = int(50 + 130 * t)
        g = int(120 + 70 * t)
        b = int(215 - 40 * t)
        draw.line([(0, y), (width - 1, y)], fill=(r, g, b))

    # Sun highlight disc in sky
    for r in range(12, 0, -1):
        alpha = (13 - r) / 13.0
        cr = int(255 * alpha + 240 * (1 - alpha))
        cg = int(245 * alpha + 180 * (1 - alpha))
        cb = int(220 * alpha + 120 * (1 - alpha))
        draw.ellipse([(92 - r, 20 - r), (92 + r, 20 + r)], fill=(cr, cg, cb))
    draw.ellipse([(91, 19), (93, 21)], fill=(255, 255, 255))

    # 2. Distant hills / foliage (y: 40 to 68)
    for x in range(width):
        elevation = int(8 * math.sin(x * 0.06) + 4 * math.sin(x * 0.15))
        top_y = 42 + elevation
        for y in range(top_y, 68):
            t = (y - top_y) / max(1, 68 - top_y)
            gr = int(45 + 35 * t + (x % 5) * 2)
            gg = int(125 - 35 * t + (x % 4) * 3)
            gb = int(55 - 20 * t)
            draw.point((x, y), fill=(gr, gg, gb))

    # 3. Portrait / Human subject (center-left: x=24..76, y=42..112)
    face_cx, face_cy = 50, 72
    face_rx, face_ry = 18, 22
    for y in range(face_cy - face_ry, face_cy + face_ry + 1):
        for x in range(face_cx - face_rx, face_cx + face_rx + 1):
            dx = (x - face_cx) / float(face_rx)
            dy = (y - face_cy) / float(face_ry)
            d2 = dx * dx + dy * dy
            if d2 <= 1.0:
                light = max(0.0, 1.0 - (dx * 0.6 + dy * 0.5 + math.sqrt(1.0 - d2) * -0.6))
                sr = int(160 + 80 * light)
                sg = int(95 + 85 * light)
                sb = int(70 + 75 * light)
                cheek_dist = math.hypot(x - 56, y - 72)
                if cheek_dist < 8:
                    blush = (8 - cheek_dist) / 8.0 * 0.25
                    sr = int(sr * (1 - blush) + 245 * blush)
                    sg = int(sg * (1 - blush) + 110 * blush)
                    sb = int(sb * (1 - blush) + 105 * blush)
                draw.point((x, y), fill=(min(255, sr), min(255, sg), min(255, sb)))

    hair_color = (25, 20, 22)
    for y in range(face_cy - face_ry - 4, face_cy):
        for x in range(face_cx - face_rx - 4, face_cx + face_rx + 5):
            dx = (x - face_cx) / float(face_rx + 3)
            dy = (y - face_cy) / float(face_ry + 2)
            d2 = dx * dx + dy * dy
            if 0.7 < d2 <= 1.15 and (y < face_cy - 4 or abs(x - face_cx) > 13):
                draw.point((x, y), fill=hair_color)

    # 4. Right side: deep shadow, textures & contrast elements (x: 80 to 127, y: 55 to 112)
    for y in range(55, 113):
        for x in range(78, width):
            tx = (x - 78) / 49.0
            ty = (y - 55) / 57.0
            cr = int(30 + 110 * ty * (1.0 - tx))
            cg = int(15 + 40 * ty * (1.0 - tx))
            cb = int(25 + 30 * tx)
            noise = ((x * 37 + y * 17) % 19) - 9
            draw.point((x, y), fill=(max(0, min(255, cr + noise)), max(0, min(255, cg + noise)), max(0, min(255, cb + noise))))

    for y in range(95, 113):
        for x in range(108, width):
            draw.point((x, y), fill=(5, 5, 8))

    # 5. Bottom calibrated color checker strip (y: 114 to 127)
    swatches = [
        (230, 30, 30),    # Red
        (35, 185, 45),    # Green
        (30, 90, 230),    # Blue
        (30, 215, 220),   # Cyan
        (215, 40, 185),   # Magenta
        (240, 220, 35),   # Yellow
        (128, 128, 128),  # 50% Neutral Gray
        (255, 255, 255),  # Pure White
        (0, 0, 0),        # Pure Black
    ]
    swatch_w = width / len(swatches)
    for i, color in enumerate(swatches):
        x0 = int(i * swatch_w)
        x1 = int((i + 1) * swatch_w) if i < len(swatches) - 1 else width
        draw.rectangle([(x0, 114), (x1 - 1, 127)], fill=color)

    return img

def create_second_scene(width=128, height=128):
    img = Image.new("RGB", (width, height), (0, 0, 0))
    draw = ImageDraw.Draw(img)

    for y in range(height):
        t = y / float(height)
        r = int(240 * (1 - t * 0.4) + 20 * t)
        g = int(60 * (1 - t) + 30 * t)
        b = int(120 * (1 - t) + 180 * t)
        draw.line([(0, y), (width - 1, y)], fill=(r, g, b))

    buildings = [(5, 45), (20, 60), (35, 35), (55, 70), (70, 40), (85, 55), (105, 30), (120, 65)]
    for bx, bh in buildings:
        bw = 14
        draw.rectangle([(bx - bw//2, height - bh), (bx + bw//2, height)], fill=(12, 10, 20))
        for wy in range(height - bh + 6, height - 8, 8):
            draw.rectangle([(bx - 3, wy), (bx + 3, wy + 3)], fill=(255, 210, 80))

    return img

def apply_video_filter(img, vf):
    w, h = img.size
    src_pixels = img.load()
    out = Image.new("RGB", (w, h))
    dst_pixels = out.load()

    mode = vf["mode"]
    b = vf["b"]
    c = vf["c"]
    s = vf["s"]
    l = vf["l"] / 100.0
    r_gain = vf["r"]
    g_gain = vf["g"]
    b_gain = vf["blue"]
    hue_rad = math.radians(vf["h"])

    cf = 1.0 + c
    sat = -1.0 if mode == 1 else (s / 100.0)
    sf = max(0.0, 1.0 + sat)
    cos_h = math.cos(hue_rad)
    sin_h = math.sin(hue_rad)
    inv255 = 1.0 / 255.0

    for y in range(h):
        for x in range(w):
            r_int, g_int, b_int = src_pixels[x, y]
            src_r = r_int * inv255
            src_g = g_int * inv255
            src_b = b_int * inv255

            if mode > 1.5:  # NEGATIVE
                fr = 1.0 - src_r
                fg = 1.0 - src_g
                fb = 1.0 - src_b
            else:
                fr = src_r + b
                fg = src_g + b
                fb = src_b + b

                fr = (fr - 0.5) * cf + 0.5
                fg = (fg - 0.5) * cf + 0.5
                fb = (fb - 0.5) * cf + 0.5

                gray = fr * 0.2126 + fg * 0.7152 + fb * 0.0722
                fr = gray + (fr - gray) * sf
                fg = gray + (fg - gray) * sf
                fb = gray + (fb - gray) * sf

                cross_x = INV_SQRT3 * (fg - fb)
                cross_y = INV_SQRT3 * (fb - fr)
                cross_z = INV_SQRT3 * (fr - fg)
                axis_dot = (fr + fg + fb) * ONE_THIRD * (1.0 - cos_h)

                fr = fr * cos_h + cross_x * sin_h + axis_dot
                fg = fg * cos_h + cross_y * sin_h + axis_dot
                fb = fb * cos_h + cross_z * sin_h + axis_dot

                fr = fr * r_gain + l
                fg = fg * g_gain + l
                fb = fb * b_gain + l

            out_r = int(max(0.0, min(1.0, fr)) * 255.0 + 0.5)
            out_g = int(max(0.0, min(1.0, fg)) * 255.0 + 0.5)
            out_b = int(max(0.0, min(1.0, fb)) * 255.0 + 0.5)
            dst_pixels[x, y] = (out_r, out_g, out_b)

    return out

def generate_effect_preview(base_img, preset):
    pid = preset["id"]
    w, h = base_img.size
    img = base_img.copy()

    if "cyber_signal" in pid or "neon" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                rx = min(w - 1, x + 4)
                bx = max(0, x - 4)
                r_val = int(src[rx, y][0] * 1.2)
                g_val = int(src[x, y][1] * 1.1)
                b_val = int(src[bx, y][2] * 1.3)
                dst[x, y] = (min(255, r_val), min(255, g_val), min(255, b_val))
        img = dst_img
        d = ImageDraw.Draw(img)
        for y in range(0, h, 3):
            d.line([(0, y), (w, y)], fill=(10, 10, 20))

    elif "impact" in pid or "damage" in pid or "headshot" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.35)), int(g * 0.8), int(b * 0.8))
        img = dst_img
        d = ImageDraw.Draw(img)
        for r in range(40, 80, 6):
            d.rectangle([(80 - r, 80 - r), (48 + r, 48 + r)], outline=(220, 20, 20), width=2)

    elif "soft_dream" in pid:
        blurred = img.filter(ImageFilter.GaussianBlur(radius=4))
        img = Image.blend(img, blurred, 0.45)
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.12)), min(255, int(g * 1.1)), min(255, int(b * 1.1)))
        img = dst_img

    elif "tape" in pid or "vhs" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                rx = min(w - 1, x + 3)
                r = src[rx, y][0]
                g = src[x, y][1]
                b = src[x, y][2]
                dst[x, y] = (r, g, b)
        img = dst_img
        d = ImageDraw.Draw(img)
        for y in range(0, h, 4):
            d.line([(0, y), (w, y)], fill=(20, 20, 20))
        d.line([(0, 60), (w, 60)], fill=(220, 220, 220), width=2)

    elif "liquid_lens" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        cx, cy = w / 2.0, h / 2.0
        for y in range(h):
            for x in range(w):
                dx = (x - cx) / cx
                dy = (y - cy) / cy
                r = math.hypot(dx, dy)
                if r < 1.0:
                    theta = math.atan2(dy, dx)
                    r_src = r * r
                    sx = int(cx + r_src * cx * math.cos(theta))
                    sy = int(cy + r_src * cy * math.sin(theta))
                    dst[x, y] = src[min(w - 1, max(0, sx)), min(h - 1, max(0, sy))]
                else:
                    dst[x, y] = src[x, y]
        img = dst_img

    elif "film" in pid or "35mm" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                noise = random.randint(-8, 8)
                nr = min(255, max(0, int(r * 1.12) + noise))
                ng = min(255, max(0, int(g * 1.02) + noise))
                nb = min(255, max(0, int(b * 0.88) + noise))
                dst[x, y] = (nr, ng, nb)
        img = dst_img

    elif "cinema" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                if "dark" in pid:
                    dst[x, y] = (max(0, min(255, int((r - 20) * 0.95))), max(0, min(255, int((g - 20) * 1.05))), max(0, min(255, int((b - 20) * 1.18))))
                elif "cold" in pid:
                    dst[x, y] = (min(255, int(r * 0.88)), min(255, int(g * 1.02)), min(255, int(b * 1.2)))
                else:
                    dst[x, y] = (min(255, int(r * 1.15)), min(255, int(g * 1.05)), min(255, int(b * 0.85)))
        img = dst_img

    elif "explosion" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.4) + 30), min(255, int(g * 0.9)), min(255, int(b * 0.5)))
        img = dst_img

    elif "speed" in pid or "zoom" in pid:
        blurred = img.filter(ImageFilter.BoxBlur(radius=3))
        img = Image.blend(img, blurred, 0.6)

    elif "victory" in pid:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.25) + 25), min(255, int(g * 1.15)), min(255, int(b * 0.75)))
        img = dst_img

    else:
        src = img.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.1)), g, min(255, int(b * 1.1)))
        img = dst_img

    return img

def generate_transition_preview(scene_a, scene_b, trans):
    tid = trans["id"]
    w, h = scene_a.size
    result = Image.new("RGB", (w, h))

    if "cross_dissolve" in tid or "fade" in tid or "blur_dissolve" in tid:
        result = Image.blend(scene_a, scene_b, 0.5)
        if "blur" in tid:
            result = result.filter(ImageFilter.GaussianBlur(radius=3))

    elif "dip_to_black" in tid or "fade_through_black" in tid:
        blended = Image.blend(scene_a, scene_b, 0.5)
        src = blended.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (int(r * 0.35), int(g * 0.35), int(b * 0.35))
        result = dst_img

    elif "dip_to_white" in tid or "fade_through_white" in tid or "flash" in tid:
        blended = Image.blend(scene_a, scene_b, 0.5)
        src = blended.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            for x in range(w):
                r, g, b = src[x, y]
                dst[x, y] = (min(255, int(r * 1.5) + 80), min(255, int(g * 1.5) + 80), min(255, int(b * 1.5) + 80))
        result = dst_img

    elif "slide_left" in tid or "push_left" in tid:
        result.paste(scene_a.crop((w // 2, 0, w, h)), (0, 0))
        result.paste(scene_b.crop((0, 0, w // 2, h)), (w // 2, 0))
        draw = ImageDraw.Draw(result)
        draw.line([(w // 2, 0), (w // 2, h)], fill=(255, 255, 255), width=1)

    elif "slide_right" in tid or "push_right" in tid:
        result.paste(scene_b.crop((w // 2, 0, w, h)), (0, 0))
        result.paste(scene_a.crop((0, 0, w // 2, h)), (w // 2, 0))
        draw = ImageDraw.Draw(result)
        draw.line([(w // 2, 0), (w // 2, h)], fill=(255, 255, 255), width=1)

    elif "slide_up" in tid or "push_up" in tid:
        result.paste(scene_a.crop((0, h // 2, w, h)), (0, 0))
        result.paste(scene_b.crop((0, 0, w, h // 2)), (0, h // 2))
        draw = ImageDraw.Draw(result)
        draw.line([(0, h // 2), (w, h // 2)], fill=(255, 255, 255), width=1)

    elif "slide_down" in tid or "push_down" in tid:
        result.paste(scene_b.crop((0, h // 2, w, h)), (0, 0))
        result.paste(scene_a.crop((0, 0, w, h // 2)), (0, h // 2))
        draw = ImageDraw.Draw(result)
        draw.line([(0, h // 2), (w, h // 2)], fill=(255, 255, 255), width=1)

    elif "wipe_left" in tid:
        result.paste(scene_a, (0, 0))
        result.paste(scene_b.crop((w // 2, 0, w, h)), (w // 2, 0))
        draw = ImageDraw.Draw(result)
        draw.line([(w // 2, 0), (w // 2, h)], fill=(255, 255, 255), width=2)

    elif "wipe_right" in tid:
        result.paste(scene_a, (0, 0))
        result.paste(scene_b.crop((0, 0, w // 2, h)), (w // 2, 0))
        draw = ImageDraw.Draw(result)
        draw.line([(w // 2, 0), (w // 2, h)], fill=(255, 255, 255), width=2)

    elif "circle_wipe" in tid:
        result.paste(scene_a, (0, 0))
        mask = Image.new("L", (w, h), 0)
        mdraw = ImageDraw.Draw(mask)
        mdraw.ellipse([(w // 4, h // 4), (3 * w // 4, 3 * h // 4)], fill=255)
        result.paste(scene_b, (0, 0), mask)

    elif "zoom" in tid or "spin" in tid:
        zoomed = scene_b.resize((w // 2, h // 2), Image.BILINEAR)
        result = scene_a.copy()
        result.paste(zoomed, (w // 4, h // 4))
        draw = ImageDraw.Draw(result)
        draw.rectangle([(w // 4, h // 4), (3 * w // 4, 3 * h // 4)], outline=(255, 255, 255), width=1)

    elif "glitch" in tid:
        blended = Image.blend(scene_a, scene_b, 0.5)
        src = blended.load()
        dst_img = Image.new("RGB", (w, h))
        dst = dst_img.load()
        for y in range(h):
            is_glitch_row = (y % 20) < 6
            for x in range(w):
                if is_glitch_row:
                    rx = min(w - 1, x + 8)
                    bx = max(0, x - 8)
                    dst[x, y] = (src[rx, y][0], src[x, y][1], src[bx, y][2])
                else:
                    dst[x, y] = src[x, y]
        result = dst_img

    elif "whip" in tid or "motion" in tid or "pan" in tid:
        blended = Image.blend(scene_a, scene_b, 0.5)
        result = blended.filter(ImageFilter.BoxBlur(radius=4))

    elif "flip" in tid or "cube" in tid or "page_turn" in tid or "perspective" in tid:
        result.paste(scene_a.crop((0, 0, w // 2, h)), (0, 0))
        half_b = scene_b.resize((w // 2, h), Image.BILINEAR)
        result.paste(half_b, (w // 2, 0))
        draw = ImageDraw.Draw(result)
        draw.line([(w // 2, 0), (w // 2, h)], fill=(40, 40, 60), width=2)

    else:
        result = Image.blend(scene_a, scene_b, 0.5)
        draw = ImageDraw.Draw(result)
        draw.line([(0, 0), (w, h)], fill=(255, 255, 255), width=1)

    return result

def pack_atlas(tiles, atlas_dim=512, tile_dim=128):
    atlas = Image.new("RGB", (atlas_dim, atlas_dim), (24, 24, 28))
    for idx, tile in enumerate(tiles[:16]):
        col = idx % 4
        row = idx // 4
        x = col * tile_dim
        y = row * tile_dim
        resized = tile.resize((tile_dim, tile_dim), Image.LANCZOS)
        atlas.paste(resized, (x, y))
    return atlas

def main():
    print("=== Generating Recly Pre-rendered Preview Atlases ===")
    out_dir = "app/src/main/assets/editor/previews"
    os.makedirs(out_dir, exist_ok=True)

    base_img = create_base_image(128, 128)
    base_img_path = os.path.join(out_dir, "preview_base.webp")
    base_img.save(base_img_path, format="WEBP", quality=92)
    print(f"[OK] Generated base preview photo: {base_img_path} ({os.path.getsize(base_img_path)} bytes)")

    scene_b = create_second_scene(128, 128)

    filter_tiles = []
    for vf in VIDEO_FILTERS:
        ftile = apply_video_filter(base_img, vf)
        filter_tiles.append(ftile)

    atlas_f1 = pack_atlas(filter_tiles[0:16])
    atlas_f1_path = os.path.join(out_dir, "filters_atlas_01.webp")
    atlas_f1.save(atlas_f1_path, format="WEBP", quality=90)

    atlas_f2 = pack_atlas(filter_tiles[16:32])
    atlas_f2_path = os.path.join(out_dir, "filters_atlas_02.webp")
    atlas_f2.save(atlas_f2_path, format="WEBP", quality=90)
    print(f"[OK] Generated Filters Atlas 1: {atlas_f1_path} ({os.path.getsize(atlas_f1_path)} bytes)")
    print(f"[OK] Generated Filters Atlas 2: {atlas_f2_path} ({os.path.getsize(atlas_f2_path)} bytes)")

    with open("app/src/main/assets/editor/effect_presets.json") as f:
        effect_presets = json.load(f)

    effect_tiles = []
    for ep in effect_presets:
        etile = generate_effect_preview(base_img, ep)
        effect_tiles.append(etile)

    atlas_e1 = pack_atlas(effect_tiles[0:16])
    atlas_e1_path = os.path.join(out_dir, "effects_atlas_01.webp")
    atlas_e1.save(atlas_e1_path, format="WEBP", quality=90)

    atlas_e2 = pack_atlas(effect_tiles[16:])
    atlas_e2_path = os.path.join(out_dir, "effects_atlas_02.webp")
    atlas_e2.save(atlas_e2_path, format="WEBP", quality=90)
    print(f"[OK] Generated Effects Atlas 1: {atlas_e1_path} ({os.path.getsize(atlas_e1_path)} bytes)")
    print(f"[OK] Generated Effects Atlas 2: {atlas_e2_path} ({os.path.getsize(atlas_e2_path)} bytes)")

    with open("app/src/main/assets/editor/transitions.json") as f:
        transitions = json.load(f)

    trans_tiles = []
    for tr in transitions:
        ttile = generate_transition_preview(base_img, scene_b, tr)
        trans_tiles.append(ttile)

    for a_idx in range(5):
        chunk = trans_tiles[a_idx * 16 : (a_idx + 1) * 16]
        atlas_t = pack_atlas(chunk)
        atlas_t_path = os.path.join(out_dir, f"transitions_atlas_{a_idx+1:02d}.webp")
        atlas_t.save(atlas_t_path, format="WEBP", quality=90)
        print(f"[OK] Generated Transitions Atlas {a_idx+1}: {atlas_t_path} ({os.path.getsize(atlas_t_path)} bytes)")

    registry = {
        "filters": {},
        "effects": {},
        "transitions": {}
    }
    for idx, vf in enumerate(VIDEO_FILTERS):
        atlas_id = "filters_atlas_01" if idx < 16 else "filters_atlas_02"
        local_idx = idx % 16
        registry["filters"][vf["name"]] = {
            "atlas": atlas_id,
            "col": local_idx % 4,
            "row": local_idx // 4
        }

    for idx, ep in enumerate(effect_presets):
        atlas_id = "effects_atlas_01" if idx < 16 else "effects_atlas_02"
        local_idx = idx % 16
        registry["effects"][ep["id"]] = {
            "atlas": atlas_id,
            "col": local_idx % 4,
            "row": local_idx // 4
        }

    for idx, tr in enumerate(transitions):
        atlas_num = (idx // 16) + 1
        atlas_id = f"transitions_atlas_{atlas_num:02d}"
        local_idx = idx % 16
        registry["transitions"][tr["id"]] = {
            "atlas": atlas_id,
            "col": local_idx % 4,
            "row": local_idx // 4
        }

    registry_path = os.path.join(out_dir, "preview_registry.json")
    with open(registry_path, "w") as f:
        json.dump(registry, f, indent=2)
    print(f"[OK] Generated preview registry mapping: {registry_path}")
    print("=== All Atlases Successfully Created! ===")

if __name__ == "__main__":
    main()
