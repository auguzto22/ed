"""Tiny helpers to author Lottie (bodymovin 5.x) JSON for the Recly sticker library.

Everything produced here uses only features supported by lottie-android 6.x shape layers:
ellipse, rect, polystar, path, fill, stroke, trim path, repeater-free groups with a trailing
transform. Keyframes use the modern format (per-keyframe "s" with i/o easing handles).
"""
import math

FR = 30
SIZE = 400
C = SIZE / 2

EASE_OUT = ({"x": [0.2], "y": [1]}, {"x": [0.33], "y": [0]})
EASE_IN_OUT = ({"x": [0.6], "y": [1]}, {"x": [0.4], "y": [0]})
LINEAR = ({"x": [1], "y": [1]}, {"x": [0], "y": [0]})


def static(value):
    return {"a": 0, "k": value}


def anim(keys, ease=EASE_IN_OUT):
    """keys: list of (frame, value). Values are lists (multi-dim) or numbers."""
    out = []
    for index, (t, v) in enumerate(keys):
        entry = {"t": t, "s": v if isinstance(v, list) else [v]}
        if index < len(keys) - 1:
            entry["i"], entry["o"] = ease[0], ease[1]
        out.append(entry)
    return {"a": 1, "k": out}


def rgba(hex_color, alpha=1.0):
    h = hex_color.lstrip("#")
    return [round(int(h[i:i + 2], 16) / 255, 4) for i in (0, 2, 4)] + [alpha]


def fill(color, opacity=100):
    return {"ty": "fl", "c": static(rgba(color)), "o": static(opacity), "r": 1, "nm": "Fill"}


def stroke(color, width, opacity=100, cap=2):
    return {"ty": "st", "c": static(rgba(color)), "o": static(opacity), "w": static(width),
            "lc": cap, "lj": 2, "ml": 4, "nm": "Stroke"}


def trim(start=None, end=None, offset=0):
    return {"ty": "tm", "s": start if start is not None else static(0), "e": end if end is not None else static(100),
            "o": static(offset), "m": 1, "nm": "Trim"}


def ellipse(w, h=None, pos=(0, 0)):
    return {"ty": "el", "d": 1, "s": static([w, h if h is not None else w]), "p": static(list(pos)), "nm": "Ellipse"}


def rect(w, h, pos=(0, 0), radius=0):
    return {"ty": "rc", "d": 1, "s": static([w, h]), "p": static(list(pos)), "r": static(radius), "nm": "Rect"}


def star(points, outer, inner, pos=(0, 0), rotation=0, polygon=False, inner_round=0, outer_round=0):
    shape = {"ty": "sr", "sy": 2 if polygon else 1, "d": 1, "pt": static(points), "p": static(list(pos)),
             "r": static(rotation), "or": static(outer), "os": static(outer_round), "nm": "Star"}
    if not polygon:
        shape["ir"] = static(inner)
        shape["is"] = static(inner_round)
    return shape


def path(vertices, closed=True, in_t=None, out_t=None):
    n = len(vertices)
    return {"ty": "sh", "d": 1, "ks": static({
        "c": closed,
        "v": [list(map(float, v)) for v in vertices],
        "i": [list(map(float, v)) for v in (in_t or [[0, 0]] * n)],
        "o": [list(map(float, v)) for v in (out_t or [[0, 0]] * n)],
    }), "nm": "Path"}


def group_transform(pos=(0, 0), scale=100, rotation=0, opacity=100, anchor=(0, 0)):
    return {"ty": "tr",
            "p": pos if isinstance(pos, dict) else static(list(pos)),
            "a": static(list(anchor)),
            "s": scale if isinstance(scale, dict) else static([scale, scale]),
            "r": rotation if isinstance(rotation, dict) else static(rotation),
            "o": opacity if isinstance(opacity, dict) else static(opacity),
            "sk": static(0), "sa": static(0), "nm": "Transform"}


def group(items, name="Group", **transform):
    return {"ty": "gr", "it": list(items) + [group_transform(**transform)], "nm": name, "np": len(items)}


def layer(shapes, ind=1, name="Layer", pos=None, scale=None, rotation=None, opacity=None, op=60, ip=0):
    return {
        "ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1,
        "ks": {
            "o": opacity or static(100),
            "r": rotation or static(0),
            "p": pos or static([C, C, 0]),
            "a": static([0, 0, 0]),
            "s": scale or static([100, 100, 100]),
        },
        "ao": 0, "shapes": shapes, "ip": ip, "op": op, "st": 0, "bm": 0,
    }


def composition(name, layers, op=60):
    for layer_ in layers:
        layer_["op"] = op
    # Lottie draws the first layer on top; callers pass back-to-front, so reverse here.
    ordered = list(reversed(layers))
    for index, layer_ in enumerate(ordered):
        layer_["ind"] = index + 1
    return {"v": "5.7.4", "fr": FR, "ip": 0, "op": op, "w": SIZE, "h": SIZE, "nm": name, "ddd": 0,
            "assets": [], "layers": ordered}


# ── Common motion envelopes for a whole layer ────────────────────────────────

def motion(kind, op=60):
    """Returns layer transform overrides (pos/scale/rotation/opacity) for a named motion."""
    p = [C, C, 0]
    if kind == "pop":
        return {"scale": anim([(0, [0, 0, 100]), (10, [118, 118, 100]), (18, [94, 94, 100]), (26, [100, 100, 100]), (op, [100, 100, 100])], EASE_OUT)}
    if kind == "bounce":
        return {"pos": anim([(0, [C, C - 60, 0]), (12, [C, C + 8, 0]), (20, [C, C - 18, 0]), (28, [C, C, 0]), (op, [C, C, 0])], EASE_OUT)}
    if kind == "float":
        return {"pos": anim([(0, [C, C, 0]), (op // 2, [C, C - 16, 0]), (op, [C, C, 0])])}
    if kind == "pulse":
        return {"scale": anim([(0, [100, 100, 100]), (op // 2, [112, 112, 100]), (op, [100, 100, 100])])}
    if kind == "spin":
        return {"rotation": anim([(0, 0), (op, 360)], LINEAR)}
    if kind == "wobble":
        return {"rotation": anim([(0, 0), (op // 6, -12), (op // 3, 10), (op // 2, -6), (2 * op // 3, 4), (op, 0)])}
    if kind == "blink":
        return {"opacity": anim([(0, 100), (op // 4, 100), (op // 4 + 3, 15), (op // 2, 100), (3 * op // 4, 100), (3 * op // 4 + 3, 15), (op, 100)], LINEAR)}
    if kind == "swing":
        return {"rotation": anim([(0, -15), (op // 2, 15), (op, -15)])}
    if kind == "zoom":
        return {"scale": anim([(0, [40, 40, 100]), (14, [100, 100, 100]), (op, [100, 100, 100])], EASE_OUT),
                "opacity": anim([(0, 0), (8, 100), (op, 100)])}
    if kind == "drop":
        return {"pos": anim([(0, [C, C - 220, 0]), (14, [C, C + 10, 0]), (20, [C, C, 0]), (op, [C, C, 0])], EASE_OUT)}
    if kind == "slide":
        return {"pos": anim([(0, [C - 220, C, 0]), (14, [C + 8, C, 0]), (20, [C, C, 0]), (op, [C, C, 0])], EASE_OUT)}
    if kind == "heartbeat":
        return {"scale": anim([(0, [100, 100, 100]), (6, [118, 118, 100]), (12, [100, 100, 100]), (18, [114, 114, 100]), (26, [100, 100, 100]), (op, [100, 100, 100])])}
    if kind == "shake":
        return {"pos": anim([(0, [C, C, 0]), (3, [C - 10, C, 0]), (6, [C + 10, C, 0]), (9, [C - 8, C, 0]), (12, [C + 6, C, 0]), (15, [C, C, 0]), (op, [C, C, 0])], LINEAR)}
    if kind == "jelly":
        return {"scale": anim([(0, [100, 100, 100]), (8, [125, 80, 100]), (16, [85, 118, 100]), (24, [108, 94, 100]), (32, [100, 100, 100]), (op, [100, 100, 100])])}
    return {}


def apply_motion(layer_, kind, op=60):
    overrides = motion(kind, op)
    mapping = {"pos": "p", "scale": "s", "rotation": "r", "opacity": "o"}
    for key, value in overrides.items():
        layer_["ks"][mapping[key]] = value
    return layer_


def polar(radius, degrees):
    a = math.radians(degrees)
    return [radius * math.cos(a), radius * math.sin(a)]
