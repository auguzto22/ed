#!/usr/bin/env python3
"""Build Recly Originals from independently authored geometric source data.

The source of truth is this script plus fonts-src/*/design.json. No existing font
is opened, traced, renamed, transformed, or used as an outline source.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from fontTools.agl import UV2AGL
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, newTable

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "fonts-src"
OUTPUT_ROOT = ROOT / "app/src/main/assets/fonts"
SPECIMEN_ROOT = ROOT / "build/font-specimens"
UPM = 1000
REQUIRED = (
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    "áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇ"
    ".,:;!?'\"-_+=/()[]{}@#$%&*ºª•—"
)

# Independently authored 5 x 7 construction grids. These describe letter ideas,
# not sampled outlines. Family renderers below turn the grids into distinct vectors.
CAPS = {
    "A":("01110","10001","10001","11111","10001","10001","10001"),
    "B":("11110","10001","10001","11110","10001","10001","11110"),
    "C":("01111","10000","10000","10000","10000","10000","01111"),
    "D":("11110","10001","10001","10001","10001","10001","11110"),
    "E":("11111","10000","10000","11110","10000","10000","11111"),
    "F":("11111","10000","10000","11110","10000","10000","10000"),
    "G":("01111","10000","10000","10111","10001","10001","01111"),
    "H":("10001","10001","10001","11111","10001","10001","10001"),
    "I":("11111","00100","00100","00100","00100","00100","11111"),
    "J":("00111","00010","00010","00010","00010","10010","01100"),
    "K":("10001","10010","10100","11000","10100","10010","10001"),
    "L":("10000","10000","10000","10000","10000","10000","11111"),
    "M":("10001","11011","10101","10101","10001","10001","10001"),
    "N":("10001","11001","11001","10101","10011","10011","10001"),
    "O":("01110","10001","10001","10001","10001","10001","01110"),
    "P":("11110","10001","10001","11110","10000","10000","10000"),
    "Q":("01110","10001","10001","10001","10101","10010","01101"),
    "R":("11110","10001","10001","11110","10100","10010","10001"),
    "S":("01111","10000","10000","01110","00001","00001","11110"),
    "T":("11111","00100","00100","00100","00100","00100","00100"),
    "U":("10001","10001","10001","10001","10001","10001","01110"),
    "V":("10001","10001","10001","10001","10001","01010","00100"),
    "W":("10001","10001","10001","10101","10101","11011","10001"),
    "X":("10001","10001","01010","00100","01010","10001","10001"),
    "Y":("10001","10001","01010","00100","00100","00100","00100"),
    "Z":("11111","00001","00010","00100","01000","10000","11111"),
}

LOWER = {
    "a":("00000","00000","01110","00001","01111","10001","01111"),
    "b":("10000","10000","10110","11001","10001","10001","11110"),
    "c":("00000","00000","01111","10000","10000","10000","01111"),
    "d":("00001","00001","01101","10011","10001","10001","01111"),
    "e":("00000","00000","01110","10001","11111","10000","01111"),
    "f":("00110","01001","01000","11100","01000","01000","01000"),
    "g":("00000","00000","01111","10001","01111","00001","11110"),
    "h":("10000","10000","10110","11001","10001","10001","10001"),
    "i":("00100","00000","01100","00100","00100","00100","01110"),
    "j":("00010","00000","00110","00010","00010","10010","01100"),
    "k":("10000","10000","10010","10100","11000","10100","10010"),
    "l":("01100","00100","00100","00100","00100","00100","01110"),
    "m":("00000","00000","11010","10101","10101","10101","10101"),
    "n":("00000","00000","10110","11001","10001","10001","10001"),
    "o":("00000","00000","01110","10001","10001","10001","01110"),
    "p":("00000","00000","11110","10001","11110","10000","10000"),
    "q":("00000","00000","01111","10001","01111","00001","00001"),
    "r":("00000","00000","10110","11001","10000","10000","10000"),
    "s":("00000","00000","01111","10000","01110","00001","11110"),
    "t":("01000","01000","11100","01000","01000","01001","00110"),
    "u":("00000","00000","10001","10001","10001","10011","01101"),
    "v":("00000","00000","10001","10001","10001","01010","00100"),
    "w":("00000","00000","10001","10001","10101","10101","01010"),
    "x":("00000","00000","10001","01010","00100","01010","10001"),
    "y":("00000","00000","10001","10001","01111","00001","11110"),
    "z":("00000","00000","11111","00010","00100","01000","11111"),
}

DIGITS = {
    "0":("01110","10011","10101","10101","11001","10001","01110"),
    "1":("00100","01100","00100","00100","00100","00100","01110"),
    "2":("01110","10001","00001","00010","00100","01000","11111"),
    "3":("11110","00001","00001","01110","00001","00001","11110"),
    "4":("00010","00110","01010","10010","11111","00010","00010"),
    "5":("11111","10000","10000","11110","00001","00001","11110"),
    "6":("01110","10000","10000","11110","10001","10001","01110"),
    "7":("11111","00001","00010","00100","01000","01000","01000"),
    "8":("01110","10001","10001","01110","10001","10001","01110"),
    "9":("01110","10001","10001","01111","00001","00001","01110"),
}

PUNCT = {
    ".":("0","0","0","0","0","1","1"), ",":("0","0","0","0","0","1","1","1"),
    ":":("0","1","1","0","1","1","0"), ";":("0","1","1","0","1","1","1"),
    "!":("1","1","1","1","1","0","1"), "?":("1110","0001","0010","0100","0100","0000","0100"),
    "'":("1","1","1"), '"':("101","101","101"), "-":("000","000","000","111","000","000","000"),
    "_":("00000","00000","00000","00000","00000","00000","11111"),
    "+":("00000","00100","00100","11111","00100","00100","00000"),
    "=":("00000","00000","11111","00000","11111","00000","00000"),
    "/":("00001","00010","00010","00100","01000","01000","10000"),
    "(":("001","010","100","100","100","010","001"), ")":("100","010","001","001","001","010","100"),
    "[":("111","100","100","100","100","100","111"), "]":("111","001","001","001","001","001","111"),
    "{":("0011","0100","0100","1000","0100","0100","0011"), "}":("1100","0010","0010","0001","0010","0010","1100"),
    "@":("01110","10001","10111","10101","10111","10000","01111"),
    "#":("01010","11111","01010","01010","11111","01010","01010"),
    "$":("00100","01111","10100","01110","00101","11110","00100"),
    "%":("11001","11010","00100","00100","01000","10110","00110"),
    "&":("01100","10010","10100","01000","10101","10010","01101"),
    "*":("00000","10101","01110","11111","01110","10101","00000"),
    "º":("01110","10001","10001","01110"), "ª":("01110","00001","01111","10001","01111"),
    "•":("000","111","111","111","000"), "—":("00000","00000","00000","11111","00000","00000","00000"),
}

ACCENTS = {
    "á":("a","acute"), "à":("a","grave"), "â":("a","circumflex"), "ã":("a","tilde"), "ä":("a","dieresis"),
    "é":("e","acute"), "è":("e","grave"), "ê":("e","circumflex"), "ë":("e","dieresis"),
    "í":("i","acute"), "ì":("i","grave"), "î":("i","circumflex"), "ï":("i","dieresis"),
    "ó":("o","acute"), "ò":("o","grave"), "ô":("o","circumflex"), "õ":("o","tilde"), "ö":("o","dieresis"),
    "ú":("u","acute"), "ù":("u","grave"), "û":("u","circumflex"), "ü":("u","dieresis"), "ç":("c","cedilla"),
    "Á":("A","acute"), "À":("A","grave"), "Â":("A","circumflex"), "Ã":("A","tilde"), "Ä":("A","dieresis"),
    "É":("E","acute"), "È":("E","grave"), "Ê":("E","circumflex"), "Ë":("E","dieresis"),
    "Í":("I","acute"), "Ì":("I","grave"), "Î":("I","circumflex"), "Ï":("I","dieresis"),
    "Ó":("O","acute"), "Ò":("O","grave"), "Ô":("O","circumflex"), "Õ":("O","tilde"), "Ö":("O","dieresis"),
    "Ú":("U","acute"), "Ù":("U","grave"), "Û":("U","circumflex"), "Ü":("U","dieresis"), "Ç":("C","cedilla"),
}


def poly(pen, points):
    pen.moveTo(points[0])
    for p in points[1:]: pen.lineTo(p)
    pen.closePath()


def circle(pen, x, y, r, n=12):
    # Match the clockwise winding used by stroke rectangles so overlapping joins
    # remain filled in both Android/Skia and FreeType rasterizers.
    poly(pen, [(x + math.cos(-i * 2 * math.pi / n) * r, y + math.sin(-i * 2 * math.pi / n) * r) for i in range(n)])


def stroke(pen, a, b, width, mode, seed=0):
    ax, ay = a; bx, by = b
    dx, dy = bx-ax, by-ay
    length = max(math.hypot(dx, dy), 1)
    nx, ny = -dy/length, dx/length
    if mode == "brush":
        w1, w2 = width * (0.72 + (seed % 3)*.09), width * (1.15 - (seed % 2)*.18)
        poly(pen, [(ax+nx*w1/2,ay+ny*w1/2),(bx+nx*w2/2,by+ny*w2/2),
                   (bx-nx*w2*.36-dx*.05,by-ny*w2*.36-dy*.05),(ax-nx*w1*.55-dx*.03,ay-ny*w1*.55-dy*.03)])
    elif mode in ("gothic", "urban"):
        cut = width * (.28 if mode == "gothic" else .18)
        poly(pen, [(ax+nx*width/2+dx/length*cut,ay+ny*width/2+dy/length*cut),
                   (bx+nx*width/2-dx/length*cut,by+ny*width/2-dy/length*cut),
                   (bx+dx/length*cut,by+dy/length*cut),(bx-nx*width/2-dx/length*cut,by-ny*width/2-dy/length*cut),
                   (ax-nx*width/2+dx/length*cut,ay-ny*width/2+dy/length*cut),(ax-dx/length*cut,ay-dy/length*cut)])
    else:
        poly(pen, [(ax+nx*width/2,ay+ny*width/2),(bx+nx*width/2,by+ny*width/2),
                   (bx-nx*width/2,by-ny*width/2),(ax-nx*width/2,ay-ny*width/2)])
        if mode in ("stroke", "mono", "signature"):
            circle(pen, ax, ay, width/2); circle(pen, bx, by, width/2)


def active_cells(pattern):
    return {(x, y) for y, row in enumerate(pattern) for x, bit in enumerate(row) if bit == "1"}


def render_pattern(pen, pattern, cfg, lower=False, accent=None):
    cells = active_cells(pattern)
    cols = max(len(row) for row in pattern)
    rows = len(pattern)
    width_scale = cfg["width"]
    step_x = 105 * width_scale
    if lower:
        step_y = 76
        top = cfg["xHeight"]
        if any(pattern[i] != "0" * len(pattern[i]) for i in range(min(2, rows))): top = 700
        if pattern in (LOWER.get(k) for k in "gjpqy"): top = 440
    else:
        step_y = 100
        top = 700
    left = 90
    slant = cfg["slant"]
    cell_w = cfg["weight"] * (1.0 if cfg["mode"] not in ("editorial",) else .72)
    cell_h = cfg["weight"]

    def center(x, y):
        yy = top - y * step_y
        return (left + x * step_x + slant * yy, yy)

    mode = cfg["mode"]
    if mode in ("pixel", "block", "arcade"):
        for x, y in sorted(cells):
            cx, cy = center(x, y)
            w = (step_x * (.86 if mode == "block" else .72))
            h = (step_y * (.9 if mode == "block" else .72))
            if mode == "pixel":
                poly(pen, [(cx-w/2,cy-h/2),(cx+w/2,cy-h/2),(cx+w/2,cy+h/2),(cx-w/2,cy+h/2)])
            elif mode == "arcade":
                c=min(w,h)*.22
                poly(pen, [(cx-w/2+c,cy-h/2),(cx+w/2-c,cy-h/2),(cx+w/2,cy-h/2+c),
                           (cx+w/2,cy+h/2-c),(cx+w/2-c,cy+h/2),(cx-w/2+c,cy+h/2),
                           (cx-w/2,cy+h/2-c),(cx-w/2,cy-h/2+c)])
            else:
                # Offset trapezoids create the compressed poster's own rhythm.
                poly(pen, [(cx-w*.46,cy-h/2),(cx+w*.50,cy-h/2),(cx+w*.43,cy+h/2),(cx-w*.50,cy+h/2)])
    else:
        edges=[]
        for x,y in sorted(cells):
            for dx,dy in ((1,0),(0,1),(1,1),(-1,1)):
                if (x+dx,y+dy) in cells:
                    if dx and dy and ((x+dx,y) in cells or (x,y+dy) in cells): continue
                    edges.append(((x,y),(x+dx,y+dy)))
        isolated = [c for c in cells if not any(c in edge for edge in edges)]
        for idx,(a,b) in enumerate(edges):
            w=cell_w
            if mode == "editorial": w *= .50 if a[1] == b[1] else 1.18
            if mode == "gothic" and a[1] == b[1]: w *= .62
            stroke(pen,center(*a),center(*b),w,mode,idx)
        for x,y in isolated: circle(pen,*center(x,y),cell_w/2,8 if mode in ("gothic","urban") else 12)
        if mode == "editorial":
            # Fine independent slab/wedge terminals, consistently tied to the construction grid.
            for x,y in cells:
                neighbors=sum(((x+dx,y+dy) in cells) for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)))
                if neighbors <= 1:
                    cx,cy=center(x,y); sw=cell_w*1.45; sh=max(14,cell_w*.20)
                    poly(pen,[(cx-sw/2,cy-sh/2),(cx+sw/2,cy-sh/2),(cx+sw*.38,cy+sh/2),(cx-sw*.38,cy+sh/2)])
        if mode == "gothic":
            for x,y in cells:
                cx,cy=center(x,y)
                if y in (0,rows-1): poly(pen,[(cx,cy+cell_w*.62),(cx+cell_w*.38,cy),(cx,cy-cell_w*.35),(cx-cell_w*.38,cy)])
        if mode == "signature":
            base_y = 80 if not lower else 55
            stroke(pen,(left-45,base_y),(left+cols*step_x-35,base_y+18),cell_w*.42,"signature",1)
        if mode == "urban":
            # Small offset tags are part of the Urban construction, not an outline copy.
            for x,y in sorted(cells)[::4]:
                cx,cy=center(x,y); poly(pen,[(cx+cell_w*.35,cy+cell_w*.2),(cx+cell_w*.72,cy),(cx+cell_w*.5,cy-cell_w*.35)])

    if accent:
        center_x = left + (cols-1)*step_x/2 + slant*(820 if not lower else 635)
        y = 805 if not lower else 625
        aw=max(38,cfg["weight"]*.52)
        if accent == "acute": stroke(pen,(center_x-aw,y-15),(center_x+aw,y+55),max(24,aw*.55),mode,2)
        elif accent == "grave": stroke(pen,(center_x-aw,y+55),(center_x+aw,y-15),max(24,aw*.55),mode,2)
        elif accent == "circumflex":
            stroke(pen,(center_x-aw,y),(center_x,y+55),max(22,aw*.48),mode,2); stroke(pen,(center_x,y+55),(center_x+aw,y),max(22,aw*.48),mode,3)
        elif accent == "tilde":
            stroke(pen,(center_x-aw*1.25,y+10),(center_x-aw*.35,y+42),max(20,aw*.42),mode,2)
            stroke(pen,(center_x-aw*.35,y+42),(center_x+aw*.45,y+8),max(20,aw*.42),mode,3)
            stroke(pen,(center_x+aw*.45,y+8),(center_x+aw*1.25,y+38),max(20,aw*.42),mode,4)
        elif accent == "dieresis":
            circle(pen,center_x-aw*.75,y+25,max(16,aw*.28),8); circle(pen,center_x+aw*.75,y+25,max(16,aw*.28),8)
        elif accent == "cedilla":
            cy=-40
            stroke(pen,(center_x+15,40),(center_x-5,cy),max(22,aw*.45),mode,2)
            stroke(pen,(center_x-5,cy),(center_x+35,cy-55),max(22,aw*.45),mode,3)
    advance = int(left*2 + max(cols-1,0)*step_x + step_x*.78 + cfg["tracking"])
    if mode == "mono": advance = 680
    return max(advance, 230)


def make_glyph(char, cfg):
    pen=TTGlyphPen(None)
    if char == " ": return pen.glyph(), 330 if cfg["mode"] != "mono" else 680
    base, accent = ACCENTS.get(char,(char,None))
    lower = base in LOWER
    pattern = CAPS.get(base) or LOWER.get(base) or DIGITS.get(base) or PUNCT.get(base)
    if pattern is None: raise KeyError(f"No source pattern for {char!r}")
    advance=render_pattern(pen,pattern,cfg,lower,accent)
    return pen.glyph(),advance


def build_one(source_file: Path, output_root: Path):
    cfg=json.loads(source_file.read_text(encoding="utf-8"))
    chars = " " + "".join(CAPS) + "".join(LOWER) + "".join(DIGITS) + "".join(PUNCT) + "".join(ACCENTS)
    chars = "".join(dict.fromkeys(chars))
    glyph_order=[".notdef"]+[UV2AGL.get(ord(c),f"uni{ord(c):04X}") for c in chars]
    glyphs={}; metrics={}
    notdef=TTGlyphPen(None); poly(notdef,[(60,0),(500,0),(500,700),(60,700)]); poly(notdef,[(130,80),(130,620),(430,620),(430,80)])
    glyphs[".notdef"]=notdef.glyph(); metrics[".notdef"]=(560,0)
    cmap={}
    for char,name in zip(chars,glyph_order[1:]):
        glyph,advance=make_glyph(char,cfg); glyphs[name]=glyph; metrics[name]=(advance,0); cmap[ord(char)]=name
    fb=FontBuilder(UPM,isTTF=True)
    fb.setupGlyphOrder(glyph_order); fb.setupCharacterMap(cmap); fb.setupGlyf(glyphs); fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=900,descent=-250,lineGap=100)
    fb.setupNameTable({"familyName":cfg["family"],"styleName":cfg["style"],"uniqueFontIdentifier":f"Recly: {cfg['family']} {cfg['style']}: 1.000","fullName":f"{cfg['family']} {cfg['style']}","psName":(cfg["family"].replace(" ","")+"-"+cfg["style"]),"version":"Version 1.000","manufacturer":"Recly","designer":"Recly Type Lab","description":cfg["personality"],"licenseDescription":"Copyright 2026 Recly. Original glyph software for use with the Recly application and project.","vendorURL":"https://recly.app"})
    fb.setupOS2(sTypoAscender=900,sTypoDescender=-250,sTypoLineGap=100,usWinAscent=900,usWinDescent=250,sxHeight=cfg["xHeight"],sCapHeight=700,usWeightClass=700 if cfg["style"]=="Bold" else 400,usWidthClass=max(1,min(9,round(cfg["width"]*5))))
    fb.setupPost(italicAngle=-cfg["slant"]*12,isFixedPitch=1 if cfg["mode"] in ("mono","pixel") else 0)
    fb.setupMaxp()
    font=fb.font
    kern=newTable("kern"); kern.version=0
    from fontTools.ttLib.tables._k_e_r_n import KernTable_format_0
    sub=KernTable_format_0(); sub.version=0; sub.coverage=1
    pairs={"AV":-55,"VA":-50,"To":-42,"Ta":-40,"Wa":-35,"Yo":-46,"LT":-24,"PA":-28}
    sub.kernTable={(UV2AGL[ord(a)],UV2AGL[ord(b)]):v for (a,b),v in pairs.items()}
    kern.kernTables=[sub]; font["kern"]=kern
    output_root.mkdir(parents=True,exist_ok=True); out=output_root/cfg["file"]; font.save(out)
    # Reopen immediately: catches malformed tables/checksums and records actual glyph count.
    checked=TTFont(out); count=len(checked.getGlyphOrder()); checked.close()
    return cfg,out,count,chars


def specimens(results):
    from PIL import Image, ImageDraw, ImageFont
    SPECIMEN_ROOT.mkdir(parents=True,exist_ok=True)
    sample=("Recly Originals\nABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789\n\n"
            "Olá! Edição rápida.\nAÇÃO • VÍDEO • CÂMERA\nSão Luís — coração, edição e criação.")
    for cfg,path,_,_ in results:
        image=Image.new("RGB",(1800,720),(248,247,244)); draw=ImageDraw.Draw(image)
        font=ImageFont.truetype(str(path),52); title=ImageFont.truetype(str(path),74)
        draw.text((65,45),cfg["family"],font=title,fill=(20,20,24))
        draw.multiline_text((65,150),sample,font=font,fill=(28,30,36),spacing=18)
        draw.text((65,665),f"{cfg['personality']} | {cfg['use']}",font=ImageFont.load_default(size=18),fill=(80,82,90))
        image.save(SPECIMEN_ROOT/(path.stem+".png"))


def validation_report(results):
    records=[]
    required_tables={"head","hhea","maxp","OS/2","hmtx","cmap","loca","glyf","name","post","kern"}
    for cfg,path,count,_ in results:
        font=TTFont(path,recalcBBoxes=False,recalcTimestamp=False)
        cmap=font.getBestCmap() or {}; missing=[c for c in REQUIRED if ord(c) not in cmap]
        tables=required_tables.difference(font.keys())
        invalid_bounds=[]
        glyf=font["glyf"]
        for name in font.getGlyphOrder():
            glyph=glyf[name]
            if glyph.numberOfContours and (glyph.xMin < -400 or glyph.yMin < -300 or glyph.xMax > 1600 or glyph.yMax > 950):
                invalid_bounds.append(name)
        records.append({
            "family":cfg["family"], "file":str(path.relative_to(ROOT)), "glyphCount":count,
            "encodedCharacters":len(cmap), "missingRequiredCharacters":"".join(missing),
            "missingRequiredTables":sorted(tables), "outOfBoundsGlyphs":invalid_bounds,
            "unitsPerEm":font["head"].unitsPerEm, "ascender":font["hhea"].ascent,
            "descender":font["hhea"].descent, "lineGap":font["hhea"].lineGap,
            "valid":not missing and not tables and not invalid_bounds,
        })
        font.close()
    report={"format":"Recly Originals validation v1","fonts":records,"allValid":all(r["valid"] for r in records)}
    (SPECIMEN_ROOT/"validation-report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    if not report["allValid"]: raise SystemExit("Font validation failed; inspect build/font-specimens/validation-report.json")


def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--output",type=Path,default=OUTPUT_ROOT); ap.add_argument("--no-specimens",action="store_true")
    args=ap.parse_args(); sources=sorted(SOURCE_ROOT.glob("recly-*/design.json"))
    if len(sources)!=11: raise SystemExit(f"Expected 11 design files, found {len(sources)}")
    results=[build_one(p,args.output) for p in sources]
    validation_report(results)
    if not args.no_specimens: specimens(results)
    for cfg,path,count,chars in results: print(f"OK {path.relative_to(ROOT)} glyphs={count} encoded={len(chars)} family={cfg['family']}")


if __name__ == "__main__": main()
