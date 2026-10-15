"""Original scene for the effects library previews (editor/previews/effect_loop_base.webp).

12 frames of 162x288 side by side: sunset, bright sun (bloom/halation), dark edges (vignettes),
twinkling stars and two moving balls (trails, echo, motion blur). The loop closes on itself.
Run: python3 tools/make_effect_loop_base.py  (writes effect_loop_base.webp in the current folder).
"""
import math, random
from PIL import Image, ImageDraw, ImageFilter
W, H, N, SS = 162, 288, 12, 4          # frame size, frames, supersampling
w, h = W*SS, H*SS
random.seed(7)
stars = [(random.uniform(0, w), random.uniform(0, h*.42), random.uniform(.6, 1.6)*SS, random.uniform(0, 6.28)) for _ in range(38)]

def lerp(a, b, t): return tuple(int(a[i] + (b[i]-a[i])*t) for i in range(3))

def sky():
    img = Image.new('RGB', (w, h))
    px = img.load()
    stops = [(0, (18, 10, 52)), (.30, (74, 28, 112)), (.50, (214, 74, 110)), (.60, (255, 150, 90))]
    for y in range(int(h*.62)):
        t = y / h
        for i in range(len(stops)-1):
            if stops[i][0] <= t <= stops[i+1][0]:
                c = lerp(stops[i][1], stops[i+1][1], (t-stops[i][0])/(stops[i+1][0]-stops[i][0])); break
        else: c = stops[-1][1]
        for x in range(w): px[x, y] = c
    return img

base_sky = sky()
horizon = int(h*.60)

def frame(i):
    ph = 2*math.pi*i/N
    img = base_sky.copy()
    d = ImageDraw.Draw(img, 'RGBA')
    # twinkling stars
    for sx, sy, r, p in stars:
        a = int(140 + 115*math.sin(ph*2 + p))
        d.ellipse([sx-r, sy-r, sx+r, sy+r], fill=(255, 255, 255, max(0, a)))
    # sun glow + disc (bright area for bloom / halation)
    sun = Image.new('RGBA', (w, h), (0, 0, 0, 0)); sd = ImageDraw.Draw(sun)
    cx, cy, R = w*.5, horizon - h*.035, w*.17
    for k in range(10, 0, -1):
        rr = R*(1 + k*.22); sd.ellipse([cx-rr, cy-rr, cx+rr, cy+rr], fill=(255, 190, 120, 14))
    sun = sun.filter(ImageFilter.GaussianBlur(6*SS))
    img.paste(sun, (0, 0), sun)
    d = ImageDraw.Draw(img, 'RGBA')
    d.ellipse([cx-R, cy-R, cx+R, cy+R], fill=(255, 236, 190))
    # mountains (dark silhouettes)
    for layer, (col, amp, off) in enumerate([((58, 24, 78), .10, .0), ((30, 14, 46), .07, 1.7)]):
        pts = [(0, h)]
        for x in range(0, w+1, 4*SS):
            y = horizon - h*amp*(.55 + .45*math.sin(x/w*7 + off)) * (.6 + .4*math.cos(x/w*3.1 + off))
            pts.append((x, y))
        pts.append((w, h)); d.polygon(pts, fill=col)
    # sea
    d.rectangle([0, horizon, w, h], fill=(14, 22, 48))
    for k in range(26):
        y = horizon + (k+1)**1.55 * SS*1.3
        if y > h: break
        span = w*(.10 + .013*k) * (1 + .25*math.sin(ph + k))
        a = int(170 - k*5)
        d.rectangle([cx-span, y, cx+span, y + SS*(1 + k*.12)], fill=(255, 170, 110, max(0, a)))
    # moving subject: neon ball along a closed loop (gives trails / motion blur something to show)
    bx = w*.5 + w*.30*math.sin(ph)
    by = h*.80 + h*.06*math.sin(2*ph)
    br = w*.075
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0)); gd = ImageDraw.Draw(glow)
    gd.ellipse([bx-br*1.8, by-br*1.8, bx+br*1.8, by+br*1.8], fill=(0, 255, 230, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(5*SS)); img.paste(glow, (0, 0), glow)
    d = ImageDraw.Draw(img, 'RGBA')
    d.ellipse([bx-br, by-br, bx+br, by+br], fill=(0, 230, 210))
    d.ellipse([bx-br*.55, by-br*.6, bx+br*.05, by-br*.05], fill=(220, 255, 250))
    # second smaller pink ball, opposite phase
    px_, py_ = w*.5 - w*.26*math.sin(ph), h*.90 - h*.03*math.cos(ph)
    pr = w*.045
    d.ellipse([px_-pr, py_-pr, px_+pr, py_+pr], fill=(255, 70, 170))
    return img.resize((W, H), Image.LANCZOS)

frames = [frame(i) for i in range(N)]
sheet = Image.new('RGB', (W*N, H))
for i, f in enumerate(frames): sheet.paste(f, (i*W, 0)); f.save(f'f{i:02d}.png')
sheet.save('effect_loop_base.webp', 'WEBP', quality=90, method=6)
print('sheet', sheet.size)
