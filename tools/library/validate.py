"""Structural checks for generated Lottie files, animation presets and effect shaders."""
import re

SHAPE_TYPES = {"el", "rc", "sr", "sh", "fl", "st", "tm", "gr", "tr"}


def _prop(value, where, dims=None):
    assert isinstance(value, dict) and "a" in value and "k" in value, f"{where}: bad property {value!r}"
    if value["a"] == 0:
        k = value["k"]
        if isinstance(k, list) and dims:
            assert len(k) in dims, f"{where}: expected {dims} dims, got {k}"
        return
    keys = value["k"]
    assert isinstance(keys, list) and len(keys) >= 2, f"{where}: animated property needs 2+ keyframes"
    last = -1
    width = None
    for index, kf in enumerate(keys):
        assert kf["t"] > last, f"{where}: keyframe times must increase ({kf['t']} after {last})"
        last = kf["t"]
        s = kf["s"]
        assert isinstance(s, list) and all(isinstance(x, (int, float)) for x in s), f"{where}: bad keyframe value"
        width = width or len(s)
        assert len(s) == width, f"{where}: keyframe widths differ"
        if dims:
            assert len(s) in dims, f"{where}: expected {dims} dims, got {s}"
        if index < len(keys) - 1:
            assert "i" in kf and "o" in kf, f"{where}: missing easing"


def _shapes(items, where):
    assert items, f"{where}: empty shape list"
    for item in items:
        ty = item.get("ty")
        assert ty in SHAPE_TYPES, f"{where}: unsupported shape {ty}"
        if ty == "gr":
            assert item["it"][-1]["ty"] == "tr", f"{where}: group must end with transform"
            assert any(x["ty"] in ("fl", "st", "gr") for x in item["it"]), f"{where}: group draws nothing"
            _shapes(item["it"], where + "/" + item.get("nm", "gr"))
        elif ty == "tr":
            for key, dims in (("p", (2,)), ("a", (2,)), ("s", (2,)), ("r", None), ("o", None)):
                _prop(item[key], f"{where}/tr.{key}", dims)
        elif ty in ("el", "rc"):
            _prop(item["s"], f"{where}/{ty}.s", (2,))
            _prop(item["p"], f"{where}/{ty}.p", (2,))
        elif ty == "sr":
            for key in ("pt", "or", "r"):
                _prop(item[key], f"{where}/sr.{key}")
            if item["sy"] == 1:
                _prop(item["ir"], f"{where}/sr.ir")
        elif ty == "sh":
            data = item["ks"]["k"]
            n = len(data["v"])
            assert n >= 2 and len(data["i"]) == n and len(data["o"]) == n, f"{where}: path tangent mismatch"
        elif ty in ("fl", "st"):
            _prop(item["c"], f"{where}/{ty}.c", (4,))
            _prop(item["o"], f"{where}/{ty}.o")
        elif ty == "tm":
            for key in ("s", "e", "o"):
                _prop(item[key], f"{where}/tm.{key}")


def lottie(doc, name):
    for key in ("v", "fr", "ip", "op", "w", "h", "layers"):
        assert key in doc, f"{name}: missing {key}"
    assert doc["layers"], f"{name}: no layers"
    inds = [layer["ind"] for layer in doc["layers"]]
    assert len(set(inds)) == len(inds), f"{name}: duplicate layer ind"
    for layer in doc["layers"]:
        assert layer["ty"] == 4, f"{name}: only shape layers are generated"
        for key, dims in (("p", (2, 3)), ("a", (2, 3)), ("s", (2, 3)), ("r", None), ("o", None)):
            _prop(layer["ks"][key], f"{name}/ks.{key}", dims)
            if layer["ks"][key]["a"]:
                assert layer["ks"][key]["k"][-1]["t"] <= doc["op"], f"{name}: keyframe after op"
        _shapes(layer["shapes"], name)


ANIM_ID = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


def animation(doc, name):
    assert ANIM_ID.match(doc["id"]), f"{name}: bad id"
    points = doc["points"]
    assert len(points) >= 2, f"{name}: need 2+ points"
    last = -1.0
    D = dict(zoom=1.0, x=0.0, y=0.0, rotation=0.0, opacity=1.0)
    points = [{**D, **p} for p in points]
    for p in points:
        assert 0 <= p["time"] <= 1 and p["time"] > last, f"{name}: times must increase within 0..1"
        last = p["time"]
        assert .1 <= p["zoom"] <= 4, f"{name}: zoom {p['zoom']}"
        assert -1 <= p["x"] <= 1 and -1 <= p["y"] <= 1, f"{name}: offset"
        assert -360 <= p["rotation"] <= 360, f"{name}: rotation"
        assert 0 <= p["opacity"] <= 1, f"{name}: opacity"
    assert points[0]["time"] == 0 and points[-1]["time"] == 1, f"{name}: must span 0..1"
    if doc["category"] == "LOOP":
        a, b = points[0], points[-1]
        for key in ("zoom", "x", "y", "rotation", "opacity"):
            assert abs(a[key] - b[key]) < 1e-6, f"{name}: loop must end where it starts ({key})"


def shader(source, name):
    assert "vec4 reclyEffect(" in source, f"{name}: missing reclyEffect"
    # Same rule the app enforces in AssetRepository.validateShader.
    m = re.search(r"#|\b(?:uniform|attribute|varying|while|for|do|discard|main)\b", source)
    assert not m, f"{name}: forbidden token {m.group(0)!r}"
    assert source.count("{") == source.count("}"), f"{name}: unbalanced braces"
    assert source.count("(") == source.count(")"), f"{name}: unbalanced parentheses"
    assert len(source) < 65536
