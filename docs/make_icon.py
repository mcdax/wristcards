"""Draws the Wristcards launcher icon: blue-to-purple bezel with crown and 12/3/6/9 marks, fanned cards with a soft shadow."""
import math
from PIL import Image, ImageDraw, ImageFilter

S = 1024

def grad(c1, c2):
    g = Image.new("RGBA", (S, S))
    px = g.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2 * S)
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)
    return g

def card(cx, cy, w, h, ang, fill, r):
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), radius=r, fill=fill)
    return layer.rotate(ang, center=(cx, cy), resample=Image.BICUBIC)

def icon(bg1, bg2, bezel, face, back, front, check, ring_gap):
    c = S / 2
    mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).ellipse((0, 0, S - 1, S - 1), fill=255)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); im.paste(grad(bg1, bg2), (0, 0), mask)
    d = ImageDraw.Draw(im)
    def circ(r, fill): d.ellipse((c - r, c - r, c + r, c + r), fill=fill)
    d.rounded_rectangle((c + S * 0.40, c - S * 0.06, c + S * 0.475, c + S * 0.06), radius=S * 0.02, fill=bezel)
    circ(S * 0.43, bezel)
    if ring_gap: circ(S * 0.385, ring_gap)
    circ(S * 0.37, face)
    for i in range(4):
        a = math.radians(i * 90); r1, r2 = S * 0.395, S * 0.425
        d.line((c + r1 * math.sin(a), c - r1 * math.cos(a), c + r2 * math.sin(a), c - r2 * math.cos(a)), fill=ring_gap or bg1, width=int(S * 0.018))
    w, h, r = S * 0.34, S * 0.45, S * 0.04
    fx, fy = c + S * 0.065, c + S * 0.01
    # soft shadow under the cards
    sh = card(fx + S * 0.02, fy + S * 0.03, w, h, -10, (0, 0, 0, 90), r).filter(ImageFilter.GaussianBlur(S * 0.02))
    face_mask = Image.new("L", (S, S), 0); ImageDraw.Draw(face_mask).ellipse((c - S * 0.37, c - S * 0.37, c + S * 0.37, c + S * 0.37), fill=255)
    sh.putalpha(Image.composite(sh.getchannel("A"), Image.new("L", (S, S), 0), face_mask))
    im.alpha_composite(sh)
    for L in (card(c - S * 0.075, c + S * 0.005, w, h, 14, back, r), card(fx, fy, w + S * 0.03, h + S * 0.03, -10, face, r * 1.3), card(fx, fy, w, h, -10, front, r)):
        im.alpha_composite(L)
    chk = Image.new("RGBA", (S, S), (0, 0, 0, 0)); cd = ImageDraw.Draw(chk)
    pts = [(fx - S * 0.08, fy), (fx - S * 0.022, fy + S * 0.058), (fx + S * 0.085, fy - S * 0.062)]
    cd.line(pts, fill=check, width=int(S * 0.04), joint="curve")
    for p in (pts[0], pts[2]): cd.ellipse((p[0] - S * 0.02, p[1] - S * 0.02, p[0] + S * 0.02, p[1] + S * 0.02), fill=check)
    im.alpha_composite(chk.rotate(-10, center=(fx, fy), resample=Image.BICUBIC))
    return im


im = icon((41, 128, 255), (140, 60, 245), (255, 255, 255, 255), (255, 255, 255, 255), (255, 170, 60, 255), (60, 120, 240, 255), (255, 255, 255, 255), None)
im.save("art/icon-1024.png")
im.resize((512, 512), Image.LANCZOS).save("art/icon-512.png")
res = "app/app/src/main/res"
for dn, s in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    x = im.resize((s, s), Image.LANCZOS)
    x.save(f"{res}/mipmap-{dn}/ic_launcher.png"); x.save(f"{res}/mipmap-{dn}/ic_launcher_round.png")
print("ok")
